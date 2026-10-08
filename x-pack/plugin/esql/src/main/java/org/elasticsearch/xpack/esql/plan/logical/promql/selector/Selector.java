/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.promql.selector;

import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.QlIllegalArgumentException;
import org.elasticsearch.xpack.esql.core.capabilities.Resolvables;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.TimeSeriesMetadataAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.predicate.Predicates;
import org.elasticsearch.xpack.esql.parser.promql.PromqlLogicalPlanBuilder;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;
import org.elasticsearch.xpack.esql.plan.logical.local.EmptyLocalSupplier;
import org.elasticsearch.xpack.esql.plan.logical.local.LocalRelation;
import org.elasticsearch.xpack.esql.plan.logical.promql.PromqlPlan;
import org.elasticsearch.xpack.esql.plan.logical.promql.TranslationContext;
import org.elasticsearch.xpack.esql.plan.logical.promql.TranslationContext.IntermediateResult;
import org.elasticsearch.xpack.esql.plan.logical.promql.TranslationContext.IntermediateResult.Kind;
import org.elasticsearch.xpack.esql.plan.logical.promql.TranslationSchema;
import org.elasticsearch.xpack.esql.session.Configuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.xpack.esql.plan.logical.promql.TranslationSchema.project;

/**
 * Base class representing a PromQL vector selector: which series to read and when to evaluate them.
 * <pre>
 * metric-name constraints  every __name__ matcher with its operator ({@link MetricNameMatchers})
 * label predicates         every other matcher bound to its label field ({@link LabelPredicate})
 * evaluation               range, offset, @ timestamp
 * </pre>
 * Every spelling of a selector normalizes to the same shape: {@code tx{host="a"}}, {@code {__name__="tx", host="a"}},
 * {@code {host="a", __name__="tx"}} and {@code {"tx", host="a"}} all carry one {@code __name__="tx"} constraint and one
 * {@code host="a"} predicate. Repeated matchers are a conjunction and all of them are kept.
 */
public abstract sealed class Selector extends UnaryPlan implements PromqlPlan permits InstantSelector, RangeSelector, LiteralSelector {
    // implements TelemetryAware

    /**
     * The field of the one metric the name constraints admit, read directly; null unless they admit exactly one.
     * A literal selector carries its literal here.
     */
    private final Expression series;
    private final MetricNameMatchers metricName;
    private final List<LabelPredicate> labelPredicates;
    private final Evaluation evaluation;
    protected List<Attribute> output;

    Selector(
        Source source,
        LogicalPlan child,
        Expression series,
        MetricNameMatchers metricName,
        List<LabelPredicate> labelPredicates,
        Evaluation evaluation
    ) {
        super(source, child);
        this.series = series;
        this.metricName = metricName;
        this.labelPredicates = labelPredicates;
        this.evaluation = evaluation;
    }

    public Expression series() {
        return series;
    }

    public MetricNameMatchers metricName() {
        return metricName;
    }

    public List<LabelPredicate> labelPredicates() {
        return labelPredicates;
    }

    public Evaluation evaluation() {
        return evaluation;
    }

    @Override
    public boolean expressionsResolved() {
        return (series == null || series.resolved()) && Resolvables.resolved(labelPredicates);
    }

    @Override
    public boolean equals(Object o) {
        if (super.equals(o)) {
            Selector selector = (Selector) o;
            return Objects.equals(evaluation, selector.evaluation)
                && Objects.equals(metricName, selector.metricName)
                && Objects.equals(series, selector.series)
                && Objects.equals(labelPredicates, selector.labelPredicates);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), series, metricName, labelPredicates, evaluation);
    }

    @Override
    public String getWriteableName() {
        throw new UnsupportedOperationException("should not serialize");
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        throw new UnsupportedOperationException("should not serialize");
    }

    /**
     * The samples a source-backed selector reads: the field of the one metric its name constraints admit, or nothing at all
     * when they admit none. Selecting metrics by a general name constraint is rejected by the verifier before translation.
     */
    protected final Expression samples() {
        return switch (metricName.selection()) {
            case EXACT -> series;
            case EMPTY -> new Literal(source(), null, DataType.DOUBLE);
            case GENERAL -> throw new QlIllegalArgumentException("no metric field to read for selector [{}]", sourceText());
        };
    }

    /**
     * The selector's predicate over the source relation: every label predicate lowered over its own field, and {@code false}
     * when the metric name constraints admit no metric. Null when there is nothing to filter on.
     */
    protected final Expression predicate(Configuration configuration) {
        List<Expression> conditions = new ArrayList<>(labelPredicates.size() + 1);
        if (metricName.selection() == MetricNameMatchers.Selection.EMPTY) {
            conditions.add(Literal.fromBoolean(source(), false));
        }
        for (LabelPredicate labelPredicate : labelPredicates) {
            conditions.add(labelPredicate.condition(configuration));
        }
        return conditions.isEmpty() ? null : Predicates.combineAnd(conditions);
    }

    /**
     * Translates a source-backed selector (instant or range) reading {@code value} per series; the selector predicate becomes
     * a pending filter. Shared by the selectors that read the source relation; each still declares its own
     * {@link #translate} so a new selector cannot inherit this lowering by accident.
     */
    protected final IntermediateResult translateSeries(TranslationContext context, Expression value) {
        LogicalPlan input = context.cmd().child();
        LogicalPlan foldedPlan = PromqlLogicalPlanBuilder.tryFoldRelation(context.cmd(), input);

        if (foldedPlan != null) {
            var empty = new LocalRelation(
                context.cmd().source(),
                List.of(context.cmd().valueAttribute(), context.cmd().stepAttribute()),
                EmptyLocalSupplier.EMPTY
            );
            return new IntermediateResult(empty, TranslationSchema.EMPTY, Literal.NULL, context.cmd().stepAttribute(), null, Kind.CONSTANT);
        }

        List<Attribute> dimensions = input.output()
            .stream()
            .filter(attribute -> attribute instanceof FieldAttribute field && field.isDimension())
            .filter(attribute -> attribute instanceof TimeSeriesMetadataAttribute == false)
            .toList();
        // Expose only required labels that exist on the relation. Consumers null-fill any required label that is absent.
        TranslationSchema schema = project(context.required(), TranslationContext.mapFinite(dimensions));
        return new IntermediateResult(input, schema, value, context.stepAttr(), predicate(context.configuration()));
    }
}
