/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.promql.selector;

import org.elasticsearch.xpack.esql.core.tree.Node;
import org.elasticsearch.xpack.esql.core.tree.NodeStringMapper;
import org.elasticsearch.xpack.esql.core.tree.NodeStringRenderable;

import java.util.List;
import java.util.Objects;

/**
 * The {@code __name__} matchers of a selector, in source order and with their operators. Every spelling of a metric name
 * contributes one: {@code tx{...}}, {@code {"tx",...}} and {@code {__name__="tx",...}} all add an equality matcher.
 * <p>
 * The matchers are a conjunction, so they are resolved together rather than one at a time. An equality supplies a candidate
 * name that every matcher must accept; matchers that admit no common name select nothing; anything else constrains the metric
 * name without naming a metric:
 * <pre>
 * {__name__!="rx", __name__="tx"}   EXACT(tx)
 * {__name__="tx", __name__!="tx"}   EMPTY
 * {"tx", "rx"}                      EMPTY
 * {__name__=~"t.*", host="a"}       GENERAL
 * {host="a"}                        GENERAL
 * </pre>
 * A negative matcher's value or a regex pattern is never a metric name: only a matcher accepting exactly one string supplies
 * a candidate.
 */
public final class MetricNameMatchers implements NodeStringRenderable {

    /** A selector without any {@code __name__} matcher, constraining only its labels. */
    public static final MetricNameMatchers NONE = new MetricNameMatchers(List.of());

    /** How the matchers select metrics. */
    public enum Selection {
        /** Exactly one metric name satisfies every matcher, so the selector reads that metric's field. */
        EXACT,
        /** No metric name satisfies every matcher, so the selector selects no series. */
        EMPTY,
        /** Any metric whose name satisfies every matcher, possibly none of them by name: selection happens per metric. */
        GENERAL
    }

    private final List<LabelMatcher> matchers;
    private final Selection selection;
    private final String exactName;
    private final int namingMatcher;

    public MetricNameMatchers(List<LabelMatcher> matchers) {
        Objects.requireNonNull(matchers, "metric name matchers cannot be null");
        for (LabelMatcher matcher : matchers) {
            if (LabelMatcher.NAME.equals(matcher.name()) == false) {
                throw new IllegalArgumentException("expected a [" + LabelMatcher.NAME + "] matcher, got [" + matcher + "]");
            }
        }
        this.matchers = List.copyOf(matchers);

        // An equality-like matcher - one accepting exactly one string - supplies the candidate name. Two of them disagreeing,
        // or any matcher rejecting the candidate, leaves no metric to select.
        String candidate = null;
        int candidateIndex = -1;
        boolean conflicting = false;
        for (int i = 0; i < this.matchers.size(); i++) {
            LabelMatcher matcher = this.matchers.get(i);
            String exact = matcher.isNegation() ? null : AutomatonUtils.matchesExact(matcher.automaton());
            if (exact == null) {
                continue;
            }
            if (candidate == null) {
                candidate = exact;
                candidateIndex = i;
            } else if (candidate.equals(exact) == false) {
                conflicting = true;
            }
        }
        if (candidate == null) {
            this.selection = admitsNone(this.matchers) ? Selection.EMPTY : Selection.GENERAL;
        } else {
            this.selection = conflicting == false && admits(this.matchers, candidate) ? Selection.EXACT : Selection.EMPTY;
        }
        this.exactName = selection == Selection.EXACT ? candidate : null;
        this.namingMatcher = selection == Selection.EXACT ? candidateIndex : -1;
    }

    /** Whether every matcher accepts {@code name}. A metric always has a name, so the empty string never qualifies. */
    private static boolean admits(List<LabelMatcher> matchers, String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (LabelMatcher matcher : matchers) {
            if (matcher.matches(name) == false) {
                return false;
            }
        }
        return true;
    }

    private static boolean admitsNone(List<LabelMatcher> matchers) {
        for (LabelMatcher matcher : matchers) {
            if (matcher.matchesNone()) {
                return true;
            }
        }
        return false;
    }

    public List<LabelMatcher> matchers() {
        return matchers;
    }

    public boolean isEmpty() {
        return matchers.isEmpty();
    }

    public Selection selection() {
        return selection;
    }

    /** The only metric name the matchers admit, or null unless {@link #selection()} is {@link Selection#EXACT}. */
    public String exactName() {
        return exactName;
    }

    /** The position of the matcher that supplied {@link #exactName()}, or -1 unless {@link #selection()} is {@link Selection#EXACT}. */
    public int namingMatcher() {
        return namingMatcher;
    }

    /** Whether a metric named {@code name} satisfies every matcher. */
    public boolean admits(String name) {
        return admits(matchers, name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return matchers.equals(((MetricNameMatchers) o).matchers);
    }

    @Override
    public int hashCode() {
        return matchers.hashCode();
    }

    @Override
    public String toString() {
        return matchers.toString();
    }

    /** Renders like {@link #toString()}, routing each matcher through the mapper so match values tokenize under anonymization. */
    @Override
    public void nodeString(StringBuilder sb, Node.NodeStringFormat format, NodeStringMapper mapper) {
        sb.append('[');
        for (int i = 0; i < matchers.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            matchers.get(i).nodeString(sb, format, mapper);
        }
        sb.append(']');
    }
}
