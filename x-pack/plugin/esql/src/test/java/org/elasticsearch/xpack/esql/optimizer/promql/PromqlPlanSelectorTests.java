/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.promql;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.xpack.esql.analysis.UnmappedResolution;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.MetadataAttribute;
import org.elasticsearch.xpack.esql.core.expression.UnresolvedAttribute;
import org.elasticsearch.xpack.esql.core.expression.predicate.regex.RegexMatch;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.expression.function.aggregate.LastOverTime;
import org.elasticsearch.xpack.esql.expression.function.aggregate.PromqlHistogramQuantile;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Rate;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Sum;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.StartsWith;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.regex.RLike;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Not;
import org.elasticsearch.xpack.esql.expression.predicate.nulls.IsNull;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.In;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.NotEquals;
import org.elasticsearch.xpack.esql.index.EsIndex;
import org.elasticsearch.xpack.esql.index.IndexProperties;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.MetricSamples;
import org.elasticsearch.xpack.esql.plan.logical.TimeSeriesAggregate;
import org.elasticsearch.xpack.esql.plan.logical.local.EmptyLocalSupplier;
import org.elasticsearch.xpack.esql.plan.logical.local.LocalRelation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.xpack.esql.EsqlTestUtils.as;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

public class PromqlPlanSelectorTests extends AbstractPromqlPlanOptimizerTests {

    public PromqlPlanSelectorTests(VersionMode versionMode) {
        super(versionMode);
    }

    /**
     * Regression guard for the promcheck "Unknown column [label]" failures: {@code sum by (<absent>) (metric)}
     * must drop the absent grouping label rather than emit a dangling reference. Strict {@link UnmappedResolution#DEFAULT}
     * is used on purpose; the lenient {@code NULLIFY} mode most PromQL tests run with hides the dangling reference.
     */
    public void testGroupByAbsentLabelIsDropped() {
        var analyzed = analyzerWithEnrichPolicies().addK8s()
            .unmappedResolution(UnmappedResolution.DEFAULT)
            .query("PROMQL index=k8s step=1m result=(sum by (missing_label) (network.bytes_in))");
        var optimized = logicalOptimizer.optimize(analyzed);
        assertThat(outputColumns(optimized), equalTo(List.of("result", "step")));
        assertNoUnresolvedAttributes(optimized);
    }

    /**
     * A grouping label that collides with a metric field of the same name is not a real label, so it must be
     * dropped: the output carries only result and step, not a {@code name} column derived from the counter.
     * The grouping-key assertion is the stronger guard: the projection alone could be sanitized downstream, but
     * grouping the aggregate by a metric value would silently change the result, so {@code name} must not appear
     * as a grouping key either.
     */
    public void testGroupByMetricNamedLabelIsDropped() {
        var plan = planPromqlMetricNamedLabel("PROMQL index=metrics step=30s result=(sum by (name) (container_cpu_usage_seconds_total))");
        assertThat(outputColumns(plan), equalTo(List.of("result", "step")));
        assertThat(groupingKeyNames(plan), not(hasItem("name")));
    }

    public void testHistogramQuantileExplicitLeUsesOuterAggregate() {
        var plan = planPromqlClassicHistogram(
            "PROMQL index=histograms step=1m result=(histogram_quantile(0.5, sum by (cluster, le) "
                + "(http_request_duration_seconds_bucket)))"
        );

        // `le` is dropped and `cluster` retained: the output carries cluster, not le. (The histogram regroup packs its
        // carried dimensions for multi-value safety, so the grouping keys are packed aliases - assert on the output.)
        assertThat(outputColumns(plan), equalTo(List.of("result", "step", "cluster")));
        assertThat(collectHistogramQuantiles(plan), hasSize(1));
    }

    public void testHistogramQuantileRateResolvesImplicitLe() {
        var plan = planPromqlClassicHistogram(
            "PROMQL index=histograms step=1m result=(histogram_quantile(0.5, rate(http_request_duration_seconds_bucket[5m])))"
        );

        assertThat(outputColumns(plan), equalTo(List.of("result", "step", "_timeseries")));
        assertThat(collectHistogramQuantiles(plan), hasSize(1));
    }

    public void testRangeSelector() {
        var plan = planPromql("PROMQL index=k8s step=1h ( max by (pod) (last_over_time(network.bytes_in[1h])) )");
        var lot = collectInnerLastOverTimes(plan).getFirst();
        assertThat(lot.window().fold(FoldContext.small()), equalTo(Duration.ofHours(1)));
    }

