/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.promql;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.xpack.esql.VerificationException;
import org.elasticsearch.xpack.esql.action.EsqlCapabilities;
import org.elasticsearch.xpack.esql.common.Failures;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.MetadataAttribute;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.plan.logical.ExecutesOn;
import org.elasticsearch.xpack.esql.plan.logical.LimitRatioBy;
import org.elasticsearch.xpack.esql.plan.logical.PipelineBreaker;
import org.junit.Before;

import java.util.List;

import static org.elasticsearch.xpack.esql.EsqlTestUtils.as;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;

public class PromqlPlanLimitRatioTests extends AbstractPromqlPlanOptimizerTests {

    public PromqlPlanLimitRatioTests(VersionMode versionMode) {
        super(versionMode);
    }

    @Before
    public void assumeLimitRatioEnabled() {
        assumeTrue("Requires PROMQL_LIMIT_RATIO capability", EsqlCapabilities.Cap.PROMQL_LIMIT_RATIO.isEnabled());
    }

    /**
     * {@code limit_ratio} over an aggregate samples result series, not raw series: the input rows are
     * groups carrying no {@code _timeseries}, so the sampling key is the concrete group columns from
     * below (here {@code pod}) appended to the groupings -- no synthesized key, no extra plan node.
     */
    public void testLimitRatioOverAggregateKeysOnGroupColumns() {
        var plan = logicalOptimizerWithLatestVersion.optimize(
            planPromql("PROMQL index=k8s step=1h result=(limit_ratio(0.5, sum by (pod) (network.total_bytes_in{cluster=\"prod\"})))", false)
        );

        var node = as(plan.collect(LimitRatioBy.class).get(0), LimitRatioBy.class);
        assertThat(node.groupings().size(), equalTo(2));
        Attribute pod = as(node.groupings().get(1), Attribute.class);
        assertThat(pod.name(), equalTo("pod"));
        assertThat(node.child().output().stream().map(Attribute::id).toList(), hasItem(pod.id()));
    }

    /**
     * At series grain the sampling key is the {@code _timeseries} blob appended to the groupings.
     */
    public void testLimitRatioBareKeysOnTimeseries() {
        var node = limitRatioByNode();
        assertThat(node.groupings().size(), equalTo(2));
        Attribute key = as(node.groupings().get(1), Attribute.class);
        assertThat(MetadataAttribute.isTimeSeriesAttribute(key), equalTo(true));
        assertThat(node.child().output().stream().map(Attribute::id).toList(), hasItem(key.id()));
    }

    public void testLimitRatioProducesLimitRatioBy() {
        var plan = logicalOptimizerWithLatestVersion.optimize(
            planPromql("PROMQL index=k8s step=1h result=(limit_ratio(0.5, network.bytes_in))", false)
        );

        var node = as(plan.collect(LimitRatioBy.class).get(0), LimitRatioBy.class);
        assertThat(((Number) node.ratio().fold(FoldContext.small())).doubleValue(), closeTo(0.5, 1e-10));
    }

    /**
     * Unlike aggregations, {@code limit_ratio} keeps the full label identity of each selected series.
     */
    public void testLimitRatioBareKeepsFullSeriesIdentity() {
        var plan = logicalOptimizerWithLatestVersion.optimize(
            planPromql("PROMQL index=k8s step=1h result=(limit_ratio(0.3, network.bytes_in))", false)
        );

        assertThat(plan.output().stream().map(Attribute::name).toList(), hasItem(MetadataAttribute.TIMESERIES));
    }

