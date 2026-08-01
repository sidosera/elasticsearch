/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

#pragma once

#include <cstdint>

#if defined(_WIN32)
#if defined(ESQL_EVAL_BUILDING_LIBRARY)
#define ESQL_EVAL_EXPORT __declspec(dllexport)
#else
#define ESQL_EVAL_EXPORT __declspec(dllimport)
#endif
#else
#define ESQL_EVAL_EXPORT
#endif

namespace esql_eval {
constexpr std::uint8_t STATUS_OK = 0;
constexpr std::uint8_t STATUS_NULL = 1;
}

extern "C" ESQL_EVAL_EXPORT void esql_eval_sqrt_doubles(
    const double* values,
    double* result,
    std::uint8_t* status,
    std::int32_t position_count
);
