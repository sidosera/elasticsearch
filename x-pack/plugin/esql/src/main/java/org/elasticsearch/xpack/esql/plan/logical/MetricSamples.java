/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.AttributeSet;
import org.elasticsearch.xpack.esql.core.expression.MetadataAttribute;
import org.elasticsearch.xpack.esql.core.expression.NameId;
import org.elasticsearch.xpack.esql.core.expression.Nullability;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;
import org.elasticsearch.xpack.esql.plan.logical.promql.PromqlLabels;
import org.elasticsearch.xpack.esql.plan.logical.promql.selector.LabelMatcher;
import org.elasticsearch.xpack.esql.plan.logical.promql.selector.MetricNameMatchers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads the samples of every metric whose name satisfies a set of {@code __name__} matchers, for selections that name no
 * single metric, such as {@code {__name__=~"http_.*"}}. The coordinator never enumerates the metric fields: each shard selects
 * among its own mapped metrics, by the name it exposes them under, and reads the value of exactly the metrics it selects.
 * <p>
 * Each input document becomes one row per selected metric holding a value in it. The rows keep the document's columns and
 * add the sample value, the metric name and a series identity: the {@code _tsid} extended with the metric, so the metrics of
 * one document are separate series. The stored {@code __name__} label and the input {@code _tsid} are not passed through, as
 * the metric name and the series identity replace them.
 * <p>
 * A metric is exposed under the name an exact metric name resolves to it on that shard: the short name of a passthrough
 * field when the short name resolves to the field, its full path otherwise.
 */
public class MetricSamples extends UnaryPlan {
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
        LogicalPlan.class,
        "MetricSamples",
        MetricSamples::new
    );

    private static final TransportVersion ESQL_PROMQL_METRIC_SAMPLES = TransportVersion.fromName("esql_promql_metric_samples");

    public static final String VALUE_NAME = "_sample";

    private final MetricNameMatchers metricName;
    private final Attribute tsid;
    private final Attribute seriesId;
    private final Attribute name;
    private final Attribute value;
    private List<Attribute> lazyOutput;

    public MetricSamples(
        Source source,
        LogicalPlan child,
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

    /** Selects the metrics satisfying {@code metricName} out of {@code child}, whose {@code _tsid} is {@code tsid}. */
    public static MetricSamples select(Source source, LogicalPlan child, MetricNameMatchers metricName, Attribute tsid) {
        return new MetricSamples(
            source,
            child,
            metricName,
            tsid,
            new MetadataAttribute(source, MetadataAttribute.TSID_FIELD, DataType.TSID_DATA_TYPE, false),
            new ReferenceAttribute(source, null, LabelMatcher.NAME, DataType.KEYWORD, Nullability.FALSE, new NameId(), false),
            new ReferenceAttribute(source, null, VALUE_NAME, DataType.DOUBLE, Nullability.FALSE, new NameId(), true)
        );
    }

    private MetricSamples(StreamInput in) throws IOException {
        this(
            Source.readFrom((PlanStreamInput) in),
            in.readNamedWriteable(LogicalPlan.class),
            readMetricName(in),
            in.readNamedWriteable(Attribute.class),
            in.readNamedWriteable(Attribute.class),
            in.readNamedWriteable(Attribute.class),
            in.readNamedWriteable(Attribute.class)
        );
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        if (out.getTransportVersion().supports(ESQL_PROMQL_METRIC_SAMPLES) == false) {
            throw new IllegalArgumentException("selecting PromQL metrics by a __name__ pattern is not supported by every node");
        }
        Source.EMPTY.writeTo(out);
        out.writeNamedWriteable(child());
        out.writeCollection(metricName.matchers(), (o, m) -> {
            o.writeEnum(m.matcher());
            o.writeStringCollection(m.values());
        });
        out.writeNamedWriteable(tsid);
        out.writeNamedWriteable(seriesId);
        out.writeNamedWriteable(name);
        out.writeNamedWriteable(value);
    }

    private static MetricNameMatchers readMetricName(StreamInput in) throws IOException {
        List<LabelMatcher> matchers = in.readCollectionAsList(i -> {
            LabelMatcher.Matcher matcher = i.readEnum(LabelMatcher.Matcher.class);
            return new LabelMatcher(LabelMatcher.NAME, i.readStringCollectionAsList(), matcher);
        });
        return matchers.isEmpty() ? MetricNameMatchers.NONE : new MetricNameMatchers(matchers);
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    /** The {@code __name__} matchers every selected metric satisfies. */
    public MetricNameMatchers metricName() {
        return metricName;
    }

    /** The {@code _tsid} of the input documents. */
    public Attribute tsid() {
        return tsid;
    }

    /** The series identity of each sample: its document's {@code _tsid} extended with its metric. */
    public Attribute seriesId() {
        return seriesId;
    }

    /** The name of each sample's metric, as the shard exposes it. */
    public Attribute name() {
        return name;
    }

    /** The value of each sample. */
    public Attribute value() {
        return value;
    }

    @Override
    protected AttributeSet computeReferences() {
        return AttributeSet.of(tsid);
    }

    @Override
    public List<Attribute> output() {
        if (lazyOutput == null) {
            lazyOutput = output(child().output(), tsid, List.of(seriesId, name, value));
        }
        return lazyOutput;
    }

    /**
     * The input columns without the input {@code _tsid} and any stored {@code __name__} label, followed by {@code added}.
     */
    public static List<Attribute> output(List<Attribute> input, Attribute tsid, List<Attribute> added) {
        List<Attribute> output = new ArrayList<>(input.size() + added.size());
        for (Attribute attribute : input) {
            if (attribute.semanticEquals(tsid) == false && LabelMatcher.NAME.equals(PromqlLabels.labelName(attribute)) == false) {
                output.add(attribute);
            }
        }
        output.addAll(added);
        return output;
    }

    @Override
    public boolean expressionsResolved() {
        return tsid.resolved() && seriesId.resolved() && name.resolved() && value.resolved();
    }

    @Override
    public boolean skipTelemetry() {
        // part of the PROMQL command's translation, which reports its own telemetry
        return true;
    }

    @Override
    public MetricSamples replaceChild(LogicalPlan newChild) {
        return new MetricSamples(source(), newChild, metricName, tsid, seriesId, name, value);
    }

    @Override
    protected NodeInfo<MetricSamples> info() {
        return NodeInfo.create(this, MetricSamples::new, child(), metricName, tsid, seriesId, name, value);
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
        MetricSamples other = (MetricSamples) obj;
        return Objects.equals(child(), other.child())
            && Objects.equals(metricName, other.metricName)
            && Objects.equals(tsid, other.tsid)
            && Objects.equals(seriesId, other.seriesId)
            && Objects.equals(name, other.name)
            && Objects.equals(value, other.value);
    }
}