    /**
     * {@code limit_ratio(...) by (...)} is membership-neutral like Prometheus: the outer partitions
     * neither join the sampling key nor change which series are kept. The key stays the input
     * identity ({@code _timeseries} at series grain) and full series identity is preserved.
     */
    public void testLimitRatioByGroupingPartitionsByLabelAndKeepsFullIdentity() {
        var plan = logicalOptimizerWithLatestVersion.optimize(
            planPromql("PROMQL index=k8s step=1h result=(limit_ratio(0.5, network.bytes_in) by (pod))", false)
        );

        assertThat(plan.output().stream().map(Attribute::name).toList(), hasItem(MetadataAttribute.TIMESERIES));

        var node = as(plan.collect(LimitRatioBy.class).get(0), LimitRatioBy.class);
        // Membership-neutral: only step plus the series identity, no outer partition label.
        assertThat(node.groupings().size(), equalTo(2));
        Attribute key = as(node.groupings().get(1), Attribute.class);
        assertThat(MetadataAttribute.isTimeSeriesAttribute(key), equalTo(true));
    }

    /**
     * Bare and outer-grouped {@code limit_ratio} sample on identical keys, so they select
     * identical series: the outer {@code by} must not append partition labels to the key.
     */
    public void testLimitRatioOuterGroupingIsMembershipNeutral() {
        var bare = limitRatioByNode("PROMQL index=k8s step=1h result=(limit_ratio(0.5, network.bytes_in))");
        var grouped = limitRatioByNode("PROMQL index=k8s step=1h result=(limit_ratio(0.5, network.bytes_in) by (pod))");

        assertThat(samplingKeyNames(bare), equalTo(samplingKeyNames(grouped)));
        assertThat(bare.groupings().size(), equalTo(2));
        assertThat(grouped.groupings().size(), equalTo(2));
        assertThat(MetadataAttribute.isTimeSeriesAttribute(as(grouped.groupings().get(1), Attribute.class)), equalTo(true));
    }

    /**
     * A missing outer partition label must not materialize an extra null key column: it ranks
     * as one partition for order-statistic reductions, but {@code limit_ratio} ignores it entirely.
     */
    public void testLimitRatioMissingOuterLabelAddsNoNullKey() {
        var bare = limitRatioByNode("PROMQL index=k8s step=1h result=(limit_ratio(0.5, network.bytes_in))");
        var missing = limitRatioByNode("PROMQL index=k8s step=1h result=(limit_ratio(0.5, network.bytes_in) by (does_not_exist))");

        assertThat(samplingKeyNames(missing), equalTo(samplingKeyNames(bare)));
        assertThat(missing.groupings().size(), equalTo(2));
        assertThat(missing.child().output().stream().noneMatch(a -> a.name().equals("does_not_exist")), equalTo(true));
    }

    /**
     * Reordered outer grouping labels sample identically: the key derives solely from the input
     * identity, so label order in the outer {@code by} cannot change the hash.
     */
    public void testLimitRatioReorderedOuterGroupingIdentical() {
        var bare = limitRatioByNode("PROMQL index=k8s step=1h result=(limit_ratio(0.5, network.bytes_in))");
        var ordered = limitRatioByNode("PROMQL index=k8s step=1h result=(limit_ratio(0.5, network.bytes_in) by (pod, cluster))");
        var reordered = limitRatioByNode("PROMQL index=k8s step=1h result=(limit_ratio(0.5, network.bytes_in) by (cluster, pod))");

        assertThat(samplingKeyNames(ordered), equalTo(samplingKeyNames(bare)));
        assertThat(samplingKeyNames(reordered), equalTo(samplingKeyNames(bare)));
    }

    /**
     * Over an aggregate the sampling key is the aggregated identity (here {@code pod, cluster}),
     * regardless of any outer {@code by}: outer partitions must not narrow or widen the hashed set.
     */
    public void testLimitRatioOverSumByIgnoresOuterGrouping() {
        var bare = limitRatioByNode("PROMQL index=k8s step=1h result=(limit_ratio(0.5, sum by (pod, cluster) (network.bytes_in)))");
        var outer = limitRatioByNode(
            "PROMQL index=k8s step=1h result=(limit_ratio(0.5, sum by (pod, cluster) (network.bytes_in)) by (pod))"
        );
        var reorderedOuter = limitRatioByNode(
            "PROMQL index=k8s step=1h result=(limit_ratio(0.5, sum by (pod, cluster) (network.bytes_in)) by (cluster, pod))"
        );
        var missingOuter = limitRatioByNode(
            "PROMQL index=k8s step=1h result=(limit_ratio(0.5, sum by (pod, cluster) (network.bytes_in)) by (does_not_exist))"
        );

        assertThat(samplingKeyNames(bare).stream().sorted().toList(), equalTo(List.of("cluster", "pod")));
        assertThat(samplingKeyNames(outer).stream().sorted().toList(), equalTo(List.of("cluster", "pod")));
        assertThat(samplingKeyNames(reorderedOuter).stream().sorted().toList(), equalTo(List.of("cluster", "pod")));
        assertThat(samplingKeyNames(missingOuter).stream().sorted().toList(), equalTo(List.of("cluster", "pod")));
    }

