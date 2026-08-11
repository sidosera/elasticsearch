/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.logical.promql;

import org.elasticsearch.common.logging.HeaderWarning;
import org.elasticsearch.common.time.DateUtils;
import org.elasticsearch.xpack.esql.VerificationException;
import org.elasticsearch.xpack.esql.analysis.AnalyzerContext;
import org.elasticsearch.xpack.esql.analysis.AnalyzerRules;
import org.elasticsearch.xpack.esql.core.QlIllegalArgumentException;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FoldContext;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.MetadataAttribute;
import org.elasticsearch.xpack.esql.core.expression.NameId;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.expression.Nullability;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.expression.predicate.regex.RLikePattern;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.Order;
import org.elasticsearch.xpack.esql.expression.function.aggregate.AggregateFunction;
import org.elasticsearch.xpack.esql.expression.function.aggregate.LastOverTime;
import org.elasticsearch.xpack.esql.expression.function.aggregate.PromqlHistogramQuantile;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Scalar;
import org.elasticsearch.xpack.esql.expression.function.aggregate.TimeSeriesAggregateFunction;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Values;
import org.elasticsearch.xpack.esql.expression.function.grouping.TStep;
import org.elasticsearch.xpack.esql.expression.function.grouping.TimeSeriesWithout;
import org.elasticsearch.xpack.esql.expression.function.scalar.convert.ToDatetime;
import org.elasticsearch.xpack.esql.expression.function.scalar.convert.ToDouble;
import org.elasticsearch.xpack.esql.expression.function.scalar.convert.ToInteger;
import org.elasticsearch.xpack.esql.expression.function.scalar.convert.ToString;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.EndsWith;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.StartsWith;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.regex.RLike;
import org.elasticsearch.xpack.esql.expression.predicate.Predicates;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Not;
import org.elasticsearch.xpack.esql.expression.predicate.nulls.IsNotNull;
import org.elasticsearch.xpack.esql.expression.predicate.nulls.IsNull;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Add;
import org.elasticsearch.xpack.esql.expression.predicate.operator.arithmetic.Sub;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.In;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.NotEquals;
import org.elasticsearch.xpack.esql.expression.promql.function.PromqlFunctionRegistry.PromqlContext;
import org.elasticsearch.xpack.esql.optimizer.rules.logical.TemporaryNameGenerator;
import org.elasticsearch.xpack.esql.optimizer.rules.logical.TranslateTimeSeriesAggregate;
import org.elasticsearch.xpack.esql.optimizer.rules.logical.promql.PromqlAttributesTranslationContext.Header;
import org.elasticsearch.xpack.esql.optimizer.rules.logical.promql.PromqlAttributesTranslationContext.NamedColumn;
import org.elasticsearch.xpack.esql.optimizer.rules.logical.promql.PromqlAttributesTranslationContext.TimeSeriesColumn;
import org.elasticsearch.xpack.esql.parser.promql.PromqlLogicalPlanBuilder;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.Fork;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.PackDims;
import org.elasticsearch.xpack.esql.plan.logical.Project;
import org.elasticsearch.xpack.esql.plan.logical.TimeSeriesAggregate;
import org.elasticsearch.xpack.esql.plan.logical.TopNBy;
import org.elasticsearch.xpack.esql.plan.logical.UnionAll;
import org.elasticsearch.xpack.esql.plan.logical.UnpackDims;
import org.elasticsearch.xpack.esql.plan.logical.join.InnerJoin;
import org.elasticsearch.xpack.esql.plan.logical.local.EmptyLocalSupplier;
import org.elasticsearch.xpack.esql.plan.logical.local.LocalRelation;
import org.elasticsearch.xpack.esql.plan.logical.promql.AcrossSeriesAggregate;
import org.elasticsearch.xpack.esql.plan.logical.promql.AcrossSeriesReduction;
import org.elasticsearch.xpack.esql.plan.logical.promql.HistogramQuantile;
import org.elasticsearch.xpack.esql.plan.logical.promql.PromqlCommand;
import org.elasticsearch.xpack.esql.plan.logical.promql.PromqlFunctionCall;
import org.elasticsearch.xpack.esql.plan.logical.promql.ScalarConversionFunction;
import org.elasticsearch.xpack.esql.plan.logical.promql.ScalarFunction;
import org.elasticsearch.xpack.esql.plan.logical.promql.ValueTransformationFunction;
import org.elasticsearch.xpack.esql.plan.logical.promql.operator.VectorBinaryComparison;
import org.elasticsearch.xpack.esql.plan.logical.promql.operator.VectorBinaryOperator;
import org.elasticsearch.xpack.esql.plan.logical.promql.operator.VectorBinarySet;
import org.elasticsearch.xpack.esql.plan.logical.promql.operator.VectorMatch;
import org.elasticsearch.xpack.esql.plan.logical.promql.selector.InstantSelector;
import org.elasticsearch.xpack.esql.plan.logical.promql.selector.LabelMatcher;
import org.elasticsearch.xpack.esql.plan.logical.promql.selector.LabelMatchers;
import org.elasticsearch.xpack.esql.plan.logical.promql.selector.LiteralSelector;
import org.elasticsearch.xpack.esql.plan.logical.promql.selector.RangeSelector;
import org.elasticsearch.xpack.esql.plan.logical.promql.selector.Selector;
import org.elasticsearch.xpack.esql.session.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.elasticsearch.xpack.esql.expression.function.aggregate.AggregateFunction.withFilter;
import static org.elasticsearch.xpack.esql.expression.predicate.Predicates.combineAnd;
import static org.elasticsearch.xpack.esql.expression.predicate.Predicates.combineAndNullable;
import static org.elasticsearch.xpack.esql.optimizer.rules.logical.promql.PromqlAttributesTranslationContext.findById;
import static org.elasticsearch.xpack.esql.optimizer.rules.logical.promql.PromqlAttributesTranslationContext.findByIdOrName;
import static org.elasticsearch.xpack.esql.optimizer.rules.logical.promql.PromqlAttributesTranslationContext.findByName;
import static org.elasticsearch.xpack.esql.optimizer.rules.logical.promql.PromqlAttributesTranslationContext.nullColumn;
import static org.elasticsearch.xpack.esql.optimizer.rules.logical.promql.PromqlAttributesTranslationContext.resolveColumn;
import static org.elasticsearch.xpack.esql.optimizer.rules.logical.promql.PromqlAttributesTranslationContext.toCanonicalName;
import static org.elasticsearch.xpack.esql.plan.logical.promql.AcrossSeriesAggregate.Grouping.WITHOUT;
import static org.elasticsearch.xpack.esql.plan.logical.promql.operator.VectorBinarySet.SetOp.UNION;
import static org.elasticsearch.xpack.esql.plan.logical.promql.operator.VectorMatch.Condition;
import static org.elasticsearch.xpack.esql.plan.logical.promql.operator.VectorMatch.Joining;

/**
 * Translates PromQL logical plan into ESQL plan. Runs before {@link TranslateTimeSeriesAggregate} to convert
 * PromQL-specific nodes into standard ESQL nodes (TimeSeriesAggregate, Aggregate, Eval, etc.). Examples:
 * <pre>
 * PromQL: sum by (cluster) (rate(http_requests[5m]))
 * Result: TimeSeriesAggregate[sum(rate(value)), groupBy=[step, cluster]]
 *
 * PromQL: time() - avg(sum by (cluster) (rate(http_requests[5m])))
 * Result: Eval[time() - avg_result]
 *           \_ Aggregate[avg(sum_result), groupBy=[step]]
 *                 \_ TimeSeriesAggregate[sum(rate(value)), groupBy=[step, cluster]]
 * </pre>
 * Mechanism: a {@link Translation} instance per command; recursive descent via {@code doTranslateNode()} where every AST
 * node produces a {@link IntermediateResult} its parent composes, and the top-level forms (single translateIntermediate,
 * {@code or} union, vector-match join) stitch finished tables. A vector-match operand is translated like a union branch
 * (its own step/value ids against the shared command); the build side is then re-identified so the two join inputs
 * share no attribute ids.
 * <p>
 * Every shape-changing node (across-series aggregate, histogram_quantile, vector-match join) runs the same phases:
 * <ol>
 * <li><b>translate</b> - compile the subtree(s) optimistically against the surrounding demand;</li>
 * <li><b>negotiate</b> - derive the node's requirement from the shapes the subtrees actually produced, and translate
 * once more a subtree missing required columns it can offer ({@link Translation#retried});</li>
 * <li><b>bind</b> - link the node's symbolic columns to the concrete columns of the produced plans, null-defining
 * required columns that are genuinely unavailable ({@link Header#bind});</li>
 * <li><b>emit</b> - assemble the ESQL plan nodes over the bound columns;</li>
 * <li><b>report</b> - return the produced header for the parent to compose against.</li>
 * </ol>
 */