    public void testRangeSelectorWithDifferentStep() {
        var plan = planPromql("PROMQL index=k8s step=5m sum by (pod) (avg_over_time(events_received[10m]))");
        var tsAggregate = plan.collect(TimeSeriesAggregate.class).getFirst();
        assertThat(tsAggregate.timeBucket().buckets().fold(FoldContext.small()), equalTo(Duration.ofMinutes(5)));
        var sum = tsAggregate.aggregates().getFirst().collect(Sum.class).getFirst();
        assertThat(sum.window().fold(FoldContext.small()), equalTo(Duration.ofMinutes(10)));
    }

    public void testLabelSelector() {
        var plan = planPromql("""
            PROMQL index=k8s step=1m (
                max by (pod) (avg_over_time(network.bytes_in{pod=~"host-0|host-1|host-2"}[5m]))
              )
            """);
        var in = collectInnermostSelectorFilter(plan, In.class);
        assertThat(in.value().sourceText(), equalTo("pod"));
        assertThat(in.list().stream().map(Expression::toString).toList(), containsInAnyOrder("host-0", "host-1", "host-2"));
    }

    public void testLabelSelectorPrefix() {
        var plan = planPromql("""
            PROMQL index=k8s step=1m avg by (pod) (avg_over_time(network.bytes_in{pod=~"host-.*"}[5m]))
            """);
        assertTrue(anyFilterContains(plan, StartsWith.class));
        assertFalse(anyFilterContains(plan, NotEquals.class));
    }

    public void testLabelSelectorProperPrefix() {
        var plan = planPromql("""
            PROMQL index=k8s step=1m avg(avg_over_time(network.bytes_in{pod=~"host-.+"}[1h]))
            """);
        assertTrue(anyFilterContains(plan, StartsWith.class));
        assertTrue(anyFilterContains(plan, NotEquals.class));
    }

    public void testLabelSelectorRegex() {
        var plan = planPromql("PROMQL index=k8s step=1m avg(avg_over_time(network.bytes_in{pod=~\"[a-z]+\"}[1h]))");
        assertTrue(anyFilterContains(plan, RegexMatch.class));
    }

    public void testLabelSelectorNotEquals() {
        var plan = planPromql("PROMQL index=k8s step=1m avg(network.bytes_in{pod!=\"foo\"})");
        var not = collectInnermostSelectorFilter(plan, Not.class);
        var in = as(not.field(), In.class);
        assertThat(as(in.value(), FieldAttribute.class).name(), equalTo("pod"));
        assertThat(in.list(), hasSize(1));
        assertThat(as(as(in.list().getFirst(), Literal.class).value(), BytesRef.class).utf8ToString(), equalTo("foo"));
    }

    /** A label matcher filters on its own label wherever the {@code __name__} matcher sits among the matchers. */
    public void testLabelSelectorWithNameMatcherInBraces() {
        for (String selector : List.of(
            "network.bytes_in{pod!=\"foo\"}",
            "{__name__=\"network.bytes_in\",pod!=\"foo\"}",
            "{pod!=\"foo\",__name__=\"network.bytes_in\"}",
            "{\"network.bytes_in\",pod!=\"foo\"}",
            "{pod!=\"foo\",\"network.bytes_in\"}"
        )) {
            var plan = planPromql("PROMQL index=k8s step=1m avg(" + selector + ")");
            var not = collectInnermostSelectorFilter(plan, Not.class);
            var in = as(not.field(), In.class);
            assertThat(selector, as(in.value(), FieldAttribute.class).name(), equalTo("pod"));
            assertThat(selector, lastOverTimeFields(plan), equalTo(List.of("network.bytes_in")));
        }
    }

