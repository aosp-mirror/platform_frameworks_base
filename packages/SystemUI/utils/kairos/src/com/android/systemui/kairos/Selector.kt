/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.kairos

import com.android.systemui.kairos.internal.DerivedMapCheap
import com.android.systemui.kairos.internal.StateImpl
import com.android.systemui.kairos.internal.init

/**
 * Returns a [StateSelector] that can be used to efficiently check if the input [State] is currently
 * holding a specific value.
 *
 * An example:
 * ```
 *   val intState: State<Int> = ...
 *   val intSelector: StateSelector<Int> = intState.selector()
 *   // Tracks if intState is holding 1
 *   val isOne: State<Boolean> = intSelector.whenSelected(1)
 * ```
 *
 * This is semantically equivalent to `val isOne = intState.map { i -> i == 1 }`, but is
 * significantly more efficient; specifically, using [State.map] in this way incurs a `O(n)`
 * performance hit, where `n` is the number of different [State.map] operations used to track a
 * specific value. [selector] internally uses a [HashMap] to lookup the appropriate downstream
 * [State] to update, and so operates in `O(1)`.
 *
 * Note that the returned [StateSelector] should be cached and re-used to gain the performance
 * benefit.
 *
 * @see groupByKey
 */
@ExperimentalKairosApi
fun <A> State<A>.selector(numDistinctValues: Int? = null): StateSelector<A> =
    StateSelector(
        this,
        changes
            .map { new -> mapOf(new to true, sampleDeferred().value to false) }
            .groupByKey(numDistinctValues),
    )

/**
 * Tracks the currently selected value of type [A] from an upstream [State].
 *
 * @see selector
 */
@ExperimentalKairosApi
class StateSelector<in A>
internal constructor(
    private val upstream: State<A>,
    private val groupedChanges: GroupedEvents<A, Boolean>,
) {
    /**
     * Returns a [State] that tracks whether the upstream [State] is currently holding the given
     * [value].
     *
     * @see selector
     */
    fun whenSelected(value: A): State<Boolean> {
        val operatorName = "StateSelector#whenSelected"
        val name = "$operatorName[$value]"
        return StateInit(
            init(name) {
                StateImpl(
                    name,
                    operatorName,
                    groupedChanges.impl.eventsForKey(value),
                    DerivedMapCheap(upstream.init) { it == value },
                )
            }
        )
    }

    operator fun get(value: A): State<Boolean> = whenSelected(value)
}
