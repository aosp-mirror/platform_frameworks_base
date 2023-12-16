/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.annotation.MainThread
import android.util.Log
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AuthDialogPanelInteractionDetector
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val shadeInteractorLazy: Lazy<ShadeInteractor>,
) {
    private var shadeExpansionCollectorJob: Job? = null

    @MainThread
    fun enable(onShadeInteraction: Runnable) {
        if (shadeExpansionCollectorJob != null) {
            Log.e(TAG, "Already enabled")
            return
        }
        //TODO(b/313957306) delete this check
        if (shadeInteractorLazy.get().isUserInteracting.value) {
            // Workaround for b/311266890. This flow is in an error state that breaks this.
            Log.e(TAG, "isUserInteracting already true, skipping enable")
            return
        }
        shadeExpansionCollectorJob =
            scope.launch {
                Log.i(TAG, "Enable detector")
                // wait for it to emit true once
                shadeInteractorLazy.get().isUserInteracting.first { it }
                Log.i(TAG, "Detector detected shade interaction")
                onShadeInteraction.run()
            }
        shadeExpansionCollectorJob?.invokeOnCompletion { shadeExpansionCollectorJob = null }
    }

    @MainThread
    fun disable() {
        Log.i(TAG, "Disable detector")
        shadeExpansionCollectorJob?.cancel()
    }
}

private const val TAG = "AuthDialogPanelInteractionDetector"