    /**
     * Name matchers that admit exactly one metric keep the direct read of that metric's field: the other name matchers are
     * satisfied by it and leave no trace in the plan, so the plan is the one of the plain metric name.
     */
    public void testExactNameAmongNameMatchersReadsTheMetricField() {
        for (String selector : List.of(
            "{__name__!=\"network.bytes_out\",__name__=\"network.bytes_in\",pod=\"a\"}",
            "{__name__=\"network.bytes_in\",__name__=~\"network\\\\..*\",pod=\"a\"}",
            "{__name__=~\"network\\\\.bytes_in\",pod=\"a\"}"
        )) {
            var plan = planPromql("PROMQL index=k8s step=1m sum(" + selector + ")");
            assertThat(selector, lastOverTimeFields(plan), equalTo(List.of("network.bytes_in")));
            var equals = collectInnermostSelectorFilter(plan, Equals.class);
            assertThat(selector, as(equals.left(), FieldAttribute.class).name(), equalTo("pod"));
            assertThat(
                selector,
                collectSelectorFilters(plan).stream().anyMatch(f -> f.condition().anyMatch(e -> e instanceof RLike)),
                equalTo(false)
            );
        }
    }

    /** Name matchers that admit no common metric select no series, like a metric that does not exist. */
    public void testContradictoryNameMatchersSelectNothing() {
        for (String selector : List.of(
            "{__name__=\"network.bytes_in\",__name__!=\"network.bytes_in\"}",
            "{__name__=\"network.bytes_in\",__name__=\"network.bytes_out\"}",
            "{\"network.bytes_in\",\"network.bytes_out\"}",
            "{__name__=\"network.bytes_in\",__name__!~\"network.*\",pod=\"a\"}"
        )) {
            for (String query : List.of(
                selector,
                "avg(" + selector + ")",
                "sum by (pod) (" + selector + ")",
                "max_over_time(" + selector + "[5m])"
            )) {
                var plan = planPromql("PROMQL index=k8s step=1m " + query);
                assertThat(query, as(plan, LocalRelation.class).supplier(), equalTo(EmptyLocalSupplier.EMPTY));
            }
        }
    }

    /**
     * A selector naming no single metric reads the samples of every metric selected by name: the label predicates filter the
     * source below the reader, and the per-series aggregation groups by the reader's series identity, which extends the
     * {@code _tsid} with the metric so the metrics of one document stay apart.
     */
    public void testSelectorWithoutExactNameReadsMetricSamples() {
        for (String selector : List.of(
            "{__name__=~\"network\\\\.bytes_.*\",pod=\"a\"}",
            "{__name__!=\"network.bytes_in\",pod=\"a\"}",
            "{pod=\"a\"}"
        )) {
            var plan = planPromql("PROMQL index=k8s step=1m " + selector);
            var samples = plan.collect(MetricSamples.class);
            assertThat(selector, samples, hasSize(1));
            var metricSamples = samples.getFirst();
            var filter = as(metricSamples.child(), Filter.class);
            assertThat(selector, as(filter.child(), EsRelation.class).output(), hasItem(metricSamples.tsid()));
            var tsAggregate = plan.collect(TimeSeriesAggregate.class).getFirst();
            assertThat(selector, tsAggregate.groupings().getFirst(), equalTo(metricSamples.seriesId()));
            assertThat(selector, as(collectInnerLastOverTimes(plan).getFirst().field(), Attribute.class), equalTo(metricSamples.value()));
            assertThat(selector, outputColumns(plan), equalTo(List.of(selector, "step", "_timeseries", "__name__")));
        }
    }

    /** An exact metric name, however spelled, keeps reading its field directly. */
    public void testExactNameDoesNotReadMetricSamples() {
        for (String selector : List.of(
            "network.bytes_in{pod=\"a\"}",
            "{__name__=\"network.bytes_in\",pod=\"a\"}",
            "{__name__!=\"network.bytes_out\",__name__=\"network.bytes_in\",pod=\"a\"}"
        )) {
            var plan = planPromql("PROMQL index=k8s step=1m " + selector);
            assertThat(selector, plan.collect(MetricSamples.class), empty());
            assertThat(selector, lastOverTimeFields(plan), equalTo(List.of("network.bytes_in")));
            assertThat(selector, outputColumns(plan), equalTo(List.of(selector, "step", "_timeseries")));
        }
    }

    /** Aggregations drop the metric name of metrics selected by name, keeping only the labels they group by. */
    public void testAggregationsOverMetricSamplesDropTheMetricName() {
        var by = planPromql("PROMQL index=k8s step=1m result=(sum by (pod) ({__name__=~\"network\\\\.bytes_.*\"}))");
        assertThat(by.collect(MetricSamples.class), hasSize(1));
        assertThat(outputColumns(by), equalTo(List.of("result", "step", "pod")));
        var without = planPromql("PROMQL index=k8s step=1m result=(sum without (pod) ({__name__=~\"network\\\\.bytes_.*\"}))");
        assertThat(without.collect(MetricSamples.class), hasSize(1));
        assertThat(outputColumns(without), equalTo(List.of("result", "step", "_timeseries")));
    }

