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

package com.android.systemui.biometrics.ui.binder

import android.util.Log
import androidx.core.view.isInvisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.ui.view.UdfpsTouchOverlay
import com.android.systemui.biometrics.ui.viewmodel.UdfpsTouchOverlayViewModel
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.lifecycle.repeatWhenAttached
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
object UdfpsTouchOverlayBinder {

    /**
     * Updates visibility for the UdfpsTouchOverlay which controls whether the view will receive
     * touches or not. For some devices, this is instead handled by UdfpsOverlayInteractor, so this
     * viewBinder will send the information to the interactor.
     */
    @JvmStatic
    fun bind(
        view: UdfpsTouchOverlay,
        viewModel: UdfpsTouchOverlayViewModel,
        udfpsOverlayInteractor: UdfpsOverlayInteractor,
    ) {
        if (DeviceEntryUdfpsRefactor.isUnexpectedlyInLegacyMode()) return
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    viewModel.shouldHandleTouches.collect { shouldHandleTouches ->
                        Log.d(
                            "UdfpsTouchOverlayBinder",
                            "[$view]: update shouldHandleTouches=$shouldHandleTouches"
                        )
                        view.isInvisible = !shouldHandleTouches
                        udfpsOverlayInteractor.setHandleTouches(shouldHandleTouches)
                    }
                }
            }
        }
    }
}
