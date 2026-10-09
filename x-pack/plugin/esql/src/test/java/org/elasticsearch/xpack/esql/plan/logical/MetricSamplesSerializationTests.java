/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical;

import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.plan.logical.promql.selector.LabelMatcher;
import org.elasticsearch.xpack.esql.plan.logical.promql.selector.MetricNameMatchers;

import java.io.IOException;
import java.util.List;

import static org.elasticsearch.xpack.esql.expression.function.ReferenceAttributeTestUtils.randomReferenceAttribute;

public class MetricSamplesSerializationTests extends AbstractLogicalPlanSerializationTests<MetricSamples> {
    @Override
    protected MetricSamples createTestInstance() {
        return new MetricSamples(
            randomSource(),
            randomChild(0),
            randomMetricName(),
            randomReferenceAttribute(false),
            randomReferenceAttribute(false),
            randomReferenceAttribute(false),
            randomReferenceAttribute(false)
        );
    }

    private static MetricNameMatchers randomMetricName() {
        if (randomBoolean()) {
            return MetricNameMatchers.NONE;
        }
        return new MetricNameMatchers(
            randomList(
                1,
                3,
                () -> new LabelMatcher(
                    LabelMatcher.NAME,
                    randomList(1, 2, () -> randomAlphaOfLength(4)),
                    randomFrom(LabelMatcher.Matcher.values())
                )
            )
        );
    }

    @Override
    protected MetricSamples mutateInstance(MetricSamples instance) throws IOException {
        LogicalPlan child = instance.child();
        MetricNameMatchers metricName = instance.metricName();
        Attribute tsid = instance.tsid();
        Attribute seriesId = instance.seriesId();
        Attribute name = instance.name();
        Attribute value = instance.value();
        switch (between(0, 5)) {
            case 0 -> child = randomValueOtherThan(child, () -> randomChild(0));
            case 1 -> metricName = randomValueOtherThan(metricName, MetricSamplesSerializationTests::randomMetricName);
            case 2 -> tsid = randomValueOtherThan(tsid, () -> randomReferenceAttribute(false));
            case 3 -> seriesId = randomValueOtherThan(seriesId, () -> randomReferenceAttribute(false));
            case 4 -> name = randomValueOtherThan(name, () -> randomReferenceAttribute(false));
            case 5 -> value = randomValueOtherThan(value, () -> randomReferenceAttribute(false));
        }
        return new MetricSamples(Source.EMPTY, child, metricName, tsid, seriesId, name, value);
    }

    @Override
    protected boolean alwaysEmptySource() {
        return true;
    }

    /** The matchers keep their operators and every value, in order, across the wire. */
    public void testMatchersRoundTrip() throws IOException {
        MetricNameMatchers metricName = new MetricNameMatchers(
            List.of(
                new LabelMatcher(LabelMatcher.NAME, "tx", LabelMatcher.Matcher.NEQ),
                new LabelMatcher(LabelMatcher.NAME, List.of("r.*", "t.*"), LabelMatcher.Matcher.REG)
            )
        );
        MetricSamples original = new MetricSamples(
            Source.EMPTY,
            randomChild(0),
            metricName,
            randomReferenceAttribute(false),
            randomReferenceAttribute(false),
            randomReferenceAttribute(false),
            randomReferenceAttribute(false)
        );
        assertEquals(metricName, copyInstance(original).metricName());
    }
}