public final class TranslatePromqlToEsqlPlan extends AnalyzerRules.ParameterizedAnalyzerRule<PromqlCommand, AnalyzerContext> {
    // Sentinel bounds for open-ended range queries (PROMQL step=X without explicit start/end): TStep requires explicit bounds,
    // so pass the widest representable range. EPOCH/MAX_MILLIS_BEFORE_9999 avoid time boundary handling in the engine.
    private static final Instant EPOCH_MIN = Instant.EPOCH;
    private static final Instant EPOCH_MAX = Instant.ofEpochMilli(DateUtils.MAX_MILLIS_BEFORE_9999);

    /** The lifecycle of an intermediate result. A constant is always a finished (aggregation-free) local relation. */
    private enum Kind {
        BEFORE_INITIAL_AGGREGATE(false, false),
        AFTER_INITIAL_AGGREGATE(true, false),
        CONSTANT(true, true);

        final boolean constant;
        final boolean afterInitialAggregation;

        Kind(boolean afterInitialAggregation, boolean constant) {
            this.afterInitialAggregation = afterInitialAggregation;
            this.constant = constant;
        }
    }

    /**
     * The single value flowing through the compiler: a table - an ESQL plan together with its defined columns. Every AST
     * node translates to a Table and the stitching operations (joins, unions, regroups, the command coda) compose tables
     * by their declared columns instead of rediscovering them in the plan output. Mid-descent {@code value} is a (possibly
     * not yet materialized) expression parents compose into larger expressions; a finished translateIntermediate's {@code value} is a
     * defined column ({@link #valueColumn()}) and its {@code step} is filled in.
     */
    private record IntermediateResult(
        /* Output ESQL plan: the source relation (cmd.child()) with this node's operators stacked on top. */
        LogicalPlan plan,
        /* This node's numeric value: an expression mid-descent, a defined column once the translateIntermediate is finished. */
        Expression value,
        /* Label matcher predicate; flows up until pushed to the relation or folded into an doTranslateAgg filter. */
        Expression pendingFilter,
        /* The label shape this subtree exposes. */
        Header header,
        /* The translator tracks what it built instead of inspecting the plan. */
        Kind kind
    ) {
        IntermediateResult {
            if (kind.afterInitialAggregation) {
                header = header.transformExpressions((column, grouping) -> resolveColumn(column, plan.output()));
            }
        }

        IntermediateResult(LogicalPlan plan, Expression value) {
            this(plan, value, null, Header.undefined(), Kind.BEFORE_INITIAL_AGGREGATE);
        }

        IntermediateResult(LogicalPlan plan, Expression value, Expression selectorFilter) {
            this(plan, value, selectorFilter, Header.undefined(), Kind.BEFORE_INITIAL_AGGREGATE);
        }

        IntermediateResult(LogicalPlan plan, Expression value, Expression selectorFilter, Header header) {
            this(plan, value, selectorFilter, header, Kind.BEFORE_INITIAL_AGGREGATE);
        }

        /** This table rebuilt around a new plan/value, keeping its other properties. */
        IntermediateResult with(LogicalPlan plan, Expression value, Header header) {
            return new IntermediateResult(plan, value, pendingFilter, header, kind);
        }

        /** The value as a defined column; only valid on a finished table. */
        Attribute valueColumn() {
            return (Attribute) value;
        }
    }

    @Override
    protected boolean skipResolved() {
        return false;
    }

    @Override
    protected LogicalPlan rule(PromqlCommand cmd, AnalyzerContext context) {
        Translation translation = new Translation(cmd, context, null, Header.undefined(), null);
        return translation.translateFinal();
    }

