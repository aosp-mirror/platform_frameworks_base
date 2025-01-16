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
import com.android.systemui.Flags.glanceableHubBlurredBackground
import com.android.systemui.keyguard.ui.transitions.GlanceableHubTransition
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.window.domain.interactor.WindowRootViewBlurInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

typealias BlurAppliedUiEvent = Int

/** View model for window root view. */
class WindowRootViewModel
@AssistedInject
constructor(
    private val primaryBouncerTransitions: Set<@JvmSuppressWildcards PrimaryBouncerTransition>,
    private val glanceableHubTransitions: Set<@JvmSuppressWildcards GlanceableHubTransition>,
    private val blurInteractor: WindowRootViewBlurInteractor,
) : ExclusiveActivatable() {

    private val blurEvents = Channel<BlurAppliedUiEvent>(Channel.BUFFERED)
    private val _blurState = MutableStateFlow(BlurState(0, false))
    val blurState = _blurState.asStateFlow()

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

            launchTraced("WindowRootViewModel#blurState") {
                combine(blurInteractor.blurRadius, blurInteractor.isBlurOpaque, ::BlurState)
                    .collect { _blurState.value = it }
            }

            launchTraced("WindowRootViewModel#bouncerTransitions") {
                primaryBouncerTransitions
                    .map { transition ->
                        transition.windowBlurRadius.onEach { blurRadius ->
                            if (isLoggable) {
                                Log.d(
                                    TAG,
                                    "${transition.javaClass.simpleName} windowBlurRadius $blurRadius",
                                )
                            }
                        }
                    }
                    .merge()
                    .collect { blurRadius ->
                        blurInteractor.requestBlurForBouncer(blurRadius.toInt())
                    }
            }

            if (glanceableHubBlurredBackground()) {
                launchTraced("WindowRootViewModel#glanceableHubTransitions") {
                    glanceableHubTransitions
                        .map { transition ->
                            transition.windowBlurRadius.onEach { blurRadius ->
                                if (isLoggable) {
                                    Log.d(
                                        TAG,
                                        "${transition.javaClass.simpleName} windowBlurRadius $blurRadius",
                                    )
                                }
                            }
                        }
                        .merge()
                        .collect { blurRadius ->
                            blurInteractor.requestBlurForGlanceableHub(blurRadius.toInt())
                        }
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
        val isLoggable = Log.isLoggable(TAG, Log.DEBUG) || Build.isDebuggable()
    }
}

/**
 * @property radius Radius of blur to be applied on the window root view.
 * @property isOpaque Whether the blur applied is opaque or transparent.
 */
data class BlurState(val radius: Int, val isOpaque: Boolean)
