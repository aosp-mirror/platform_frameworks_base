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

package com.android.systemui.lifecycle

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.StateFactoryMarker
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keeps snapshot/Compose [State]s up-to-date.
 *
 * ```kotlin
 * val hydrator = Hydrator()
 * val state: Int by hydrator.hydratedStateOf(upstreamFlow)
 *
 * override suspend fun activate(): Nothing {
 *     hydrator.activate()
 * }
 * ```
 */
class Hydrator : ExclusiveActivatable() {

    private val children = mutableListOf<Activatable>()

    /**
     * Returns a snapshot [State] that's kept up-to-date as long as the [SysUiViewModel] is active.
     *
     * @param source The upstream [StateFlow] to collect from; values emitted to it will be
     *   automatically set on the returned [State].
     */
    @StateFactoryMarker
    fun <T> hydratedStateOf(
        source: StateFlow<T>,
    ): State<T> {
        return hydratedStateOf(
            initialValue = source.value,
            source = source,
        )
    }

    /**
     * Returns a snapshot [State] that's kept up-to-date as long as the [SysUiViewModel] is active.
     *
     * @param initialValue The first value to place on the [State]
     * @param source The upstream [Flow] to collect from; values emitted to it will be automatically
     *   set on the returned [State].
     */
    @StateFactoryMarker
    fun <T> hydratedStateOf(
        initialValue: T,
        source: Flow<T>,
    ): State<T> {
        check(!isActive) { "Cannot call hydratedStateOf after Hydrator is already active." }

        val mutableState = mutableStateOf(initialValue)
        children.add(
            object : ExclusiveActivatable() {
                override suspend fun onActivated(): Nothing {
                    source.collect { mutableState.value = it }
                    awaitCancellation()
                }
            }
        )
        return mutableState
    }

    override suspend fun onActivated() = coroutineScope {
        children.forEach { child -> launch { child.activate() } }
        awaitCancellation()
    }
}
