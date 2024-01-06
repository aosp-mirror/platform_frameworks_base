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

package com.android.systemui.deviceentry.shared.model

import android.hardware.face.FaceManager
import android.os.SystemClock.elapsedRealtime

/**
 * Authentication status provided by
 * [com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepository]
 */
sealed class FaceAuthenticationStatus

/** Success authentication status. */
data class SuccessFaceAuthenticationStatus(
    val successResult: FaceManager.AuthenticationResult,
    // present to break equality check if the same error occurs repeatedly.
    @JvmField val createdAt: Long = elapsedRealtime()
) : FaceAuthenticationStatus()

/** Face authentication help message. */
data class HelpFaceAuthenticationStatus(
    val msgId: Int,
    val msg: String?, // present to break equality check if the same error occurs repeatedly.
    @JvmField val createdAt: Long = elapsedRealtime()
) : FaceAuthenticationStatus()

/** Face acquired message. */
data class AcquiredFaceAuthenticationStatus(
    val acquiredInfo: Int, // present to break equality check if the same error occurs repeatedly.
    @JvmField val createdAt: Long = elapsedRealtime()
) : FaceAuthenticationStatus()

/** Face authentication failed message. */
data class FailedFaceAuthenticationStatus(
    // present to break equality check if the same error occurs repeatedly.
    @JvmField val createdAt: Long = elapsedRealtime()
) : FaceAuthenticationStatus()

/** Face authentication error message */
data class ErrorFaceAuthenticationStatus(
    val msgId: Int,
    val msg: String? = null,
    // present to break equality check if the same error occurs repeatedly.
    @JvmField val createdAt: Long = elapsedRealtime()
) : FaceAuthenticationStatus() {
    /**
     * Method that checks if [msgId] is a lockout error. A lockout error means that face
     * authentication is locked out.
     */
    fun isLockoutError() =
        msgId == FaceManager.FACE_ERROR_LOCKOUT_PERMANENT || msgId == FaceManager.FACE_ERROR_LOCKOUT

    /**
     * Method that checks if [msgId] is a cancellation error. This means that face authentication
     * was cancelled before it completed.
     */
    fun isCancellationError() = msgId == FaceManager.FACE_ERROR_CANCELED

    /** Method that checks if [msgId] is a hardware error. */
    fun isHardwareError() =
        msgId == FaceManager.FACE_ERROR_HW_UNAVAILABLE ||
            msgId == FaceManager.FACE_ERROR_UNABLE_TO_PROCESS

    companion object {
        /**
         * Error message that is created when cancel confirmation is not received from FaceManager
         * after we request for a cancellation of face auth.
         */
        fun cancelNotReceivedError() = ErrorFaceAuthenticationStatus(-1, "")
    }
}

/** Face detection success message. */
data class FaceDetectionStatus(
    val sensorId: Int,
    val userId: Int,
    val isStrongBiometric: Boolean,
    // present to break equality check if the same error occurs repeatedly.
    @JvmField val createdAt: Long = elapsedRealtime()
)
