/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.promql;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.TestAnalyzer;
import org.elasticsearch.xpack.esql.VerificationException;
import org.elasticsearch.xpack.esql.common.Failures;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.expression.TimeSeriesMetadataAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Sum;
import org.elasticsearch.xpack.esql.expression.function.grouping.TimeSeriesWithout;
import org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataFunction;
import org.elasticsearch.xpack.esql.optimizer.rules.PlanConsistencyChecker;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.join.InnerJoin;
import org.elasticsearch.xpack.esql.plugin.EsqlFlags;

import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.xpack.esql.EsqlTestUtils.analyzer;
import static org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataFunction.Operation.GET;
import static org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataFunction.Operation.HASH;
import static org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataFunction.Operation.SET;
import static org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataFunction.Operation.TO_STRING;
import static org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataFunction.Operation.UNSET;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/** Exercises real query analysis with the prototype flag, plus the disabled and mixed-version compatibility paths. */
public class FunctionalTimeSeriesTranslationTests extends ESTestCase {
    public void testDisabledByDefaultIncludingSnapshots() {
        assertFalse(EsqlFlags.ESQL_PROMQL_FUNCTIONAL_TIMESERIES.getDefault(Settings.EMPTY));
        assertFalse(EsqlFlags.DEFAULTS.promqlFunctionalTimeSeries());
        assertTrue(operations(analyzer().addK8s().query(query("sum without(pod)(network.bytes_in)"))).isEmpty());
    }

    public void testMixedVersionUsesWholeLegacyTranslation() {
        LogicalPlan plan = prototype().minimumTransportVersion(TranslationContext.ESQ_ENABLE_TIMESERIES_METADATA_ALIASING)
            .query(query("sum without(pod)(network.bytes_in)"));
        assertTrue(operations(plan).isEmpty());
        assertFalse(plan.collect(EsRelation.class).isEmpty());
    }

    public void testUnprototypedFamilyUsesWholeLegacyTranslation() {
        LogicalPlan plan = prototype().query(query("topk(3, network.bytes_in)"));
        assertTrue(operations(plan).isEmpty());
    }

    public void testMixedVersionRetainsLegacyValidation() {
        var error = expectThrows(
            VerificationException.class,
            () -> prototype().minimumTransportVersion(TranslationContext.ESQ_ENABLE_TIMESERIES_METADATA_ALIASING)
                .query(query("sum without(cluster)(sum without(pod)(network.bytes_in))"))
        );
        assertThat(error.getMessage(), containsString("nested WITHOUT over WITHOUT"));
    }

    public void testRepeatedWithoutReadsOnlyOneSourceIdentity() {
        LogicalPlan plan = prototype().query(query("sum without(cluster)(sum without(pod)(network.bytes_in))"));
        assertThat(operations(plan), hasItem(UNSET));
        assertThat(operations(plan), hasItem(HASH));
        assertThat(operations(plan), hasItem(TO_STRING));
        assertOneMetadataReader(plan);
        plan.forEachExpressionDown(TimeSeriesWithout.class, e -> fail("functional path must not request source projections"));
    }

    public void testDirectByDoesNotReadMetadata() {
        LogicalPlan plan = prototype().query(query("sum by(cluster, pod)(rate(network.bytes_in[5m]))"));
        assertTrue(operations(plan).isEmpty());
        for (EsRelation relation : plan.collect(EsRelation.class)) {
            assertFalse(relation.output().stream().anyMatch(TimeSeriesMetadataAttribute.class::isInstance));
        }
    }

    public void testRfcShapeBecomesFiniteAtBy() {
        LogicalPlan plan = prototype().query(
            query(
                "sum without(disk)(label_replace("
                    + "sum by(cluster, pod)(sum without(host)(rate(network.bytes_in[5m]))),"
                    + " \"disk\", \"$1\", \"pod\", \"^(.+)$\"))"
            )
        );
        assertThat(operations(plan), hasItem(UNSET));
        assertThat(operations(plan), not(hasItem(SET)));
        assertThat(operations(plan), not(hasItem(TO_STRING)));
        assertOneMetadataReader(plan);
    }

