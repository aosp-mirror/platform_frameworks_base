/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone

import android.annotation.IntDef
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricSourceType
import android.provider.Settings
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.stack.StackScrollAlgorithm
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.tuner.TunerService
import java.io.FileDescriptor
import java.io.PrintWriter
import javax.inject.Inject

@SysUISingleton
open class KeyguardBypassController : Dumpable, StackScrollAlgorithm.BypassController {

    private val mKeyguardStateController: KeyguardStateController
    private val statusBarStateController: StatusBarStateController
    @BypassOverride private val bypassOverride: Int
    private var hasFaceFeature: Boolean
    private var pendingUnlock: PendingUnlock? = null
    var userHasDeviceEntryIntent: Boolean = false // ie: attempted udfps auth

    @IntDef(
        FACE_UNLOCK_BYPASS_NO_OVERRIDE,
        FACE_UNLOCK_BYPASS_ALWAYS,
        FACE_UNLOCK_BYPASS_NEVER
    )
    @Retention(AnnotationRetention.SOURCE)
    private annotation class BypassOverride

    /**
     * Pending unlock info:
     *
     * The pending unlock type which is set if the bypass was blocked when it happened.
     *
     * Whether the pending unlock type is strong biometric or non-strong biometric
     * (i.e. weak or convenience).
     */
    private data class PendingUnlock(
        val pendingUnlockType: BiometricSourceType,
        val isStrongBiometric: Boolean
    )

    lateinit var unlockController: BiometricUnlockController
    var isPulseExpanding = false

    /** delegates to [bypassEnabled] but conforms to [StackScrollAlgorithm.BypassController] */
    override fun isBypassEnabled() = bypassEnabled

    /**
     * If face unlock dismisses the lock screen or keeps user on keyguard for the current user.
     */
    var bypassEnabled: Boolean = false
        get() {
            val enabled = when (bypassOverride) {
                FACE_UNLOCK_BYPASS_ALWAYS -> true
                FACE_UNLOCK_BYPASS_NEVER -> false
                else -> field
            }
            return enabled && mKeyguardStateController.isFaceAuthEnabled
        }
        private set

    var bouncerShowing: Boolean = false
    var altBouncerShowing: Boolean = false
    var launchingAffordance: Boolean = false
    var qSExpanded = false
        set(value) {
            val changed = field != value
            field = value
            if (changed && !value) {
                maybePerformPendingUnlock()
            }
        }

    @Inject
    constructor(
        context: Context,
        tunerService: TunerService,
        statusBarStateController: StatusBarStateController,
        lockscreenUserManager: NotificationLockscreenUserManager,
        keyguardStateController: KeyguardStateController,
        dumpManager: DumpManager
    ) {
        this.mKeyguardStateController = keyguardStateController
        this.statusBarStateController = statusBarStateController

        bypassOverride = context.resources.getInteger(R.integer.config_face_unlock_bypass_override)

        hasFaceFeature = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FACE)
        if (!hasFaceFeature) {
            return
        }

        dumpManager.registerDumpable("KeyguardBypassController", this)
        statusBarStateController.addCallback(object : StatusBarStateController.StateListener {
            override fun onStateChanged(newState: Int) {
                if (newState != StatusBarState.KEYGUARD) {
                    pendingUnlock = null
                }
            }
        })

        val dismissByDefault = if (context.resources.getBoolean(
                        com.android.internal.R.bool.config_faceAuthDismissesKeyguard)) 1 else 0
        tunerService.addTunable(object : TunerService.Tunable {
            override fun onTuningChanged(key: String?, newValue: String?) {
                bypassEnabled = tunerService.getValue(key, dismissByDefault) != 0
            }
        }, Settings.Secure.FACE_UNLOCK_DISMISSES_KEYGUARD)
        lockscreenUserManager.addUserChangedListener(
                object : NotificationLockscreenUserManager.UserChangedListener {
                    override fun onUserChanged(userId: Int) {
                        pendingUnlock = null
                    }
                })
    }

    /**
     * Notify that the biometric unlock has happened.
     *
     * @return false if we can not wake and unlock right now
     */
    fun onBiometricAuthenticated(
        biometricSourceType: BiometricSourceType,
        isStrongBiometric: Boolean
    ): Boolean {
        if (biometricSourceType == BiometricSourceType.FACE && bypassEnabled) {
            val can = canBypass()
            if (!can && (isPulseExpanding || qSExpanded)) {
                pendingUnlock = PendingUnlock(biometricSourceType, isStrongBiometric)
            }
            return can
        }
        return true
    }

    fun maybePerformPendingUnlock() {
        if (pendingUnlock != null) {
            if (onBiometricAuthenticated(pendingUnlock!!.pendingUnlockType,
                            pendingUnlock!!.isStrongBiometric)) {
                unlockController.startWakeAndUnlock(pendingUnlock!!.pendingUnlockType,
                        pendingUnlock!!.isStrongBiometric)
                pendingUnlock = null
            }
        }
    }

    /**
     * If keyguard can be dismissed because of bypass.
     */
    fun canBypass(): Boolean {
        if (bypassEnabled) {
            return when {
                bouncerShowing -> true
                altBouncerShowing -> true
                statusBarStateController.state != StatusBarState.KEYGUARD -> false
                launchingAffordance -> false
                isPulseExpanding || qSExpanded -> false
                else -> true
            }
        }
        return false
    }

    /**
     * If shorter animations should be played when unlocking.
     */
    fun canPlaySubtleWindowAnimations(): Boolean {
        if (bypassEnabled) {
            return when {
                statusBarStateController.state != StatusBarState.KEYGUARD -> false
                qSExpanded -> false
                else -> true
            }
        }
        return false
    }

    fun onStartedGoingToSleep() {
        pendingUnlock = null
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.println("KeyguardBypassController:")
        if (pendingUnlock != null) {
            pw.println("  mPendingUnlock.pendingUnlockType: ${pendingUnlock!!.pendingUnlockType}")
            pw.println("  mPendingUnlock.isStrongBiometric: ${pendingUnlock!!.isStrongBiometric}")
        } else {
            pw.println("  mPendingUnlock: $pendingUnlock")
        }
        pw.println("  bypassEnabled: $bypassEnabled")
        pw.println("  canBypass: ${canBypass()}")
        pw.println("  bouncerShowing: $bouncerShowing")
        pw.println("  altBouncerShowing: $altBouncerShowing")
        pw.println("  isPulseExpanding: $isPulseExpanding")
        pw.println("  launchingAffordance: $launchingAffordance")
        pw.println("  qSExpanded: $qSExpanded")
        pw.println("  hasFaceFeature: $hasFaceFeature")
        pw.println("  userHasDeviceEntryIntent: $userHasDeviceEntryIntent")
    }

    companion object {
        const val BYPASS_FADE_DURATION = 67

        private const val FACE_UNLOCK_BYPASS_NO_OVERRIDE = 0
        private const val FACE_UNLOCK_BYPASS_ALWAYS = 1
        private const val FACE_UNLOCK_BYPASS_NEVER = 2
    }
}