    /**
     * One translation pass: the command, the analyzer context, and the state of the translateIntermediate
     * being compiled. Independent parts compile separately - like modules - each with its own instance: a narrowed
     * required header is {@link #withPushDownHeader}, and a union branch or a vector-match operand is a fresh
     * instance with its own step bucket and evaluation time.
     */
    private record Translation(
        PromqlCommand cmd,
        AnalyzerContext analyzer,
        /* Alias for the step bucket expression used in all aggregation groupings. May be null for empty indices. */
        Alias stepBucketAlias,
        /* The header the result subtree MUST produce. */
        Header headerToPushDown,
        /* The current translateIntermediate evaluation time (default: @timestamp). */
        Expression time
    ) {
        Configuration configuration() {
            return analyzer.configuration();
        }

        Attribute stepAttr() {
            return stepBucketAlias != null ? stepBucketAlias.toAttribute() : cmd.stepAttribute();
        }

        Translation withPushDownHeader(Header pushDownHeader) {
            return new Translation(cmd, analyzer, stepBucketAlias, pushDownHeader, time);
        }

        /**
         * Translates one branch with its own step bucket and evaluation time, against this instance's
         * {@link #headerToPushDown} - the interface the surrounding query compiled against.
         */
        IntermediateResult translateIntermediate(LogicalPlan branch, NameId stepId, NameId valueId) {
            Expression branchTime = cmd.collectEvaluationTimestampForBranch(branch);
            Alias step = canCreateStepBucket() ? emitStepBucketExpression(stepId, branchTime) : null;
            var run = new Translation(cmd, analyzer, step, headerToPushDown, branchTime);
            return run.translateIntermediate(branch, valueId);
        }

        LogicalPlan translateFinal() {
            // A vector-matched arithmetic/comparison operator or an `and` set operator matches two series pipelines on shared
            // keys, so - like a top-level `or` union - it is emitted as its own top-level combining node (an InnerJoin) rather
            // than folded into a single aggregate; nested matches are handled inside doTranslateBinaryOp instead.
            if (cmd.promqlPlan() instanceof VectorBinaryOperator op && isJoinOperator(op)) {
                return doTranslateFinal(doTranslateBinOpInnerJoin(op).plan(), false);
            }

            // `or` is the only set operator that adds rows (more series), requiring a top-level multi-branch `UnionAll` that
            // cannot compose as a single-value sub-expression; `and`/`unless` translate as joins inside doTranslateNode.
            // PromQL `or` is left-associative, so flatten the top-level chain into independent branches.
            var branches = new ArrayList<LogicalPlan>();
            flattenUnion(cmd.promqlPlan(), branches);

            if (branches.size() == 1) {
                IntermediateResult intermediateResult = translateIntermediate(cmd.promqlPlan(), cmd.stepId(), cmd.valueId());
                return doTranslateFinal(intermediateResult.plan(), intermediateResult.kind.constant);
            }
            // Compile every branch as its own module (own step/value ids, own shifted evaluation timestamp), then link.
            var intermediateResultPlan = doTranslateUnion(
                branches.stream().map(b -> translateIntermediate(b, new NameId(), new NameId())).toList()
            );
            return doTranslateFinal(intermediateResultPlan, false);
        }

        // -- helpers --

        /* Shared by every `final` translation root */
        private LogicalPlan doTranslateFinal(LogicalPlan plan, boolean localRelation) {
            plan = emitNullsFilter(cmd.source(), emitFinalProjection(plan), cmd.valueAttribute());
            return localRelation ? plan : emitByStepFilter(plan);
        }

        /**
         * Union combinator over independently translated tabular results.
         * {@link UnionAll} aligns columns by name and null-fills missing header, then
         * {@link TopNBy} keeps single row per {@code (step, labelset)} group ordered by incoming IR order.
         */
        private LogicalPlan doTranslateUnion(List<IntermediateResult> intermediateResults) {
            // Already validated against Fork.MAX_BRANCHES by PromqlCommand.verify
            assert Fork.exceedsMaxBranches(intermediateResults.size()) == false
                : "invariant: union branch count ["
                    + intermediateResults.size()
                    + "] must be less of equal Fork.MAX_BRANCHES ["
                    + Fork.MAX_BRANCHES
                    + "]";

            var source = cmd.source();
            var branchPlans = new ArrayList<LogicalPlan>(intermediateResults.size());
            for (int i = 0; i < intermediateResults.size(); i++) {
                // Drop null-valued rows per branch so an absent left side does not shadow a present right side.
                var ir = intermediateResults.get(i);
                LogicalPlan branchPlan = emitNullsFilter(source, ir.plan(), ir.valueColumn());
                var branchTagExpression = new Alias(source, cmd.branchColumnName(), new Literal(source, i, DataType.INTEGER));
                branchPlans.add(new Eval(source, branchPlan, List.of(branchTagExpression)));
            }

            // The attribute ids chosen here are preserved by name when the analyzer later recomputes the UnionAll output,
            // so the groupings below remain valid. The command coda projects the synthetic branch tag away.
            List<Attribute> unionOutput = VectorBinarySet.unionOutputByName(branchPlans);
            var union = new UnionAll(source, branchPlans, unionOutput);

            // Left-preferring dedup: group by every column except the value and the branch tag, keep the lowest branch.
            var groupings = new ArrayList<Expression>();
            Attribute branchAttr = null;
            for (Attribute attr : unionOutput) {
                if (attr.name().equals(cmd.branchColumnName())) {
                    branchAttr = attr;
                } else if (attr.name().equals(cmd.valueColumnName()) == false) {
                    groupings.add(attr);
                }
            }
            var order = new Order(source, branchAttr, Order.OrderDirection.ASC, Order.NullsPosition.LAST);
            return new TopNBy(source, union, List.of(order), new Literal(source, 1, DataType.INTEGER), groupings);
        }

        /**
         * Translates independent query fragment into intermediate result (IR).
         * Think of IR as table
         */
        private IntermediateResult translateIntermediate(LogicalPlan branch, NameId valueId) {
            IntermediateResult ir = doTranslateTryInline(doTranslateNode(branch));

            var plan = ir.plan();
            var valueExpr = ir.value();
            var header = ir.header();
            // A vector match self-filters each operand's own source with that operand's own @timestamp; a combined outer
            // source-time filter would push one operand's @timestamp across both sources - skip over InnerJoin.
            Expression timeFilter = plan.anyMatch(p -> p instanceof InnerJoin) ? null : emitBySrcTimeFilter(branch);
            var filter = combineAndNullable(Arrays.asList(ir.pendingFilter(), timeFilter));
            if (filter != null) {
                plan = pushDownSrcTimestampFilter(plan, filter);
            }

            if (ir.kind.constant == false) {
                // TimeSeriesAggregate always applies because InstantSelectors adds implicit last_over_time().
                // TODO: with metric references without last_over_time, a plain Aggregate could do (#141501 discussion).
                if (ir.kind.afterInitialAggregation == false) {
                    plan = emitInitialAggregate(plan, header, valueExpr);
                    valueExpr = collectValueAttribute(plan);
                }
                if (branch instanceof VectorBinaryComparison comparison && comparison.filterMode()) {
                    // Filter-mode comparison (metric > x): keep the left operand's value, filter rows by the comparison.
                    ToDouble right = new ToDouble(comparison.right().source(), ((LiteralSelector) comparison.right()).literal());
                    var condition = comparison.op().asFunction().create(comparison.source(), valueExpr, right, configuration());
                    plan = new Filter(comparison.source(), plan, condition);
                }
            }

            // The value column definition: the translateIntermediate's value expression cast to double under the caller's id.
            Alias value = emitValueDoubleCastExpression(valueExpr, valueId);
            plan = new Eval(cmd.source(), plan, List.of(value));
            if (ir.kind.constant == false) {
                plan = pushDownEvaluationTimestampFilter(plan, branch);
            }

            Kind kind = ir.kind.constant ? Kind.CONSTANT : Kind.AFTER_INITIAL_AGGREGATE;
            return new IntermediateResult(plan, value.toAttribute(), null, header, kind);
        }

        /** Folds a branch whose value depends on nothing but the step column into a compile-time step/value relation. */
        private IntermediateResult doTranslateTryInline(IntermediateResult result) {
            Attribute stepAttr = cmd.stepAttribute();
            if (result.kind.constant
                || cmd.start().value() == null
                || result.value().references().stream().allMatch(ref -> ref.semanticEquals(stepAttr)) == false) {
                return result;
            }
            var plan = PromqlLogicalPlanBuilder.buildLocalRelation(cmd);
            var step = plan.output().getFirst();
            var value = result.value().transformUp(Attribute.class, attr -> attr.semanticEquals(stepAttr) ? step : attr);
            return new IntermediateResult(plan, value, result.pendingFilter(), result.header(), Kind.CONSTANT);
        }

        /**
         * Recursively translates a PromQL plan node. The source relation {@code cmd.child()} is the leaf at the bottom of
         * the produced subtree; the PromQL tree is walked top-down and the ESQL plan assembled bottom-up on the way back.
         */
        private IntermediateResult doTranslateNode(LogicalPlan node) {
            return switch (node) {
                case AcrossSeriesAggregate agg -> doTranslateAcrossSeriesAgg(agg);
                case AcrossSeriesReduction reduction -> doTranslateAcrossSeriesReduction(reduction);
                case HistogramQuantile histogramQuantile -> doTranslateHq(histogramQuantile);
                case ScalarConversionFunction scalar -> doTranslateScalarConvertion(scalar);
                case PromqlFunctionCall functionCall -> doTranslateFunc(functionCall);
                case ScalarFunction scalarFunction -> doTranslateScalarFunc(scalarFunction);
                case VectorBinaryOperator binaryOp -> doTranslateBinaryOp(binaryOp);
                case Selector selector -> doTranslateSelector(selector);
                default -> throw new QlIllegalArgumentException("Unsupported PromQL plan node: {}", node);
            };
        }

        /**
         * Expressions compose lazily up the tree until they cross an aggregation boundary: once the plan below is
         * aggregated, the expression must materialize as the value column (an Eval) so parents reference it by attribute.
         */
        private IntermediateResult doTranslateAddValueEval(IntermediateResult t, Expression value, Header header) {
            if (t.kind.afterInitialAggregation == false) {
                return t.with(t.plan(), value, header);
            }
            Alias alias = new Alias(value.source(), cmd.valueColumnName(), value);
            return t.with(new Eval(cmd.source(), t.plan(), List.of(alias)), alias.toAttribute(), header);
        }

        /**
         * The <b>translate</b> and <b>negotiate</b> phases for a node with one child: translate the child against a
         * demand and, when the returned header misses columns the node requires, push the missing columns down and
         * translate once more. The requirement is a function of the produced header because a node's required columns
         * (a regrouping, join keys) are only known once the child's shape is. Columns still missing after the retry
         * are genuinely unavailable and null-define in the <b>bind</b> phase.
         */
        private IntermediateResult translateChecked(
            Header demand,
            UnaryOperator<Header> requirement,
            Function<Translation, IntermediateResult> translate
        ) {
            IntermediateResult ir = translate.apply(withPushDownHeader(demand));
            return ir.kind.constant ? ir : retried(ir, demand, requirement.apply(ir.header()), translate);
        }

        /**
         * The <b>negotiate</b> phase: when a translated subtree misses required columns, translate it once more with
         * the missing columns pushed down. A column still missing after the retry is genuinely unavailable and
         * null-defines in the <b>bind</b> phase.
         */
        private IntermediateResult retried(
            IntermediateResult ir,
            Header demand,
            Header required,
            Function<Translation, IntermediateResult> translate
        ) {
            Header missing = required.missing(ir.header());
            return missing.isDefined() ? translate.apply(withPushDownHeader(demand.plus(missing))) : ir;
        }

        /**
         * Translates {@code AcrossSeriesAggregate} to an ESQL {@code Aggregate}. PromQL aggregation shape is dynamic and
         * cannot be enumerated at plan time. A parent translates its child, inspects the returned shape, and re-invokes
         * it with an additional load-time identity requirement when necessary. Only {@code AcrossSeriesAggregate}
         * creates plan-level aggregation nodes; within-series aggregates and function calls lower to expressions.
         */
        private IntermediateResult doTranslateAcrossSeriesAgg(AcrossSeriesAggregate agg) {
            // translate + negotiate: the child compiles against this demand transposed across the aggregate; the
            // requirement (the regrouping) is a function of the shape the child produces.
            Header demand = headerToPushDown.withAcrossSeriesAgg(agg.grouping(), agg.groupings());
            UnaryOperator<Header> regroup = header -> header.withAcrossSeriesAgg(agg.grouping(), agg.groupings(), agg.output());
            IntermediateResult ir = translateChecked(demand, regroup, translation -> translation.doTranslateNode(agg.child()));
            if (ir.kind.constant) {
                return ir;
            }

            // report: the regrouped surface, carrying the non-grouping columns the surrounding query demands.
            Header grouping = regroup.apply(ir.header());
            assert grouping.missing(ir.header()).hasTimeSeriesColumns() == false
                : "invariant: identity grouping [" + grouping + "] not covered by [" + ir.header() + "]";
            Header outputHeader = ir.header().regrouped(grouping, this.headerToPushDown);

            // bind + emit: doTranslateAgg links the header to the child plan and builds the aggregate over it.
            var promqlCtx = new PromqlContext(time, AggregateFunction.NO_WINDOW, stepAttr(), configuration());
            return doTranslateAgg(ir, ir.plan(), outputHeader, agg.grouping() == WITHOUT, agg.buildEsqlFunction(ir.value(), promqlCtx));
        }

        /**
         * Translates an {@link AcrossSeriesReduction} ({@code topk}/{@code bottomk}): collapse the child to one row
         * per series, then rank and keep the top {@code k}. A {@code by} clause only partitions the ranking; it does
         * not change output header.
         */
        private IntermediateResult doTranslateAcrossSeriesReduction(AcrossSeriesReduction plan) {
            if (plan.grouping() == WITHOUT) {
                throw new VerificationException("function [{}] is not yet supported with [{}]", plan.functionName(), WITHOUT.name());
            }

            Translation childTranslation = withPushDownHeader(Header.undefined());
            IntermediateResult childResult = childTranslation.doTranslateNode(plan.child());
            if (childResult.kind.constant) {
                return childResult;
            }

            var header = childResult.header().including(plan.groupings());

            var promqlCtx = new PromqlContext(time, AggregateFunction.NO_WINDOW, stepAttr(), configuration());
            IntermediateResult aggregated = doTranslateAgg(childResult, childResult.plan(), header, false, childResult.value());
            LogicalPlan result = emitTopNBy(plan, aggregated.plan(), header, promqlCtx);
            return aggregated.with(result, aggregated.value(), header);
        }

        /** Ranks the already-collapsed per-series rows and keeps the top {@code k} within each step. */
        private LogicalPlan emitTopNBy(
            AcrossSeriesReduction reduction,
            LogicalPlan resultPlan,
            Header header,
            PromqlContext promqlContext
        ) {
            var groupings = new ArrayList<Expression>();
            groupings.add(stepAttr());
            if (reduction.grouping() == AcrossSeriesAggregate.Grouping.BY) {
                header = header.transformExpressions((column, grouping) -> resolveColumn(column, resultPlan.output()));
                for (var label : reduction.groupings()) {
                    Attribute resolved = header.column(toCanonicalName(label));
                    assert resolved != null : "invariant: [ " + reduction.functionName() + " ] requre a partition label [ " + label + " ]";
                    groupings.add(resolved);
                }
            }
            var order = (Order) reduction.buildEsqlFunction(collectValueAttribute(resultPlan), promqlContext);
            return new TopNBy(
                reduction.source(),
                resultPlan,
                order != null ? List.of(order) : List.of(),
                new ToInteger(reduction.source(), reduction.parameters().getFirst()),
                groupings
            );
        }

        /** The doTranslateAgg combinator: regroups a grouped table, or emits the innermost `_timeseries` doTranslateAgg over a raw one. */
        private IntermediateResult doTranslateAgg(IntermediateResult child, LogicalPlan plan, Header header, boolean pack, Expression agg) {
            LogicalPlan result;
            if (child.kind.afterInitialAggregation) {
                result = emitIntermediateAggregate(plan, header, agg, header.hasTimeSeriesGrouping() || pack);
            } else {
                result = emitInitialAggregate(plan, header, agg);
            }
            return new IntermediateResult(
                result,
                collectValueAttribute(result),
                child.pendingFilter(),
                header,
                Kind.AFTER_INITIAL_AGGREGATE
            );
        }

        private IntermediateResult doTranslateHq(HistogramQuantile hq) {
            // translate + negotiate: histogram_quantile is a regroup without the `le` bucket label; the requirement
            // is empty until the child exposes `le` at all (native histograms and le-less inputs take the paths below).
            UnaryOperator<Header> regroup = header -> {
                Attribute bucket = header.column(HistogramQuantile.LE_LABEL);
                return bucket == null ? Header.undefined() : header.groupedWithout(List.of(bucket));
            };
            IntermediateResult ir = translateChecked(headerToPushDown, regroup, translation -> translation.doTranslateNode(hq.child()));
            if (ir.kind.constant) {
                return ir;
            }
            var definition = PromqlHistogramQuantile.PROMQL_DEFINITION;

            // native histograms - distinguishable only at this point in planning are regular value transformations.
            if (ir.value().resolved() && ir.value().dataType().isHistogram()) {
                return doTranslateFunc(new ValueTransformationFunction(hq.source(), hq.child(), definition, hq.parameters()));
            }

            // classic counter backed histograms need the special treatment below;
            LogicalPlan childPlan = ir.plan();
            var le = ir.header().column(HistogramQuantile.LE_LABEL);
            if (le == null) {
                // like prometheus, return warning and drop series w/o `le`
                HeaderWarning.addWarning("histogram_quantile: input vector has no le label; no buckets to evaluate");
                var skipAllFilter = new Filter(hq.source(), childPlan, Literal.FALSE);
                var nullGrouping = new Values(hq.source(), new Literal(hq.source(), null, DataType.DOUBLE));
                return doTranslateAgg(ir, skipAllFilter, ir.header(), false, nullGrouping);
            }

            // bind + emit: regroup without `le` and build the quantile aggregate over the bound bucket column.
            Header grouping = regroup.apply(ir.header());
            if (ir.kind.afterInitialAggregation == false) {
                childPlan = emitInitialAggregate(childPlan, ir.header(), ir.value());
                ir = new IntermediateResult(
                    childPlan,
                    collectValueAttribute(childPlan),
                    ir.pendingFilter(),
                    ir.header(),
                    Kind.AFTER_INITIAL_AGGREGATE
                );
                le = ir.header().column(HistogramQuantile.LE_LABEL);
                assert le != null : "invariant: [ " + HistogramQuantile.LE_LABEL + " ] required";
            }

            // histogram_quantile groups by every label except the `le` bucket label, so `le` is the single excluded
            // dimension - the returned header drops it and the innermost `_timeseries` excludes it. Bucket counts are
            // consumed as doubles; counter buckets are frequently integer/long typed, so cast explicitly.
            Header header = ir.header().regrouped(grouping, headerToPushDown);
            Expression count = new ToDouble(hq.source(), ir.value());
            Expression quantile = new PromqlHistogramQuantile(hq.source(), count, le, hq.quantile());
            return doTranslateAgg(ir, childPlan, header, true, quantile);
        }

        /** scalar(): collapse to one value per step, e.g. scalar(sum by (cluster) (metric)). */
        private IntermediateResult doTranslateScalarConvertion(ScalarConversionFunction scalarFunc) {
            IntermediateResult child = doTranslateNode(scalarFunc.child());
            if (child.value().foldable()) {
                return new IntermediateResult(child.plan(), new ToDouble(scalarFunc.source(), child.value()), child.pendingFilter());
            }
            var scalarExpr = new Scalar(scalarFunc.source(), child.value());
            return doTranslateAgg(child, child.plan(), Header.undefined(), false, scalarExpr);
        }

        /** Translates a generic PromQL function call (rate, ceil, abs, etc.) into an expression over the child's value. */
        private IntermediateResult doTranslateFunc(PromqlFunctionCall functionCall) {
            IntermediateResult child = doTranslateNode(functionCall.child());
            if (child.kind.constant) {
                return child;
            }
            Expression window = AggregateFunction.NO_WINDOW;
            if (functionCall.child() instanceof RangeSelector rangeSelector) {
                window = isImplicitRangePlaceholder(rangeSelector.range()) ? cmd.resolveImplicitRangeWindow() : rangeSelector.range();
            }
            var promqlCtx = new PromqlContext(time, window, stepAttr(), configuration());
            return doTranslateAddValueEval(child, functionCall.buildEsqlFunction(child.value(), promqlCtx), child.header());
        }

        /** Translates a scalar function (time(), etc.): an expression over the unchanged source. */
        private IntermediateResult doTranslateScalarFunc(ScalarFunction scalarFunction) {
            var function = scalarFunction.buildEsqlFunction(new PromqlContext(cmd.timestamp(), null, cmd.stepAttribute(), configuration()));
            return new IntermediateResult(cmd.child(), function);
        }

        /**
         * Operands that share a frame - the same source grouped the same way - fold into one expression over that
         * frame. Only operands whose identity differs have to be matched, which goes through
         * {@link #doTranslateBinOpInnerJoin} over independently compiled operands. A nested {@code or} keeps the
         * expression-merge path.
         */
        private IntermediateResult doTranslateBinaryOp(VectorBinaryOperator op) {
            if ((op instanceof VectorBinarySet s && UNION == s.op()) == false) {
                if (isScalar(op.left()) == false && isScalar(op.right()) == false && op.left().equals(op.right()) == false) {
                    return doTranslateBinOpInnerJoin(op);
                }
                // an explicit modifier forces matching even when the operands would fold
                if (hasVectorMatch(op)) {
                    return doTranslateBinOpInnerJoin(op);
                }
            }

            // In cases like:
            // `avg(...) by(A) + sum(...) by(B)`,
            // if A = B the result is computed in one pass:
            // `a=avg(...), b=sum(...), result=a+b by (A)`;
            // which is cheaper than join.
            return doTranslateBinaryOpAggregate(op);
        }

        /** compose operator as an expression over shared aggregate */
        private IntermediateResult doTranslateBinaryOpAggregate(VectorBinaryOperator binaryOp) {
            IntermediateResult left = doTranslateNode(binaryOp.left());
            Expression leftExpr = new ToDouble(left.value().source(), left.value());
            if (binaryOp instanceof VectorBinaryComparison comp && comp.filterMode()) {
                return left.with(left.plan(), leftExpr, left.header());
            }

            IntermediateResult right = doTranslateNode(binaryOp.right());
            Expression rightExpr = new ToDouble(right.value().source(), right.value());
            Expression binaryExpr = binaryOp.binaryOp().asFunction().create(binaryOp.source(), leftExpr, rightExpr, configuration());

            LogicalPlan plan;
            Expression filter;
            if (left.kind.afterInitialAggregation && right.kind.afterInitialAggregation) {
                plan = emitBinaryOperatorAggregateExpression(left, right);
                filter = null;
            } else {
                plan = left.kind.afterInitialAggregation ? left.plan() : right.plan();
                filter = combineAndNullable(Arrays.asList(left.pendingFilter(), right.pendingFilter()));
            }
            Header shape = left.header().isDefined() ? left.header() : right.header();
            Kind kind = left.kind.afterInitialAggregation || right.kind.afterInitialAggregation
                ? Kind.AFTER_INITIAL_AGGREGATE
                : Kind.BEFORE_INITIAL_AGGREGATE;
            IntermediateResult result = new IntermediateResult(plan, null, filter, shape, kind);
            return doTranslateAddValueEval(result, binaryExpr, shape);
        }

        /**
         * Translates a vector-matched join operator into an {@link InnerJoin}: each operand becomes an independent series
         * pipeline, joined on shared {@code step} + label keys, and the result value is computed on the joined rows.
         * The operands compile against the labels the join demands, like any other header push-down: a demanded label
         * comes back as a concrete column wherever the operand can carry it, and a label the operand dropped stays
         * absent and null-fills at the join.
         */
        private IntermediateResult doTranslateBinOpInnerJoin(VectorBinaryOperator op) {
            // translate: the operands compile against the surrounding demand alone; what the join itself needs is
            // negotiated below, from the shapes the operands actually produced.
            Function<Translation, IntermediateResult> left = t -> t.translateIntermediate(op.left(), new NameId(), new NameId());
            Function<Translation, IntermediateResult> right = t -> t.translateIntermediate(op.right(), new NameId(), new NameId());
            IntermediateResult lhs = left.apply(this);
            IntermediateResult rhs = right.apply(this);

            // negotiate: the key set dispatches on the matching mode, over what each side offers (an opaque identity
            // offers every source label it does not exclude). A plain match keys on the labels both sides offer;
            // on(...) keys on the named labels wherever either side offers them (the other side's key binds to null,
            // matching PromQL's absent-label-equals-empty rule); ignoring(...) removes the named labels from the
            // plain intersection. Each side is then asked for the keys plus the labels the node's declared output
            // must expose (on(...) keys and group modifier labels), and a side whose header misses a column it can
            // offer is translated once more with it pushed down. Key columns are re-identified so a key bound as a
            // null definition on one side never collides with a live attribute on the other.
            Header declared = Header.fromAttributes(op.output());
            Header lhsOffer = lhs.header().expand();
            Header rhsOffer = rhs.header().expand();
            Header named = labelsHeader(op.match().filterLabels());
            Header keys = (switch (op.match().condition()) {
                case NONE -> lhsOffer.minus(lhsOffer.missing(rhsOffer));
                case ON -> named.bind(lhsOffer.plus(rhsOffer), false);
                case IGNORING -> lhsOffer.minus(lhsOffer.missing(rhsOffer)).minus(named);
            }).transformExpressions((column, grouping) -> new NamedColumn(column.attribute().withId(new NameId())));
            lhs = retried(lhs, headerToPushDown, keys.plus(declared).bind(lhsOffer, false), left);
            rhs = retried(rhs, headerToPushDown, keys.plus(declared).bind(rhsOffer, false), right);

            // bind: the operands' carried headers are the final surfaces; link the negotiated columns to them. The
            // build side is re-identified first so the join inputs share no attribute ids (both stack on the same
            // source relation). Every key comes from an operand's offer, so after the retry at least one side names
            // it; a key the other side lacks binds to a null definition there. The output binds the declared surface
            // plus the surrounding demand the same way.
            boolean rightJoining = op.match().joining() == Joining.RIGHT;
            IntermediateResult probe = rightJoining ? rhs : lhs;
            IntermediateResult build = reidentified(rightJoining ? lhs : rhs);
            assert keys.missing(probe.header()).missing(build.header()).isDefined() == false
                : "invariant: negotiated keys [" + keys + "] not covered by either operand";
            Header probeKeys = keys.bind(probe.header(), true);
            Header buildKeys = keys.bind(build.header(), true);
            Header surface = outputSurface(op.match(), probe.header(), build.header());
            Header output = declared.plus(headerToPushDown).bind(surface, true);

            // emit: materialize the key definitions, pack the keys into one single-valued column per side, join on
            // [step, pack], and compute the operator's value over the joined rows.
            LogicalPlan probePlan = emitHeaderDefinitions(probe.plan(), probeKeys);
            LogicalPlan buildPlan = emitHeaderDefinitions(build.plan(), buildKeys);
            Attribute probeStep = findByName(probe.plan().output(), cmd.stepColumnName());
            Attribute buildStep = findByName(build.plan().output(), cmd.stepColumnName());
            List<Attribute> probeFields = List.of(probeStep);
            List<Attribute> buildFields = List.of(buildStep);
            if (probeKeys.isDefined()) {
                Attribute probePack = PackDims.newPackedAttribute(cmd.source());
                Attribute buildPack = PackDims.newPackedAttribute(cmd.source());
                probePlan = new PackDims(cmd.source(), probePlan, probeKeys.labels(), probePack);
                buildPlan = new PackDims(cmd.source(), buildPlan, buildKeys.labels(), buildPack);
                probeFields = List.of(probeStep, probePack);
                buildFields = List.of(buildStep, buildPack);
            }
            List<Attribute> added = List.of();
            if (op instanceof VectorBinarySet) {
                List<NamedExpression> dedupKeys = List.copyOf(buildFields);
                buildPlan = new Aggregate(buildPlan.source(), buildPlan, new ArrayList<>(dedupKeys), dedupKeys);
            } else {
                added = buildPlan.output()
                    .stream()
                    .filter(attr -> attr.semanticEquals(build.valueColumn()) || contains(surface.labels(), attr))
                    .toList();
            }
            boolean oneToOne = op instanceof VectorBinarySet == false && op.match().joining() == Joining.NONE;
            LogicalPlan join = new InnerJoin(cmd.source(), probePlan, buildPlan, probeFields, buildFields, added, oneToOne);
            Expression leftValue = rightJoining ? build.value() : probe.value();
            Expression rightValue = rightJoining ? probe.value() : build.value();
            Expression lhsExpr = new ToDouble(leftValue.source(), leftValue);
            Expression rhsExpr = new ToDouble(rightValue.source(), rightValue);
            Expression result = op instanceof VectorBinarySet
                ? lhsExpr
                : op.binaryOp().asFunction().create(op.source(), lhsExpr, rhsExpr, configuration());
            Expression filter = null;
            if (op instanceof VectorBinaryComparison comparison) {
                filter = comparison.filterMode() ? result : null;
                result = comparison.filterMode() ? lhsExpr : new ToDouble(result.source(), result);
            }
            Alias stepAlias = new Alias(probeStep.source(), probeStep.name(), probeStep, cmd.stepId());
            Alias valueAlias = new Alias(op.source(), cmd.valueColumnName(), result, new NameId());
            LogicalPlan plan = emitHeaderDefinitions(new Eval(cmd.source(), join, List.of(valueAlias, stepAlias)), output);
            if (filter != null) {
                plan = new Filter(op.source(), plan, filter);
            }
            List<NamedExpression> projected = new ArrayList<>(List.of(valueAlias.toAttribute(), stepAlias.toAttribute()));
            projected.addAll(output.exposedExpressions());
            plan = new Project(cmd.source(), plan, projected);

            // report: the output surface the parent composes against.
            return new IntermediateResult(plan, valueAlias.toAttribute(), null, output, Kind.AFTER_INITIAL_AGGREGATE);
        }

        /**
         * Re-identifies a finished operand so it shares no attribute ids with the other join input (both stack on
         * the same source relation). The value column is renamed in the same pass so it cannot collide by name with
         * the other side's after the join.
         */
        private IntermediateResult reidentified(IntermediateResult ir) {
            Map<NameId, NameId> ids = new HashMap<>();
            String valueName = TemporaryNameGenerator.locallyUniqueTemporaryName(cmd.valueColumnName());
            LogicalPlan plan = ir.plan()
                .transformExpressionsDown(Expression.class, e -> reidExpr(renamed(e, cmd.valueColumnName(), valueName), ids));
            Expression value = reidExpr(renamed(ir.valueColumn(), cmd.valueColumnName(), valueName), ids);
            Header header = mapHeaderAttributes(ir.header(), ids);
            return new IntermediateResult(plan, value, ir.pendingFilter(), header, ir.kind);
        }

        /** Fold left and right aggregates into a single plan. */
        private LogicalPlan emitBinaryOperatorAggregateExpression(IntermediateResult left, IntermediateResult right) {
            var names = new TemporaryNameGenerator.Monotonic();
            var rightAgg = right.plan().collect(Aggregate.class).getFirst();

            var result = left.plan().transformDown(Aggregate.class, leftAgg -> {
                Set<String> leftGroupingNames = new HashSet<>();
                for (Expression grouping : leftAgg.groupings()) {
                    if (grouping instanceof NamedExpression ne) {
                        leftGroupingNames.add(ne.name());
                    }
                }
                Set<String> rightGroupingNames = new HashSet<>();
                for (Expression grouping : rightAgg.groupings()) {
                    if (grouping instanceof NamedExpression ne) {
                        rightGroupingNames.add(ne.name());
                    }
                }
                boolean groupingsCompatible = leftAgg.groupings().size() == rightAgg.groupings().size()
                    && leftGroupingNames.equals(rightGroupingNames);

                if (groupingsCompatible == false) {
                    throw new VerificationException(
                        "binary operations between vectors with mismatched grouping keys are not yet supported"
                    );
                }

                var uniqueAggregates = new LinkedHashSet<Expression>();
                uniqueAggregates.addAll(withFilter(leftAgg.aggregates(), left.pendingFilter()));
                uniqueAggregates.addAll(withFilter(rightAgg.aggregates(), right.pendingFilter()));

                var newAggregates = uniqueAggregates.stream().map(e -> (NamedExpression) e).map(e -> {
                    Expression inner = e;
                    if (e instanceof Alias a) {
                        inner = a.child();
                    }
                    return new Alias(e.source(), names.next(e.name()), inner, e.id());
                }).toList();

                return leftAgg.with(leftAgg.child(), leftAgg.groupings(), newAggregates);
            });

            var rightEvals = right.plan().collect(Eval.class);
            for (Eval eval : rightEvals.reversed()) {
                result = new Eval(eval.source(), result, eval.fields());
            }
            return result;
        }

        /** Translates a selector (instant, range, or literal); label matchers lower to a pending filter predicate. */
        private IntermediateResult doTranslateSelector(Selector selector) {
            LogicalPlan input = cmd.child();
            LogicalPlan foldedPlan = PromqlLogicalPlanBuilder.tryFoldRelation(cmd, input);
            Expression matcher = emitMatchersPredicateExpression(
                selector.source(),
                selector.labels(),
                selector.labelMatchers(),
                configuration()
            );

            if (selector instanceof LiteralSelector literalSelector) {
                return foldedPlan != null
                    ? new IntermediateResult(foldedPlan, literalSelector.literal(), matcher, Header.undefined(), Kind.CONSTANT)
                    : new IntermediateResult(input, literalSelector.literal(), matcher, Header.undefined());
            }
            if (foldedPlan != null) {
                var empty = new LocalRelation(cmd.source(), List.of(cmd.valueAttribute(), cmd.stepAttribute()), EmptyLocalSupplier.EMPTY);
                return new IntermediateResult(empty, Literal.NULL, null, Header.undefined(), Kind.CONSTANT);
            }

            // An instant selector maps to LastOverTime to get the latest sample per time series.
            Expression expr = selector instanceof InstantSelector
                ? new LastOverTime(selector.source(), selector.series(), AggregateFunction.NO_WINDOW, time)
                : selector.series();
            List<Attribute> dimensions = Header.dimensions(input.output());
            return new IntermediateResult(
                input,
                expr,
                matcher,
                headerToPushDown.withIdentityGrouping().including(dimensions).withUniverse(dimensions)
            );
        }

        /**
         * The innermost doTranslateAgg owns the physical {@code _timeseries} grouping and materializes every ephemeral
         * column in the header with that column's own exclusions.
         */
        private LogicalPlan emitInitialAggregate(LogicalPlan plan, Header header, Expression agg) {
            Source source = cmd.promqlPlan().source();
            boolean needsTimeSeriesGrouping = header.hasTimeSeriesColumns();
            // TranslateTimeSeriesAggregate splits this node into two phases, replacing inner TimeSeriesAggregateFunctions
            // (e.g. LastOverTime) with references to phase-1 results; the phase-2 expression must remain a valid
            // AggregateFunction inside the Aggregate node:
            // Sum(LastOverTime(m)) -> Sum(ref) -- Sum survives, no wrap needed
            // LastOverTime(m) -> ref -- bare ref, needs Values(ref)
            // Mul(LastOverTime(m), 8) -> Mul(ref, 8) -- not an agg, needs Values(Mul(ref,8))
            // Guarded by needsTimeSeriesGrouping because without dimension grouping (e.g. constants like vector(5))
            // TranslateTimeSeriesAggregate passes Literals straight to phase 1.
            boolean wrapWithValues = (agg instanceof AggregateFunction == false) || (agg instanceof TimeSeriesAggregateFunction);
            if (needsTimeSeriesGrouping && wrapWithValues) {
                agg = new Values(agg.source(), agg);
            }

            var names = new TemporaryNameGenerator.Monotonic();
            Header physicalHeader = header.transformExpressions((col, grouping) -> {
                if (col instanceof TimeSeriesColumn tc) {
                    List<Expression> excluded = tc.exclusions().stream().<Expression>map(label -> {
                        Attribute resolved = findByName(plan.output(), toCanonicalName(label));
                        return resolved != null ? resolved : label;
                    }).toList();
                    String name = grouping ? MetadataAttribute.TIMESERIES : names.next(MetadataAttribute.TIMESERIES);
                    Alias alias = new Alias(source, name, new TimeSeriesWithout(source, excluded), tc.attribute().id());
                    return new TimeSeriesColumn(alias, tc.exclusions());
                }
                var m = findByIdOrName(col.attribute(), plan.output());
                return m != null ? new NamedColumn(m) : null;
            });
            // Every exposed column is functionally dependent on the series identity, so grouping by all of them preserves
            // per-series granularity while making the full transformed header available to the surrounding query.
            List<NamedExpression> groupKeys = physicalHeader.expressions();
            List<NamedExpression> outKeys = physicalHeader.exposedExpressions();

            var value = new Alias(agg.source(), cmd.valueColumnName(), agg);
            return new TimeSeriesAggregate(
                source,
                plan,
                groupings(stepBucketAlias, groupKeys),
                aggregates(value, stepAttr(), outKeys),
                null,
                time,
                TimeSeriesAggregate.Origin.PROMQL_COMMAND
            );
        }

        /**
         * Regroups an already-aggregated child. Every regroup first resolves its physical header and null-fills missing
         * grouping columns. A WITHOUT regroup additionally packs dimensions before aggregation to prevent multi-valued
         * dimensions from splitting rows and double-counting, then unpacks them afterwards.
         */
        private LogicalPlan emitIntermediateAggregate(LogicalPlan plan, Header header, Expression aggExpr, boolean requiresPacking) {
            Source source = cmd.source();
            Attribute step = stepAttr();
            if (aggExpr instanceof AggregateFunction == false) {
                aggExpr = new Values(aggExpr.source(), aggExpr);
            }
            NamedExpression value = new Alias(aggExpr.source(), cmd.valueColumnName(), aggExpr);
            List<Attribute> available = plan.output();
            Header physicalHeader = header.transformExpressions((column, grouping) -> {
                if (column instanceof TimeSeriesColumn timeSeries) {
                    Attribute resolved = findById(timeSeries.attribute(), available);
                    return resolved != null ? new TimeSeriesColumn(resolved, timeSeries.exclusions()) : null;
                }
                Attribute resolved = findByIdOrName(column.attribute(), available);
                if (resolved != null) {
                    return new NamedColumn(resolved);
                }
                return grouping ? new NamedColumn(nullColumn(column.attribute())) : null;
            });
            plan = emitHeaderDefinitions(plan, physicalHeader);

            if (requiresPacking) {
                // TranslateTimeSeriesAggregate unpacks the inner TSA's dimensions and this regroup re-packs them.
                List<Attribute> dims = physicalHeader.exposedExpressions().stream().map(NamedExpression::toAttribute).toList();
                if (dims.isEmpty()) {
                    return new Aggregate(source, plan, groupings(step, List.of()), aggregates(value, step, List.of()));
                }
                Attribute packed = PackDims.newPackedAttribute(source);
                PackDims packDims = new PackDims(source, plan, dims, packed);
                Alias packedGrouping = PackDims.newPackedGrouping(source, packed);
                Aggregate agg = new Aggregate(
                    source,
                    packDims,
                    groupings(step, List.of(packedGrouping)),
                    aggregates(value, step, List.of(packedGrouping.toAttribute()))
                );
                Header unpackedHeader = physicalHeader.transformExpressions((col, grouping) -> {
                    Attribute dim = col.attribute();
                    String name = grouping && col instanceof TimeSeriesColumn ? MetadataAttribute.TIMESERIES : dim.name();
                    var unpacked = new ReferenceAttribute(
                        dim.source(),
                        null,
                        name,
                        dim.dataType().noText(),
                        Nullability.TRUE,
                        dim.id(),
                        false
                    );
                    return col instanceof TimeSeriesColumn tc ? new TimeSeriesColumn(unpacked, tc.exclusions()) : new NamedColumn(unpacked);
                });
                List<Attribute> unpackedDims = unpackedHeader.exposedExpressions().stream().map(NamedExpression::toAttribute).toList();
                UnpackDims unpackDims = new UnpackDims(source, agg, packedGrouping.toAttribute(), unpackedDims);
                List<NamedExpression> projections = new ArrayList<>(List.of(value.toAttribute(), step));
                projections.addAll(unpackedDims);
                return new Project(source, unpackDims, projections);
            } else {
                List<NamedExpression> keys = physicalHeader.exposedExpressions();
                return new Aggregate(source, plan, groupings(step, keys), aggregates(value, step, keys));
            }
        }

        private LogicalPlan emitHeaderDefinitions(LogicalPlan plan, Header header) {
            List<Alias> definitions = header.expressions().stream().filter(Alias.class::isInstance).map(Alias.class::cast).toList();
            return definitions.isEmpty() ? plan : new Eval(cmd.source(), plan, definitions);
        }

        /** Projects the plan to the command's declared output, re-aliasing columns that match by name but not by id. */
        private LogicalPlan emitFinalProjection(LogicalPlan plan) {
            var lookupMap = new HashMap<String, Attribute>();
            for (var attr : plan.output()) {
                lookupMap.put(attr.name(), attr);
            }
            // Under a passthrough mapping the plan carries the concrete field (`labels.instance`) while a match key
            // the operator declared itself is named for the label alone, so fall back to the canonical name.
            for (var attr : plan.output()) {
                lookupMap.putIfAbsent(toCanonicalName(attr), attr);
            }
            var projected = new ArrayList<>(cmd.output());
            var evals = new ArrayList<Alias>();
            for (int i = 0; i < projected.size(); i++) {
                var attr = projected.get(i);
                var lookupAttr = lookupMap.get(attr.name());
                if (lookupAttr != null && lookupAttr.semanticEquals(attr) == false) {
                    var alias = new Alias(lookupAttr.source(), attr.name(), lookupAttr, attr.id());
                    evals.add(alias);
                    projected.set(i, alias.toAttribute());
                }
            }
            if (evals.isEmpty() == false) {
                plan = new Eval(cmd.source(), plan, evals);
            }
            return new Project(cmd.source(), plan, projected);
        }

        /** Keeps only steps within the query range; step header are anchored at {@code start} and offset-independent. */
        private LogicalPlan emitByStepFilter(LogicalPlan plan) {
            var source = cmd.source();
            var step = cmd.stepAttribute();
            var start = cmd.start();
            var end = cmd.end();
            var lo = new GreaterThanOrEqual(source, step, start.value() != null ? start : Literal.dateTime(source, EPOCH_MIN));
            var hi = new LessThanOrEqual(source, step, end.value() != null ? end : Literal.dateTime(source, EPOCH_MAX));
            return new Filter(source, plan, new And(source, lo, hi));
        }

        /**
         * The source-time pushdown predicate. Expressed over the <b>raw</b> source timestamp (not the offset-shifted
         * evaluation timestamp) so it can push down to the index; the branch offset is instead folded into the bounds.
         * Expressing it over the shifted timestamp while also adjusting the bounds would apply the offset twice.
         */
        private Expression emitBySrcTimeFilter(LogicalPlan branch) {
            if (cmd.start().value() == null || cmd.end().value() == null) {
                return null;
            }
            var source = cmd.source();
            var offset = cmd.collectFirstOffsetForBranch(branch);
            var timestamp = cmd.timestamp();
            var window = cmd.sourceFilterWindow();
            var lo = new Sub(source, cmd.start(), Literal.timeDuration(source, window.plus(offset)), configuration());
            var hi = new Sub(source, cmd.end(), Literal.timeDuration(source, offset), configuration());
            return new And(source, new GreaterThanOrEqual(source, timestamp, lo), new LessThanOrEqual(source, timestamp, hi));
        }

        /** Adds an Eval on top of the source relation materializing the evaluation timestamp (@timestamp + offset). */
        private LogicalPlan pushDownEvaluationTimestampFilter(LogicalPlan plan, LogicalPlan branch) {
            if (time instanceof ReferenceAttribute ref && cmd.timestampColumnName().equals(ref.name())) {
                Expression base = cmd.timestamp();
                if (base.dataType() == DataType.DATE_NANOS) {
                    base = new ToDatetime(base.source(), base, configuration());
                }
                var offset = cmd.collectFirstOffsetForBranch(branch);
                var shifted = offset.isZero()
                    ? base
                    : new Add(cmd.source(), base, Literal.timeDuration(cmd.source(), offset), configuration());
                var time = new Alias(cmd.source(), cmd.timestampColumnName(), shifted, ref.id());
                return plan.transformUp(node -> node == cmd.child(), node -> new Eval(cmd.source(), node, List.of(time)));
            }
            return plan;
        }

        /** Pushes the label filter down to the EsRelation, combining with an existing relation filter. */
        private LogicalPlan pushDownSrcTimestampFilter(LogicalPlan plan, Expression filterCondition) {
            return plan.transformUp(LogicalPlan.class, p -> {
                if (p instanceof Filter f && f.child() instanceof EsRelation) {
                    return new Filter(f.source(), f.child(), new And(f.source(), f.condition(), filterCondition));
                } else if (p instanceof EsRelation) {
                    return new Filter(cmd.source(), p, filterCondition);
                }
                return p;
            });
        }

        /** The value column definition: the translateIntermediate's value expression, cast to double unless it provably is one. */
        private Alias emitValueDoubleCastExpression(Expression valueExpr, NameId valueId) {
            if ((valueExpr instanceof Attribute == false && valueExpr.resolved() && valueExpr.dataType() == DataType.DOUBLE) == false) {
                valueExpr = new ToDouble(cmd.source(), valueExpr);
            }
            return new Alias(cmd.source(), cmd.valueColumnName(), valueExpr, valueId);
        }

        /**
         * The {@code step} bucket for a branch: the {@link TStep} grouping key shared across all aggregation groupings,
         * derived from the (possibly offset-shifted) evaluation timestamp - so an {@code offset} shifts which samples
         * fall into each fixed output bucket without moving the buckets. {@code stepId} names the synthetic column.
         */
        private Alias emitStepBucketExpression(NameId stepId, Expression time) {
            Expression size;
            Expression start;
            Expression end;
            if (cmd.isInstantQuery()) {
                size = Literal.timeDuration(cmd.source(), cmd.resolveInstantQueryWindow());
                start = new Sub(cmd.source(), cmd.start(), size, configuration());
                end = cmd.end();
            } else {
                size = cmd.resolveTimeBucketSize();
                start = cmd.start().value() != null ? cmd.start() : Literal.dateTime(cmd.source(), EPOCH_MIN);
                end = cmd.end().value() != null ? cmd.end() : Literal.dateTime(cmd.source(), EPOCH_MAX);
            }
            var tstep = new TStep(size.source(), size, start, end, time, configuration());
            return new Alias(tstep.source(), cmd.stepColumnName(), tstep, stepId);
        }

        private boolean canCreateStepBucket() {
            if (cmd.timestamp() == null || cmd.timestamp().resolved() == false) {
                return cmd.isRangeQuery() == false || cmd.buckets() == null || cmd.buckets().value() == null;
            }
            return true;
        }
    }

