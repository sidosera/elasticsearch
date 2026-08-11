/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.logical.promql;

import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.expression.MetadataAttribute;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.expression.TimeSeriesMetadataAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.plan.logical.promql.AcrossSeriesAggregate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.elasticsearch.xpack.esql.plan.logical.promql.PromqlLabels.PROMETHEUS_LABELS_PREFIX;

/**
 * The required and actual header of a PromQL translation attempt.
 */
public final class PromqlAttributesTranslationContext {

    private PromqlAttributesTranslationContext() {}

    /**
     * A column exposed by a translated subtree. A column carries the expression currently denoting it; whether that
     * expression is linked to a given plan is a derived property, re-established at plan boundaries via
     * {@link #resolveColumn}.
     */
    public sealed interface Column permits NamedColumn, TimeSeriesColumn {
        NamedExpression expression();

        default Attribute attribute() {
            return expression().toAttribute();
        }
    }

    /** A named column. */
    public record NamedColumn(NamedExpression expression) implements Column {}

    /**
     * A time-series metadata block-loader column with its exclusion set (skip-set). The identity stands for "every
     * label of its source except the exclusions"; {@code labels} is that source universe, bound at the leaf where the
     * symbolic identity meets a concrete relation ({@link Header#withUniverse}) and empty on demand columns, which
     * only name an exclusion set.
     */
    public record TimeSeriesColumn(NamedExpression expression, List<Attribute> exclusions, List<Attribute> labels) implements Column {
        public TimeSeriesColumn {
            exclusions = distinctByCanonicalName(exclusions);
            labels = distinctByCanonicalName(labels);
        }

        public TimeSeriesColumn(NamedExpression expression, List<Attribute> exclusions) {
            this(expression, exclusions, List.of());
        }

        public static TimeSeriesColumn of(List<Attribute> exclusions) {
            return new TimeSeriesColumn(FieldAttribute.timeSeriesAttribute(Source.EMPTY), exclusions);
        }
    }

    @FunctionalInterface
    public interface Fn {
        Column transform(Column column, boolean grouping);
    }

    /**
     * The symbolic columns exposed by a translated subtree. The same type flows in both directions of the
     * negotiation: upward as the surface a subtree produced, and downward as the demand a parent pushes to a
     * child - the columns the child must expose ({@link #missing}) and, when non-empty, the concrete grouping the
     * demand pins ({@code groupBy}). Translation may widen a demand ({@link #plus}) and retry a child.
     */
    public static final class Header {
        private final List<Column> groupBy;
        private final List<Column> columns;

        public Header(List<? extends Column> groupBy, List<? extends Column> columns) {
            this.groupBy = distinct(groupBy);
            this.columns = distinct(columns);
        }

        /** No series identity; as a demand, no requirement. */
        public static Header undefined() {
            return new Header(List.of(), List.of());
        }

        /**
         * The header over the given attributes: their named labels plus, when present, the packed time-series
         * identity.
         */
        public static Header fromAttributes(List<Attribute> attributes) {
            List<Attribute> labels = new ArrayList<>();
            TimeSeriesColumn timeSeries = null;
            for (Attribute attribute : attributes) {
                if (MetadataAttribute.isTimeSeriesAttributeName(attribute.name())) {
                    timeSeries = new TimeSeriesColumn(attribute, List.of());
                } else {
                    labels.add(attribute);
                }
            }
            Header header = undefined().including(labels);
            return timeSeries != null ? header.including(timeSeries) : header;
        }

        /** The concrete dimension columns in a source relation. */
        public static List<Attribute> dimensions(List<Attribute> attributes) {
            return attributes.stream()
                .filter(attribute -> attribute instanceof FieldAttribute field && field.isDimension())
                .filter(attribute -> attribute instanceof TimeSeriesMetadataAttribute == false)
                .toList();
        }

        /** Add named column requirements without changing the primary grouping. */
        public Header including(List<Attribute> labels) {
            var exposed = new ArrayList<>(columns);
            distinctByCanonicalName(labels).stream().map(NamedColumn::new).forEach(exposed::add);
            return new Header(groupBy, exposed);
        }

        /** Add a time-series identity requirement without changing the primary grouping. */
        public Header including(TimeSeriesColumn required) {
            var exposed = new ArrayList<>(columns);
            exposed.add(required);
            return new Header(groupBy, exposed);
        }