    /**
     * A constant vector's empty label set is a valid sampling identity: the sampler applies
     * directly with an empty key (step only, excluded from hashing) and no aggregation.
     * Ratio zero keeps nothing, so the instant query yields zero rows.
     */
    public void testLimitRatioOverConstantInstantRatioZeroHasEmptyKey() {
        var plan = logicalOptimizerWithLatestVersion.optimize(
            planPromql("PROMQL index=empty_index time=\"2025-01-01T00:00:00Z\" result=(limit_ratio(0, vector(1)))", false, false)
        );

        var node = as(plan.collect(LimitRatioBy.class).get(0), LimitRatioBy.class);
        assertThat(((Number) node.ratio().fold(FoldContext.small())).doubleValue(), closeTo(0.0, 1e-12));
        // Empty sampling key: step only, excluded from hashing.
        assertThat(node.groupings().size(), equalTo(1));
        assertThat(plan.collect(org.elasticsearch.xpack.esql.plan.logical.Aggregate.class).isEmpty(), equalTo(true));
        assertThat(plan.collect(org.elasticsearch.xpack.esql.plan.logical.TimeSeriesAggregate.class).isEmpty(), equalTo(true));
        var failures = new Failures();
        node.postOptimizationVerification(failures);
        assertThat(failures.hasFailures(), equalTo(false));
    }

    /**
     * Ratio one over a constant keeps the single empty identity.
     */
    public void testLimitRatioOverConstantInstantRatioOneHasEmptyKey() {
        var plan = logicalOptimizerWithLatestVersion.optimize(
            planPromql("PROMQL index=empty_index time=\"2025-01-01T00:00:00Z\" result=(limit_ratio(1, vector(1)))", false, false)
        );

        var node = as(plan.collect(LimitRatioBy.class).get(0), LimitRatioBy.class);
        assertThat(((Number) node.ratio().fold(FoldContext.small())).doubleValue(), closeTo(1.0, 1e-12));
        assertThat(node.groupings().size(), equalTo(1));
    }

    /**
     * Fractional complementary ratios over the same empty identity keep complementary subsets:
     * exactly one of {@code r} and {@code -(1 - r)} keeps the row, without asserting which one
     * (the hash seed varies between JVMs).
     */
    public void testLimitRatioOverConstantComplementaryRatios() {
        var positive = limitRatioByNode(
            "PROMQL index=empty_index time=\"2025-01-01T00:00:00Z\" result=(limit_ratio(0.3, vector(1)))",
            false
        );
        var negative = limitRatioByNode(
            "PROMQL index=empty_index time=\"2025-01-01T00:00:00Z\" result=(limit_ratio(-0.7, vector(1)))",
            false
        );

        assertThat(positive.groupings().size(), equalTo(1));
        assertThat(negative.groupings().size(), equalTo(1));
        double rPos = ((Number) positive.ratio().fold(FoldContext.small())).doubleValue();
        double rNeg = ((Number) negative.ratio().fold(FoldContext.small())).doubleValue();
        assertThat(rPos, closeTo(0.3, 1e-12));
        assertThat(rNeg, closeTo(-0.7, 1e-12));
        // Both share the same empty identity, so exactly one keeps it: the offsets are
        // below 0.3 versus at or above 0.3. Which one keeps varies with the per-JVM hash seed,
        // so complementarity itself is pinned at operator level (empty-key tests) rather than
        // asserting a particular fractional subset here.
    }

