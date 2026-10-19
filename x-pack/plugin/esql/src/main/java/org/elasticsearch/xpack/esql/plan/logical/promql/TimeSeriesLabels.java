/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical.promql;

import org.elasticsearch.core.Nullable;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import static org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataFunction.Operation.GET;
import static org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataFunction.Operation.HASH;
import static org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataFunction.Operation.SET;
import static org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataFunction.Operation.TO_STRING;
import static org.elasticsearch.xpack.esql.expression.function.scalar.timeseries.TimeSeriesMetadataFunction.Operation.UNSET;

/**
 * The current labels, not a collection of projections that ancestors might need. Promoted names are absent from
 * metadata. Expressions are lazy views until a plan boundary materializes them; at most one metadata value survives.
 */
public record TimeSeriesLabels(Map<String, Expression> promoted, @Nullable Expression metadata) {
    public static final TimeSeriesLabels EMPTY = new TimeSeriesLabels(Map.of(), null);

    public TimeSeriesLabels {
        if (metadata != null && metadata.dataType() != DataType.TIMESERIES) {
            throw new IllegalArgumentException("metadata must have type [timeseries]");
        }
        promoted = Collections.unmodifiableMap(new LinkedHashMap<>(promoted));
    }

    public Expression get(String name) {
        Expression column = promoted.get(name);
        return column != null ? column : metadata == null ? Literal.NULL : operation(GET, List.of(name), List.of(metadata));
    }

    public TimeSeriesLabels by(Collection<String> names) {
        var columns = new LinkedHashMap<String, Expression>();
        names.forEach(name -> columns.put(name, get(name)));
        return new TimeSeriesLabels(columns, null);
    }

    public TimeSeriesLabels without(Collection<String> names) {
        var columns = new LinkedHashMap<>(promoted);
        names.forEach(columns::remove);
        var remaining = new TreeSet<>(names);
        remaining.removeAll(promoted.keySet());
        Expression rest = metadata == null || remaining.isEmpty() ? metadata : operation(UNSET, List.copyOf(remaining), List.of(metadata));
        return new TimeSeriesLabels(columns, rest);
    }

    /** Promotion is explicit, removing the name from the dictionary before introducing its column. */
    public TimeSeriesLabels promote(String name, Expression value) {
        TimeSeriesLabels rest = without(List.of(name));
        var columns = new LinkedHashMap<>(rest.promoted);
        columns.put(name, value);
        return new TimeSeriesLabels(columns, rest.metadata);
    }

    /** Only use for dictionary-local functions or functions of the join's matched-entry domain. */
    public TimeSeriesLabels set(String name, Expression value) {
        if (metadata == null || promoted.containsKey(name)) {
            return promote(name, value);
        }
        return new TimeSeriesLabels(promoted, operation(SET, List.of(name), List.of(metadata, value)));
    }

    public Expression hash() {
        return fullSetOperation(HASH);
    }

    public Expression render() {
        return fullSetOperation(TO_STRING);
    }

    /** Refresh references together with the plan when translating independent join inputs. */
    public TimeSeriesLabels transform(UnaryOperator<Expression> transform) {
        var columns = new LinkedHashMap<String, Expression>();
        promoted.forEach((name, value) -> columns.put(name, value.transformDown(Expression.class, transform)));
        return new TimeSeriesLabels(columns, metadata == null ? null : metadata.transformDown(Expression.class, transform));
    }

    private Expression fullSetOperation(TimeSeriesMetadataFunction.Operation operation) {
        // Canonical label order is independent of AST order or which side of a join promoted a label.
        var names = new TreeSet<>(promoted.keySet());
        var arguments = new ArrayList<Expression>();
        arguments.add(metadata == null ? new Literal(Source.EMPTY, null, DataType.TIMESERIES) : metadata);
        names.forEach(name -> arguments.add(promoted.get(name)));
        return operation(operation, List.copyOf(names), arguments);
    }

    private static Expression operation(TimeSeriesMetadataFunction.Operation op, List<String> labels, List<Expression> values) {
        return new TimeSeriesMetadataFunction(Source.EMPTY, op, labels, values);
    }
}
