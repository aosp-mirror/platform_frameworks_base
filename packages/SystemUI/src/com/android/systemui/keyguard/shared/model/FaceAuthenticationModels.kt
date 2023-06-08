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

import android.hardware.face.FaceManager

/** Authentication status provided by [com.android.keyguard.faceauth.KeyguardFaceAuthManager] */
sealed class AuthenticationStatus

/** Success authentication status. */
data class SuccessAuthenticationStatus(val successResult: FaceManager.AuthenticationResult) :
    AuthenticationStatus()

/** Face authentication help message. */
data class HelpAuthenticationStatus(val msgId: Int, val msg: String?) : AuthenticationStatus()

/** Face acquired message. */
data class AcquiredAuthenticationStatus(val acquiredInfo: Int) : AuthenticationStatus()

/** Face authentication failed message. */
object FailedAuthenticationStatus : AuthenticationStatus()

/** Face authentication error message */
data class ErrorAuthenticationStatus(val msgId: Int, val msg: String?) : AuthenticationStatus() {
    /**
     * Method that checks if [msgId] is a lockout error. A lockout error means that face
     * authentication is locked out.
     */
    fun isLockoutError() = msgId == FaceManager.FACE_ERROR_LOCKOUT_PERMANENT

    /**
     * Method that checks if [msgId] is a cancellation error. This means that face authentication
     * was cancelled before it completed.
     */
    fun isCancellationError() = msgId == FaceManager.FACE_ERROR_CANCELED
}

/** Face detection success message. */
data class DetectionStatus(val sensorId: Int, val userId: Int, val isStrongBiometric: Boolean)
