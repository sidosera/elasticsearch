/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.expression;

import org.elasticsearch.nativeaccess.NativeAccess;

import java.util.Optional;

/**
 * Loads ES|QL expression kernels from Elasticsearch's native-access layer.
 */
public final class EsqlKernelLibrary {
    private EsqlKernelLibrary() {}

    /**
     * Returns the {@code sqrt(double)} kernel when available.
     */
    public static Optional<AbstractEsqlKernelExpressionEvaluator.DoubleUnaryKernel> sqrtDoubles() {
        return NativeAccess.instance()
            .getEsqlKernelLibrary()
            .filter(org.elasticsearch.nativeaccess.EsqlKernelLibrary::hasSqrtDoubles)
            .map(kernelLibrary -> (values, results, status, positionCount) -> {
                double[] array = values.asArray();
                if (array == null) {
                    throw new IllegalStateException("C++ sqrt evaluator requires array-backed double vector input");
                }
                kernelLibrary.sqrtDoubles(array, results, status, positionCount);
            });
    }
}
