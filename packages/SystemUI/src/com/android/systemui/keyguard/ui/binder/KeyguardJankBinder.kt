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

package com.android.systemui.keyguard.ui.binder

import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.jank.Cuj.CUJ_LOCKSCREEN_TRANSITION_FROM_AOD
import com.android.internal.jank.Cuj.CUJ_LOCKSCREEN_TRANSITION_TO_AOD
import com.android.internal.jank.Cuj.CUJ_SCREEN_OFF_SHOW_AOD
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.viewmodel.KeyguardJankViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Jank monitoring related to keyguard and transitions. */
@OptIn(ExperimentalCoroutinesApi::class)
object KeyguardJankBinder {
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: KeyguardJankViewModel,
        jankMonitor: InteractionJankMonitor?,
        clockInteractor: KeyguardClockInteractor,
        keyguardViewMediator: KeyguardViewMediator?,
        mainImmediateDispatcher: CoroutineDispatcher,
    ): DisposableHandle? {
        if (jankMonitor == null) {
            return null
        }

        fun processStep(step: TransitionStep, cuj: Int) {
            val clockId = clockInteractor.renderedClockId
            when (step.transitionState) {
                TransitionState.STARTED -> {
                    val builder =
                        InteractionJankMonitor.Configuration.Builder.withView(cuj, view)
                            .setTag(clockId)
                    jankMonitor.begin(builder)
                }

                TransitionState.CANCELED -> jankMonitor.cancel(cuj)

                TransitionState.FINISHED -> jankMonitor.end(cuj)

                TransitionState.RUNNING -> Unit
            }
        }

        return view.repeatWhenAttached(mainImmediateDispatcher) {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    viewModel.goneToAodTransition.collect {
                        processStep(it, CUJ_SCREEN_OFF_SHOW_AOD)
                        if (it.transitionState == TransitionState.FINISHED) {
                            keyguardViewMediator?.maybeHandlePendingLock()
                        }
                    }
                }

                launch {
                    viewModel.lockscreenToAodTransition.collect {
                        processStep(it, CUJ_LOCKSCREEN_TRANSITION_TO_AOD)
                    }
                }

                launch {
                    viewModel.aodToLockscreenTransition.collect {
                        processStep(it, CUJ_LOCKSCREEN_TRANSITION_FROM_AOD)
                    }
                }
            }
        }
    }
}