    public void testIgnoringRawLabelsDerivesMatchKeysFromCurrentIdentity() {
        LogicalPlan plan = prototype().query(query("network.bytes_in / ignoring(pod) group_left network.bytes_in"));
        assertEquals(1, plan.collect(InnerJoin.class).size());
        assertThat(operations(plan), hasItem(UNSET));
        assertOneMetadataReader(plan);
    }

    public void testCopyIntoRawManySideUsesSet() {
        LogicalPlan plan = prototype().query(query("network.bytes_in / on(cluster) group_left(pod) network.bytes_in"));
        assertThat(operations(plan), hasItem(SET));
        assertOneMetadataReader(plan);
    }

    public void testFiniteLabelsStayColumnsAfterBy() {
        LogicalPlan plan = prototype().query(
            query("sum without(pod)(label_replace(sum by(cluster, pod)(network.bytes_in)," + " \"pod\", \"$1\", \"cluster\", \"(.+)\"))")
        );
        assertThat(operations(plan), not(hasItem(SET)));
        assertThat(operations(plan), not(hasItem(TO_STRING)));
        assertFalse(plan.collect(CheckSeriesUniqueness.class).isEmpty());
        assertOneMetadataReader(plan);
    }

    public void testRuntimeRelabelUsesGetSetAndChecksCollisions() {
        LogicalPlan plan = prototype().query(query("label_replace(network.bytes_in, \"pod\", \"$1\", \"cluster\", \"(.+)\")"));
        assertThat(operations(plan), hasItem(GET));
        assertThat(operations(plan), hasItem(SET));
        assertEquals(1, plan.collect(CheckSeriesUniqueness.class).size());
        assertOneMetadataReader(plan);
    }

    public void testNewLabelSurvivesFiniteRelabel() {
        LogicalPlan plan = prototype().query(
            query("label_replace(sum by(cluster)(network.bytes_in), \"disk\", \"$1\", \"cluster\", \"(.+)\")")
        );
        assertThat(plan.output().stream().map(a -> a.name()).toList(), hasItem("disk"));
        assertThat(operations(plan), not(hasItem(SET)));
        assertThat(operations(plan), not(hasItem(TO_STRING)));
    }

    public void testGroupRightBuildsFromTheOneSide() {
        LogicalPlan plan = prototype().query(query("sum by(cluster)(network.bytes_in) / on(cluster) group_right network.bytes_in"));
        InnerJoin join = plan.collect(InnerJoin.class).getFirst();
        var buildSums = new ArrayList<Sum>();
        join.right().forEachExpressionDown(Sum.class, buildSums::add);
        assertFalse(buildSums.isEmpty());
        join.left().forEachExpressionDown(Sum.class, sum -> fail("the aggregate is the build input"));
        assertFalse(join.unique());
        assertOneMetadataReader(plan);
    }

    public void testNestedJoinsPreserveCurrentMetadata() {
        LogicalPlan plan = prototype().query(
            query(
                "(network.bytes_in / on(cluster) group_left sum by(cluster)(network.bytes_in))"
                    + " / on(cluster) group_left sum by(cluster)(network.bytes_in)"
            )
        );
        assertEquals(2, plan.collect(InnerJoin.class).size());
        assertOneMetadataReader(plan);
    }

    public void testDefaultRawToFiniteMatchUsesFullIdentity() {
        LogicalPlan plan = prototype().query(query("network.bytes_in / sum by(cluster)(network.bytes_in)"));
        assertEquals(1, plan.collect(InnerJoin.class).size());
        assertThat(operations(plan), hasItem(HASH));
        assertThat(plan.toString(), not(containsString("{}")));
        assertOneMetadataReader(plan);
    }

    public void testOnGroupLeftKeepsManySideIdentity() {
        LogicalPlan plan = prototype().query(query("network.bytes_in / on(cluster) group_left sum by(cluster)(network.bytes_in)"));
        assertEquals(1, plan.collect(InnerJoin.class).size());
        assertThat(operations(plan), hasItem(TO_STRING));
        assertOneMetadataReader(plan);
    }

    public void testValueFunctionsAndScalarArithmeticKeepTypedLabels() {
        LogicalPlan plan = prototype().query(query("ceil(rate(network.bytes_in[5m])) / 2"));
        assertOneMetadataReader(plan);
        assertThat(operations(plan), hasItem(TO_STRING));
    }

