/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.util.kotlin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object BooleanFlowOperators {
    /**
     * Logical AND operator for boolean flows. Will collect all flows and [combine] them to
     * determine the result.
     *
     * Usage:
     * ```
     * val result = allOf(flow1, flow2)
     * ```
     */
    fun allOf(vararg flows: Flow<Boolean>): Flow<Boolean> = flows.asIterable().all()

    /**
     * Logical AND operator for boolean flows. Will collect all flows and [combine] them to
     * determine the result.
     */
    fun Array<Flow<Boolean>>.all(): Flow<Boolean> = allOf(*this)

    /**
     * Logical AND operator for boolean flows. Will collect all flows and [combine] them to
     * determine the result.
     */
    fun Iterable<Flow<Boolean>>.all(): Flow<Boolean> =
        combine(this) { values -> values.all { it } }.distinctUntilChanged()

    /**
     * Logical NOT operator for a boolean flow.
     *
     * Usage:
     * ```
     * val negatedFlow = not(flow)
     * ```
     */
    fun not(flow: Flow<Boolean>) = flow.map { !it }

    /**
     * Logical OR operator for a boolean flow. Will collect all flows and [combine] them to
     * determine the result.
     */
    fun anyOf(vararg flows: Flow<Boolean>): Flow<Boolean> = flows.asIterable().any()

    /**
     * Logical OR operator for a boolean flow. Will collect all flows and [combine] them to
     * determine the result.
     */
    fun Array<Flow<Boolean>>.any(): Flow<Boolean> = anyOf(*this)

    /**
     * Logical OR operator for a boolean flow. Will collect all flows and [combine] them to
     * determine the result.
     */
    fun Iterable<Flow<Boolean>>.any(): Flow<Boolean> =
        combine(this) { values -> values.any { it } }.distinctUntilChanged()

    /**
     * Returns a Flow that produces `true` when all input flows are producing `false`, otherwise
     * produces `false`.
     */
    fun noneOf(vararg flows: Flow<Boolean>): Flow<Boolean> = not(anyOf(*flows))

    /**
     * Returns a Flow that produces `true` when all input flows are producing `false`, otherwise
     * produces `false`.
     */
    fun Array<Flow<Boolean>>.none(): Flow<Boolean> = noneOf(*this)

    /**
     * Returns a Flow that produces `true` when all input flows are producing `false`, otherwise
     * produces `false`.
     */
    fun Iterable<Flow<Boolean>>.none(): Flow<Boolean> = not(any())
}
