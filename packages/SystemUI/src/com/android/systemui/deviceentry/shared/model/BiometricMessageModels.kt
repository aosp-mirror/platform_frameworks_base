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

/**
 * BiometricMessage provided by
 * [com.android.systemui.deviceentry.domain.interactor.BiometricMessageInteractor]
 */
sealed class BiometricMessage(
    val message: String?,
)

/** Face biometric message */
open class FaceMessage(faceMessage: String?) : BiometricMessage(faceMessage)

/** Face timeout message. */
data class FaceTimeoutMessage(
    private val faceTimeoutMessage: String?,
) : FaceMessage(faceTimeoutMessage)

data class FaceLockoutMessage(private val msg: String?) : FaceMessage(msg)

data class FaceFailureMessage(private val msg: String) : FaceMessage(msg)

/** Fingerprint biometric message */
open class FingerprintMessage(fingerprintMessage: String?) : BiometricMessage(fingerprintMessage)

data class FingerprintLockoutMessage(
    private val fingerprintLockoutMessage: String?,
) : FingerprintMessage(fingerprintLockoutMessage)

data class FingerprintFailureMessage(private val msg: String?) : FingerprintMessage(msg)
