/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.compute.aggregation;

import org.apache.lucene.util.PriorityQueue;
import org.elasticsearch.benchmark.Utils;
import org.elasticsearch.common.breaker.NoopCircuitBreaker;
import org.elasticsearch.compute.aggregation.AbstractRateGroupingFunction.FlushQueue;
import org.elasticsearch.compute.aggregation.AbstractRateGroupingFunction.LongBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Microbenchmark for rate-aggregation flush merge queues: the previous
 * {@code PriorityQueue<Slice>} versus the in-place primitive {@link FlushQueue}.
 *
 * <p>Params cover slice count, samples per slice, and overlap. Each invocation
 * rebuilds the queue from a fixed layout and drains it with the same control flow
 * as {@code flushGroup} (including the {@code secondNextTimestamp} batch path).
 */
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(1)
public class FlushQueueBenchmark {

    private static final String PRIMITIVE = "primitive";
    private static final String LEGACY = "legacy";

    static {
        Utils.configureBenchmarkLogging();
    }

    static {
        if ("true".equals(System.getProperty("skipSelfTest")) == false) {
            selfTest();
        }
    }

    @Param({ "1", "2", "4", "8", "16", "64" })
    public int slices;

    @Param({ "1", "4", "16", "256" })
    public int samplesPerSlice;

    @Param({ "none", "partial", "interleave" })
    public String overlap;

    @Param({ PRIMITIVE, LEGACY })
    public String impl;

    private LongBuffer timestamps;
    private long[] values;
    private int[] sliceOffsetsTemplate;
    private int[] scratchOffsets;

    @Setup
    public void setup() {
        int total = slices * samplesPerSlice;
        timestamps = new LongBuffer(new NoopCircuitBreaker("bench"), Math.max(total, 1));
        timestamps.ensureCapacity(total);
        values = new long[total];
        sliceOffsetsTemplate = new int[slices * 2];
        buildLayout(total);
        scratchOffsets = Arrays.copyOf(sliceOffsetsTemplate, sliceOffsetsTemplate.length);
    }

    @TearDown
    public void tearDown() {
        if (timestamps != null) {
            timestamps.close();
        }
    }

    @Setup(Level.Invocation)
    public void resetOffsets() {
        System.arraycopy(sliceOffsetsTemplate, 0, scratchOffsets, 0, sliceOffsetsTemplate.length);
    }

    @Benchmark
    public void merge(Blackhole bh) {
        long checksum = switch (impl) {
            case PRIMITIVE -> drainPrimitive(timestamps, values, scratchOffsets);
            case LEGACY -> drainLegacy(timestamps, values, scratchOffsets);
            default -> throw new IllegalStateException("unknown impl " + impl);
        };
        bh.consume(checksum);
    }

    private void buildLayout(int total) {
        long[] ts = new long[total];
        for (int s = 0; s < slices; s++) {
            int start = s * samplesPerSlice;
            int end = start + samplesPerSlice;
            sliceOffsetsTemplate[s * 2] = start;
            sliceOffsetsTemplate[s * 2 + 1] = end;
            long base = switch (overlap) {
                case "none" -> (long) (slices - s) * samplesPerSlice * 10L;
                case "partial" -> (long) (slices - s) * (samplesPerSlice / 2 + 1) * 10L;
                case "interleave" -> slices - s;
                default -> throw new IllegalStateException("unknown overlap " + overlap);
            };
            for (int i = 0; i < samplesPerSlice; i++) {
                long step = "interleave".equals(overlap) ? (long) slices : 1L;
                ts[start + i] = base - i * step;
                values[start + i] = start + i;
            }
        }
        for (long t : ts) {
            timestamps.append(t);
        }
    }

    static long drainPrimitive(LongBuffer timestamps, long[] values, int[] sliceOffsets) {
        FlushQueue q = FlushQueue.fromSliceOffsets(timestamps, sliceOffsets);
        if (q == null) {
            return 0;
        }
        if (q.valueCount == 1) {
            return values[q.topStart()];
        }
        long checksum = 0;
        int position = q.consumeTop();
        long prevValue = values[position];
        checksum += prevValue;
        if (q.topExhausted()) {
            q.popTop();
        } else {
            q.updateTop();
        }
        long secondNextTimestamp = q.secondNextTimestamp();
        while (q.size() > 1) {
            if (q.topLastTimestamp() > secondNextTimestamp) {
                for (int p = q.topStart(); p < q.topEnd(); p++) {
                    long val = values[p];
                    checksum += val;
                    prevValue = val;
                }
                q.popTop();
                secondNextTimestamp = q.secondNextTimestamp();
                continue;
            }
            long val = values[q.consumeTop()];
            checksum += val;
            prevValue = val;
            if (q.topExhausted()) {
                q.popTop();
                secondNextTimestamp = q.secondNextTimestamp();
            } else if (q.topNextTimestamp() < secondNextTimestamp) {
                q.updateTop();
                secondNextTimestamp = q.secondNextTimestamp();
            }
        }
        for (int p = q.topStart(); p < q.topEnd(); p++) {
            checksum += values[p];
        }
        return checksum;
    }

