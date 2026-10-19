/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.timeseries;

import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.Nullability;
import org.elasticsearch.xpack.esql.core.expression.function.scalar.ScalarFunction;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;

import java.util.List;
import java.util.Objects;

/**
 * Planning-only interface to the RFC's dictionary operations. These are not registered ES|QL functions.
 * GET produces a column view; UNSET and SET produce a dictionary and ordinal remap. SET's function must be
 * constant for each entry (or each matched pair of entries when copying join labels). Empty strings remove labels.
 * TO_STRING renders entries only at the result boundary. Null metadata denotes the empty label set.
 * <p>
 * HASH denotes an equality-preserving key, not bare 128-bit bytes: execution must retain the referenced entries
 * and verify their equality on fingerprint collisions, including across exchanges. Page ordinals are never keys.
 * SET and UNSET preserve the dedicated TIMESERIES type. GET and TO_STRING expose strings; HASH exposes a key.
 * This class deliberately has neither an evaluator nor a wire format.
 */
public final class TimeSeriesMetadataFunction extends ScalarFunction {
    /** Dictionary operations exposed to translation; execution is intentionally left to a later change. */
    public enum Operation {
        GET,
        UNSET,
        SET,
        HASH,
        TO_STRING
    }

    private final Operation operation;
    private final List<String> labels;

    /**
     * Construct a planning expression for a dictionary operation.
     * @param source source location
     * @param operation requested primitive
     * @param labels selected names, or promoted column names for HASH/TO_STRING
     * @param arguments metadata first, then the SET value or the named promoted columns for HASH/TO_STRING
     */
    public TimeSeriesMetadataFunction(Source source, Operation operation, List<String> labels, List<Expression> arguments) {
        super(source, List.copyOf(arguments));
        this.operation = operation;
        this.labels = List.copyOf(labels);
        boolean valid = switch (operation) {
            case GET -> labels.size() == 1 && arguments.size() == 1;
            case UNSET -> arguments.size() == 1;
            case SET -> labels.size() == 1 && arguments.size() == 2;
            case HASH, TO_STRING -> arguments.size() == labels.size() + 1;
        };
        if (valid == false || labels.stream().distinct().count() != labels.size()) {
            throw new IllegalArgumentException("invalid time-series metadata operation [" + operation + "]");
        }
    }

    public Operation operation() {
        return operation;
    }

    public List<String> labels() {
        return labels;
    }

    @Override
    public DataType dataType() {
        return switch (operation) {
            case SET, UNSET -> DataType.TIMESERIES;
            case GET, HASH, TO_STRING -> DataType.KEYWORD;
        };
    }

    @Override
    protected TypeResolution resolveType() {
        if (childrenResolved() == false) {
            return new TypeResolution("Unresolved children");
        }
        return children().getFirst().dataType() == DataType.TIMESERIES
            ? TypeResolution.TYPE_RESOLVED
            : new TypeResolution(
                "Time-series metadata operations require [timeseries], received [" + children().getFirst().dataType().typeName() + "]"
            );
    }

    @Override
    public Nullability nullable() {
        return operation == Operation.GET ? Nullability.TRUE : Nullability.FALSE;
    }

    @Override
    public String functionName() {
        return "TimeSeries" + operation + labels;
    }

    @Override
    public TimeSeriesMetadataFunction replaceChildren(List<Expression> children) {
        return new TimeSeriesMetadataFunction(source(), operation, labels, children);
    }

    @Override
    protected NodeInfo<TimeSeriesMetadataFunction> info() {
        return NodeInfo.create(this, TimeSeriesMetadataFunction::new, operation, labels, children());
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), operation, labels);
    }

    @Override
    public boolean equals(Object other) {
        return super.equals(other)
            && operation == ((TimeSeriesMetadataFunction) other).operation
            && labels.equals(((TimeSeriesMetadataFunction) other).labels);
    }

    @Override
    public String getWriteableName() {
        throw unimplemented();
    }

    @Override
    public void writeTo(StreamOutput out) {
        throw unimplemented();
    }

    private static UnsupportedOperationException unimplemented() {
        return new UnsupportedOperationException("functional time-series metadata execution is not implemented");
    }
}