    // -- pure helpers, independent of the running translation --

    /** A binary operator translated to an {@link InnerJoin}: arithmetic/comparison with on/ignoring matching, or {@code and}. */
    private static boolean isJoinOperator(VectorBinaryOperator op) {
        return op instanceof VectorBinarySet set ? set.op() == VectorBinarySet.SetOp.INTERSECT : hasVectorMatch(op);
    }

    /** Whether the node evaluates to a PromQL scalar (one value per step, no labelset to vector-match on). */
    private static boolean isScalar(LogicalPlan node) {
        return switch (node) {
            case LiteralSelector ignored -> true;
            case ScalarFunction ignored -> true;
            case ScalarConversionFunction ignored -> true;
            case VectorBinaryOperator op -> isScalar(op.left()) && isScalar(op.right());
            default -> false;
        };
    }

    /** Whether {@code attributes} holds the given column, comparing by attribute identity. */
    private static boolean contains(List<Attribute> attributes, Attribute candidate) {
        return attributes.stream().anyMatch(candidate::semanticEquals);
    }

    /** Whether the operator declares explicit vector matching (on/ignoring, group_left/right). */
    private static boolean hasVectorMatch(VectorBinaryOperator op) {
        VectorMatch match = op.match();
        return match.condition() != Condition.NONE || match.joining() != Joining.NONE;
    }

