/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.promql;

import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;

import java.util.Objects;

/**
 * Execution placeholder: reject duplicate (timestamp, complete label set) after a non-aggregating label rewrite.
 * An injective remap proves uniqueness only within its page. Execution must also check across pages/nodes, using
 * actual label equality on hash collisions. This node never deduplicates samples or aggregates their values.
 */
public final class CheckSeriesUniqueness extends UnaryPlan {
    private final Expression step;
    private final Expression identity;

    public CheckSeriesUniqueness(Source source, LogicalPlan child, Expression step, Expression identity) {
        super(source, child);
        this.step = step;
        this.identity = identity;
    }

    public Expression step() {
        return step;
    }

    public Expression identity() {
        return identity;
    }

    @Override
    public boolean skipTelemetry() {
        return true;
    }

    @Override
    public boolean expressionsResolved() {
        return step.resolved() && identity.resolved();
    }

    @Override
    public CheckSeriesUniqueness replaceChild(LogicalPlan child) {
        return new CheckSeriesUniqueness(source(), child, step, identity);
    }

    @Override
    protected NodeInfo<CheckSeriesUniqueness> info() {
        return NodeInfo.create(this, CheckSeriesUniqueness::new, child(), step, identity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), step, identity);
    }

    @Override
    public boolean equals(Object other) {
        return super.equals(other)
            && step.equals(((CheckSeriesUniqueness) other).step)
            && identity.equals(((CheckSeriesUniqueness) other).identity);
    }

    @Override
    public String getWriteableName() {
        throw new UnsupportedOperationException("series uniqueness execution is not implemented");
    }

    @Override
    public void writeTo(StreamOutput out) {
        throw new UnsupportedOperationException("series uniqueness execution is not implemented");
    }
}
