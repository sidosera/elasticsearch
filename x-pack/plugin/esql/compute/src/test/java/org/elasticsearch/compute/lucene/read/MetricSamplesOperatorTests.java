/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.lucene.read;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.DocBlock;
import org.elasticsearch.compute.data.DocVector;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.lucene.IndexedByShardIdFromSingleton;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.Operator;
import org.elasticsearch.compute.test.ComputeTestCase;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.NumberFieldMapper;
import org.elasticsearch.index.mapper.SourceLoader;
import org.junit.After;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

public class MetricSamplesOperatorTests extends ComputeTestCase {

    private final Directory directory = newDirectory();
    private DirectoryReader reader;

    @After
    public void closeIndex() throws IOException {
        IOUtils.close(reader, directory);
    }

    /**
     * Documents shaped like both ingestion layouts: an OTel document holding several metrics, remote-write documents holding
     * one each, a document holding a metric sparsely and one holding none.
     */
    private static final List<Map<String, Double>> DOCS = List.of(
        Map.of("tx", 10.0, "rx", 2.0),
        Map.of("rx", 3.0),
        Map.of("tx", 12.0),
        Map.of()
    );
    private static final String[] TSIDS = { "series-a", "series-b", "series-b", "series-c" };

    /** Every metric holding a value yields one row, metric by metric, and the metrics of one document get distinct series. */
    public void testUnpivotsEveryMetricWithAValue() throws Exception {
        index();
        List<Row> rows = run(List.of(metric("tx", ElementType.DOUBLE, "tx"), metric("rx", ElementType.LONG, "rx")), 2);
        assertThat(
            rows.stream().map(r -> r.name + "@" + r.doc + "=" + r.value).toList(),
            equalTo(List.of("tx@0=10.0", "tx@2=12.0", "rx@0=2.0", "rx@1=3.0"))
        );
        Set<BytesRef> seriesIds = new HashSet<>();
        for (Row row : rows) {
            assertTrue("series of " + row, seriesIds.add(row.seriesId));
            assertThat(row.tsid, equalTo(new BytesRef(TSIDS[row.doc])));
        }
    }

    /** Loading one metric per chunk emits each chunk as its own page with the same rows. */
    public void testChunksEmitTheSameRows() throws Exception {
        index();
        List<Metric> metrics = List.of(metric("tx", ElementType.DOUBLE, "tx"), metric("rx", ElementType.LONG, "rx"));
        assertThat(run(metrics, 1), equalTo(run(metrics, 2)));
    }

    /** A metric a shard does not select contributes nothing there, even if it loads values. */
    public void testMetricNotSelectedByTheShardIsSkipped() throws Exception {
        index();
        List<Row> rows = run(List.of(metric("tx", ElementType.DOUBLE, "tx"), metric("rx", ElementType.LONG, null)), 2);
        assertThat(rows.stream().map(r -> r.name).distinct().toList(), equalTo(List.of("tx")));
    }

    /** A page where no selected metric has a value emits nothing. */
    public void testNoValuesEmitNothing() throws Exception {
        index();
        assertThat(run(List.of(metric("absent", ElementType.DOUBLE, "absent")), 1), hasSize(0));
    }

    public void testSeriesIdsOfDifferentPairsNeverCollide() {
        BytesRef left = new BytesRef();
        BytesRef right = new BytesRef();
        MetricSamplesOperator.appendSeriesId(left, new BytesRef("ab"), new BytesRef("c"));
        MetricSamplesOperator.appendSeriesId(right, new BytesRef("a"), new BytesRef("bc"));
        assertThat(left, not(equalTo(right)));
    }

    private record Metric(String field, ElementType type, String name) {}

    private record Row(int doc, BytesRef tsid, BytesRef seriesId, String name, double value) {}

    private static Metric metric(String field, ElementType type, String name) {
        return new Metric(field, type, name);
    }

