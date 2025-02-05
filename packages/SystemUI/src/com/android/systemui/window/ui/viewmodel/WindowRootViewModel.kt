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

package com.android.systemui.window.ui.viewmodel

import android.os.Build
import android.util.Log
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.Flags
import com.android.systemui.keyguard.ui.transitions.GlanceableHubTransition
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

typealias BlurAppliedUiEvent = Int

/** View model for window root view. */
class WindowRootViewModel
@AssistedInject
constructor(
    primaryBouncerTransitions: Set<@JvmSuppressWildcards PrimaryBouncerTransition>,
    glanceableHubTransitions: Set<@JvmSuppressWildcards GlanceableHubTransition>,
    private val blurInteractor: WindowRootViewBlurInteractor,
) : ExclusiveActivatable() {

    private val blurEvents = Channel<BlurAppliedUiEvent>(Channel.BUFFERED)

    private val bouncerBlurRadiusFlows =
        if (Flags.bouncerUiRevamp())
            primaryBouncerTransitions.map { it.windowBlurRadius.logIfPossible(it.javaClass.name) }
        else emptyList()

    private val glanceableHubBlurRadiusFlows =
        if (Flags.glanceableHubBlurredBackground())
            glanceableHubTransitions.map { it.windowBlurRadius.logIfPossible(it.javaClass.name) }
        else emptyList()

    val blurRadius: Flow<Float> =
        listOf(
                *bouncerBlurRadiusFlows.toTypedArray(),
                *glanceableHubBlurRadiusFlows.toTypedArray(),
                blurInteractor.blurRadius.map { it.toFloat() }.logIfPossible("ShadeBlur"),
            )
            .merge()

    val isBlurOpaque =
        blurInteractor.isBlurOpaque.distinctUntilChanged().logIfPossible("isBlurOpaque")

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launchTraced("WindowRootViewModel#blurEvents") {
                for (event in blurEvents) {
                    if (isLoggable) {
                        Log.d(TAG, "blur applied for $event")
                    }
                    blurInteractor.onBlurApplied(event)
                }
            }
        }
        awaitCancellation()
    }

    fun onBlurApplied(blurRadius: Int) {
        blurEvents.trySend(blurRadius)
    }

    @AssistedFactory
    interface Factory {
        fun create(): WindowRootViewModel
    }

    private companion object {
        const val TAG = "WindowRootViewModel"
        val isLoggable = Log.isLoggable(TAG, Log.VERBOSE) || Build.isDebuggable()

        fun <T> Flow<T>.logIfPossible(loggingInfo: String): Flow<T> {
            return onEach { if (isLoggable) Log.v(TAG, "$loggingInfo $it") }
        }
    }
}

/**
 * @property radius Radius of blur to be applied on the window root view.
 * @property isOpaque Whether the blur applied is opaque or transparent.
 */
data class BlurState(val radius: Int, val isOpaque: Boolean)