        /**
         * Transposes this demand across an aggregate: the demand the aggregate's child sees. A {@code by} fixes
         * identity to finite keys, so upstream demands stop at the aggregate; a {@code without} forwards them.
         */
        public Header withAcrossSeriesAgg(AcrossSeriesAggregate.Grouping grouping, List<Attribute> labels) {
            return switch (grouping) {
                case BY -> undefined().including(labels);
                case WITHOUT -> labels.isEmpty() && labels().isEmpty() == false ? groupedBy(labels()) : this;
                case NONE -> undefined();
            };
        }

        /**
         * Union: this header's columns plus the other's. Adding an identity column to an ungrouped header pins it as
         * the grouping: when a demand has no grouping yet, the required identity <em>is</em> the leaf identity (e.g. a
         * single {@code without(pod)} → {@code ta·skip{pod}}), and pinning it stops {@link #withIdentityGrouping} from
         * also inventing a full {@code ta·skip{}} and emitting two {@code _timeseries} columns.
         */
        public Header plus(Header other) {
            Header result = this;
            for (Column column : other.columns) {
                result = result.plus(column);
            }
            return result;
        }

        private Header plus(Column column) {
            if (column instanceof TimeSeriesColumn && groupBy.isEmpty()) {
                var exposed = new ArrayList<Column>(columns.size() + 1);
                exposed.add(column);
                for (Column existing : columns) {
                    if (eq(existing, column) == false) {
                        exposed.add(existing);
                    }
                }
                return new Header(List.of(column), exposed);
            }
            if (contains(columns, column)) {
                return this;
            }
            var exposed = new ArrayList<>(columns);
            exposed.add(column);
            return new Header(groupBy, exposed);
        }

        /** Difference: this header without the other's columns. */
        public Header minus(Header other) {
            return transformExpressions((column, grouping) -> contains(other.columns, column) ? null : column);
        }

        /**
         * The columns of this required header that the actual header does not cover - the compatibility check of the
         * translation protocol. Identity requirements come back re-identified so they can be pushed down as a demand
         * into a fresh subtree.
         */
        public Header missing(Header actual) {
            var uncovered = new ArrayList<Column>();
            for (Column column : columns) {
                if (column instanceof TimeSeriesColumn tc) {
                    if (actual.timeSeries(tc.exclusions()) == null) {
                        uncovered.add(TimeSeriesColumn.of(tc.exclusions()));
                    }
                } else if (actual.column(toCanonicalName(column.attribute())) == null) {
                    uncovered.add(column);
                }
            }
            return new Header(List.of(), uncovered);
        }

        /** Replaces opaque identity columns with the source labels they stand for (every label of their universe not excluded). */
        public Header expand() {
            Header result = undefined().including(labels());
            for (Column column : columns) {
                if (column instanceof TimeSeriesColumn timeSeries) {
                    Set<String> excluded = toCanonicalNames(timeSeries.exclusions());
                    result = result.including(
                        timeSeries.labels().stream().filter(label -> excluded.contains(toCanonicalName(label)) == false).toList()
                    );
                }
            }
            return result;
        }

        /** Binds every identity column to the label universe it ranges over - the source dimensions of a leaf. */
        public Header withUniverse(List<Attribute> universe) {
            return transformExpressions(
                (column, grouping) -> column instanceof TimeSeriesColumn timeSeries
                    ? new TimeSeriesColumn(timeSeries.expression(), timeSeries.exclusions(), universe)
                    : column
            );
        }

        /** The named columns of this header; on a demand, the labels a parent requires. */
        public List<Attribute> labels() {
            return columns.stream().filter(NamedColumn.class::isInstance).map(Column::attribute).toList();
        }

        /**
         * The leaf surface implied by this demand: keeps the concrete grouping when the demand pins one, otherwise
         * groups by the full series identity.
         */
        public Header withIdentityGrouping() {
            if (groupBy.isEmpty() == false) {
                return this;
            }
            TimeSeriesColumn identity = TimeSeriesColumn.of(List.of());
            var exposed = new ArrayList<Column>(columns.size() + 1);
            exposed.add(identity);
            exposed.addAll(columns);
            return new Header(List.of(identity), exposed);
        }

        /** Grouping produced by applying an across-series aggregation to this child header. */
        public Header withAcrossSeriesAgg(AcrossSeriesAggregate.Grouping grouping, List<Attribute> labels, List<Attribute> output) {
            return switch (grouping) {
                case BY -> groupedBy(output);
                case WITHOUT -> groupedWithout(labels);
                case NONE -> undefined();
            };
        }

