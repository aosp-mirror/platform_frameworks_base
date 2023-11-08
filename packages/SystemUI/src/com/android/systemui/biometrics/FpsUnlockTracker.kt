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

package com.android.systemui.biometrics

import android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START
import android.hardware.biometrics.BiometricSourceType
import android.hardware.biometrics.BiometricSourceType.FINGERPRINT
import android.util.Log
import com.android.app.tracing.TraceStateLogger
import com.android.internal.util.LatencyTracker
import com.android.internal.util.LatencyTracker.ACTION_KEYGUARD_FPS_UNLOCK_TO_HOME
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.KeyguardUnlockAnimationController
import com.android.systemui.keyguard.KeyguardUnlockAnimationController.KeyguardUnlockAnimationListener
import com.android.systemui.plugins.statusbar.StatusBarStateController
import javax.inject.Inject

private const val TAG = "FpsUnlockTracker"
private const val TRACE_COUNTER_NAME = "FpsUnlockStage"
private const val TRACE_TAG_AOD = "AOD"
private const val TRACE_TAG_KEYGUARD = "KEYGUARD"
private const val DEBUG = true

/** This is a class for monitoring unlock latency of fps and logging stages in perfetto. */
@SysUISingleton
class FpsUnlockTracker
@Inject
constructor(
    private val statusBarStateController: StatusBarStateController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val keyguardUnlockAnimationController: KeyguardUnlockAnimationController,
    private val latencyTracker: LatencyTracker,
) {
    private val fpsTraceStateLogger = TraceStateLogger(TRACE_COUNTER_NAME)
    private var fpsAuthenticated: Boolean = false

    private val keyguardUpdateMonitorCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onBiometricAcquired(
                biometricSourceType: BiometricSourceType?,
                acquireInfo: Int
            ) {
                if (keyguardUpdateMonitor.isUnlockingWithFingerprintAllowed) {
                    onHalAuthenticationStage(acquireInfo)
                }
            }

            override fun onBiometricAuthenticated(
                userId: Int,
                biometricSourceType: BiometricSourceType?,
                isStrongBiometric: Boolean
            ) {
                if (biometricSourceType == FINGERPRINT) {
                    fpsAuthenticated = true
                    onExitKeyguard()
                }
            }

            override fun onBiometricError(
                msgId: Int,
                errString: String?,
                biometricSourceType: BiometricSourceType?
            ) {
                if (biometricSourceType == FINGERPRINT) {
                    latencyTracker.onActionCancel(ACTION_KEYGUARD_FPS_UNLOCK_TO_HOME)
                }
            }

            override fun onBiometricRunningStateChanged(
                running: Boolean,
                biometricSourceType: BiometricSourceType?
            ) {
                if (biometricSourceType != FINGERPRINT || !running) {
                    return
                }
                onWaitForAuthenticationStage()
            }
        }

    private val keyguardUnlockAnimationListener =
        object : KeyguardUnlockAnimationListener {
            override fun onUnlockAnimationFinished() = onUnlockedStage()
        }

    /** Start tracking the fps unlock. */
    fun startTracking() {
        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
        keyguardUnlockAnimationController.addKeyguardUnlockAnimationListener(
            keyguardUnlockAnimationListener
        )
    }

    /** Stop tracking the fps unlock. */
    fun stopTracking() {
        keyguardUpdateMonitor.removeCallback(keyguardUpdateMonitorCallback)
        keyguardUnlockAnimationController.removeKeyguardUnlockAnimationListener(
            keyguardUnlockAnimationListener
        )
    }

    /**
     * The stage when the devices is locked and is possible to be unlocked via fps. However, in some
     * situations, it might be unlocked only via bouncer.
     */
    fun onWaitForAuthenticationStage() {
        val stage =
            if (keyguardUpdateMonitor.isUnlockingWithFingerprintAllowed)
                FpsUnlockStage.WAIT_FOR_AUTHENTICATION.name
            else FpsUnlockStage.WAIT_FOR_AUTHENTICATION.name + "(Not allowed)"
        fpsTraceStateLogger.log(stage)
        if (DEBUG) {
            Log.d(TAG, "onWaitForAuthenticationStage: stage=$stage")
        }
    }

    /**
     * The stage dedicated to UDFPS, SFPS should not enter this stage. The only place where invokes
     * this function is UdfpsController#onFingerDown.
     */
    fun onUiReadyStage() {
        if (!keyguardUpdateMonitor.isUdfpsSupported || !keyguardUpdateMonitor.isUdfpsEnrolled) {
            return
        }
        fpsTraceStateLogger.log(FpsUnlockStage.UI_READY.name)
        startLatencyTracker()
        if (DEBUG) {
            Log.d(TAG, "onUiReadyStage: dozing=${statusBarStateController.isDozing}")
        }
    }

    /** The stage when the HAL is authenticating the fingerprint. */
    fun onHalAuthenticationStage(acquire: Int) {
        fpsTraceStateLogger.log("${FpsUnlockStage.HAL_AUTHENTICATION.name}($acquire)")
        // Start latency tracker here only for SFPS, UDFPS should start at onUiReadyStage.
        if (
            keyguardUpdateMonitor.isSfpsSupported &&
                keyguardUpdateMonitor.isSfpsEnrolled &&
                acquire == FINGERPRINT_ACQUIRED_START
        ) {
            startLatencyTracker()
        }
        if (DEBUG) {
            Log.d(
                TAG,
                "onHalAuthenticationStage: acquire=$acquire" +
                    ", sfpsSupported=${keyguardUpdateMonitor.isSfpsSupported}" +
                    ", sfpsEnrolled=${keyguardUpdateMonitor.isSfpsEnrolled}"
            )
        }
    }

    /** The stage when the authentication is succeeded and is going to exit keyguard. */
    fun onExitKeyguard() {
        fpsTraceStateLogger.log(FpsUnlockStage.EXIT_KEYGUARD.name)
        if (DEBUG) {
            Log.d(TAG, "onExitKeyguard: fpsAuthenticated=$fpsAuthenticated")
        }
    }

    /**
     * The stage when the unlock animation is finished which means the user can start interacting
     * with the device.
     */
    fun onUnlockedStage() {
        fpsTraceStateLogger.log(FpsUnlockStage.UNLOCKED.name)
        if (fpsAuthenticated) {
            // The device is unlocked successfully via fps, end the instrument.
            latencyTracker.onActionEnd(ACTION_KEYGUARD_FPS_UNLOCK_TO_HOME)
        } else {
            // The device is unlocked but not via fps, maybe bouncer? Cancel the instrument.
            latencyTracker.onActionCancel(ACTION_KEYGUARD_FPS_UNLOCK_TO_HOME)
        }
        if (DEBUG) {
            Log.d(TAG, "onUnlockedStage: fpsAuthenticated=$fpsAuthenticated")
        }
        fpsAuthenticated = false
    }

    private fun startLatencyTracker() {
        latencyTracker.onActionCancel(ACTION_KEYGUARD_FPS_UNLOCK_TO_HOME)
        val tag = if (statusBarStateController.isDozing) TRACE_TAG_AOD else TRACE_TAG_KEYGUARD
        latencyTracker.onActionStart(ACTION_KEYGUARD_FPS_UNLOCK_TO_HOME, tag)
    }
}

private enum class FpsUnlockStage {
    WAIT_FOR_AUTHENTICATION,
    UI_READY,
    HAL_AUTHENTICATION,
    EXIT_KEYGUARD,
    UNLOCKED
}
