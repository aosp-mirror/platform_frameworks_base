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
import com.android.systemui.Flags
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.viewModel
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.statusbar.BlurUtils
import com.android.systemui.window.ui.viewmodel.WindowRootViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

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
            var frameCallbackPendingExecution: FrameCallback? = null
            view.viewModel(
                minWindowLifecycleState = WindowLifecycleState.ATTACHED,
                factory = { viewModelFactory.create() },
                traceName = "WindowRootViewBinder#bind",
            ) { viewModel ->
                try {
                    Log.d(TAG, "Launching coroutines that update window root view state")
                    launch {
                        viewModel.blurState
                            .filter { it.radius >= 0 }
                            .collect { blurState ->
                                val newFrameCallback = FrameCallback {
                                    frameCallbackPendingExecution = null
                                    blurUtils.applyBlur(
                                        view.rootView?.viewRootImpl,
                                        blurState.radius,
                                        blurState.isOpaque,
                                    )
                                    viewModel.onBlurApplied(blurState.radius)
                                }
                                blurUtils.prepareBlur(view.rootView?.viewRootImpl, blurState.radius)
                                if (frameCallbackPendingExecution != null) {
                                    choreographer.removeFrameCallback(frameCallbackPendingExecution)
                                }
                                frameCallbackPendingExecution = newFrameCallback
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
