/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.framework.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Returns a [Flow] whose values are a list which containing the results of asynchronously applying
 * the given [transform] function to each element in the original flow's list.
 */
inline fun <T, R> Flow<List<T>>.asyncMapItem(crossinline transform: (T) -> R): Flow<List<R>> =
    map { list -> list.asyncMap(transform) }

/**
 * Delays the flow a little bit, wait the other flow's first value.
 */
fun <T1, T2> Flow<T1>.waitFirst(otherFlow: Flow<T2>): Flow<T1> =
    combine(otherFlow.distinctUntilChangedBy {}) { value, _ -> value }

/**
 * Returns a [Flow] whose values are generated list by combining the most recently emitted non null
 * values by each flow.
 */
inline fun <reified T : Any> combineToList(vararg flows: Flow<T?>): Flow<List<T>> = combine(
    flows.asList(),
) { array: Array<T?> -> array.filterNotNull() }

class StateFlowBridge<T> {
    private val stateFlow = MutableStateFlow<T?>(null)
    val flow = stateFlow.filterNotNull()

    fun setIfAbsent(value: T) {
        if (stateFlow.value == null) {
            stateFlow.value = value
        }
    }

    @Composable
    fun Sync(state: State<T>) {
        LaunchedEffect(state.value) {
            stateFlow.value = state.value
        }
    }
}
