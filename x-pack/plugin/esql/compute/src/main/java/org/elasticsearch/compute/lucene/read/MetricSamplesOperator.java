/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.lucene.read;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.DocBlock;
import org.elasticsearch.compute.data.DocVector;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.FloatBlock;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.lucene.IndexedByShardId;
import org.elasticsearch.compute.operator.AbstractPageMappingToIteratorOperator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.Operator;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.ReleasableIterator;
import org.elasticsearch.core.Releasables;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * Reads the samples of every metric selected by name, for metric names the coordinator cannot enumerate: one output row per
 * input document and selected metric holding a value in it. Selection and reading happen together, so a row always carries
 * the value of the very metric it names.
 * <p>
 * Every output row repeats the columns of its document and appends three:
 * <ol>
 *     <li>the series identity: the document's {@code _tsid} extended with the metric name, so the metrics of one document
 *     never share a series merely because they share a {@code _tsid}</li>
 *     <li>the metric name, as exposed by the shard holding the document</li>
 *     <li>the sample value, as a {@code double}; a multi-valued metric keeps all of its values at the position</li>
 * </ol>
 * The metrics are resolved per shard before this operator runs (see {@link Metric}); a metric that a shard does not select
 * loads as {@code null} there and contributes no row. Metrics are read a {@link Factory#chunkSize() chunk} at a time, and
 * each chunk is emitted as its own page before the next one is loaded, so memory scales with the chunk rather than with the
 * number of selected metrics. Within a page, rows are emitted metric by metric so each series stays contiguous in input order.
 */
public final class MetricSamplesOperator extends AbstractPageMappingToIteratorOperator {

    /**
     * A metric field that some shard selects.
     *
     * @param field      how to load the field on each shard; shards not selecting it must load constant {@code null}s
     * @param nameOfShard the metric name a shard exposes the field under, or {@code null} when the shard does not select it
     */
    public record Metric(ValuesSourceReaderOperator.FieldInfo field, IntFunction<BytesRef> nameOfShard) {}

    /**
     * Builds {@link MetricSamplesOperator}s.
     *
     * @param metrics      every metric any shard selects
     * @param chunkSize    how many metrics to load at once
     * @param docChannel   the channel holding the documents
     * @param tsidChannel  the channel holding the {@code _tsid} of each document
     */
    public record Factory(
        List<Metric> metrics,
        int chunkSize,
        ByteSizeValue jumboSize,
        IndexedByShardId<ValuesSourceReaderOperator.ShardContext> shardContexts,
        int docChannel,
        int tsidChannel,
        double sourceReservationFactor,
        LongSupplier directoryBytesRead
    ) implements OperatorFactory {
        public Factory {
            if (chunkSize <= 0) {
                throw new IllegalArgumentException("chunk size must be positive, got [" + chunkSize + "]");
            }
        }

        @Override
        public Operator get(DriverContext driverContext) {
            List<ValuesSourceReaderOperator> readers = new ArrayList<>();
            List<List<Metric>> chunks = new ArrayList<>();
            boolean success = false;
            try {
                for (int from = 0; from < metrics.size(); from += chunkSize) {
                    List<Metric> chunk = metrics.subList(from, Math.min(metrics.size(), from + chunkSize));
                    readers.add(
                        new ValuesSourceReaderOperator(
                            driverContext,
                            jumboSize.getBytes(),
                            chunk.stream().map(Metric::field).toList(),
                            shardContexts,
                            false,
                            docChannel,
                            sourceReservationFactor,
                            Integer.MAX_VALUE,
                            directoryBytesRead
                        )
                    );
                    chunks.add(chunk);
                }
                MetricSamplesOperator operator = new MetricSamplesOperator(
                    driverContext.blockFactory(),
                    chunks,
                    readers,
                    docChannel,
                    tsidChannel
                );
                success = true;
                return operator;
            } finally {
                if (success == false) {
                    Releasables.close(readers);
                }
            }
        }

        @Override
        public String describe() {
            return "MetricSamplesOperator[metrics=" + metrics.size() + ", chunk=" + chunkSize + "]";
        }
    }

    private final BlockFactory blockFactory;
    private final List<List<Metric>> chunks;
    private final List<ValuesSourceReaderOperator> readers;
    private final int docChannel;
    private final int tsidChannel;

    MetricSamplesOperator(
        BlockFactory blockFactory,
        List<List<Metric>> chunks,
        List<ValuesSourceReaderOperator> readers,
        int docChannel,
        int tsidChannel
    ) {
        this.blockFactory = blockFactory;
        this.chunks = chunks;
        this.readers = readers;
        this.docChannel = docChannel;
        this.tsidChannel = tsidChannel;
    }

    @Override
    protected ReleasableIterator<Page> receive(Page page) {
        return new ChunkIterator(page);
    }

    /**
     * Loads one chunk of metrics after another over the same input page, emitting the samples of each chunk before loading the
     * next. A chunk whose metrics hold no value in the page emits nothing.
     */
    private final class ChunkIterator implements ReleasableIterator<Page> {
        private final Page input;
        private final Deque<Page> pending = new ArrayDeque<>();
        private int chunk;

        ChunkIterator(Page input) {
            this.input = input;
        }

        @Override
        public boolean hasNext() {
            while (pending.isEmpty() && chunk < chunks.size()) {
                load(chunk++);
            }
            return pending.isEmpty() == false;
        }

        @Override
        public Page next() {
            if (hasNext() == false) {
                throw new NoSuchElementException();
            }
            return pending.removeFirst();
        }

        /** Loads one chunk of metrics; the reader may split the page, so a chunk can yield several pages. */
        private void load(int chunk) {
            ValuesSourceReaderOperator reader = readers.get(chunk);
            reader.addInput(input.shallowCopy());
            Page loaded;
            while ((loaded = reader.getOutput()) != null) {
                try {
                    Page samples = samples(chunks.get(chunk), input.getBlockCount(), loaded);
                    if (samples != null) {
                        pending.addLast(samples);
                    }
                } finally {
                    loaded.releaseBlocks();
                }
            }
        }

        @Override
        public void close() {
            List<Releasable> releasables = new ArrayList<>(pending.size() + 1);
            for (Page page : pending) {
                releasables.add(page::releaseBlocks);
            }
            pending.clear();
            releasables.add(input::releaseBlocks);
            Releasables.closeExpectNoException(releasables);
        }
    }

    /**
     * Unpivots {@code loaded} - the first {@code inputBlocks} blocks of the input page followed by one block per metric of
     * {@code metrics} - into one row per document and metric with a value.
     */
    private Page samples(List<Metric> metrics, int inputBlocks, Page loaded) {
        DocVector docs = loaded.<DocBlock>getBlock(docChannel).asVector();
        int rows = loaded.getPositionCount();
        int count = 0;
        for (int m = 0; m < metrics.size(); m++) {
            Block values = loaded.getBlock(inputBlocks + m);
            for (int p = 0; p < rows; p++) {
                if (selected(metrics.get(m), docs, values, p)) {
                    count++;
                }
            }
        }
        if (count == 0) {
            return null;
        }
        // Row bookkeeping is proportional to the rows emitted, which the output blocks already account for many times over;
        // reserve it anyway so a page selecting many metrics trips the breaker before it allocates.
        long reserved = 2L * Integer.BYTES * count;
        blockFactory.breaker().addEstimateBytesAndMaybeBreak(reserved, "MetricSamplesOperator");
        Block[] appended = new Block[3];
        Page repeated = null;
        try {
            int[] positions = new int[count];
            int[] metricOfRow = new int[count];
            int row = 0;
            for (int m = 0; m < metrics.size(); m++) {
                Block values = loaded.getBlock(inputBlocks + m);
                for (int p = 0; p < rows; p++) {
                    if (selected(metrics.get(m), docs, values, p)) {
                        positions[row] = p;
                        metricOfRow[row] = m;
                        row++;
                    }
                }
            }
            appended[0] = seriesIds(metrics, docs, loaded.getBlock(tsidChannel), positions, metricOfRow);
            appended[1] = names(metrics, docs, positions, metricOfRow);
            appended[2] = values(inputBlocks, loaded, positions, metricOfRow);
            int[] inputChannels = new int[inputBlocks];
            for (int c = 0; c < inputBlocks; c++) {
                inputChannels[c] = c;
            }
            try (Page input = loaded.projectBlocks(inputChannels)) {
                repeated = input.filter(true, positions, 0, count);
            }
            Page result = repeated.appendBlocks(appended);
            repeated = null;
            Arrays.fill(appended, null);
            return result;
        } finally {
            Releasables.closeExpectNoException(appended);
            if (repeated != null) {
                repeated.releaseBlocks();
            }
            blockFactory.breaker().addWithoutBreaking(-reserved);
        }
    }

    /** Whether the document at {@code position} holds a value of {@code metric} on a shard that selects it. */
    private static boolean selected(Metric metric, DocVector docs, Block values, int position) {
        return values.isNull(position) == false && metric.nameOfShard().apply(docs.shards().getInt(position)) != null;
    }

    /** The {@code _tsid} of each row's document, extended with the length-suffixed metric name so the pair stays unique. */
    private Block seriesIds(List<Metric> metrics, DocVector docs, BytesRefBlock tsids, int[] positions, int[] metricOfRow) {
        BytesRef scratch = new BytesRef();
        BytesRef none = new BytesRef();
        try (BytesRefVector.Builder builder = blockFactory.newBytesRefVectorBuilder(positions.length)) {
            BytesRef seriesId = new BytesRef();
            for (int i = 0; i < positions.length; i++) {
                int p = positions[i];
                BytesRef tsid = tsids.isNull(p) ? none : tsids.getBytesRef(tsids.getFirstValueIndex(p), scratch);
                BytesRef name = metrics.get(metricOfRow[i]).nameOfShard().apply(docs.shards().getInt(p));
                appendSeriesId(seriesId, tsid, name);
                builder.appendBytesRef(seriesId);
            }
            return builder.build().asBlock();
        }
    }

    /** {@code tsid ++ name ++ int32(name.length)}: reading from the end recovers both parts, so distinct pairs never collide. */
    static void appendSeriesId(BytesRef into, BytesRef tsid, BytesRef name) {
        int length = tsid.length + name.length + Integer.BYTES;
        if (into.bytes.length < length) {
            into.bytes = new byte[length];
        }
        System.arraycopy(tsid.bytes, tsid.offset, into.bytes, 0, tsid.length);
        System.arraycopy(name.bytes, name.offset, into.bytes, tsid.length, name.length);
        int at = tsid.length + name.length;
        into.bytes[at] = (byte) (name.length >>> 24);
        into.bytes[at + 1] = (byte) (name.length >>> 16);
        into.bytes[at + 2] = (byte) (name.length >>> 8);
        into.bytes[at + 3] = (byte) name.length;
        into.offset = 0;
        into.length = length;
    }

    private Block names(List<Metric> metrics, DocVector docs, int[] positions, int[] metricOfRow) {
        try (BytesRefVector.Builder builder = blockFactory.newBytesRefVectorBuilder(positions.length)) {
            for (int i = 0; i < positions.length; i++) {
                builder.appendBytesRef(metrics.get(metricOfRow[i]).nameOfShard().apply(docs.shards().getInt(positions[i])));
            }
            return builder.build().asBlock();
        }
    }

    private Block values(int inputBlocks, Page loaded, int[] positions, int[] metricOfRow) {
        try (DoubleBlock.Builder builder = blockFactory.newDoubleBlockBuilder(positions.length)) {
            for (int i = 0; i < positions.length; i++) {
                Block values = loaded.getBlock(inputBlocks + metricOfRow[i]);
                int p = positions[i];
                int first = values.getFirstValueIndex(p);
                int valueCount = values.getValueCount(p);
                if (valueCount > 1) {
                    builder.beginPositionEntry();
                }
                for (int v = first; v < first + valueCount; v++) {
                    builder.appendDouble(doubleValue(values, v));
                }
                if (valueCount > 1) {
                    builder.endPositionEntry();
                }
            }
            return builder.build();
        }
    }

    private static double doubleValue(Block values, int valueIndex) {
        return switch (values) {
            case DoubleBlock d -> d.getDouble(valueIndex);
            case LongBlock l -> l.getLong(valueIndex);
            case IntBlock i -> i.getInt(valueIndex);
            case FloatBlock f -> f.getFloat(valueIndex);
            default -> throw new IllegalArgumentException("metric samples must be numeric, got [" + values.elementType() + "]");
        };
    }

    @Override
    public String toString() {
        return "MetricSamplesOperator[metrics=" + chunks.stream().mapToInt(List::size).sum() + "]";
    }

    @Override
    public void close() {
        Releasables.closeExpectNoException(super::close, Releasables.wrap(readers));
    }
}
