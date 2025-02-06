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

package com.android.systemui.window.ui

import android.util.Log
import android.view.Choreographer
import android.view.Choreographer.FrameCallback
import com.android.app.tracing.coroutines.TrackTracer
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.Flags
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.viewModel
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.statusbar.BlurUtils
import com.android.systemui.window.ui.viewmodel.WindowRootViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter

/**
 * View binder that wires up window level UI transformations like blur to the [WindowRootView]
 * instance.
 */
object WindowRootViewBinder {
    private const val TAG = "WindowRootViewBinder"

    fun bind(
        view: WindowRootView,
        viewModelFactory: WindowRootViewModel.Factory,
        blurUtils: BlurUtils?,
        choreographer: Choreographer?,
        mainDispatcher: CoroutineDispatcher,
    ) {
        if (!Flags.bouncerUiRevamp() && !Flags.glanceableHubBlurredBackground()) return
        if (blurUtils == null || choreographer == null) return

        view.repeatWhenAttached(mainDispatcher) {
            Log.d(TAG, "Binding root view")
            view.viewModel(
                minWindowLifecycleState = WindowLifecycleState.ATTACHED,
                factory = { viewModelFactory.create() },
                traceName = "WindowRootViewBinder#bind",
            ) { viewModel ->
                try {
                    Log.d(TAG, "Launching coroutines that update window root view state")
                    launchTraced("WindowBlur") {
                        var wasUpdateScheduledForThisFrame = false
                        var lastScheduledBlurRadius = 0
                        var lastScheduleBlurOpaqueness = false

                        // Creating the callback once and not for every coroutine invocation
                        val newFrameCallback = FrameCallback {
                            wasUpdateScheduledForThisFrame = false
                            val blurRadiusToApply = lastScheduledBlurRadius
                            blurUtils.applyBlur(
                                view.rootView?.viewRootImpl,
                                blurRadiusToApply,
                                lastScheduleBlurOpaqueness,
                            )
                            TrackTracer.instantForGroup(
                                "windowBlur",
                                "appliedBlurRadius",
                                blurRadiusToApply,
                            )
                            viewModel.onBlurApplied(blurRadiusToApply)
                        }

                        combine(viewModel.blurRadius, viewModel.isBlurOpaque, ::Pair)
                            .filter { it.first >= 0 }
                            .collect { (blurRadius, isOpaque) ->
                                // Expectation is that we schedule only one blur radius value
                                // per frame
                                if (wasUpdateScheduledForThisFrame) {
                                    return@collect
                                }
                                TrackTracer.instantForGroup(
                                    "windowBlur",
                                    "preparedBlurRadius",
                                    blurRadius,
                                )
                                lastScheduledBlurRadius = blurRadius.toInt()
                                lastScheduleBlurOpaqueness = isOpaque
                                wasUpdateScheduledForThisFrame = true
                                blurUtils.prepareBlur(
                                    view.rootView?.viewRootImpl,
                                    lastScheduledBlurRadius,
                                )
                                choreographer.postFrameCallback(newFrameCallback)
                            }
                    }
                    awaitCancellation()
                } finally {
                    Log.d(TAG, "Wrapped up coroutines that update window root view state")
                }
            }
        }
    }
}