    public void testComparisonFilterKeepsTypedLabels() {
        LogicalPlan plan = prototype().query(query("network.bytes_in > 0"));
        assertOneMetadataReader(plan);
        assertThat(operations(plan), hasItem(TO_STRING));
    }

    public void testClassicHistogramUsesCurrentMetadata() {
        LogicalPlan plan = prototype().query(query("histogram_quantile(0.9, rate(network.bytes_in[5m]))"));
        assertOneMetadataReader(plan);
        assertThat(operations(plan), hasItem(GET));
        assertThat(operations(plan), hasItem(UNSET));
    }

    public void testMetadataOperationsAreTyped() {
        var metadata = new ReferenceAttribute(Source.EMPTY, "_timeseries", DataType.TIMESERIES);
        var set = new TimeSeriesMetadataFunction(Source.EMPTY, SET, List.of("pod"), List.of(metadata, Literal.keyword(Source.EMPTY, "a")));
        var unset = new TimeSeriesMetadataFunction(Source.EMPTY, UNSET, List.of("pod"), List.of(set));
        assertEquals(DataType.TIMESERIES, set.dataType());
        assertEquals(DataType.TIMESERIES, unset.dataType());
        assertTrue(set.typeResolved().resolved());
        assertTrue(unset.typeResolved().resolved());
        for (var operation : List.of(GET, HASH, TO_STRING)) {
            var expression = new TimeSeriesMetadataFunction(
                Source.EMPTY,
                operation,
                operation == GET ? List.of("pod") : List.of(),
                List.of(unset)
            );
            assertEquals(DataType.KEYWORD, expression.dataType());
            assertTrue(expression.typeResolved().resolved());
        }
        for (var operation : TimeSeriesMetadataFunction.Operation.values()) {
            var arguments = operation == SET
                ? List.<Expression>of(Literal.keyword(Source.EMPTY, "{}"), Literal.keyword(Source.EMPTY, "a"))
                : List.<Expression>of(Literal.keyword(Source.EMPTY, "{}"));
            var expression = new TimeSeriesMetadataFunction(
                Source.EMPTY,
                operation,
                operation == SET || operation == GET ? List.of("pod") : List.of(),
                arguments
            );
            assertFalse(expression.typeResolved().resolved());
        }
    }

    private static TestAnalyzer prototype() {
        return analyzer().addK8s()
            .minimumTransportVersion(TranslationContext.PROMQL_FUNCTIONAL_TIMESERIES)
            .flags(EsqlFlags.DEFAULTS.withPromqlFunctionalTimeSeries(true));
    }

    private static String query(String expression) {
        return "PROMQL index=k8s step=1h result=(" + expression + ")";
    }

    private static List<TimeSeriesMetadataFunction.Operation> operations(LogicalPlan plan) {
        var operations = new ArrayList<TimeSeriesMetadataFunction.Operation>();
        plan.forEachExpressionDown(TimeSeriesMetadataFunction.class, function -> operations.add(function.operation()));
        return operations;
    }

    private static void assertOneMetadataReader(LogicalPlan plan) {
        Failures failures = new Failures();
        plan.forEachUp(node -> PlanConsistencyChecker.checkPlan(node, failures));
        assertFalse(failures.toString(), failures.hasFailures());
        plan.forEachUp(
            node -> assertTrue(
                node.nodeName() + " exposes more than one metadata value",
                node.output().stream().filter(attribute -> attribute.dataType() == DataType.TIMESERIES).count() <= 1
            )
        );
        plan.forEachExpressionDown(TimeSeriesMetadataFunction.class, function -> {
            assertTrue(function.typeResolved().toString(), function.typeResolved().resolved());
            assertEquals(DataType.TIMESERIES, function.children().getFirst().dataType());
        });
        for (EsRelation relation : plan.collect(EsRelation.class)) {
            relation.output()
                .stream()
                .filter(TimeSeriesMetadataAttribute.class::isInstance)
                .forEach(attribute -> assertEquals(DataType.TIMESERIES, attribute.dataType()));
            assertTrue(relation.output().stream().filter(TimeSeriesMetadataAttribute.class::isInstance).count() <= 1);
        }
    }
}
