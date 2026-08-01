/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

#include "esql_eval.h"

#include <cmath>

extern "C" ESQL_EVAL_EXPORT void esql_eval_sqrt_doubles(
    const double* values,
    double* result,
    std::uint8_t* status,
    std::int32_t position_count
) {
    for (std::int32_t p = 0; p < position_count; ++p) {
        if (values[p] < 0) {
            status[p] = esql_eval::STATUS_NULL;
        } else {
            result[p] = std::sqrt(values[p]);
            status[p] = esql_eval::STATUS_OK;
        }
    }
}
