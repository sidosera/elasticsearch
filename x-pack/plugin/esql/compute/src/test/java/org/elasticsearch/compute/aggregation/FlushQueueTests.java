/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.aggregation;

import org.apache.lucene.util.PriorityQueue;
import org.elasticsearch.compute.aggregation.AbstractRateGroupingFunction.FlushQueue;
import org.elasticsearch.compute.aggregation.AbstractRateGroupingFunction.LongBuffer;
import org.elasticsearch.compute.test.ComputeTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class FlushQueueTests extends ComputeTestCase {

    public void testEmptyGroupReturnsNull() {
        try (LongBuffer timestamps = newTimestamps(new long[] { 1, 2 })) {
            int[] sliceOffsets = new int[] { 0, 0, 1, 1 }; // two empty slices
            assertThat(buildQueue(timestamps, sliceOffsets, 0, 2), nullValue());
        }
    }

    public void testOneSlice() {
        long[] ts = { 30, 20, 10 };
        try (LongBuffer timestamps = newTimestamps(ts)) {
            int[] sliceOffsets = { 0, 3 };
            FlushQueue q = buildQueue(timestamps, sliceOffsets, 0, 1);
            assertThat(q.size(), equalTo(1));
            assertThat(q.valueCount, equalTo(3));
            assertThat(q.topStart(), equalTo(0));
            assertThat(q.topEnd(), equalTo(3));
            assertThat(q.topNextTimestamp(), equalTo(30L));
            assertThat(q.topLastTimestamp(), equalTo(10L));
            assertTrue(q.checkHeapInvariant());

            assertThat(q.consumeTop(), equalTo(0));
            assertThat(q.topStart(), equalTo(1));
            assertFalse(q.topExhausted());
            q.updateTop();
            assertTrue(q.checkHeapInvariant());
            assertThat(q.topNextTimestamp(), equalTo(20L));
        }
    }

    public void testTwoSlicesAlreadyOrdered() {
        // slice A: 50,40 ; slice B: 30,20 — A should stay root
        try (LongBuffer timestamps = newTimestamps(new long[] { 50, 40, 30, 20 })) {
            int[] sliceOffsets = { 0, 2, 2, 4 };
            FlushQueue q = buildQueue(timestamps, sliceOffsets, 0, 2);
            assertThat(q.size(), equalTo(2));
            assertThat(q.topNextTimestamp(), equalTo(50L));
            assertThat(q.secondNextTimestamp(), equalTo(30L));
            assertTrue(q.checkHeapInvariant());
        }
    }

    public void testTwoSlicesRequireSwap() {
        // first pair older, second newer — heapify must swap
        try (LongBuffer timestamps = newTimestamps(new long[] { 10, 5, 40, 30 })) {
            int[] sliceOffsets = { 0, 2, 2, 4 };
            FlushQueue q = buildQueue(timestamps, sliceOffsets, 0, 2);
            assertThat(q.topNextTimestamp(), equalTo(40L));
            assertThat(q.topStart(), equalTo(2));
            assertTrue(q.checkHeapInvariant());
        }
    }

    public void testThreeOrMoreSlices() {
        try (LongBuffer timestamps = newTimestamps(new long[] { 10, 9, 50, 40, 30, 20 })) {
            int[] sliceOffsets = { 0, 2, 2, 4, 4, 6 };
            FlushQueue q = buildQueue(timestamps, sliceOffsets, 0, 3);
            assertThat(q.size(), equalTo(3));
            assertThat(q.topNextTimestamp(), equalTo(50L));
            assertThat(q.secondNextTimestamp(), equalTo(Math.max(10L, 30L)));
            assertTrue(q.checkHeapInvariant());
        }
    }

    public void testRootAdvanceWithoutExhaustion() {
        try (LongBuffer timestamps = newTimestamps(new long[] { 50, 40, 30, 20 })) {
            int[] sliceOffsets = { 0, 2, 2, 4 };
            FlushQueue q = buildQueue(timestamps, sliceOffsets, 0, 2);
            assertThat(q.consumeTop(), equalTo(0));
            assertFalse(q.topExhausted());
            q.updateTop();
            assertTrue(q.checkHeapInvariant());
            // after advancing 50→40, other slice at 30; root may stay or swap depending on 40 vs 30
            assertThat(q.topNextTimestamp(), equalTo(40L));
        }
    }

    public void testRootExhaustionAndReplacement() {
        try (LongBuffer timestamps = newTimestamps(new long[] { 50, 30, 20 })) {
            int[] sliceOffsets = { 0, 1, 1, 3 };
            FlushQueue q = buildQueue(timestamps, sliceOffsets, 0, 2);
            assertThat(q.consumeTop(), equalTo(0));
            assertTrue(q.topExhausted());
            q.popTop();
            assertThat(q.size(), equalTo(1));
            assertThat(q.topNextTimestamp(), equalTo(30L));
            assertTrue(q.checkHeapInvariant());
        }
    }

    public void testRepeatedRootRemovals() {
        try (LongBuffer timestamps = newTimestamps(new long[] { 90, 80, 70, 60, 50, 40 })) {
            int[] sliceOffsets = { 0, 2, 2, 4, 4, 6 };
            FlushQueue q = buildQueue(timestamps, sliceOffsets, 0, 3);
            List<Long> order = new ArrayList<>();
            while (q.size() > 0) {
                order.add(timestamps.get(q.consumeTop()));
                if (q.topExhausted()) {
                    q.popTop();
                } else {
                    q.updateTop();
                }
                assertTrue(q.checkHeapInvariant());
            }
            assertThat(order, equalTo(List.of(90L, 80L, 70L, 60L, 50L, 40L)));
        }
    }

    public void testEqualTimestampsPreferSmallerStart() {
        // both slices start at ts=100; smaller start index should win
        try (LongBuffer timestamps = newTimestamps(new long[] { 100, 50, 100, 40 })) {
            int[] sliceOffsets = { 2, 4, 0, 2 }; // deliberately put higher start first
            FlushQueue q = buildQueue(timestamps, sliceOffsets, 0, 2);
            assertThat(q.topStart(), equalTo(0));
            assertThat(q.topNextTimestamp(), equalTo(100L));
            assertTrue(q.checkHeapInvariant());
        }
    }

    public void testEmptyRangesExcluded() {
        try (LongBuffer timestamps = newTimestamps(new long[] { 30, 20 })) {
            int[] sliceOffsets = { 0, 0, 0, 2, 2, 2 };
            FlushQueue q = buildQueue(timestamps, sliceOffsets, 0, 3);
            assertThat(q.size(), equalTo(1));
            assertThat(q.valueCount, equalTo(2));
            assertThat(q.topNextTimestamp(), equalTo(30L));
        }
    }

    public void testLengthOneSlices() {
        try (LongBuffer timestamps = newTimestamps(new long[] { 10, 30, 20 })) {
            int[] sliceOffsets = { 0, 1, 1, 2, 2, 3 };
            FlushQueue q = buildQueue(timestamps, sliceOffsets, 0, 3);
            assertThat(q.topNextTimestamp(), equalTo(30L));
            assertThat(q.consumeTop(), equalTo(1));
            assertTrue(q.topExhausted());
            q.popTop();
            assertTrue(q.checkHeapInvariant());
            assertThat(q.topNextTimestamp(), equalTo(20L));
        }
    }

    public void testMergeMatchesReferenceDistinctTimestamps() {
        for (int iter = 0; iter < 100; iter++) {
            MergeCase c = randomMergeCase(false);
            try (LongBuffer timestamps = newTimestamps(c.timestamps)) {
                MergeResult expected = referenceMerge(c.timestamps, c.values, c.sliceOffsets);
                MergeResult actual = primitiveMerge(timestamps, c.values, Arrays.copyOf(c.sliceOffsets, c.sliceOffsets.length));
                assertThat("iter=" + iter, actual, equalTo(expected));
            }
        }
    }

    public void testMergeMatchesReferenceWithDuplicateTimestamps() {
        for (int iter = 0; iter < 100; iter++) {
            MergeCase c = randomMergeCase(true);
            try (LongBuffer timestamps = newTimestamps(c.timestamps)) {
                MergeResult expected = referenceMerge(c.timestamps, c.values, c.sliceOffsets);
                MergeResult actual = primitiveMerge(timestamps, c.values, Arrays.copyOf(c.sliceOffsets, c.sliceOffsets.length));
                assertThat("iter=" + iter, actual, equalTo(expected));
            }
        }
    }

    public void testPrimitiveMatchesLegacyOnDistinctTimestamps() {
        for (int iter = 0; iter < 100; iter++) {
            MergeCase c = randomMergeCase(false);
            try (LongBuffer timestamps = newTimestamps(c.timestamps)) {
                MergeResult legacy = legacyMerge(timestamps, c.values, c.sliceOffsets);
                MergeResult primitive = primitiveMerge(timestamps, c.values, Arrays.copyOf(c.sliceOffsets, c.sliceOffsets.length));
                assertThat("iter=" + iter, primitive, equalTo(legacy));
            }
        }
    }

    public void testBatchPathNonOverlappingSlices() {
        // non-overlapping: first slice entirely newer than second
        long[] ts = { 100, 90, 80, 50, 40, 30 };
        long[] values = { 1, 2, 3, 4, 5, 6 };
        try (LongBuffer timestamps = newTimestamps(ts)) {
            int[] sliceOffsets = { 0, 3, 3, 6 };
            MergeResult actual = primitiveMerge(timestamps, values, sliceOffsets);
            MergeResult expected = referenceMerge(ts, values, new int[] { 0, 3, 3, 6 });
            assertThat(actual, equalTo(expected));
        }
    }

    private static FlushQueue buildQueue(LongBuffer timestamps, int[] sliceOffsets, int startIndex, int endIndex) {
        if (startIndex == 0 && endIndex == sliceOffsets.length / 2) {
            return FlushQueue.fromSliceOffsets(timestamps, sliceOffsets);
        }
        int write = startIndex;
        int valueCount = 0;
        for (int i = startIndex; i < endIndex; i++) {
            int start = sliceOffsets[i * 2];
            int end = sliceOffsets[i * 2 + 1];
            if (start < end) {
                if (write != i) {
                    sliceOffsets[write * 2] = start;
                    sliceOffsets[write * 2 + 1] = end;
                }
                valueCount += end - start;
                write++;
            }
        }
        int size = write - startIndex;
        if (valueCount == 0) {
            return null;
        }
        return new FlushQueue(timestamps, sliceOffsets, startIndex * 2, size, valueCount);
    }

    private LongBuffer newTimestamps(long[] values) {
        LongBuffer buf = new LongBuffer(blockFactory().breaker(), values.length);
        buf.ensureCapacity(values.length);
        for (long v : values) {
            buf.append(v);
        }
        return buf;
    }

    private record MergeCase(long[] timestamps, long[] values, int[] sliceOffsets) {}

    private record MergeResult(int samples, long resets, long lastTs, long lastValue, long firstTs, long firstValue) {}

    private MergeCase randomMergeCase(boolean allowDuplicateTimestamps) {
        int numSlices = randomIntBetween(1, 8);
        int[] lengths = new int[numSlices];
        int total = 0;
        for (int s = 0; s < numSlices; s++) {
            lengths[s] = allowDuplicateTimestamps ? randomIntBetween(0, 16) : randomIntBetween(1, 8);
            total += lengths[s];
        }
        if (total == 0) {
            lengths[0] = randomIntBetween(1, 4);
            total = lengths[0];
        }
        long[] allTs = new long[total];
        long[] allValues = new long[total];
        if (allowDuplicateTimestamps) {
            long t = randomLongBetween(1_000, 1_000_000);
            for (int i = 0; i < total; i++) {
                allTs[i] = t;
                allValues[i] = randomLongBetween(0, 1000);
                if (rarely() == false) {
                    t -= randomLongBetween(0, 20);
                }
            }
        } else {
            // distinct timestamps 0..total-1, shuffled as (ts,value) pairs
            for (int i = 0; i < total; i++) {
                allTs[i] = i;
                allValues[i] = randomLongBetween(0, 1000);
            }
            for (int i = total - 1; i > 0; i--) {
                int j = randomIntBetween(0, i);
                long tmpTs = allTs[i];
                allTs[i] = allTs[j];
                allTs[j] = tmpTs;
                long tmpV = allValues[i];
                allValues[i] = allValues[j];
                allValues[j] = tmpV;
            }
        }
        int[] sliceOffsets = new int[numSlices * 2];
        int pos = 0;
        for (int s = 0; s < numSlices; s++) {
            int start = pos;
            int end = pos + lengths[s];
            // sort slice ascending by timestamp, keeping values paired, then reverse to descending
            Integer[] order = new Integer[end - start];
            for (int i = 0; i < order.length; i++) {
                order[i] = start + i;
            }
            Arrays.sort(order, Comparator.comparingLong(i -> allTs[i]));
            long[] sortedTs = new long[order.length];
            long[] sortedVals = new long[order.length];
            for (int i = 0; i < order.length; i++) {
                int src = order[order.length - 1 - i]; // descending
                sortedTs[i] = allTs[src];
                sortedVals[i] = allValues[src];
            }
            System.arraycopy(sortedTs, 0, allTs, start, order.length);
            System.arraycopy(sortedVals, 0, allValues, start, order.length);
            sliceOffsets[s * 2] = start;
            sliceOffsets[s * 2 + 1] = end;
            pos = end;
        }
        return new MergeCase(allTs, allValues, sliceOffsets);
    }

    /** Reference: materialize all samples, sort by (ts desc, index asc), compute endpoints and resets. */
    private static MergeResult referenceMerge(long[] timestamps, long[] values, int[] sliceOffsets) {
        record Sample(long ts, long value, int index) {}
        List<Sample> samples = new ArrayList<>();
        int numSlices = sliceOffsets.length / 2;
        for (int s = 0; s < numSlices; s++) {
            int start = sliceOffsets[s * 2];
            int end = sliceOffsets[s * 2 + 1];
            for (int i = start; i < end; i++) {
                samples.add(new Sample(timestamps[i], values[i], i));
            }
        }
        if (samples.isEmpty()) {
            return new MergeResult(0, 0, 0, 0, 0, 0);
        }
        samples.sort(Comparator.comparingLong(Sample::ts).reversed().thenComparingInt(Sample::index));
        long lastTs = samples.get(0).ts;
        long lastValue = samples.get(0).value;
        long prev = lastValue;
        long resets = 0;
        for (int i = 1; i < samples.size(); i++) {
            long val = samples.get(i).value;
            if (val > prev) {
                resets += val;
            }
            prev = val;
        }
        Sample oldest = samples.get(samples.size() - 1);
        return new MergeResult(samples.size(), resets, lastTs, lastValue, oldest.ts, prev);
    }

    /** Merge using the primitive FlushQueue with the same control flow as flushGroup. */
    private static MergeResult primitiveMerge(LongBuffer timestamps, long[] values, int[] sliceOffsets) {
        FlushQueue q = buildQueue(timestamps, sliceOffsets, 0, sliceOffsets.length / 2);
        if (q == null) {
            return new MergeResult(0, 0, 0, 0, 0, 0);
        }
        if (q.valueCount == 1) {
            int p = q.topStart();
            long t = timestamps.get(p);
            long v = values[p];
            return new MergeResult(1, 0, t, v, t, v);
        }
        int position = q.consumeTop();
        long lastTimestamp = timestamps.get(position);
        long lastValue = values[position];
        if (q.topExhausted()) {
            q.popTop();
        } else {
            q.updateTop();
        }
        assertTrue(q.checkHeapInvariant());
        long prevValue = lastValue;
        long resets = 0;
        long secondNextTimestamp = q.secondNextTimestamp();
        while (q.size() > 1) {
            if (q.topLastTimestamp() > secondNextTimestamp) {
                for (int p = q.topStart(); p < q.topEnd(); p++) {
                    long val = values[p];
                    if (val > prevValue) {
                        resets += val;
                    }
                    prevValue = val;
                }
                q.popTop();
                secondNextTimestamp = q.secondNextTimestamp();
                assertTrue(q.checkHeapInvariant());
                continue;
            }
            long val = values[q.consumeTop()];
            if (val > prevValue) {
                resets += val;
            }
            prevValue = val;
            if (q.topExhausted()) {
                q.popTop();
                secondNextTimestamp = q.secondNextTimestamp();
                assertTrue(q.checkHeapInvariant());
            } else if (q.topNextTimestamp() < secondNextTimestamp) {
                q.updateTop();
                secondNextTimestamp = q.secondNextTimestamp();
                assertTrue(q.checkHeapInvariant());
            }
            // else: lazy path — root still beats both children, heap stays valid without sift
        }
        for (int p = q.topStart(); p < q.topEnd(); p++) {
            long val = values[p];
            if (val > prevValue) {
                resets += val;
            }
            prevValue = val;
        }
        return new MergeResult(q.valueCount, resets, lastTimestamp, lastValue, timestamps.get(q.topEnd() - 1), prevValue);
    }

    /** Legacy Slice + Lucene PriorityQueue merge for differential testing. */
    private static MergeResult legacyMerge(LongBuffer timestamps, long[] values, int[] sliceOffsets) {
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
            return new MergeResult(0, 0, 0, 0, 0, 0);
        }
        if (q.valueCount == 1) {
            long t = timestamps.get(q.top().start);
            long v = values[q.top().start];
            return new MergeResult(1, 0, t, v, t, v);
        }
        LegacySlice top = q.top();
        int position = top.next();
        long lastTimestamp = timestamps.get(position);
        long lastValue = values[position];
        if (top.exhausted()) {
            q.pop();
            top = q.top();
        } else {
            top = q.updateTop();
        }
        long prevValue = lastValue;
        long resets = 0;
        long secondNextTimestamp = q.secondNextTimestamp();
        while (q.size() > 1) {
            if (top.lastTimestamp() > secondNextTimestamp) {
                for (int p = top.start; p < top.end; p++) {
                    long val = values[p];
                    if (val > prevValue) {
                        resets += val;
                    }
                    prevValue = val;
                }
                q.pop();
                top = q.top();
                secondNextTimestamp = q.secondNextTimestamp();
                continue;
            }
            long val = values[top.next()];
            if (val > prevValue) {
                resets += val;
            }
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
            long val = values[p];
            if (val > prevValue) {
                resets += val;
            }
            prevValue = val;
        }
        return new MergeResult(q.valueCount, resets, lastTimestamp, lastValue, timestamps.get(top.end - 1), prevValue);
    }

    /** Previous object-based slice cursor, retained for differential tests. */
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