    /**
     * The result surface of a join: the probe side's header, with group-modifier labels sourced from the build side.
     * A declared probe grouping is shadowed by the modifier (null when the build side cannot supply the label), but
     * an opaque probe keeps its identity's labels - a modifier cannot strip a label out of a packed series identity.
     */
    private static Header outputSurface(VectorMatch match, Header probe, Header build) {
        if (match.joining() == Joining.NONE) {
            return probe;
        }
        Header modifiers = labelsHeader(match.groupingLabels());
        Header copied = modifiers.bind(build, false);
        Header shadowed = probe.hasTimeSeriesGrouping() ? copied : modifiers;
        return probe.minus(shadowed).plus(copied);
    }

    private static Header labelsHeader(Set<String> names) {
        return Header.undefined()
            .including(names.stream().<Attribute>map(name -> new ReferenceAttribute(Source.EMPTY, name, DataType.KEYWORD)).toList());
    }

    /** Renames an attribute or alias in a re-identification pass; other expressions pass through unchanged. */
    private static Expression renamed(Expression e, String from, String to) {
        if (e instanceof Attribute a && a.name().equals(from)) {
            return a.withName(to);
        }
        if (e instanceof Alias a && a.name().equals(from)) {
            return new Alias(a.source(), to, a.child(), a.id());
        }
        return e;
    }

