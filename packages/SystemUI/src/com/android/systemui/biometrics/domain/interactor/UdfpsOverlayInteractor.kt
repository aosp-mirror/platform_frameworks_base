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

package com.android.systemui.biometrics.domain.interactor

import android.view.MotionEvent
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Encapsulates business logic for interacting with the UDFPS overlay. */
@SysUISingleton
class UdfpsOverlayInteractor
@Inject
constructor(
    private val authController: AuthController,
    private val selectedUserInteractor: SelectedUserInteractor,
    @Application scope: CoroutineScope
) {

    /** Whether a touch is within the under-display fingerprint sensor area */
    fun isTouchWithinUdfpsArea(ev: MotionEvent): Boolean {
        val isUdfpsEnrolled =
            authController.isUdfpsEnrolled(selectedUserInteractor.getSelectedUserId())
        val isWithinOverlayBounds =
            udfpsOverlayParams.value.overlayBounds.contains(ev.rawX.toInt(), ev.rawY.toInt())
        return isUdfpsEnrolled && isWithinOverlayBounds
    }

    /** Returns the current udfpsOverlayParams */
    val udfpsOverlayParams: StateFlow<UdfpsOverlayParams> =
        ConflatedCallbackFlow.conflatedCallbackFlow {
                val callback =
                    object : AuthController.Callback {
                        override fun onUdfpsLocationChanged(
                            udfpsOverlayParams: UdfpsOverlayParams
                        ) {
                            trySendWithFailureLogging(
                                udfpsOverlayParams,
                                TAG,
                                "update udfpsOverlayParams"
                            )
                        }
                    }
                authController.addCallback(callback)
                awaitClose { authController.removeCallback(callback) }
            }
            .stateIn(scope, started = SharingStarted.Eagerly, initialValue = UdfpsOverlayParams())

    companion object {
        private const val TAG = "UdfpsOverlayInteractor"
    }
}
