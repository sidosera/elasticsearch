/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.experimental.function.scalar.math;

import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.DoubleVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.expression.AbstractEsqlKernelExpressionEvaluator;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.expression.LoadFromPageEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.test.TestBlockFactory;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.core.tree.Source;

import java.util.Arrays;
import java.util.Optional;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class SqrtEsqlKernelEvaluatorTests extends ESTestCase {
    public void testFactoryUsesKernelBackendWhenAvailable() {
        ExpressionEvaluator.Factory factory = factory(Optional.of(new TestSqrtKernel()));
        try (ExpressionEvaluator evaluator = factory.get(driverContext())) {
            assertThat(evaluator, instanceOf(SqrtEsqlKernelEvaluator.class));
        }
    }

    public void testFactoryFailsWhenBackendUnavailable() {
        ExpressionEvaluator.Factory factory = factory(Optional.empty());
        IllegalStateException e = expectThrows(IllegalStateException.class, () -> factory.get(driverContext()));
        assertThat(e.getMessage(), equalTo("ES|QL kernel expression evaluator is unavailable"));
    }

    public void testKernelVectorPath() {
        DriverContext driverContext = driverContext();
        BlockFactory blockFactory = driverContext.blockFactory();
        double[] values = new double[] { 4.0, 9.0, -1.0 };
        ExpressionEvaluator.Factory factory = factory(Optional.of(new TestSqrtKernel(values)));
        try (
            ExpressionEvaluator evaluator = factory.get(driverContext);
            Page page = new Page(blockFactory.newDoubleArrayVector(values, 3).asBlock());
            DoubleBlock result = (DoubleBlock) evaluator.eval(page)
        ) {
            assertThat(result.getPositionCount(), equalTo(3));
            assertThat(result.getDouble(result.getFirstValueIndex(0)), closeTo(2.0, 0.0));
            assertThat(result.getDouble(result.getFirstValueIndex(1)), closeTo(3.0, 0.0));
            assertThat(result.getValueCount(2), equalTo(0));
        }
        assertWarnings(
            "Line -1:-1: evaluation of [] failed, treating result as null. Only first 20 failures recorded.",
            "Line -1:-1: java.lang.ArithmeticException: Square root of negative"
        );
    }

    public void testNonVectorInputFailsBeforeKernel() {
        DriverContext driverContext = driverContext();
        BlockFactory blockFactory = driverContext.blockFactory();
        ExpressionEvaluator.Factory factory = factory(Optional.of(new ThrowingSqrtKernel()));
        try (ExpressionEvaluator evaluator = factory.get(driverContext); Page page = new Page(blockWithNull(blockFactory))) {
            IllegalStateException e = expectThrows(IllegalStateException.class, () -> evaluator.eval(page));
            assertThat(e.getMessage(), equalTo("ES|QL kernel expression evaluator requires vector input"));
        }
    }

    public void testKernelReceivesVector() {
        DriverContext driverContext = driverContext();
        BlockFactory blockFactory = driverContext.blockFactory();
        DoubleVector values = blockFactory.newConstantDoubleVector(4.0, 2);
        ExpressionEvaluator.Factory factory = factory(Optional.of(new TestSqrtKernel(values)));
        try (
            ExpressionEvaluator evaluator = factory.get(driverContext);
            Page page = new Page(values.asBlock());
            DoubleBlock result = (DoubleBlock) evaluator.eval(page)
        ) {
            assertThat(result.getPositionCount(), equalTo(2));
            assertThat(result.getDouble(result.getFirstValueIndex(0)), closeTo(2.0, 0.0));
            assertThat(result.getDouble(result.getFirstValueIndex(1)), closeTo(2.0, 0.0));
        }
    }

    private static ExpressionEvaluator.Factory factory(Optional<AbstractEsqlKernelExpressionEvaluator.DoubleUnaryKernel> kernel) {
        ExpressionEvaluator.Factory field = new LoadFromPageEvaluator.Factory(0);
        return new SqrtEsqlKernelEvaluator.Factory(Source.EMPTY, field, () -> kernel);
    }

    private static DoubleBlock blockWithNull(BlockFactory blockFactory) {
        try (DoubleBlock.Builder builder = blockFactory.newDoubleBlockBuilder(2)) {
            builder.appendNull();
            builder.appendDouble(4.0);
            return builder.build();
        }
    }

    private static DriverContext driverContext() {
        BlockFactory blockFactory = TestBlockFactory.getNonBreakingInstance();
        return new DriverContext(blockFactory.bigArrays(), blockFactory, null);
    }

    private static class TestSqrtKernel implements AbstractEsqlKernelExpressionEvaluator.DoubleUnaryKernel {
        private final DoubleVector expectedValues;
        private final double[] expectedArray;

        TestSqrtKernel() {
            this(null, null);
        }

        TestSqrtKernel(DoubleVector expectedValues) {
            this(expectedValues, null);
        }

        TestSqrtKernel(double[] expectedArray) {
            this(null, expectedArray);
        }

        private TestSqrtKernel(DoubleVector expectedValues, double[] expectedArray) {
            this.expectedValues = expectedValues;
            this.expectedArray = expectedArray;
        }

        @Override
        public void eval(DoubleVector values, double[] result, byte[] status, int positionCount) {
            if (expectedValues != null) {
                assertSame(expectedValues, values);
            }
            if (expectedArray != null) {
                assertSame(expectedArray, values.asArray());
            }
            Arrays.fill(status, OK);
            for (int p = 0; p < positionCount; p++) {
                if (values.getDouble(p) < 0) {
                    status[p] = NULL;
                } else {
                    result[p] = Math.sqrt(values.getDouble(p));
                }
            }
        }
    }

    private static class ThrowingSqrtKernel implements AbstractEsqlKernelExpressionEvaluator.DoubleUnaryKernel {
        @Override
        public void eval(DoubleVector values, double[] result, byte[] status, int positionCount) {
            throw new AssertionError("kernel backend should not be called for non-vector input");
        }
    }
}
