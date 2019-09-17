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

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricSourceType
import android.provider.Settings
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.tuner.TunerService
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyguardBypassController {

    private val mKeyguardStateController: KeyguardStateController
    private val statusBarStateController: StatusBarStateController
    private var hasFaceFeature: Boolean

    /**
     * The pending unlock type which is set if the bypass was blocked when it happened.
     */
    private var pendingUnlockType: BiometricSourceType? = null

    lateinit var unlockController: BiometricUnlockController
    var isPulseExpanding = false

    /**
     * If face unlock dismisses the lock screen or keeps user on keyguard for the current user.
     */
    var bypassEnabled: Boolean = false
        get() = field && mKeyguardStateController.isFaceAuthEnabled
        private set

    var bouncerShowing: Boolean = false
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
        keyguardStateController: KeyguardStateController
    ) {
        this.mKeyguardStateController = keyguardStateController
        this.statusBarStateController = statusBarStateController

        hasFaceFeature = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FACE)
        if (!hasFaceFeature) {
            return
        }

        statusBarStateController.addCallback(object : StatusBarStateController.StateListener {
            override fun onStateChanged(newState: Int) {
                if (newState != StatusBarState.KEYGUARD) {
                    pendingUnlockType = null
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
        lockscreenUserManager.addUserChangedListener { pendingUnlockType = null }
    }

    /**
     * Notify that the biometric unlock has happened.
     *
     * @return false if we can not wake and unlock right now
     */
    fun onBiometricAuthenticated(biometricSourceType: BiometricSourceType): Boolean {
        if (bypassEnabled) {
            val can = canBypass()
            if (!can && (isPulseExpanding || qSExpanded)) {
                pendingUnlockType = biometricSourceType
            }
            return can
        }
        return true
    }

    fun maybePerformPendingUnlock() {
        if (pendingUnlockType != null) {
            if (onBiometricAuthenticated(pendingUnlockType!!)) {
                unlockController.startWakeAndUnlock(pendingUnlockType)
                pendingUnlockType = null
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
        pendingUnlockType = null
    }

    fun dump(pw: PrintWriter) {
        pw.println("KeyguardBypassController:")
        pw.print("  pendingUnlockType: "); pw.println(pendingUnlockType)
        pw.print("  bypassEnabled: "); pw.println(bypassEnabled)
        pw.print("  canBypass: "); pw.println(canBypass())
        pw.print("  bouncerShowing: "); pw.println(bouncerShowing)
        pw.print("  isPulseExpanding: "); pw.println(isPulseExpanding)
        pw.print("  launchingAffordance: "); pw.println(launchingAffordance)
        pw.print("  qSExpanded: "); pw.println(qSExpanded)
        pw.print("  hasFaceFeature: "); pw.println(hasFaceFeature)
    }

    companion object {
        const val BYPASS_PANEL_FADE_DURATION = 67
    }
}