    /** Range functions read the samples of every selected metric as their own series, as counters where they require one. */
    public void testRangeFunctionOverMetricSamples() {
        var plan = planPromql("PROMQL index=k8s step=1m rate({__name__=~\"network\\\\.total_bytes_.*\"}[5m])");
        var metricSamples = plan.collect(MetricSamples.class);
        assertThat(metricSamples, hasSize(1));
        var tsAggregate = plan.collect(TimeSeriesAggregate.class).getFirst();
        assertThat(tsAggregate.groupings().getFirst(), equalTo(metricSamples.getFirst().seriesId()));
        assertThat(tsAggregate.aggregates().stream().anyMatch(a -> a.anyMatch(Rate.class::isInstance)), equalTo(true));
        assertThat(outputColumns(plan).subList(2, 4), equalTo(List.of("_timeseries", "__name__")));
    }

    public void testLabelSelectorRegexNegation() {
        var plan = planPromql("PROMQL index=k8s step=1m avg(network.bytes_in{pod!~\"f.o\"})");
        var not = collectInnermostSelectorFilter(plan, Not.class);
        var rLike = as(not.field(), RLike.class);
        assertThat(as(rLike.field(), FieldAttribute.class).name(), equalTo("pod"));
        assertThat(rLike.pattern().pattern(), equalTo("f.o"));
    }

    public void testLabelSelectors() {
        var plan = planPromql("PROMQL index=k8s step=1m avg(network.bytes_in{pod!=\"foo\",cluster=~\"bar|baz\",region!~\"us-.*\"})");
        assertTrue(anyFilterContains(plan, Not.class));
        assertTrue(anyFilterContains(plan, In.class));
        assertTrue(anyFilterContains(plan, StartsWith.class));
    }

    /**
     * {label=""} must match series where the label is absent (NULL), because PromQL treats absent labels as "".
     * The generated filter must be: IS NULL OR field == "".
     */
    public void testEmptyStringLabelMatcherIncludesAbsentSeries() {
        var plan = planPromql("PROMQL index=k8s step=1m avg(network.bytes_in{pod=\"\"})");
        assertTrue(anyFilterContains(plan, IsNull.class));
        assertTrue(anyFilterContains(plan, Equals.class));
    }

    /**
     * {label!="foo"} must match series where the label is absent (NULL), because absent maps to ""
     * and "" != "foo". The generated filter must be: IS NULL OR NOT(field == "foo").
     */
    public void testNotEqualLabelMatcherIncludesAbsentSeries() {
        var plan = planPromql("PROMQL index=k8s step=1m avg(network.bytes_in{pod!=\"foo\"})");
        assertTrue(anyFilterContains(plan, IsNull.class));
        assertTrue(anyFilterContains(plan, Not.class));
    }

    /**
     * {label="foo"} must NOT match absent series: absent maps to "" and "" != "foo".
     * No IS NULL must appear in the generated filter.
     */
    public void testExactLabelMatcherExcludesAbsentSeries() {
        var plan = planPromql("PROMQL index=k8s step=1m avg(network.bytes_in{pod=\"foo\"})");
        assertFalse(anyFilterContains(plan, IsNull.class));
    }

    /**
     * {label!=""} must NOT match absent series: absent maps to "" and "" does not satisfy != "".
     * No IS NULL must appear in the generated filter.
     */
    public void testNotEqualsEmptyLabelMatcherExcludesAbsentSeries() {
        var plan = planPromql("PROMQL index=k8s step=1m avg(network.bytes_in{pod!=\"\"})");
        assertFalse(anyFilterContains(plan, IsNull.class));
    }

    public void testGroupByAllInstantSelector() {
        assertThat(
            outputColumns(planPromql("PROMQL index=k8s step=1m network.bytes_in")),
            equalTo(List.of("network.bytes_in", "step", "_timeseries"))
        );
    }

