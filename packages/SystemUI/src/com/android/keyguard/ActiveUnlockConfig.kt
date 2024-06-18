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

import android.content.ContentResolver
import android.database.ContentObserver
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_TIMEOUT
import android.net.Uri
import android.os.Handler
import android.os.PowerManager
import android.os.PowerManager.WAKE_REASON_UNFOLD_DEVICE
import android.os.UserHandle
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_FACE_ACQUIRE_INFO
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_FACE_ERRORS
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_WAKE
import android.provider.Settings.Secure.ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS
import android.provider.Settings.Secure.ACTIVE_UNLOCK_WAKEUPS_TO_FORCE_DISMISS_KEYGUARD
import android.util.Log
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.settings.SecureSettings
import java.io.PrintWriter
import javax.inject.Inject
import dagger.Lazy

/**
 * Handles active unlock settings changes.
 */
@SysUISingleton
class ActiveUnlockConfig @Inject constructor(
    @Main private val handler: Handler,
    private val secureSettings: SecureSettings,
    private val contentResolver: ContentResolver,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val keyguardUpdateMonitor: Lazy<KeyguardUpdateMonitor>,
    dumpManager: DumpManager
) : Dumpable {

    companion object {
        const val TAG = "ActiveUnlockConfig"
    }

    /**
     * Indicates the origin for an active unlock request.
     */
    enum class ActiveUnlockRequestOrigin {
        /**
         * Trigger ActiveUnlock on wake ups that'd trigger FaceAuth, see [FaceWakeUpTriggersConfig]
         */
        WAKE,

        /**
         * Trigger ActiveUnlock on unlock intents. This includes the bouncer showing or tapping on
         * a notification. May also include wakeups: [wakeupsConsideredUnlockIntents].
         */
        UNLOCK_INTENT,

        /**
         * Trigger ActiveUnlock on biometric failures. This may include soft errors depending on
         * the other settings. See: [faceErrorsToTriggerBiometricFailOn],
         * [faceAcquireInfoToTriggerBiometricFailOn].
         */
        BIOMETRIC_FAIL,

        /**
         * Trigger ActiveUnlock when the assistant is triggered.
         */
        ASSISTANT,
    }

    /**
     * Biometric type options.
     */
    enum class BiometricType(val intValue: Int) {
        NONE(0),
        ANY_FACE(1),
        ANY_FINGERPRINT(2),
        UNDER_DISPLAY_FINGERPRINT(3),
    }

    private var requestActiveUnlockOnWakeup = false
    private var requestActiveUnlockOnUnlockIntent = false
    private var requestActiveUnlockOnBioFail = false

    private var faceErrorsToTriggerBiometricFailOn = mutableSetOf<Int>()
    private var faceAcquireInfoToTriggerBiometricFailOn = mutableSetOf<Int>()
    private var onUnlockIntentWhenBiometricEnrolled = mutableSetOf<Int>()
    private var wakeupsConsideredUnlockIntents = mutableSetOf<Int>()
    private var wakeupsToForceDismissKeyguard = mutableSetOf<Int>()

    private val settingsObserver = object : ContentObserver(handler) {
        private val wakeUri = secureSettings.getUriFor(ACTIVE_UNLOCK_ON_WAKE)
        private val unlockIntentUri = secureSettings.getUriFor(ACTIVE_UNLOCK_ON_UNLOCK_INTENT)
        private val bioFailUri = secureSettings.getUriFor(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL)
        private val faceErrorsUri = secureSettings.getUriFor(ACTIVE_UNLOCK_ON_FACE_ERRORS)
        private val faceAcquireInfoUri =
                secureSettings.getUriFor(ACTIVE_UNLOCK_ON_FACE_ACQUIRE_INFO)
        private val unlockIntentWhenBiometricEnrolledUri =
                secureSettings.getUriFor(ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED)
        private val wakeupsConsideredUnlockIntentsUri =
            secureSettings.getUriFor(ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS)
        private val wakeupsToForceDismissKeyguardUri =
            secureSettings.getUriFor(ACTIVE_UNLOCK_WAKEUPS_TO_FORCE_DISMISS_KEYGUARD)

        fun register() {
            registerUri(
                    listOf(
                        wakeUri,
                        unlockIntentUri,
                        bioFailUri,
                        faceErrorsUri,
                        faceAcquireInfoUri,
                        unlockIntentWhenBiometricEnrolledUri,
                        wakeupsConsideredUnlockIntentsUri,
                        wakeupsToForceDismissKeyguardUri,
                    )
            )

            onChange(true, ArrayList(), 0, selectedUserInteractor.getSelectedUserId())
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
            if (selectedUserInteractor.getSelectedUserId() != userId) {
                return
            }

            if (selfChange || uris.contains(wakeUri)) {
                requestActiveUnlockOnWakeup = secureSettings.getIntForUser(
                        ACTIVE_UNLOCK_ON_WAKE, 0, selectedUserInteractor.getSelectedUserId()) == 1
            }

            if (selfChange || uris.contains(unlockIntentUri)) {
                requestActiveUnlockOnUnlockIntent = secureSettings.getIntForUser(
                        ACTIVE_UNLOCK_ON_UNLOCK_INTENT, 0,
                        selectedUserInteractor.getSelectedUserId()) == 1
            }

            if (selfChange || uris.contains(bioFailUri)) {
                requestActiveUnlockOnBioFail = secureSettings.getIntForUser(
                        ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL, 0,
                        selectedUserInteractor.getSelectedUserId()) == 1
            }

            if (selfChange || uris.contains(faceErrorsUri)) {
                processStringArray(
                        secureSettings.getStringForUser(ACTIVE_UNLOCK_ON_FACE_ERRORS,
                                selectedUserInteractor.getSelectedUserId()),
                        faceErrorsToTriggerBiometricFailOn,
                        setOf(FACE_ERROR_TIMEOUT))
            }

            if (selfChange || uris.contains(faceAcquireInfoUri)) {
                processStringArray(
                        secureSettings.getStringForUser(ACTIVE_UNLOCK_ON_FACE_ACQUIRE_INFO,
                                selectedUserInteractor.getSelectedUserId()),
                        faceAcquireInfoToTriggerBiometricFailOn,
                        emptySet())
            }

            if (selfChange || uris.contains(unlockIntentWhenBiometricEnrolledUri)) {
                processStringArray(
                        secureSettings.getStringForUser(
                                ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED,
                                selectedUserInteractor.getSelectedUserId()),
                        onUnlockIntentWhenBiometricEnrolled,
                        setOf(BiometricType.NONE.intValue))
            }

            if (selfChange || uris.contains(wakeupsConsideredUnlockIntentsUri)) {
                processStringArray(
                    secureSettings.getStringForUser(
                        ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS,
                        selectedUserInteractor.getSelectedUserId()),
                    wakeupsConsideredUnlockIntents,
                    setOf(WAKE_REASON_UNFOLD_DEVICE))
            }

            if (selfChange || uris.contains(wakeupsToForceDismissKeyguardUri)) {
                processStringArray(
                    secureSettings.getStringForUser(
                        ACTIVE_UNLOCK_WAKEUPS_TO_FORCE_DISMISS_KEYGUARD,
                        selectedUserInteractor.getSelectedUserId()),
                    wakeupsToForceDismissKeyguard,
                    setOf(WAKE_REASON_UNFOLD_DEVICE))
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
                    if (code.isNotEmpty()) {
                        try {
                            out.add(code.toInt())
                        } catch (e: NumberFormatException) {
                            Log.e(TAG, "Passed an invalid setting=$code")
                        }
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
     * Whether the PowerManager wake reason is considered an unlock intent and should use origin
     * [ActiveUnlockRequestOrigin.UNLOCK_INTENT] instead of [ActiveUnlockRequestOrigin.WAKE].
     */
    fun isWakeupConsideredUnlockIntent(pmWakeReason: Int): Boolean {
        return wakeupsConsideredUnlockIntents.contains(pmWakeReason)
    }

    /**
     * Whether the PowerManager wake reason should force dismiss the keyguard if active
     * unlock is successful.
     */
    fun shouldWakeupForceDismissKeyguard(pmWakeReason: Int): Boolean {
        return wakeupsToForceDismissKeyguard.contains(pmWakeReason)
    }

    /**
     * Whether to trigger active unlock based on where the request is coming from and
     * the current settings.
     */
    fun shouldAllowActiveUnlockFromOrigin(requestOrigin: ActiveUnlockRequestOrigin): Boolean {
        return when (requestOrigin) {
            ActiveUnlockRequestOrigin.WAKE -> requestActiveUnlockOnWakeup

            ActiveUnlockRequestOrigin.UNLOCK_INTENT ->
                requestActiveUnlockOnUnlockIntent || requestActiveUnlockOnWakeup ||
                        (shouldRequestActiveUnlockOnUnlockIntentFromBiometricEnrollment())

            ActiveUnlockRequestOrigin.BIOMETRIC_FAIL ->
                requestActiveUnlockOnBioFail || requestActiveUnlockOnUnlockIntent ||
                        requestActiveUnlockOnWakeup

            ActiveUnlockRequestOrigin.ASSISTANT -> isActiveUnlockEnabled()
        }
    }

    private fun shouldRequestActiveUnlockOnUnlockIntentFromBiometricEnrollment(): Boolean {
        if (!requestActiveUnlockOnBioFail) {
            return false
        }

        keyguardUpdateMonitor.get().let {
            val anyFaceEnrolled = it.isFaceEnabledAndEnrolled
            val anyFingerprintEnrolled = it.isUnlockWithFingerprintPossible(
                    selectedUserInteractor.getSelectedUserId())
            val udfpsEnrolled = it.isUdfpsEnrolled

            if (!anyFaceEnrolled && !anyFingerprintEnrolled) {
                return onUnlockIntentWhenBiometricEnrolled.contains(BiometricType.NONE.intValue)
            }

            if (!anyFaceEnrolled && anyFingerprintEnrolled) {
                return onUnlockIntentWhenBiometricEnrolled.contains(
                        BiometricType.ANY_FINGERPRINT.intValue) ||
                        (udfpsEnrolled && onUnlockIntentWhenBiometricEnrolled.contains(
                                BiometricType.UNDER_DISPLAY_FINGERPRINT.intValue))
            }

            if (!anyFingerprintEnrolled && anyFaceEnrolled) {
                return onUnlockIntentWhenBiometricEnrolled.contains(BiometricType.ANY_FACE.intValue)
            }
        }

        return false
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("Settings:")
        pw.println("   requestActiveUnlockOnWakeup=$requestActiveUnlockOnWakeup")
        pw.println("   requestActiveUnlockOnUnlockIntent=$requestActiveUnlockOnUnlockIntent")
        pw.println("   requestActiveUnlockOnBioFail=$requestActiveUnlockOnBioFail")

        val onUnlockIntentWhenBiometricEnrolledString =
            onUnlockIntentWhenBiometricEnrolled.map {
                for (biometricType in BiometricType.values()) {
                    if (biometricType.intValue == it) {
                        return@map biometricType.name
                    }
                }
                return@map "UNKNOWN"
            }
        pw.println("   requestActiveUnlockOnUnlockIntentWhenBiometricEnrolled=" +
                "$onUnlockIntentWhenBiometricEnrolledString")
        pw.println("   requestActiveUnlockOnFaceError=$faceErrorsToTriggerBiometricFailOn")
        pw.println("   requestActiveUnlockOnFaceAcquireInfo=" +
                "$faceAcquireInfoToTriggerBiometricFailOn")
        pw.println("   activeUnlockWakeupsConsideredUnlockIntents=${
            wakeupsConsideredUnlockIntents.map { PowerManager.wakeReasonToString(it) }
        }")
        pw.println("   activeUnlockFromWakeupsToAlwaysDismissKeyguard=${
            wakeupsToForceDismissKeyguard.map { PowerManager.wakeReasonToString(it) }
        }")

        pw.println("Current state:")
        keyguardUpdateMonitor.get().let {
            pw.println("   shouldRequestActiveUnlockOnUnlockIntentFromBiometricEnrollment=" +
                    "${shouldRequestActiveUnlockOnUnlockIntentFromBiometricEnrollment()}")
            pw.println("   isFaceEnabledAndEnrolled=${it.isFaceEnabledAndEnrolled}")
            pw.println("   fpUnlockPossible=${
                it.isUnlockWithFingerprintPossible(selectedUserInteractor.getSelectedUserId())}")
            pw.println("   udfpsEnrolled=${it.isUdfpsEnrolled}")
        }
    }
}