    /**
     * A range query over a constant shares one empty identity across steps, so a fractional
     * ratio keeps either all steps or none -- never a strict subset -- consistently.
     */
    public void testLimitRatioOverConstantRangeConsistentAcrossSteps() {
        var plan = logicalOptimizerWithLatestVersion.optimize(
            planPromql(
                "PROMQL index=empty_index start=\"2025-01-01T00:00:00Z\" end=\"2025-01-01T00:02:00Z\" step=1m result=(limit_ratio(0.3, vector(1)))",
                false,
                false
            )
        );

        var node = as(plan.collect(LimitRatioBy.class).get(0), LimitRatioBy.class);
        assertThat(node.groupings().size(), equalTo(1));
        // The constant range still fans out to one row per step; the sampler sits above it.
        assertThat(plan.collect(org.elasticsearch.xpack.esql.plan.logical.MvExpand.class).isEmpty(), equalTo(false));
        // Same empty identity at every step hashes identically, so the decision is uniform.
        // (Which way it goes varies with the per-JVM hash seed; only consistency is asserted here.
        // The operator-level test pins all-or-nothing row counts for the empty key.)
    }

    public void testLimitRatioWithoutGroupingNotYetSupported() {
        var e = expectThrows(
            VerificationException.class,
            () -> planPromql("PROMQL index=k8s step=1h result=(limit_ratio(0.5, network.bytes_in) without (pod))", true)
        );
        assertThat(e.getMessage(), containsString("limit_ratio"));
    }

    public void testLimitRatioNodeType() {
        var plan = logicalOptimizerWithLatestVersion.optimize(
            planPromql("PROMQL index=k8s step=1h result=(limit_ratio(0.1, network.bytes_in))", false)
        );

        assertThat(plan.collect(LimitRatioBy.class).get(0), instanceOf(LimitRatioBy.class));
    }

    /**
     * Unlike the other reductions ({@code TopNBy}) the node carries no placement constraints: the
     * hash predicate is per-row stateless and idempotent, so it needs no global per-group view and
     * may run anywhere, including pushed down into data-node fragments.
     */
    public void testLimitRatioHasNoPlacementConstraints() {
        var plan = logicalOptimizerWithLatestVersion.optimize(
            planPromql("PROMQL index=k8s step=1h result=(limit_ratio(0.5, network.bytes_in))", false)
        );

        var node = as(plan.collect(LimitRatioBy.class).get(0), LimitRatioBy.class);
        assertThat(node instanceof PipelineBreaker, equalTo(false));
        assertThat(node instanceof ExecutesOn.Coordinator, equalTo(false));
    }

    /**
     * Like Prometheus, a negative ratio is accepted and keeps the complement subset
     * (offsets at or above {@code 1 + r}).
     */
    public void testLimitRatioNegativeAcceptedAsComplement() {
        var plan = logicalOptimizerWithLatestVersion.optimize(
            planPromql("PROMQL index=k8s step=1h result=(limit_ratio(-0.5, network.bytes_in))", false)
        );

        var node = as(plan.collect(LimitRatioBy.class).get(0), LimitRatioBy.class);
        assertThat(((Number) node.ratio().fold(FoldContext.small())).doubleValue(), closeTo(-0.5, 1e-10));
    }

    /**
     * Like Prometheus, NaN ratios are rejected; infinite ratios clamp naturally (+Inf keeps everything).
     */
    public void testLimitRatioNaNRejected() {
        var e = expectThrows(
            VerificationException.class,
            () -> planPromql("PROMQL index=k8s step=1h result=(limit_ratio(nan, network.bytes_in))", true)
        );
        assertThat(e.getMessage(), containsString("must not be NaN"));
    }

    /**
     * Like Prometheus, infinite ratios are accepted and clamp naturally (+Inf keeps everything).
     */
    public void testLimitRatioInfiniteAccepted() {
        var plan = logicalOptimizerWithLatestVersion.optimize(
            planPromql("PROMQL index=k8s step=1h result=(limit_ratio(Inf, network.bytes_in))", false)
        );

        assertThat(plan.collect(LimitRatioBy.class).get(0), instanceOf(LimitRatioBy.class));
    }