    private static Header mapHeaderAttributes(Header header, Map<NameId, NameId> ids) {
        return header.transformExpressions((column, grouping) -> {
            if (column instanceof TimeSeriesColumn tc) {
                List<Attribute> exclusions = tc.exclusions().stream().map(a -> (Attribute) reidExpr(a, ids)).toList();
                List<Attribute> labels = tc.labels().stream().map(a -> (Attribute) reidExpr(a, ids)).toList();
                return new TimeSeriesColumn((NamedExpression) reidExpr(tc.attribute(), ids), exclusions, labels);
            }
            return new NamedColumn((NamedExpression) reidExpr(column.attribute(), ids));
        });
    }

    /** Re-ids a single attribute/alias (leaving other expressions untouched), reusing the shared map for consistency. */
    private static Expression reidExpr(Expression e, Map<NameId, NameId> ids) {
        if (e instanceof Attribute a) {
            return a.withId(ids.computeIfAbsent(a.id(), k -> new NameId()));
        }
        if (e instanceof Alias a) {
            return a.withId(ids.computeIfAbsent(a.id(), k -> new NameId()));
        }
        return e;
    }

    /** Flattens a left-associative top-level {@code or} chain into branches; branch 0 has the highest precedence. */
    private static void flattenUnion(LogicalPlan node, List<LogicalPlan> branches) {
        if (node instanceof VectorBinarySet setOp && setOp.op() == UNION) {
            flattenUnion(setOp.left(), branches);
            flattenUnion(setOp.right(), branches);
        } else {
            branches.add(node);
        }
    }