        /** Header produced by {@code BY(labels)}. Missing labels remain proxies and are null-filled during emission. */
        public Header groupedBy(List<Attribute> labels) {
            List<Column> ephemeral = distinctByCanonicalName(labels).stream().map(NamedColumn::new).map(Column.class::cast).toList();
            return new Header(ephemeral, ephemeral);
        }

        /** Header produced by {@code WITHOUT(labels)}. */
        public Header groupedWithout(List<Attribute> labels) {
            List<Attribute> removed = distinctByCanonicalName(labels);
            TimeSeriesColumn timeSeries = groupByTimeSeries();
            if (timeSeries != null) {
                TimeSeriesColumn desired = new TimeSeriesColumn(
                    FieldAttribute.timeSeriesAttribute(Source.EMPTY),
                    PromqlAttributesTranslationContext.union(timeSeries.exclusions(), removed),
                    timeSeries.labels()
                );
                TimeSeriesColumn exposed = timeSeries(desired.exclusions());
                desired = exposed == null ? desired : exposed;
                return new Header(List.of(desired), List.of(desired));
            }
            Set<String> removedNames = toCanonicalNames(removed);
            List<Column> concrete = groupBy.stream()
                .filter(NamedColumn.class::isInstance)
                .filter(column -> removedNames.contains(toCanonicalName(column.attribute())) == false)
                .toList();
            return new Header(concrete, concrete);
        }

        /** Regroup this child and preserve non-grouping columns that an ancestor requires and this child exposes. */
        public Header regrouped(Header grouping, Header required) {
            var exposed = new ArrayList<>(grouping.columns);
            for (Column demand : required.columns) {
                if (contains(required.groupBy, demand)) {
                    continue;
                }
                Column actual = columns.stream().filter(column -> eq(column, demand)).findFirst().orElse(null);
                if (actual != null && grouping.canCarry(actual) && contains(exposed, actual) == false) {
                    exposed.add(actual);
                }
            }
            return new Header(grouping.groupBy, exposed);
        }

        private boolean canCarry(Column column) {
            TimeSeriesColumn identity = groupByTimeSeries();
            if (column instanceof NamedColumn) {
                return identity != null
                    ? toCanonicalNames(identity.exclusions()).contains(toCanonicalName(column.attribute())) == false
                    : contains(groupBy, column);
            }
            if (column instanceof TimeSeriesColumn timeSeries) {
                return identity != null && toCanonicalNames(timeSeries.exclusions()).containsAll(toCanonicalNames(identity.exclusions()));
            }
            return false;
        }

        public boolean hasTimeSeriesGrouping() {
            return groupByTimeSeries() != null;
        }

        private TimeSeriesColumn groupByTimeSeries() {
            return groupBy.size() == 1 && groupBy.getFirst() instanceof TimeSeriesColumn timeSeries ? timeSeries : null;
        }

        private TimeSeriesColumn timeSeries(List<Attribute> exclusions) {
            for (Column column : columns) {
                if (column instanceof TimeSeriesColumn timeSeries) {
                    List<Attribute> left = timeSeries.exclusions();
                    if (toCanonicalNames(left).equals(toCanonicalNames(exclusions))) {
                        return timeSeries;
                    }
                }
            }
            return null;
        }

        public Header transformExpressions(Fn transformer) {
            var originals = new ArrayList<Column>();
            var transformed = new ArrayList<Column>();
            for (Column column : groupBy) {
                if (contains(originals, column) == false) {
                    originals.add(column);
                    transformed.add(transformer.transform(column, true));
                }
            }
            for (Column column : columns) {
                if (contains(originals, column) == false) {
                    originals.add(column);
                    transformed.add(transformer.transform(column, false));
                }
            }
            return new Header(transform(groupBy, originals, transformed), transform(columns, originals, transformed));
        }

        public List<NamedExpression> groupingExpressions() {
            return groupBy.stream().map(Column::expression).toList();
        }

        public List<NamedExpression> exposedExpressions() {
            return columns.stream().map(Column::attribute).map(NamedExpression.class::cast).toList();
        }

        /** Physical definitions for exposed columns that are computed inline by the consuming plan node. */
        public List<NamedExpression> expressions() {
            return columns.stream().map(Column::expression).toList();
        }

        public Attribute column(String name) {
            for (Column column : columns) {
                if (toCanonicalName(column.attribute()).equals(name)) {
                    return column.attribute();
                }
            }
            return null;
        }

        public boolean isDefined() {
            return columns.isEmpty() == false;
        }

        public boolean hasTimeSeriesColumns() {
            return columns.stream().anyMatch(TimeSeriesColumn.class::isInstance);
        }

