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

import android.app.StatusBarManager.SESSION_KEYGUARD
import android.content.Context
import android.hardware.biometrics.BiometricSourceType
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE
import com.android.keyguard.KeyguardBiometricLockoutLogger.PrimaryAuthRequiredEvent
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.SessionTracker
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Logs events when primary authentication requirements change. Primary authentication is considered
 * authentication using pin/pattern/password input.
 *
 * See [PrimaryAuthRequiredEvent] for all the events and their descriptions.
 */
@SysUISingleton
class KeyguardBiometricLockoutLogger @Inject constructor(
    context: Context?,
    private val uiEventLogger: UiEventLogger,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val sessionTracker: SessionTracker
) : CoreStartable(context) {
    private var fingerprintLockedOut = false
    private var faceLockedOut = false
    private var encryptedOrLockdown = false
    private var unattendedUpdate = false
    private var timeout = false

    override fun start() {
        mKeyguardUpdateMonitorCallback.onStrongAuthStateChanged(
                KeyguardUpdateMonitor.getCurrentUser())
        keyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback)
    }

    private val mKeyguardUpdateMonitorCallback: KeyguardUpdateMonitorCallback =
            object : KeyguardUpdateMonitorCallback() {
        override fun onLockedOutStateChanged(biometricSourceType: BiometricSourceType) {
            if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                val lockedOut = keyguardUpdateMonitor.isFingerprintLockedOut
                if (lockedOut && !fingerprintLockedOut) {
                    log(PrimaryAuthRequiredEvent.PRIMARY_AUTH_REQUIRED_FINGERPRINT_LOCKED_OUT)
                } else if (!lockedOut && fingerprintLockedOut) {
                    log(PrimaryAuthRequiredEvent.PRIMARY_AUTH_REQUIRED_FINGERPRINT_LOCKED_OUT_RESET)
                }
                fingerprintLockedOut = lockedOut
            } else if (biometricSourceType == BiometricSourceType.FACE) {
                val lockedOut = keyguardUpdateMonitor.isFaceLockedOut
                if (lockedOut && !faceLockedOut) {
                    log(PrimaryAuthRequiredEvent.PRIMARY_AUTH_REQUIRED_FACE_LOCKED_OUT)
                } else if (!lockedOut && faceLockedOut) {
                    log(PrimaryAuthRequiredEvent.PRIMARY_AUTH_REQUIRED_FACE_LOCKED_OUT_RESET)
                }
                faceLockedOut = lockedOut
            }
        }

        override fun onStrongAuthStateChanged(userId: Int) {
            if (userId != KeyguardUpdateMonitor.getCurrentUser()) {
                return
            }
            val strongAuthFlags = keyguardUpdateMonitor.strongAuthTracker
                    .getStrongAuthForUser(userId)

            val newEncryptedOrLockdown = keyguardUpdateMonitor.isEncryptedOrLockdown(userId)
            if (newEncryptedOrLockdown && !encryptedOrLockdown) {
                log(PrimaryAuthRequiredEvent.PRIMARY_AUTH_REQUIRED_ENCRYPTED_OR_LOCKDOWN)
            }
            encryptedOrLockdown = newEncryptedOrLockdown

            val newUnattendedUpdate = isUnattendedUpdate(strongAuthFlags)
            if (newUnattendedUpdate && !unattendedUpdate) {
                log(PrimaryAuthRequiredEvent.PRIMARY_AUTH_REQUIRED_UNATTENDED_UPDATE)
            }
            unattendedUpdate = newUnattendedUpdate

            val newTimeout = isStrongAuthTimeout(strongAuthFlags)
            if (newTimeout && !timeout) {
                log(PrimaryAuthRequiredEvent.PRIMARY_AUTH_REQUIRED_TIMEOUT)
            }
            timeout = newTimeout
        }
    }

    private fun isUnattendedUpdate(
        @LockPatternUtils.StrongAuthTracker.StrongAuthFlags flags: Int
    ) = containsFlag(flags, STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE)

    private fun isStrongAuthTimeout(
        @LockPatternUtils.StrongAuthTracker.StrongAuthFlags flags: Int
    ) = containsFlag(flags, STRONG_AUTH_REQUIRED_AFTER_TIMEOUT) ||
            containsFlag(flags, STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT)

    private fun log(event: PrimaryAuthRequiredEvent) =
            uiEventLogger.log(event, sessionTracker.getSessionId(SESSION_KEYGUARD))

    override fun dump(pw: PrintWriter, args: Array<String>) {
        pw.println("  mFingerprintLockedOut=$fingerprintLockedOut")
        pw.println("  mFaceLockedOut=$faceLockedOut")
        pw.println("  mIsEncryptedOrLockdown=$encryptedOrLockdown")
        pw.println("  mIsUnattendedUpdate=$unattendedUpdate")
        pw.println("  mIsTimeout=$timeout")
    }

    /**
     * Events pertaining to whether primary authentication (pin/pattern/password input) is required
     * for device entry.
     */
    @VisibleForTesting
    enum class PrimaryAuthRequiredEvent(private val mId: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Fingerprint cannot be used to authenticate for device entry. This" +
                "can persist until the next primary auth or may timeout.")
        PRIMARY_AUTH_REQUIRED_FINGERPRINT_LOCKED_OUT(924),

        @UiEvent(doc = "Fingerprint can be used to authenticate for device entry.")
        PRIMARY_AUTH_REQUIRED_FINGERPRINT_LOCKED_OUT_RESET(925),

        @UiEvent(doc = "Face cannot be used to authenticate for device entry.")
        PRIMARY_AUTH_REQUIRED_FACE_LOCKED_OUT(926),

        @UiEvent(doc = "Face can be used to authenticate for device entry.")
        PRIMARY_AUTH_REQUIRED_FACE_LOCKED_OUT_RESET(927),

        @UiEvent(doc = "Device is encrypted (ie: after reboot) or device is locked down by DPM " +
                "or a manual user lockdown.")
        PRIMARY_AUTH_REQUIRED_ENCRYPTED_OR_LOCKDOWN(928),

        @UiEvent(doc = "Primary authentication is required because it hasn't been used for a " +
                "time required by a device admin or because primary auth hasn't been used for a " +
                "time after a non-strong biometric (weak or convenience) is used to unlock the " +
                "device.")
        PRIMARY_AUTH_REQUIRED_TIMEOUT(929),

        @UiEvent(doc = "Strong authentication is required to prepare for unattended upgrade.")
        PRIMARY_AUTH_REQUIRED_UNATTENDED_UPDATE(931);

        override fun getId(): Int {
            return mId
        }
    }

    companion object {
        private fun containsFlag(strongAuthFlags: Int, flagCheck: Int): Boolean {
            return strongAuthFlags and flagCheck != 0
        }
    }
}