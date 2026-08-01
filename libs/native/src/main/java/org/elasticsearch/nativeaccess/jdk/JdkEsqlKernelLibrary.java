/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.nativeaccess.jdk;

import org.elasticsearch.foreign.LoaderHelper;
import org.elasticsearch.nativeaccess.EsqlKernelLibrary;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.elasticsearch.foreign.LinkerHelper.downcallHandle;
import static org.elasticsearch.foreign.LinkerHelper.functionAddressOrNull;

/**
 * FFM bindings to ES|QL expression kernels.
 */
public final class JdkEsqlKernelLibrary implements EsqlKernelLibrary {
    private static final FunctionDescriptor DOUBLE_UNARY = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT);

    private static final MethodHandle sqrtDoubles$mh;

    static {
        LoaderHelper.loadLibrary("esql_eval");
        MemorySegment sqrtDoublesAddress = functionAddressOrNull("esql_eval_sqrt_doubles");
        sqrtDoubles$mh = sqrtDoublesAddress == null ? null : downcallHandle(sqrtDoublesAddress, DOUBLE_UNARY);
    }

    @Override
    public boolean hasSqrtDoubles() {
        return sqrtDoubles$mh != null;
    }

    @Override
    public void sqrtDoubles(double[] values, double[] result, byte[] status, int positionCount) {
        assert values.length >= positionCount;
        assert result.length >= positionCount;
        assert status.length >= positionCount;
        try {
            sqrtDoubles$mh.invokeExact(
                MemorySegment.ofArray(values),
                MemorySegment.ofArray(result),
                MemorySegment.ofArray(status),
                positionCount
            );
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }
}