    private static List<Expression> groupings(Expression step, List<? extends NamedExpression> keys) {
        var groupings = new ArrayList<Expression>(keys.size() + 1);
        groupings.add(step);
        groupings.addAll(keys);
        return groupings;
    }

    private static List<NamedExpression> aggregates(NamedExpression value, Attribute step, List<? extends NamedExpression> keys) {
        var aggregates = new ArrayList<NamedExpression>(keys.size() + 2);
        aggregates.add(value);
        aggregates.add(step);
        aggregates.addAll(keys);
        return aggregates;
    }

    /** The first output attribute is always the value column. */
    private static Expression collectValueAttribute(LogicalPlan plan) {
        return plan.output().getFirst().toAttribute();
    }

    /** PromQL drops series with missing data: filter out rows whose value is null (null label columns are valid). */
    private static LogicalPlan emitNullsFilter(Source source, LogicalPlan plan, Attribute value) {
        return new Filter(source, plan, new IsNotNull(value.source(), value));
    }

    private static boolean isImplicitRangePlaceholder(Expression range) {
        return range.foldable()
            && range.fold(FoldContext.small()) instanceof Duration duration
            && duration.equals(PromqlLogicalPlanBuilder.IMPLICIT_RANGE_PLACEHOLDER);
    }

    /**
     * Lowers PromQL label matchers into an AND of per-label ESQL predicates. Uses {@link AutomatonUtils} to lower a
     * pattern to a predicate cheaper than a regex where possible: exact values become equality/IN, prefix/suffix
     * alternations become STARTS_WITH/ENDS_WITH disjunctions, everything else falls back to RLIKE.
     */
    private static Expression emitMatchersPredicateExpression(
        Source source,
        List<Expression> fields,
        LabelMatchers labelMatchers,
        Configuration config
    ) {
        var matchers = labelMatchers.matchers();
        List<Expression> conditions = new ArrayList<>(matchers.size());
        boolean hasNameMatcher = false;
        for (int i = 0, s = matchers.size(); i < s; i++) {
            LabelMatcher matcher = matchers.get(i);
            // the metric name matcher selects the series; it has no label field to filter on
            if (LabelMatcher.NAME.equals(matcher.name())) {
                hasNameMatcher = true;
                continue;
            }
            Expression field = fields.get(hasNameMatcher ? i - 1 : i); // adjust index if name matcher was seen
            if (field.resolved() && DataType.isString(field.dataType()) == false) {
                field = new ToString(field.source(), field, config);
            }
            conditions.add(emitMatcherConditionExpression(source, field, matcher));
        }
        return conditions.isEmpty() ? null : combineAnd(conditions);
    }

