/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.nativeaccess;

/**
 * C++ kernels for ESQL expressions.
 */
public interface EsqlKernelLibrary {
    byte OK = 0;
    byte NULL = 1;

    /**
     * {@code true} when the loaded kernel library exports {@code esql_eval_sqrt_doubles}.
     */
    boolean hasSqrtDoubles();

    /**
     * Applies {@code sqrt} to {@code values}, writing results and per-position {@code status}.
     */
    void sqrtDoubles(double[] values, double[] result, byte[] status, int positionCount);
}
