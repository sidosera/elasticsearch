/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.promql.selector;

import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.capabilities.Resolvables;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.TimeSeriesMetadataAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.parser.promql.PromqlLogicalPlanBuilder;
import org.elasticsearch.xpack.esql.plan.logical.EsRelation;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;
import org.elasticsearch.xpack.esql.plan.logical.local.EmptyLocalSupplier;
import org.elasticsearch.xpack.esql.plan.logical.local.LocalRelation;
import org.elasticsearch.xpack.esql.plan.logical.promql.PlaceholderRelation;
import org.elasticsearch.xpack.esql.plan.logical.promql.PromqlPlan;
import org.elasticsearch.xpack.esql.plan.logical.promql.TimeSeriesLabels;
import org.elasticsearch.xpack.esql.plan.logical.promql.TranslationContext;
import org.elasticsearch.xpack.esql.plan.logical.promql.TranslationContext.IntermediateResult;
import org.elasticsearch.xpack.esql.plan.logical.promql.TranslationContext.IntermediateResult.Kind;
import org.elasticsearch.xpack.esql.plan.logical.promql.TranslationSchema;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import static org.elasticsearch.xpack.esql.plan.logical.promql.TranslationSchema.project;

/**
 * Base class representing a PromQL vector selector.
 * A vector selector is defined by a set of label matchers and a point in time evaluation context.
 */
public abstract sealed class Selector extends UnaryPlan implements PromqlPlan permits InstantSelector, RangeSelector, LiteralSelector {
    // implements TelemetryAware

    // in Promql this is the __name__ label however for now, this gets mapped to an exact field
    private final Expression series;
    private final List<Expression> labels;
    private final LabelMatchers labelMatchers;
    private final Evaluation evaluation;
    protected List<Attribute> output;

    Selector(Source source, Expression series, List<Expression> labels, LabelMatchers labelMatchers, Evaluation evaluation) {
        this(source, PlaceholderRelation.INSTANCE, series, labels, labelMatchers, evaluation);
    }

    Selector(
        Source source,
        LogicalPlan child,
        Expression series,
        List<Expression> labels,
        LabelMatchers labelMatchers,
        Evaluation evaluation
    ) {
        super(source, child);
        this.series = series;
        this.labels = labels;
        this.labelMatchers = labelMatchers;
        this.evaluation = evaluation;
    }

    public Expression series() {
        return series;
    }

    public List<Expression> labels() {
        return labels;
    }

    public LabelMatchers labelMatchers() {
        return labelMatchers;
    }

    public Evaluation evaluation() {
        return evaluation;
    }

    @Override
    public boolean expressionsResolved() {
        return (series == null || series.resolved()) && Resolvables.resolved(labels);
    }

    @Override
    public boolean equals(Object o) {
        if (super.equals(o)) {
            Selector selector = (Selector) o;
            return Objects.equals(evaluation, selector.evaluation)
                && Objects.equals(labelMatchers, selector.labelMatchers)
                && Objects.equals(series, selector.series)
                && Objects.equals(labels, selector.labels);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), series, labels, labelMatchers, evaluation);
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
     * Translates a source-backed selector (instant or range) reading {@code value} per series; label matchers lower to a
     * pending filter predicate. Shared by the selectors that read the source relation; each still declares its own
     * {@link #translate} so a new selector cannot inherit this lowering by accident.
     */
    protected final IntermediateResult translateSeries(TranslationContext context, Expression value) {
        LogicalPlan input = context.cmd().child();
        LogicalPlan foldedPlan = PromqlLogicalPlanBuilder.tryFoldRelation(context.cmd(), input);
        Expression matcher = labelMatchers().predicate(source(), labels(), context.configuration());

        if (foldedPlan != null) {
            var empty = new LocalRelation(
                context.cmd().source(),
                List.of(context.cmd().valueAttribute(), context.cmd().stepAttribute()),
                EmptyLocalSupplier.EMPTY
            );
            return new IntermediateResult(empty, TranslationSchema.EMPTY, Literal.NULL, context.cmd().stepAttribute(), null, Kind.CONSTANT);
        }

        if (context.functionalMetadata()) {
            var columns = new LinkedHashMap<String, Expression>();
            var excludedFields = new TreeSet<String>();
            for (String name : context.promotedNames()) {
                Attribute field = TranslationContext.find(input.output(), name);
                if (field instanceof FieldAttribute fa && fa.isDimension()) {
                    columns.put(name, field);
                    excludedFields.add(fa.fieldName().string());
                }
            }
            // Runtime labels requested by BY still need GET even though the aggregate has finite output.
            boolean needsMetadata = context.required().isOpen() || columns.keySet().containsAll(context.promotedNames()) == false;
            var metadata = needsMetadata ? TimeSeriesMetadataAttribute.dictionary(source(), excludedFields) : null;
            LogicalPlan plan = context.pushDownEvaluationTimestampFilter(input, this);
            plan = plan.transformUp(EsRelation.class, relation -> {
                var attrs = new ArrayList<>(relation.output());
                attrs.removeIf(a -> a instanceof TimeSeriesMetadataAttribute);
                if (metadata != null) {
                    attrs.add(metadata);
                }
                return relation.withAttributes(attrs);
            });
            Expression filter = org.elasticsearch.xpack.esql.expression.predicate.Predicates.combineAndNullable(
                java.util.Arrays.asList(matcher, context.emitBySrcTimeFilter(this))
            );
            if (filter != null) {
                plan = new Filter(source(), plan, filter);
            }
            return new IntermediateResult(
                plan,
                new TimeSeriesLabels(columns, metadata),
                value,
                context.stepAttr(),
                Kind.BEFORE_INITIAL_AGGREGATE
            );
        }

        List<Attribute> dimensions = input.output()
            .stream()
            .filter(attribute -> attribute instanceof FieldAttribute field && field.isDimension())
            .filter(attribute -> attribute instanceof TimeSeriesMetadataAttribute == false)
            .toList();
        // Expose only required labels that exist on the relation. Consumers null-fill any required label that is absent.
        TranslationSchema schema = project(context.required(), TranslationContext.mapFinite(dimensions));
        return new IntermediateResult(input, schema, value, context.stepAttr(), matcher);
    }
}