    public void testLimitRatioStringRejected() {
        var e = expectThrows(
            VerificationException.class,
            () -> planPromql("PROMQL index=k8s step=1h result=(limit_ratio(\"0.5\", network.bytes_in))", true)
        );
        assertThat(e.getMessage(), containsString("numeric ratio"));
    }

    /**
     * Translator output passes post-optimization verification: the ratio is a numeric literal and
     * the field key is a resolved keyword attribute of the input.
     */
    public void testLimitRatioVerificationAcceptsTranslatorOutput() {
        var failures = new Failures();
        limitRatioByNode().postOptimizationVerification(failures);
        assertThat(failures.hasFailures(), equalTo(false));
    }

    /**
     * Post-optimization verification rejects a non-numeric ratio with source context instead of
     * failing deep in execution planning.
     */
    public void testLimitRatioVerificationRejectsNonNumericRatio() {
        var node = limitRatioByNode();
        var bad = new LimitRatioBy(
            node.source(),
            node.child(),
            new Literal(node.source(), new BytesRef("0.5"), DataType.KEYWORD),
            node.groupings()
        );
        var failures = new Failures();
        bad.postOptimizationVerification(failures);
        assertThat(failures.hasFailures(), equalTo(true));
        assertThat(failures.toString(), containsString("must be a numeric literal"));
    }

    public void testLimitRatioVerificationRejectsNaNRatio() {
        var node = limitRatioByNode();
        var bad = new LimitRatioBy(node.source(), node.child(), new Literal(node.source(), Double.NaN, DataType.DOUBLE), node.groupings());
        var failures = new Failures();
        bad.postOptimizationVerification(failures);
        assertThat(failures.hasFailures(), equalTo(true));
        assertThat(failures.toString(), containsString("must not be NaN"));
    }

    public void testLimitRatioVerificationRejectsNonAttributeKeyCarrier() {
        var node = limitRatioByNode();
        var bad = new LimitRatioBy(
            node.source(),
            node.child(),
            node.ratio(),
            List.of(node.groupings().get(0), new Literal(node.source(), new BytesRef("key"), DataType.KEYWORD))
        );
        var failures = new Failures();
        bad.postOptimizationVerification(failures);
        assertThat(failures.hasFailures(), equalTo(true));
        assertThat(failures.toString(), containsString("must be an attribute"));
    }

    public void testLimitRatioVerificationRejectsUnresolvableKeyCarrier() {
        var node = limitRatioByNode();
        var bad = new LimitRatioBy(
            node.source(),
            node.child(),
            node.ratio(),
            List.of(node.groupings().get(0), new ReferenceAttribute(node.source(), "missing", DataType.KEYWORD))
        );
        var failures = new Failures();
        bad.postOptimizationVerification(failures);
        assertThat(failures.hasFailures(), equalTo(true));
        assertThat(failures.toString(), containsString("is not produced by its input"));
    }

    private LimitRatioBy limitRatioByNode() {
        return limitRatioByNode("PROMQL index=k8s step=1h result=(limit_ratio(0.5, network.bytes_in))", false);
    }

    private LimitRatioBy limitRatioByNode(String query) {
        return limitRatioByNode(query, false);
    }

    private LimitRatioBy limitRatioByNode(String query, boolean allowEmptyReferences) {
        var plan = logicalOptimizerWithLatestVersion.optimize(planPromql(query, allowEmptyReferences, false));
        return as(plan.collect(LimitRatioBy.class).get(0), LimitRatioBy.class);
    }

    /** The sampling key names: the groupings without the leading step bucket. */
    private static List<String> samplingKeyNames(LimitRatioBy node) {
        return node.groupings().subList(1, node.groupings().size()).stream().map(g -> {
            assertThat(g, instanceOf(Attribute.class));
            return ((Attribute) g).name();
        }).toList();
    }
}