    private void index() throws IOException {
        try (IndexWriter w = new IndexWriter(directory, new IndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
            for (Map<String, Double> metrics : DOCS) {
                Document doc = new Document();
                Double tx = metrics.get("tx");
                if (tx != null) {
                    doc.add(new SortedNumericDocValuesField("tx", NumericUtils.doubleToSortableLong(tx)));
                }
                Double rx = metrics.get("rx");
                if (rx != null) {
                    doc.add(new SortedNumericDocValuesField("rx", rx.longValue()));
                }
                w.addDocument(doc);
            }
            w.commit();
        }
        reader = DirectoryReader.open(directory);
    }

    private List<Row> run(List<Metric> metrics, int chunkSize) {
        List<MetricSamplesOperator.Metric> selected = new ArrayList<>();
        for (Metric metric : metrics) {
            NumberFieldMapper.NumberType numberType = metric.type == ElementType.DOUBLE
                ? NumberFieldMapper.NumberType.DOUBLE
                : NumberFieldMapper.NumberType.LONG;
            MappedFieldType ft = new NumberFieldMapper.NumberFieldType(metric.field, numberType);
            BytesRef name = metric.name == null ? null : new BytesRef(metric.name);
            IntFunction<BytesRef> nameOfShard = shard -> name;
            selected.add(
                new MetricSamplesOperator.Metric(
                    new ValuesSourceReaderOperator.FieldInfo(
                        metric.field,
                        metric.type,
                        false,
                        (ctx, shard) -> ValuesSourceReaderOperator.load(ft.blockLoader(ValuesSourceReaderOperatorTests.blContext()))
                    ),
                    nameOfShard
                )
            );
        }
        var factory = new MetricSamplesOperator.Factory(
            selected,
            chunkSize,
            ByteSizeValue.ofGb(1),
            new IndexedByShardIdFromSingleton<>(
                new ValuesSourceReaderOperator.ShardContext(
                    reader,
                    sourcePaths -> SourceLoader.FROM_STORED_SOURCE,
                    ValuesSourceReaderOperatorTests.STORED_FIELDS_SEQUENTIAL_PROPORTIONS
                )
            ),
            0,
            1,
            1.0,
            () -> 0L
        );

        BlockFactory blockFactory = blockFactory();
        DriverContext driverContext = new DriverContext(blockFactory.bigArrays(), blockFactory, null);
        DocBlock docs;
        try (DocVector.FixedBuilder builder = DocVector.newFixedBuilder(blockFactory, DOCS.size())) {
            for (int i = 0; i < DOCS.size(); i++) {
                builder.append(0, 0, i);
            }
            docs = builder.build(DocVector.config()).asBlock();
        }
        BytesRefBlock tsids;
        IntBlock docIds;
        try (var builder = blockFactory.newBytesRefBlockBuilder(DOCS.size()); var ids = blockFactory.newIntBlockBuilder(DOCS.size())) {
            for (int i = 0; i < DOCS.size(); i++) {
                builder.appendBytesRef(new BytesRef(TSIDS[i]));
                ids.appendInt(i);
            }
            tsids = builder.build();
            docIds = ids.build();
        }

        List<Row> rows = new ArrayList<>();
        try (Operator operator = factory.get(driverContext)) {
            operator.addInput(new Page(docs, tsids, docIds));
            Page out;
            while ((out = operator.getOutput()) != null) {
                try {
                    assertThat(out.getBlockCount(), equalTo(6));
                    BytesRefBlock outTsids = out.getBlock(1);
                    IntBlock outDocs = out.getBlock(2);
                    BytesRefBlock seriesIds = out.getBlock(3);
                    BytesRefBlock names = out.getBlock(4);
                    DoubleBlock values = out.getBlock(5);
                    for (int p = 0; p < out.getPositionCount(); p++) {
                        rows.add(
                            new Row(
                                outDocs.getInt(p),
                                BytesRef.deepCopyOf(outTsids.getBytesRef(p, new BytesRef())),
                                BytesRef.deepCopyOf(seriesIds.getBytesRef(p, new BytesRef())),
                                names.getBytesRef(p, new BytesRef()).utf8ToString(),
                                values.getDouble(p)
                            )
                        );
                    }
                } finally {
                    Releasables.close(out::releaseBlocks);
                }
            }
        }
        return rows;
    }
}
