/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.promql.selector;

import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Nullability;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.convert.ToString;
import org.elasticsearch.xpack.esql.session.Configuration;

import java.util.List;
import java.util.Objects;

/**
 * A label matcher of a selector bound to the field holding its label, e.g. {@code host="a"} over {@code host}. The field is
 * this expression's only child, so the analyzer resolves it like any other reference, and the matcher can never be paired with
 * another label's field. Metric name matchers are not label predicates; see {@link MetricNameMatchers}.
 * <p>
 * Only lives inside a PromQL plan: translation lowers it to an ES|QL condition with {@link #condition}.
 */
public final class LabelPredicate extends Expression {

    private final LabelMatcher matcher;

    public LabelPredicate(Source source, Expression field, LabelMatcher matcher) {
        super(source, List.of(field));
        if (LabelMatcher.NAME.equals(matcher.name())) {
            throw new IllegalArgumentException("a [" + LabelMatcher.NAME + "] matcher selects metrics, not labels");
        }
        this.matcher = matcher;
    }

    public Expression field() {
        return children().getFirst();
    }

    public LabelMatcher matcher() {
        return matcher;
    }

    /**
     * The ES|QL condition over the bound field, with PromQL's absent-label semantics: a label missing from a series matches
     * like the empty string. Non-string fields compare by their string form, as label values are strings.
     */
    public Expression condition(Configuration configuration) {
        Expression field = field();
        if (field.resolved() && DataType.isString(field.dataType()) == false) {
            field = new ToString(field.source(), field, configuration);
        }
        return LabelMatchers.condition(source(), field, matcher);
    }

    @Override
    public DataType dataType() {
        return DataType.BOOLEAN;
    }

    @Override
    public Nullability nullable() {
        return Nullability.FALSE;
    }

    @Override
    public LabelPredicate replaceChildren(List<Expression> newChildren) {
        return new LabelPredicate(source(), newChildren.getFirst(), matcher);
    }

    @Override
    protected NodeInfo<LabelPredicate> info() {
        return NodeInfo.create(this, LabelPredicate::new, field(), matcher);
    }

    @Override
    public String getWriteableName() {
        throw new UnsupportedOperationException("should not serialize");
    }

    @Override
    public void writeTo(StreamOutput out) {
        throw new UnsupportedOperationException("should not serialize");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LabelPredicate that = (LabelPredicate) o;
        return Objects.equals(field(), that.field()) && Objects.equals(matcher, that.matcher);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field(), matcher);
    }
}
