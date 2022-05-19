/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.keyguard

import android.annotation.IntDef
import android.content.ContentResolver
import android.database.ContentObserver
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_TIMEOUT
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_FACE_ACQUIRE_INFO
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_FACE_ERRORS
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_WAKE
import android.util.Log
import com.android.keyguard.KeyguardUpdateMonitor.getCurrentUser
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.settings.SecureSettings
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Handles active unlock settings changes.
 */
@SysUISingleton
class ActiveUnlockConfig @Inject constructor(
    @Main private val handler: Handler,
    private val secureSettings: SecureSettings,
    private val contentResolver: ContentResolver,
    dumpManager: DumpManager
) : Dumpable {

    companion object {
        const val TAG = "ActiveUnlockConfig"

        const val BIOMETRIC_TYPE_NONE = 0
        const val BIOMETRIC_TYPE_ANY_FACE = 1
        const val BIOMETRIC_TYPE_ANY_FINGERPRINT = 2
        const val BIOMETRIC_TYPE_UNDER_DISPLAY_FINGERPRINT = 3
    }

    @Retention(AnnotationRetention.SOURCE)
    @IntDef(BIOMETRIC_TYPE_NONE, BIOMETRIC_TYPE_ANY_FACE, BIOMETRIC_TYPE_ANY_FINGERPRINT,
            BIOMETRIC_TYPE_UNDER_DISPLAY_FINGERPRINT)
    annotation class BiometricType

    /**
     * Indicates the origin for an active unlock request.
     */
    enum class ACTIVE_UNLOCK_REQUEST_ORIGIN {
        WAKE, UNLOCK_INTENT, BIOMETRIC_FAIL, ASSISTANT
    }

    var keyguardUpdateMonitor: KeyguardUpdateMonitor? = null
    private var requestActiveUnlockOnWakeup = false
    private var requestActiveUnlockOnUnlockIntent = false
    private var requestActiveUnlockOnBioFail = false

    private var faceErrorsToTriggerBiometricFailOn = mutableSetOf(FACE_ERROR_TIMEOUT)
    private var faceAcquireInfoToTriggerBiometricFailOn = mutableSetOf<Int>()
    private var onUnlockIntentWhenBiometricEnrolled = mutableSetOf<Int>(BIOMETRIC_TYPE_NONE)

    private val settingsObserver = object : ContentObserver(handler) {
        private val wakeUri = secureSettings.getUriFor(ACTIVE_UNLOCK_ON_WAKE)
        private val unlockIntentUri = secureSettings.getUriFor(ACTIVE_UNLOCK_ON_UNLOCK_INTENT)
        private val bioFailUri = secureSettings.getUriFor(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL)
        private val faceErrorsUri = secureSettings.getUriFor(ACTIVE_UNLOCK_ON_FACE_ERRORS)
        private val faceAcquireInfoUri =
                secureSettings.getUriFor(ACTIVE_UNLOCK_ON_FACE_ACQUIRE_INFO)
        private val unlockIntentWhenBiometricEnrolledUri =
                secureSettings.getUriFor(ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED)

        fun register() {
            registerUri(
                    listOf(
                            wakeUri,
                            unlockIntentUri,
                            bioFailUri,
                            faceErrorsUri,
                            faceAcquireInfoUri,
                            unlockIntentWhenBiometricEnrolledUri
                    )
            )

            onChange(true, ArrayList(), 0, getCurrentUser())
        }

        private fun registerUri(uris: Collection<Uri>) {
            for (uri in uris) {
                contentResolver.registerContentObserver(
                        uri,
                        false,
                        this,
                        UserHandle.USER_ALL)
            }
        }

        override fun onChange(
            selfChange: Boolean,
            uris: Collection<Uri>,
            flags: Int,
            userId: Int
        ) {
            if (getCurrentUser() != userId) {
                return
            }

            if (selfChange || uris.contains(wakeUri)) {
                requestActiveUnlockOnWakeup = secureSettings.getIntForUser(
                        ACTIVE_UNLOCK_ON_WAKE, 0, getCurrentUser()) == 1
            }

            if (selfChange || uris.contains(unlockIntentUri)) {
                requestActiveUnlockOnUnlockIntent = secureSettings.getIntForUser(
                        ACTIVE_UNLOCK_ON_UNLOCK_INTENT, 0, getCurrentUser()) == 1
            }

            if (selfChange || uris.contains(bioFailUri)) {
                requestActiveUnlockOnBioFail = secureSettings.getIntForUser(
                        ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL, 0, getCurrentUser()) == 1
            }

            if (selfChange || uris.contains(faceErrorsUri)) {
                processStringArray(
                        secureSettings.getStringForUser(ACTIVE_UNLOCK_ON_FACE_ERRORS,
                                getCurrentUser()),
                        faceErrorsToTriggerBiometricFailOn,
                        setOf(FACE_ERROR_TIMEOUT))
            }

            if (selfChange || uris.contains(faceAcquireInfoUri)) {
                processStringArray(
                        secureSettings.getStringForUser(ACTIVE_UNLOCK_ON_FACE_ACQUIRE_INFO,
                                getCurrentUser()),
                        faceAcquireInfoToTriggerBiometricFailOn,
                        setOf<Int>())
            }

            if (selfChange || uris.contains(unlockIntentWhenBiometricEnrolledUri)) {
                processStringArray(
                        secureSettings.getStringForUser(
                                ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED,
                                getCurrentUser()),
                        onUnlockIntentWhenBiometricEnrolled,
                        setOf(BIOMETRIC_TYPE_NONE))
            }
        }

        /**
         * Convert a pipe-separated set of integers into a set of ints.
         * @param stringSetting expected input are integers delineated by a pipe. For example,
         * it may look something like this: "1|5|3".
         * @param out updates the "out" Set will the integers between the pipes.
         * @param default If stringSetting is null, "out" will be populated with values in "default"
         */
        private fun processStringArray(
            stringSetting: String?,
            out: MutableSet<Int>,
            default: Set<Int>
        ) {
            out.clear()
            stringSetting?.let {
                for (code: String in stringSetting.split("|")) {
                    try {
                        out.add(code.toInt())
                    } catch (e: NumberFormatException) {
                        Log.e(TAG, "Passed an invalid setting=$code")
                    }
                }
            } ?: out.addAll(default)
        }
    }

    init {
        settingsObserver.register()
        dumpManager.registerDumpable(this)
    }

    /**
     * If any active unlock triggers are enabled.
     */
    fun isActiveUnlockEnabled(): Boolean {
        return requestActiveUnlockOnWakeup || requestActiveUnlockOnUnlockIntent ||
                requestActiveUnlockOnBioFail
    }

    /**
     * Whether the face error code from {@link BiometricFaceConstants} should trigger
     * active unlock on biometric failure.
     */
    fun shouldRequestActiveUnlockOnFaceError(errorCode: Int): Boolean {
        return faceErrorsToTriggerBiometricFailOn.contains(errorCode)
    }

    /**
     * Whether the face acquireInfo from {@link BiometricFaceConstants} should trigger
     * active unlock on biometric failure.
     */
    fun shouldRequestActiveUnlockOnFaceAcquireInfo(acquiredInfo: Int): Boolean {
        return faceAcquireInfoToTriggerBiometricFailOn.contains(acquiredInfo)
    }

    /**
     * Whether to trigger active unlock based on where the request is coming from and
     * the current settings.
     */
    fun shouldAllowActiveUnlockFromOrigin(requestOrigin: ACTIVE_UNLOCK_REQUEST_ORIGIN): Boolean {
        return when (requestOrigin) {
            ACTIVE_UNLOCK_REQUEST_ORIGIN.WAKE -> requestActiveUnlockOnWakeup

            ACTIVE_UNLOCK_REQUEST_ORIGIN.UNLOCK_INTENT ->
                requestActiveUnlockOnUnlockIntent || requestActiveUnlockOnWakeup ||
                        (shouldRequestActiveUnlockOnUnlockIntentFromBiometricEnrollment())

            ACTIVE_UNLOCK_REQUEST_ORIGIN.BIOMETRIC_FAIL ->
                requestActiveUnlockOnBioFail || requestActiveUnlockOnUnlockIntent ||
                        requestActiveUnlockOnWakeup

            ACTIVE_UNLOCK_REQUEST_ORIGIN.ASSISTANT -> isActiveUnlockEnabled()
        }
    }

    private fun shouldRequestActiveUnlockOnUnlockIntentFromBiometricEnrollment(): Boolean {
        if (!requestActiveUnlockOnBioFail) {
            return false
        }

        keyguardUpdateMonitor?.let {
            val anyFaceEnrolled = it.isFaceEnrolled
            val anyFingerprintEnrolled =
                    it.getCachedIsUnlockWithFingerprintPossible(getCurrentUser())
            val udfpsEnrolled = it.isUdfpsEnrolled

            if (!anyFaceEnrolled && !anyFingerprintEnrolled) {
                return onUnlockIntentWhenBiometricEnrolled.contains(BIOMETRIC_TYPE_NONE)
            }

            if (!anyFaceEnrolled && anyFingerprintEnrolled) {
                return onUnlockIntentWhenBiometricEnrolled.contains(
                        BIOMETRIC_TYPE_ANY_FINGERPRINT) ||
                        (udfpsEnrolled && onUnlockIntentWhenBiometricEnrolled.contains(
                                BIOMETRIC_TYPE_UNDER_DISPLAY_FINGERPRINT))
            }

            if (!anyFingerprintEnrolled && anyFaceEnrolled) {
                return onUnlockIntentWhenBiometricEnrolled.contains(BIOMETRIC_TYPE_ANY_FACE)
            }
        }

        return false
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("Settings:")
        pw.println("   requestActiveUnlockOnWakeup=$requestActiveUnlockOnWakeup")
        pw.println("   requestActiveUnlockOnUnlockIntent=$requestActiveUnlockOnUnlockIntent")
        pw.println("   requestActiveUnlockOnBioFail=$requestActiveUnlockOnBioFail")
        pw.println("   requestActiveUnlockOnUnlockIntentWhenBiometricEnrolled=" +
                "$onUnlockIntentWhenBiometricEnrolled")
        pw.println("   requestActiveUnlockOnFaceError=$faceErrorsToTriggerBiometricFailOn")
        pw.println("   requestActiveUnlockOnFaceAcquireInfo=" +
                "$faceAcquireInfoToTriggerBiometricFailOn")

        pw.println("Current state:")
        keyguardUpdateMonitor?.let {
            pw.println("   shouldRequestActiveUnlockOnUnlockIntentFromBiometricEnrollment=" +
                    "${shouldRequestActiveUnlockOnUnlockIntentFromBiometricEnrollment()}")
            pw.println("   faceEnrolled=${it.isFaceEnrolled}")
            pw.println("   fpEnrolled=${
                    it.getCachedIsUnlockWithFingerprintPossible(getCurrentUser())}")
            pw.println("   udfpsEnrolled=${it.isUdfpsEnrolled}")
        } ?: pw.println("   keyguardUpdateMonitor is uninitialized")
    }
}