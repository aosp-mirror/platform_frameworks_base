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

package com.android.systemui.keyguard.shared.model

import android.hardware.face.FaceAuthenticateOptions
import android.hardware.face.FaceAuthenticateOptions.AUTHENTICATE_REASON_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN
import android.hardware.face.FaceAuthenticateOptions.AUTHENTICATE_REASON_ASSISTANT_VISIBLE
import android.hardware.face.FaceAuthenticateOptions.AUTHENTICATE_REASON_NOTIFICATION_PANEL_CLICKED
import android.hardware.face.FaceAuthenticateOptions.AUTHENTICATE_REASON_OCCLUDING_APP_REQUESTED
import android.hardware.face.FaceAuthenticateOptions.AUTHENTICATE_REASON_PICK_UP_GESTURE_TRIGGERED
import android.hardware.face.FaceAuthenticateOptions.AUTHENTICATE_REASON_PRIMARY_BOUNCER_SHOWN
import android.hardware.face.FaceAuthenticateOptions.AUTHENTICATE_REASON_STARTED_WAKING_UP
import android.hardware.face.FaceAuthenticateOptions.AUTHENTICATE_REASON_SWIPE_UP_ON_BOUNCER
import android.hardware.face.FaceAuthenticateOptions.AUTHENTICATE_REASON_UDFPS_POINTER_DOWN
import android.hardware.face.FaceAuthenticateOptions.AUTHENTICATE_REASON_UNKNOWN
import android.hardware.face.FaceAuthenticateOptions.AuthenticateReason
import android.os.PowerManager
import android.os.PowerManager.WAKE_REASON_UNKNOWN
import android.util.Log
import com.android.internal.logging.UiEventLogger
import com.android.systemui.deviceentry.shared.FaceAuthUiEvent

/**
 * Wrapper for [FaceAuthenticateOptions] to convert SystemUI values to their corresponding value in
 * [FaceAuthenticateOptions].
 */
data class SysUiFaceAuthenticateOptions(
    val userId: Int,
    private val faceAuthUiEvent: UiEventLogger.UiEventEnum,
    @PowerManager.WakeReason val wakeReason: Int = WAKE_REASON_UNKNOWN
) {
    private val authenticateReason = setAuthenticateReason(faceAuthUiEvent)

    /**
     * The [FaceAuthUiEvent] for this operation. This method converts the UiEvent to the framework
     * [AuthenticateReason].
     */
    @AuthenticateReason
    fun setAuthenticateReason(uiEvent: UiEventLogger.UiEventEnum): Int {
        return when (uiEvent) {
            FaceAuthUiEvent.FACE_AUTH_UPDATED_STARTED_WAKING_UP -> {
                AUTHENTICATE_REASON_STARTED_WAKING_UP
            }
            FaceAuthUiEvent.FACE_AUTH_UPDATED_PRIMARY_BOUNCER_SHOWN,
            FaceAuthUiEvent.FACE_AUTH_UPDATED_PRIMARY_BOUNCER_SHOWN_OR_WILL_BE_SHOWN -> {
                AUTHENTICATE_REASON_PRIMARY_BOUNCER_SHOWN
            }
            FaceAuthUiEvent.FACE_AUTH_UPDATED_ASSISTANT_VISIBILITY_CHANGED -> {
                AUTHENTICATE_REASON_ASSISTANT_VISIBLE
            }
            FaceAuthUiEvent.FACE_AUTH_TRIGGERED_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN -> {
                AUTHENTICATE_REASON_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN
            }
            FaceAuthUiEvent.FACE_AUTH_TRIGGERED_NOTIFICATION_PANEL_CLICKED -> {
                AUTHENTICATE_REASON_NOTIFICATION_PANEL_CLICKED
            }
            FaceAuthUiEvent.FACE_AUTH_TRIGGERED_OCCLUDING_APP_REQUESTED -> {
                AUTHENTICATE_REASON_OCCLUDING_APP_REQUESTED
            }
            FaceAuthUiEvent.FACE_AUTH_TRIGGERED_PICK_UP_GESTURE_TRIGGERED -> {
                AUTHENTICATE_REASON_PICK_UP_GESTURE_TRIGGERED
            }
            FaceAuthUiEvent.FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER -> {
                AUTHENTICATE_REASON_SWIPE_UP_ON_BOUNCER
            }
            FaceAuthUiEvent.FACE_AUTH_TRIGGERED_UDFPS_POINTER_DOWN -> {
                AUTHENTICATE_REASON_UDFPS_POINTER_DOWN
            }
            else -> {
                Log.e("FaceAuthenticateOptions", " unmapped FaceAuthUiEvent $uiEvent")
                AUTHENTICATE_REASON_UNKNOWN
            }
        }
    }

    /** Builds the instance. */
    fun toFaceAuthenticateOptions(): FaceAuthenticateOptions {
        return FaceAuthenticateOptions.Builder()
            .setUserId(userId)
            .setAuthenticateReason(authenticateReason)
            .setWakeReason(wakeReason)
            .build()
    }
}
