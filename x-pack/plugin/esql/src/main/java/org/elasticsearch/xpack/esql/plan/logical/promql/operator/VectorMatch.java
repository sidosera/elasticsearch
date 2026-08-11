/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.promql.operator;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static java.util.Collections.emptySet;
import static org.elasticsearch.xpack.esql.core.util.StringUtils.EMPTY;

public class VectorMatch {

    public enum Condition {
        IGNORING,
        ON,
        NONE
    }

    public enum Joining {
        LEFT,
        RIGHT,
        NONE
    }

    public static final VectorMatch NONE = new VectorMatch(Condition.NONE, emptySet(), Joining.NONE, emptySet());

    private final Condition condition;
    private final Set<String> filterLabels;

    private final Joining joining;
    private final Set<String> groupingLabels;

    public VectorMatch(Condition condition, Set<String> filterLabels, Joining joining, Set<String> groupingLabels) {
        this.condition = condition;
        this.filterLabels = filterLabels;
        this.joining = joining;
        this.groupingLabels = groupingLabels;
    }

    public Condition condition() {
        return condition;
    }

    public Set<String> filterLabels() {
        return filterLabels;
    }

    public Joining joining() {
        return joining;
    }

    public Set<String> groupingLabels() {
        return groupingLabels;
    }

    @Override
    public boolean equals(Object o) {
        if (super.equals(o)) {
            VectorMatch that = (VectorMatch) o;
            return condition == that.condition
                && Objects.equals(filterLabels, that.filterLabels)
                && joining == that.joining
                && Objects.equals(groupingLabels, that.groupingLabels);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(condition, filterLabels, joining, groupingLabels);
    }

    @Override
    public String toString() {
        String filterString = condition != Condition.NONE ? condition.name().toLowerCase(Locale.ROOT) + "(" + filterLabels + ")" : EMPTY;
        String groupingString = joining != Joining.NONE
            ? " " + joining.name().toLowerCase(Locale.ROOT) + (groupingLabels.isEmpty() == false ? "(" + groupingLabels + ")" : EMPTY) + " "
            : EMPTY;
        return filterString + groupingString;
    }
}