    public void testGroupByAllInstantSelectorOnlyMaterializesSeriesIdentity() {
        var plan = planPromql("PROMQL index=k8s step=1m network.bytes_in", false);
        var dimensions = plan.collect(TimeSeriesAggregate.class)
            .stream()
            .flatMap(aggregate -> packedDims(aggregate.aggregates()).stream())
            .map(e -> e instanceof Attribute attribute ? attribute.name() : e.toString())
            .toList();
        assertThat(dimensions, equalTo(List.of(MetadataAttribute.TIMESERIES)));
    }

    public void testGroupByAllInstantSelectorRate() {
        assertThat(
            outputColumns(planPromql("PROMQL index=k8s step=1m rate=(rate(network.total_bytes_in[1m]))")),
            equalTo(List.of("rate", "step", "_timeseries"))
        );
    }

    public void testGroupByAllWithinSeriesAggregate() {
        assertThat(
            outputColumns(planPromql("PROMQL index=k8s step=1m count=(count_over_time(network.bytes_in[1m]))")),
            equalTo(List.of("count", "step", "_timeseries"))
        );
    }

    public void testSelectorFilterPushedToSource() {
        assertTrue(anyFilterContains(planPromql("PROMQL index=k8s step=1m result=(sum(network.bytes_in{pod=\"p1\"}))"), Equals.class));
    }

    public void testSelectorFilterPushedToSourceThroughUnaryOp() {
        assertTrue(anyFilterContains(planPromql("PROMQL index=k8s step=1m result=(-sum(network.bytes_in{pod=\"p1\"}))"), Equals.class));
    }

    public void testSelectorFilterPushedToSourceThroughScalar() {
        var plan = planPromql("PROMQL index=k8s step=1m result=(scalar(sum by (cluster) (network.bytes_in{cluster=\"prod\"})))");
        assertTrue(anyFilterContains(plan, Equals.class));
    }

    public void testBinaryOpConflictingSelectors() {
        var plan = planPromql("PROMQL index=k8s step=1m ratio=(sum(network.bytes_in{pod=\"p1\"}) / sum(network.bytes_in{pod=\"p2\"}))");
        var lots = collectInnerLastOverTimes(plan);
        assertThat(lots, hasSize(2));
        assertTrue(lots.stream().allMatch(LastOverTime::hasFilter));
        assertFalse(anyFilterContains(plan, Equals.class));
    }

    public void testBinaryOpDifferentLabelKeys() {
        var plan = planPromql("PROMQL index=k8s step=1m ratio=(sum(network.bytes_in{cluster=\"c1\"}) / sum(network.bytes_in{pod=\"p1\"}))");
        var lots = collectInnerLastOverTimes(plan);
        assertThat(lots, hasSize(2));
        assertTrue(lots.stream().allMatch(LastOverTime::hasFilter));
        assertFalse(anyFilterContains(plan, Equals.class));
    }

    public void testBinaryOpSelectorOnOneSideOnly() {
        var plan = planPromql("PROMQL index=k8s step=1m ratio=(sum(network.bytes_in{pod=\"p1\"}) / sum(network.bytes_in))");
        var lots = collectInnerLastOverTimes(plan);
        assertThat(lots, hasSize(2));
        assertThat(lots.stream().filter(LastOverTime::hasFilter).count(), equalTo(1L));
        assertFalse(anyFilterContains(plan, Equals.class));
    }

    public void testBinaryOpConflictingSelectorsInFunction() {
        var plan = planPromql("PROMQL index=k8s step=1m r=(ceil(sum(network.bytes_in{pod=\"p1\"}) / sum(network.bytes_in{pod=\"p2\"})))");
        var lots = collectInnerLastOverTimes(plan);
        assertThat(lots, hasSize(2));
        assertTrue(lots.stream().allMatch(LastOverTime::hasFilter));
        assertFalse(anyFilterContains(plan, Equals.class));
    }

    private static List<String> outputColumns(LogicalPlan plan) {
        return plan.output().stream().map(a -> a.name()).toList();
    }

    /** An index with a metric field named {@code name}, so a {@code by (name)} clause collides with a metric. */
    private LogicalPlan planPromqlMetricNamedLabel(String query) {
        var index = new EsIndex(
            "metrics",
            Map.of(
                "@timestamp",
                new EsField("@timestamp", DataType.DATETIME, Map.of(), true, EsField.TimeSeriesFieldType.NONE),
                "name",
                new EsField("name", DataType.DOUBLE, Map.of(), true, EsField.TimeSeriesFieldType.METRIC),
                "container_cpu_usage_seconds_total",
                new EsField("container_cpu_usage_seconds_total", DataType.COUNTER_LONG, Map.of(), true, EsField.TimeSeriesFieldType.METRIC)
            ),
            Map.of("metrics", new IndexProperties(IndexMode.TIME_SERIES, 0)),
            Map.of(),
            Map.of()
        );
        var analyzed = analyzerWithEnrichPolicies().addIndex(index).unmappedResolution(UnmappedResolution.NULLIFY).query(query);
        return logicalOptimizer.optimize(analyzed);
    }

