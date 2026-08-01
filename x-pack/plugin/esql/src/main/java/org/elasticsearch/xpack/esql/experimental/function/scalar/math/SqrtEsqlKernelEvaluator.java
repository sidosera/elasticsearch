/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.experimental.function.scalar.math;

import org.elasticsearch.compute.expression.AbstractEsqlKernelExpressionEvaluator;
import org.elasticsearch.compute.expression.EsqlKernelLibrary;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.WarningSourceLocation;
import org.elasticsearch.compute.operator.Warnings;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * ES|QL kernel fast path for {@code SQRT} double input.
 */
public final class SqrtEsqlKernelEvaluator extends AbstractEsqlKernelExpressionEvaluator {
    SqrtEsqlKernelEvaluator(
        WarningSourceLocation source,
        ExpressionEvaluator field,
        DriverContext driverContext,
        DoubleUnaryKernel kernel
    ) {
        super(source, field, driverContext, kernel);
    }

    @Override
    protected void registerKernelNull(Warnings warnings) {
        warnings.registerException(ArithmeticException.class, "Square root of negative");
    }

    @Override
    protected String evaluatorName() {
        return "SqrtEsqlKernelEvaluator";
    }

    public static class Factory extends AbstractEsqlKernelExpressionEvaluator.Factory {
        public Factory(WarningSourceLocation source, ExpressionEvaluator.Factory field) {
            super(source, field, EsqlKernelLibrary::sqrtDoubles);
        }

        public Factory(
            WarningSourceLocation source,
            ExpressionEvaluator.Factory field,
            Supplier<Optional<DoubleUnaryKernel>> kernelLoader
        ) {
            super(source, field, kernelLoader);
        }

        @Override
        protected SqrtEsqlKernelEvaluator newEvaluator(
            WarningSourceLocation source,
            ExpressionEvaluator field,
            DriverContext context,
            DoubleUnaryKernel kernel
        ) {
            return new SqrtEsqlKernelEvaluator(source, field, context, kernel);
        }

        @Override
        public String toString() {
            return "SqrtEsqlKernelEvaluator.Factory";
        }
    }
}