        /** Binds this logical header to another header's columns, optionally defining absent named columns as null. */
        public Header bind(Header available, boolean nullMissing) {
            return transformExpressions((column, grouping) -> {
                Column resolved = available.columns.stream().filter(candidate -> eq(column, candidate)).findFirst().orElse(null);
                if (resolved != null) {
                    return resolved;
                }
                return nullMissing && column instanceof NamedColumn ? new NamedColumn(nullColumn(column.attribute())) : null;
            });
        }

        private static List<Column> transform(List<Column> source, List<Column> originals, List<Column> transformed) {
            var result = new ArrayList<Column>(source.size());
            for (Column column : source) {
                for (int i = 0; i < originals.size(); i++) {
                    if (eq(column, originals.get(i))) {
                        Column replacement = transformed.get(i);
                        if (replacement != null) {
                            result.add(replacement);
                        }
                        break;
                    }
                }
            }
            return result;
        }
    }

    /** Links a column to the matching attribute of a plan output; keeps the column unchanged when there is none. */
    static Column resolveColumn(Column column, List<Attribute> available) {
        if (column instanceof TimeSeriesColumn tc) {
            var m = findById(tc.attribute(), available);
            return m != null ? new TimeSeriesColumn(m, tc.exclusions(), tc.labels()) : column;
        }
        var m = findByIdOrName(column.attribute(), available);
        return m != null ? new NamedColumn(m) : column;
    }

    static Attribute findById(Attribute attribute, List<Attribute> available) {
        return available.stream().filter(candidate -> candidate.id().equals(attribute.id())).findFirst().orElse(null);
    }

    static Attribute findByIdOrName(Attribute attribute, List<Attribute> available) {
        Attribute byId = findById(attribute, available);
        return byId != null ? byId : findByName(available, toCanonicalName(attribute));
    }

    private static boolean contains(List<? extends Column> columns, Column candidate) {
        return columns.stream().anyMatch(column -> eq(column, candidate));
    }

    private static boolean eq(Column left, Column right) {
        if (left instanceof TimeSeriesColumn l && right instanceof TimeSeriesColumn r) {
            return toCanonicalNames(l.exclusions()).equals(toCanonicalNames(r.exclusions()));
        }
        return left instanceof NamedColumn
            && right instanceof NamedColumn
            && toCanonicalName(left.attribute()).equals(toCanonicalName(right.attribute()));
    }

    private static List<Column> distinct(List<? extends Column> columns) {
        var result = new ArrayList<Column>();
        for (Column column : columns) {
            if (contains(result, column) == false) {
                result.add(column);
            }
        }
        return List.copyOf(result);
    }

    private static List<Attribute> distinctByCanonicalName(List<Attribute> labels) {
        var result = new ArrayList<Attribute>();
        var seen = new LinkedHashSet<String>();
        for (Attribute attribute : labels) {
            if (seen.add(toCanonicalName(attribute))) {
                result.add(attribute);
            }
        }
        return List.copyOf(result);
    }

    private static List<Attribute> union(List<Attribute> left, List<Attribute> right) {
        var result = new ArrayList<Attribute>(left.size() + right.size());
        result.addAll(left);
        result.addAll(right);
        return distinctByCanonicalName(result);
    }

    private static Set<String> toCanonicalNames(List<Attribute> attributes) {
        var names = new LinkedHashSet<String>();
        attributes.forEach(attribute -> names.add(toCanonicalName(attribute)));
        return names;
    }

    /** A definition of the attribute's column as null - the physical form of a required column nobody produces. */
    static Alias nullColumn(Attribute attribute) {
        var literal = new Literal(attribute.source(), null, attribute.resolved() ? attribute.dataType() : DataType.KEYWORD);
        return new Alias(attribute.source(), attribute.name(), literal, attribute.id());
    }

    static String toCanonicalName(Attribute attribute) {
        String name = attribute instanceof FieldAttribute field ? field.fieldName().string() : attribute.name();
        return name.startsWith(PROMETHEUS_LABELS_PREFIX) ? name.substring(PROMETHEUS_LABELS_PREFIX.length()) : name;
    }

    public static Attribute findByName(List<Attribute> attributes, String labelName) {
        Attribute bareMatch = null;
        for (Attribute attribute : attributes) {
            if (toCanonicalName(attribute).equals(labelName)) {
                if (attribute.name().equals(labelName) == false) {
                    return attribute;
                }
                bareMatch = attribute;
            }
        }
        return bareMatch;
    }
}