    private LogicalPlan planPromqlClassicHistogram(String query) {
        var index = new EsIndex(
            "histograms",
            Map.of(
                "@timestamp",
                new EsField("@timestamp", DataType.DATETIME, Map.of(), true, EsField.TimeSeriesFieldType.NONE),
                "cluster",
                new EsField("cluster", DataType.KEYWORD, Map.of(), true, EsField.TimeSeriesFieldType.DIMENSION),
                "le",
                new EsField("le", DataType.KEYWORD, Map.of(), true, EsField.TimeSeriesFieldType.DIMENSION),
                "http_request_duration_seconds_bucket",
                new EsField(
                    "http_request_duration_seconds_bucket",
                    DataType.COUNTER_LONG,
                    Map.of(),
                    true,
                    EsField.TimeSeriesFieldType.METRIC
                )
            ),
            Map.of("histograms", new IndexProperties(IndexMode.TIME_SERIES, 0)),
            Map.of(),
            Map.of()
        );
        var analyzed = analyzerWithEnrichPolicies().addIndex(index).unmappedResolution(UnmappedResolution.NULLIFY).query(query);
        return logicalOptimizer.optimize(analyzed);
    }

    /** Names of every attribute referenced by any aggregate's grouping keys (covers {@link TimeSeriesAggregate}). */
    private static List<String> groupingKeyNames(LogicalPlan plan) {
        List<String> names = new ArrayList<>();
        plan.forEachDown(Aggregate.class, agg -> names.addAll(groupingKeyNames(agg)));
        return names;
    }

    private static List<String> groupingKeyNames(Aggregate aggregate) {
        List<String> names = new ArrayList<>();
        aggregate.groupings().forEach(g -> g.forEachDown(Attribute.class, a -> names.add(a.name())));
        return names;
    }

    private static List<PromqlHistogramQuantile> collectHistogramQuantiles(LogicalPlan plan) {
        List<PromqlHistogramQuantile> quantiles = new ArrayList<>();
        plan.forEachExpressionDown(PromqlHistogramQuantile.class, quantiles::add);
        return quantiles;
    }

    private static void assertNoUnresolvedAttributes(LogicalPlan plan) {
        List<String> unresolved = new ArrayList<>();
        plan.forEachDown(lp -> lp.forEachExpressionDown(UnresolvedAttribute.class, u -> unresolved.add(u.name())));
        assertThat("plan still references unresolved attributes", unresolved, empty());
    }

    private static List<LastOverTime> collectInnerLastOverTimes(LogicalPlan plan) {
        return plan.collect(TimeSeriesAggregate.class)
            .stream()
            .flatMap(tsa -> tsa.aggregates().stream())
            .flatMap(ne -> ne.collect(LastOverTime.class).stream())
            .toList();
    }

    private static List<String> lastOverTimeFields(LogicalPlan plan) {
        return collectInnerLastOverTimes(plan).stream().map(lot -> as(lot.field(), FieldAttribute.class).name()).toList();
    }

    private static List<Filter> collectSelectorFilters(LogicalPlan plan) {
        List<Filter> result = new ArrayList<>();
        plan.forEachDown(Filter.class, f -> {
            if (f.child() instanceof EsRelation) {
                result.add(f);
            }
        });
        return result;
    }

    private static boolean anyFilterContains(LogicalPlan plan, Class<? extends Expression> type) {
        return collectSelectorFilters(plan).stream().anyMatch(f -> f.condition().anyMatch(type::isInstance));
    }

    private static <T extends Expression> T collectInnermostSelectorFilter(LogicalPlan plan, Class<T> type) {
        return collectSelectorFilters(plan).stream()
            .flatMap(f -> f.condition().collect(type).stream())
            .findFirst()
            .orElseThrow(() -> new AssertionError("No " + type.getSimpleName() + " in source filters"));
    }
}