    /** Lowers a single PromQL label matcher to an ESQL predicate; public API also used by the prometheus REST layer. */
    public static Expression emitMatcherConditionExpression(Source source, Expression field, LabelMatcher matcher) {
        if (matcher.matchesAll()) {
            return Literal.fromBoolean(source, true);
        }
        if (matcher.matchesNone()) {
            return Literal.fromBoolean(source, false);
        }
        Expression condition;
        if (matcher.isMultiValue()) {
            // each value is a regex, combine with OR; plain literals match exact with an IN clause
            condition = matcher.matcher().isRegex()
                ? Predicates.combineOr(
                    matcher.values().stream().<Expression>map(v -> new RLike(source, field, new RLikePattern(v))).toList()
                )
                : new In(source, field, matcher.values().stream().<Expression>map(v -> Literal.keyword(source, v)).toList());
            if (matcher.isNegation()) {
                condition = new Not(source, condition);
            }
        } else {
            var exact = AutomatonUtils.matchesExact(matcher.automaton());
            if (exact != null) {
                condition = new Equals(source, field, Literal.keyword(source, exact));
            } else {
                var fragments = AutomatonUtils.extractFragments(matcher.getFirstValue());
                condition = fragments != null && fragments.isEmpty() == false
                    ? emitMatcherOperatorFn(source, field, fragments)
                    // fallback: RLIKE over the full pattern, anchored per PromQL semantics
                    : new RLike(source, field, new RLikePattern(matcher.getFirstValue()));
                if (matcher.isNegation()) {
                    condition = new Not(source, condition);
                }
            }
        }
        // absent header are treated as having value "" because if the matcher accepts the empty string
        // (e.g. {label=""} or {label!="foo"}), series where the label field is NULL (absent) must also match.
        if (matcher.matchesEmpty()) {
            condition = Predicates.combineOr(List.of(new IsNull(source, field), condition));
        }
        return condition;
    }

    /** Disjoint fragments sort EXACT -> PREFIX -> SUFFIX -> REGEX (most selective first); an all-EXACT set lowers to IN. */
    private static Expression emitMatcherOperatorFn(Source source, Expression field, List<AutomatonUtils.PatternFragment> fragments) {
        var sorted = fragments.stream().sorted(Comparator.comparingInt(f -> f.type().ordinal())).toList();
        if (sorted.stream().allMatch(f -> f.type() == AutomatonUtils.PatternFragment.Type.EXACT)) {
            return new In(source, field, sorted.stream().<Expression>map(f -> Literal.keyword(source, f.value())).toList());
        }

        var expr = sorted.stream().map(f -> {
            Literal value = Literal.keyword(source, f.value());
            return switch (f.type()) {
                case EXACT -> new Equals(source, field, value);
                case PREFIX -> new StartsWith(source, field, value);
                case PROPER_PREFIX -> new And(source, new NotEquals(source, field, value), new StartsWith(source, field, value));
                case SUFFIX -> new EndsWith(source, field, value);
                case PROPER_SUFFIX -> new And(source, new NotEquals(source, field, value), new EndsWith(source, field, value));
                case REGEX -> new RLike(source, field, new RLikePattern(f.value()));
            };
        }).toList();

        return Predicates.combineOr(expr);
    }
}
