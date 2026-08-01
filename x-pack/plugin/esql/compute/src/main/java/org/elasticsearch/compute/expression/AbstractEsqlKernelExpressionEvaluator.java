/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.expression;

import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.DoubleVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.WarningSourceLocation;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.core.Releasables;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Base evaluator for single-argument {@code double -> double} functions with an ES|QL kernel implementation.
 */
public abstract class AbstractEsqlKernelExpressionEvaluator implements ExpressionEvaluator {
    private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(AbstractEsqlKernelExpressionEvaluator.class);
    private static final String SCRATCH_BREAKER_LABEL = "esql_kernel_expression_eval";

    private final WarningSourceLocation source;
    private final ExpressionEvaluator field;
    private final DriverContext driverContext;
    private final DoubleUnaryKernel kernel;
    private Warnings warnings;

    protected AbstractEsqlKernelExpressionEvaluator(
        WarningSourceLocation source,
        ExpressionEvaluator field,
        DriverContext driverContext,
        DoubleUnaryKernel kernel
    ) {
        this.source = source;
        this.field = field;
        this.driverContext = driverContext;
        this.kernel = kernel;
    }

    protected abstract void registerKernelNull(Warnings warnings);

    protected abstract String evaluatorName();

    @Override
    public final Block eval(Page page) {
        try (DoubleBlock fieldBlock = (DoubleBlock) field.eval(page)) {
            DoubleVector fieldVector = fieldBlock.asVector();
            if (fieldVector == null) {
                throw new IllegalStateException("ES|QL kernel expression evaluator requires vector input");
            }
            return eval(page.getPositionCount(), fieldVector);
        }
    }

    public final DoubleBlock eval(int positionCount, DoubleVector fieldVector) {
        long resultBytes = arrayBytes(positionCount, Double.BYTES);
        long statusBytes = arrayBytes(positionCount, Byte.BYTES);
        long scratchBytes = resultBytes + statusBytes;
        driverContext.breaker().addEstimateBytesAndMaybeBreak(scratchBytes, SCRATCH_BREAKER_LABEL);
        boolean resultArrayOwnedByBlock = false;
        try {
            double[] results = new double[positionCount];
            byte[] status = new byte[positionCount];
            kernel.eval(fieldVector, results, status, positionCount);
            if (allOk(status, positionCount)) {
                resultArrayOwnedByBlock = true;
                return driverContext.blockFactory().newDoubleArrayVector(results, positionCount, resultBytes).asBlock();
            }
            return buildResult(positionCount, results, status);
        } finally {
            long releaseBytes = statusBytes + (resultArrayOwnedByBlock ? 0 : resultBytes);
            driverContext.breaker().addWithoutBreaking(-releaseBytes, SCRATCH_BREAKER_LABEL);
        }
    }

    private static boolean allOk(byte[] status, int positionCount) {
        for (int p = 0; p < positionCount; p++) {
            if (status[p] != DoubleUnaryKernel.OK) {
                return false;
            }
        }
        return true;
    }

    private DoubleBlock buildResult(int positionCount, double[] results, byte[] status) {
        try (DoubleBlock.Builder result = driverContext.blockFactory().newDoubleBlockBuilder(positionCount)) {
            for (int p = 0; p < positionCount; p++) {
                switch (status[p]) {
                    case DoubleUnaryKernel.OK -> result.appendDouble(results[p]);
                    case DoubleUnaryKernel.NULL -> {
                        registerKernelNull(warnings());
                        result.appendNull();
                    }
                    default -> throw new IllegalStateException("unknown ES|QL kernel expression evaluator status [" + status[p] + "]");
                }
            }
            return result.build();
        }
    }

    private static long arrayBytes(int length, int elementBytes) {
        return RamUsageEstimator.alignObjectSize(RamUsageEstimator.NUM_BYTES_ARRAY_HEADER + (long) length * elementBytes);
    }

    @Override
    public final long baseRamBytesUsed() {
        return BASE_RAM_BYTES_USED + field.baseRamBytesUsed();
    }

    @Override
    public final String toString() {
        return evaluatorName() + "[field=" + field + "]";
    }

    @Override
    public final void close() {
        Releasables.closeExpectNoException(field);
    }

    private Warnings warnings() {
        if (warnings == null) {
            this.warnings = Warnings.createWarnings(driverContext.warningsMode(), source);
        }
        return warnings;
    }

    /**
     * Base factory that requires the kernel implementation to be available.
     */
    public abstract static class Factory implements ExpressionEvaluator.Factory {
        private final WarningSourceLocation source;
        private final ExpressionEvaluator.Factory field;
        private final Supplier<Optional<DoubleUnaryKernel>> kernelLoader;

        protected Factory(
            WarningSourceLocation source,
            ExpressionEvaluator.Factory field,
            Supplier<Optional<DoubleUnaryKernel>> kernelLoader
        ) {
            this.source = source;
            this.field = field;
            this.kernelLoader = kernelLoader;
        }

        @Override
        public final ExpressionEvaluator get(DriverContext context) {
            DoubleUnaryKernel kernel = kernelLoader.get()
                .orElseThrow(() -> new IllegalStateException("ES|QL kernel expression evaluator is unavailable"));
            return newEvaluator(source, field.get(context), context, kernel);
        }

        protected abstract AbstractEsqlKernelExpressionEvaluator newEvaluator(
            WarningSourceLocation source,
            ExpressionEvaluator field,
            DriverContext context,
            DoubleUnaryKernel kernel
        );
    }

    /**
     * Kernel for the single-argument {@code double -> double} evaluator shape.
     */
    @FunctionalInterface
    public interface DoubleUnaryKernel {
        byte OK = 0;
        byte NULL = 1;

        void eval(DoubleVector values, double[] results, byte[] status, int positionCount);
    }
}
