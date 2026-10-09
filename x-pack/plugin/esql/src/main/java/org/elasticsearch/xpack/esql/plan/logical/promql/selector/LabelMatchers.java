/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.promql.selector;

import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.predicate.regex.RLikePattern;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.EndsWith;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.StartsWith;
import org.elasticsearch.xpack.esql.expression.function.scalar.string.regex.RLike;
import org.elasticsearch.xpack.esql.expression.predicate.Predicates;
import org.elasticsearch.xpack.esql.expression.predicate.logical.And;
import org.elasticsearch.xpack.esql.expression.predicate.logical.Not;
import org.elasticsearch.xpack.esql.expression.predicate.nulls.IsNull;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.Equals;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.In;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.NotEquals;

import java.util.Comparator;
import java.util.List;

/**
 * Lowers PromQL label matchers to ES|QL predicates. Uses {@link AutomatonUtils} to lower a pattern to a predicate cheaper than
 * a regex where possible: exact values become equality/IN, prefix/suffix alternations become STARTS_WITH/ENDS_WITH
 * disjunctions, everything else falls back to RLIKE.
 */
public final class LabelMatchers {

    private LabelMatchers() {}

    /** Lowers a single matcher to an ES|QL predicate over {@code field}; also used by the prometheus REST layer. */
    public static Expression condition(Source source, Expression field, LabelMatcher matcher) {
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
                    ? operatorFn(source, field, fragments)
                    // fallback: RLIKE over the full pattern, anchored per PromQL semantics
                    : new RLike(source, field, new RLikePattern(matcher.getFirstValue()));
                if (matcher.isNegation()) {
                    condition = new Not(source, condition);
                }
            }
        }
        // absent labels are treated as having value "" because if the matcher accepts the empty string
        // (e.g. {label=""} or {label!="foo"}), series where the label field is NULL (absent) must also match.
        if (matcher.matchesEmpty()) {
            condition = Predicates.combineOr(List.of(new IsNull(source, field), condition));
        }
        return condition;
    }

    /** Disjoint fragments sort EXACT -> PREFIX -> SUFFIX -> REGEX (most selective first); an all-EXACT set lowers to IN. */
    private static Expression operatorFn(Source source, Expression field, List<AutomatonUtils.PatternFragment> fragments) {
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
