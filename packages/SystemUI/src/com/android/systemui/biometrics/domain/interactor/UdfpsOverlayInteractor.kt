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

import android.content.Context
import android.hardware.fingerprint.FingerprintManager
import android.util.Log
import android.view.MotionEvent
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Encapsulates business logic for interacting with the UDFPS overlay. */
@SysUISingleton
class UdfpsOverlayInteractor
@Inject
constructor(
    @Application private val context: Context,
    private val authController: AuthController,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val fingerprintManager: FingerprintManager?,
    @Application scope: CoroutineScope
) {
    private fun calculateIconSize(): Int {
        val pixelPitch = context.resources.getFloat(R.dimen.pixel_pitch)
        if (pixelPitch <= 0) {
            Log.e(
                "UdfpsOverlayInteractor",
                "invalid pixelPitch: $pixelPitch. Pixel pitch must be updated per device."
            )
        }
        return (context.resources.getFloat(R.dimen.udfps_icon_size) / pixelPitch).toInt()
    }

    private var iconSize: Int = calculateIconSize()

    /** Whether a touch is within the under-display fingerprint sensor area */
    fun isTouchWithinUdfpsArea(ev: MotionEvent): Boolean {
        val isUdfpsEnrolled =
            authController.isUdfpsEnrolled(selectedUserInteractor.getSelectedUserId())
        val isWithinOverlayBounds =
            udfpsOverlayParams.value.overlayBounds.contains(ev.rawX.toInt(), ev.rawY.toInt())
        return isUdfpsEnrolled && isWithinOverlayBounds
    }

    private var _requestId = MutableStateFlow(0L)

    /** RequestId of current AcquisitionClient */
    val requestId: StateFlow<Long> = _requestId.asStateFlow()

    fun setRequestId(requestId: Long) {
        _requestId.value = requestId
    }

    /** Sets whether Udfps overlay should handle touches */
    fun setHandleTouches(shouldHandle: Boolean = true) {
        if (authController.isUdfpsSupported
                && shouldHandle != _shouldHandleTouches.value) {
            fingerprintManager?.setIgnoreDisplayTouches(
                requestId.value,
                authController.udfpsProps!!.get(0).sensorId,
                !shouldHandle
            )
        }
        _shouldHandleTouches.value = shouldHandle
    }

    private var _shouldHandleTouches = MutableStateFlow(true)

    /** Whether Udfps overlay should handle touches */
    val shouldHandleTouches: StateFlow<Boolean> = _shouldHandleTouches.asStateFlow()

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

    // Padding between the fingerprint icon and its bounding box in pixels.
    val iconPadding: Flow<Int> =
        udfpsOverlayParams.map { params ->
            val sensorWidth = params.nativeSensorBounds.right - params.nativeSensorBounds.left
            val nativePadding = (sensorWidth - iconSize) / 2
            (nativePadding * params.scaleFactor).toInt()
        }

    companion object {
        private const val TAG = "UdfpsOverlayInteractor"
    }
}