    static long drainLegacy(LongBuffer timestamps, long[] values, int[] sliceOffsets) {
        int numSlices = sliceOffsets.length / 2;
        LegacyFlushQueue q = new LegacyFlushQueue(numSlices);
        for (int i = 0; i < numSlices; i++) {
            int start = sliceOffsets[i * 2];
            int end = sliceOffsets[i * 2 + 1];
            if (start < end) {
                q.valueCount += end - start;
                q.add(new LegacySlice(timestamps, start, end));
            }
        }
        if (q.valueCount == 0) {
            return 0;
        }
        if (q.valueCount == 1) {
            return values[q.top().start];
        }
        long checksum = 0;
        LegacySlice top = q.top();
        int position = top.next();
        long prevValue = values[position];
        checksum += prevValue;
        if (top.exhausted()) {
            q.pop();
            top = q.top();
        } else {
            top = q.updateTop();
        }
        long secondNextTimestamp = q.secondNextTimestamp();
        while (q.size() > 1) {
            if (top.lastTimestamp() > secondNextTimestamp) {
                for (int p = top.start; p < top.end; p++) {
                    long val = values[p];
                    checksum += val;
                    prevValue = val;
                }
                q.pop();
                top = q.top();
                secondNextTimestamp = q.secondNextTimestamp();
                continue;
            }
            long val = values[top.next()];
            checksum += val;
            prevValue = val;
            if (top.exhausted()) {
                q.pop();
                top = q.top();
                secondNextTimestamp = q.secondNextTimestamp();
            } else if (top.nextTimestamp < secondNextTimestamp) {
                top = q.updateTop();
                secondNextTimestamp = q.secondNextTimestamp();
            }
        }
        top = q.top();
        for (int p = top.start; p < top.end; p++) {
            checksum += values[p];
        }
        return checksum;
    }

    static void selfTest() {
        for (int slices : new int[] { 1, 2, 4, 8 }) {
            for (int samples : new int[] { 1, 4, 16 }) {
                for (String overlap : new String[] { "none", "partial", "interleave" }) {
                    FlushQueueBenchmark b = new FlushQueueBenchmark();
                    b.slices = slices;
                    b.samplesPerSlice = samples;
                    b.overlap = overlap;
                    b.setup();
                    try {
                        int[] a = Arrays.copyOf(b.sliceOffsetsTemplate, b.sliceOffsetsTemplate.length);
                        int[] c = Arrays.copyOf(b.sliceOffsetsTemplate, b.sliceOffsetsTemplate.length);
                        long primitive = drainPrimitive(b.timestamps, b.values, a);
                        long legacy = drainLegacy(b.timestamps, b.values, c);
                        if (primitive != legacy) {
                            throw new AssertionError(
                                "checksum mismatch slices="
                                    + slices
                                    + " samples="
                                    + samples
                                    + " overlap="
                                    + overlap
                                    + " primitive="
                                    + primitive
                                    + " legacy="
                                    + legacy
                            );
                        }
                    } finally {
                        b.tearDown();
                    }
                }
            }
        }
    }

    private static final class LegacySlice {
        int start;
        int end;
        long nextTimestamp;
        private long lastTimestamp = Long.MAX_VALUE;
        final LongBuffer timestamps;

        LegacySlice(LongBuffer timestamps, int start, int end) {
            this.timestamps = timestamps;
            this.start = start;
            this.end = end;
            this.nextTimestamp = timestamps.get(start);
        }

        boolean exhausted() {
            return start >= end;
        }

        int next() {
            int currentIndex = start;
            start++;
            if (start < end) {
                nextTimestamp = timestamps.get(start);
            }
            return currentIndex;
        }

        long lastTimestamp() {
            if (lastTimestamp == Long.MAX_VALUE) {
                lastTimestamp = timestamps.get(end - 1);
            }
            return lastTimestamp;
        }
    }

    private static final class LegacyFlushQueue extends PriorityQueue<LegacySlice> {
        int valueCount;

        LegacyFlushQueue(int maxSize) {
            super(maxSize);
        }

        long secondNextTimestamp() {
            final Object[] heap = getHeapArray();
            final int size = size();
            if (size == 2) {
                return ((LegacySlice) heap[2]).nextTimestamp;
            } else if (size >= 3) {
                return Math.max(((LegacySlice) heap[2]).nextTimestamp, ((LegacySlice) heap[3]).nextTimestamp);
            } else {
                return Long.MIN_VALUE;
            }
        }

        @Override
        protected boolean lessThan(LegacySlice a, LegacySlice b) {
            return a.nextTimestamp > b.nextTimestamp;
        }
    }
}
