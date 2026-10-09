/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.physical;

import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.AttributeSet;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.plan.logical.MetricSamples;
import org.elasticsearch.xpack.esql.plan.logical.promql.selector.MetricNameMatchers;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Physical counterpart of {@link MetricSamples}: reads, on each shard, the samples of the metrics that shard selects by name.
 * Planned on the data node from the plan fragment, so it is never serialized.
 */
public class MetricSamplesExec extends UnaryExec implements EstimatesRowSize {

    private final MetricNameMatchers metricName;
    private final Attribute tsid;
    private final Attribute seriesId;
    private final Attribute name;
    private final Attribute value;
    private List<Attribute> lazyOutput;

    public MetricSamplesExec(
        Source source,
        PhysicalPlan child,
        MetricNameMatchers metricName,
        Attribute tsid,
        Attribute seriesId,
        Attribute name,
        Attribute value
    ) {
        super(source, child);
        this.metricName = metricName;
        this.tsid = tsid;
        this.seriesId = seriesId;
        this.name = name;
        this.value = value;
    }

    public MetricNameMatchers metricName() {
        return metricName;
    }

    public Attribute tsid() {
        return tsid;
    }

    public Attribute seriesId() {
        return seriesId;
    }

    public Attribute name() {
        return name;
    }

    public Attribute value() {
        return value;
    }

    /** The documents the samples are read from. */
    public Attribute docAttribute() {
        for (Attribute attribute : child().output()) {
            if (EsQueryExec.isDocAttribute(attribute)) {
                return attribute;
            }
        }
        throw new IllegalStateException("reading metric samples requires the documents of [" + child().nodeName() + "]");
    }

    @Override
    protected AttributeSet computeReferences() {
        // the documents to read the samples of must survive every projection below
        AttributeSet.Builder references = AttributeSet.builder().add(tsid);
        for (Attribute attribute : child().output()) {
            if (EsQueryExec.isDocAttribute(attribute)) {
                references.add(attribute);
            }
        }
        return references.build();
    }

    @Override
    public List<Attribute> output() {
        if (lazyOutput == null) {
            lazyOutput = MetricSamples.output(child().output(), tsid, List.of(seriesId, name, value));
        }
        return lazyOutput;
    }

    @Override
    public PhysicalPlan estimateRowSize(State state) {
        state.add(false, List.of(seriesId, name, value));
        return this;
    }

    @Override
    public UnaryExec replaceChild(PhysicalPlan newChild) {
        return new MetricSamplesExec(source(), newChild, metricName, tsid, seriesId, name, value);
    }

    @Override
    protected NodeInfo<? extends PhysicalPlan> info() {
        return NodeInfo.create(this, MetricSamplesExec::new, child(), metricName, tsid, seriesId, name, value);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        throw new UnsupportedOperationException("MetricSamplesExec is local only and not serialized");
    }

    @Override
    public String getWriteableName() {
        throw new UnsupportedOperationException("MetricSamplesExec is local only and not serialized");
    }

    @Override
    public int hashCode() {
        return Objects.hash(child(), metricName, tsid, seriesId, name, value);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        MetricSamplesExec other = (MetricSamplesExec) obj;
        return Objects.equals(child(), other.child())
            && Objects.equals(metricName, other.metricName)
            && Objects.equals(tsid, other.tsid)
            && Objects.equals(seriesId, other.seriesId)
            && Objects.equals(name, other.name)
            && Objects.equals(value, other.value);
    }
}
