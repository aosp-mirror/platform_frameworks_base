/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import android.hardware.biometrics.BiometricSourceType
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.statusbar.CircleReveal
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.StatusBar
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent.StatusBarScope
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.ViewController
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Provider
import com.android.systemui.plugins.statusbar.StatusBarStateController

/***
 * Controls the ripple effect that shows when authentication is successful.
 * The ripple uses the accent color of the current theme.
 */
@StatusBarScope
class AuthRippleController @Inject constructor(
    private val statusBar: StatusBar,
    private val sysuiContext: Context,
    private val authController: AuthController,
    private val configurationController: ConfigurationController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val commandRegistry: CommandRegistry,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val bypassController: KeyguardBypassController,
    private val biometricUnlockController: BiometricUnlockController,
    private val udfpsControllerProvider: Provider<UdfpsController>,
    private val statusBarStateController: StatusBarStateController,
    rippleView: AuthRippleView?
) : ViewController<AuthRippleView>(rippleView) {
    var fingerprintSensorLocation: PointF? = null
    private var faceSensorLocation: PointF? = null
    private var circleReveal: LightRevealEffect? = null

    private var udfpsController: UdfpsController? = null

    private var dwellScale = 2f
    private var expandedDwellScale = 2.5f
    private var aodDwellScale = 1.9f
    private var aodExpandedDwellScale = 2.3f
    private var udfpsRadius: Float = -1f

    override fun onInit() {
        mView.setAlphaInDuration(sysuiContext.resources.getInteger(
                R.integer.auth_ripple_alpha_in_duration).toLong())
    }

    @VisibleForTesting
    public override fun onViewAttached() {
        authController.addCallback(authControllerCallback)
        updateRippleColor()
        updateSensorLocation()
        updateUdfpsDependentParams()
        udfpsController?.addCallback(udfpsControllerCallback)
        configurationController.addCallback(configurationChangedListener)
        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
        commandRegistry.registerCommand("auth-ripple") { AuthRippleCommand() }
    }

    @VisibleForTesting
    public override fun onViewDetached() {
        udfpsController?.removeCallback(udfpsControllerCallback)
        authController.removeCallback(authControllerCallback)
        keyguardUpdateMonitor.removeCallback(keyguardUpdateMonitorCallback)
        configurationController.removeCallback(configurationChangedListener)
        commandRegistry.unregisterCommand("auth-ripple")

        notificationShadeWindowController.setForcePluginOpen(false, this)
    }

    fun showRipple(biometricSourceType: BiometricSourceType?) {
        if (!keyguardUpdateMonitor.isKeyguardVisible ||
            keyguardUpdateMonitor.userNeedsStrongAuth()) {
            return
        }

        if (biometricSourceType == BiometricSourceType.FINGERPRINT &&
            fingerprintSensorLocation != null) {
            mView.setSensorLocation(fingerprintSensorLocation!!)
            showUnlockedRipple()
        } else if (biometricSourceType == BiometricSourceType.FACE &&
            faceSensorLocation != null) {
            if (!bypassController.canBypass()) {
                return
            }
            mView.setSensorLocation(faceSensorLocation!!)
            showUnlockedRipple()
        }
    }

    private fun showUnlockedRipple() {
        notificationShadeWindowController.setForcePluginOpen(true, this)
        val biometricUnlockMode = biometricUnlockController.mode
        val useCircleReveal = circleReveal != null &&
            (biometricUnlockMode == BiometricUnlockController.MODE_WAKE_AND_UNLOCK ||
                biometricUnlockMode == BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING ||
                biometricUnlockMode == BiometricUnlockController.MODE_WAKE_AND_UNLOCK_FROM_DREAM)
        val lightRevealScrim = statusBar.lightRevealScrim
        if (useCircleReveal) {
            lightRevealScrim?.revealEffect = circleReveal!!
        }

        mView.startUnlockedRipple(
            /* end runnable */
            Runnable {
                notificationShadeWindowController.setForcePluginOpen(false, this)
            },
            /* circleReveal */
            if (useCircleReveal) {
                lightRevealScrim
            } else {
                null
            }
        )
    }

    fun updateSensorLocation() {
        fingerprintSensorLocation = authController.fingerprintSensorLocation
        faceSensorLocation = authController.faceAuthSensorLocation
        fingerprintSensorLocation?.let {
            circleReveal = CircleReveal(
                it.x,
                it.y,
                0f,
                Math.max(
                    Math.max(it.x, statusBar.displayWidth - it.x),
                    Math.max(it.y, statusBar.displayHeight - it.y)
                )
            )
        }
    }

    private fun updateRippleColor() {
        mView.setColor(
            Utils.getColorAttr(sysuiContext, android.R.attr.colorAccent).defaultColor)
    }

    private fun showDwellRipple() {
        if (statusBarStateController.isDozing) {
            mView.startDwellRipple(
                    /* startRadius */ udfpsRadius,
                    /* endRadius */ udfpsRadius * aodDwellScale,
                    /* expandedRadius */ udfpsRadius * aodExpandedDwellScale,
                    /* isDozing */ true)
        } else {
            mView.startDwellRipple(
                    /* startRadius */ udfpsRadius,
                    /* endRadius */ udfpsRadius * dwellScale,
                    /* expandedRadius */ udfpsRadius * expandedDwellScale,
                    /* isDozing */ false)
        }
    }

    private val keyguardUpdateMonitorCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onBiometricAuthenticated(
                userId: Int,
                biometricSourceType: BiometricSourceType?,
                isStrongBiometric: Boolean
            ) {
                showRipple(biometricSourceType)
            }

        override fun onBiometricAuthFailed(biometricSourceType: BiometricSourceType?) {
            mView.retractRipple()
        }
    }

    private val configurationChangedListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onConfigChanged(newConfig: Configuration?) {
                updateSensorLocation()
            }
            override fun onUiModeChanged() {
                updateRippleColor()
            }
            override fun onThemeChanged() {
                updateRippleColor()
            }
            override fun onOverlayChanged() {
                updateRippleColor()
            }
    }

    private val udfpsControllerCallback =
        object : UdfpsController.Callback {
            override fun onFingerDown() {
                if (fingerprintSensorLocation == null) {
                    Log.e("AuthRipple", "fingerprintSensorLocation=null onFingerDown. " +
                            "Skip showing dwell ripple")
                    return
                }

                mView.setSensorLocation(fingerprintSensorLocation!!)
                showDwellRipple()
            }

            override fun onFingerUp() {
                mView.retractRipple()
            }
        }

    private val authControllerCallback = AuthController.Callback {
        updateSensorLocation()
        updateUdfpsDependentParams()
    }

    private fun updateUdfpsDependentParams() {
        authController.udfpsProps?.let {
            if (it.size > 0) {
                udfpsRadius = it[0].sensorRadius.toFloat()
                udfpsController = udfpsControllerProvider.get()

                if (mView.isAttachedToWindow) {
                    udfpsController?.addCallback(udfpsControllerCallback)
                }
            }
        }
    }

    inner class AuthRippleCommand : Command {
        fun printLockScreenDwellInfo(pw: PrintWriter) {
            pw.println("lock screen dwell ripple: " +
                    "\n\tsensorLocation=$fingerprintSensorLocation" +
                    "\n\tdwellScale=$dwellScale" +
                    "\n\tdwellExpand=$expandedDwellScale")
        }

        fun printAodDwellInfo(pw: PrintWriter) {
            pw.println("aod dwell ripple: " +
                    "\n\tsensorLocation=$fingerprintSensorLocation" +
                    "\n\tdwellScale=$aodDwellScale" +
                    "\n\tdwellExpand=$aodExpandedDwellScale")
        }

        override fun execute(pw: PrintWriter, args: List<String>) {
            if (args.isEmpty()) {
                invalidCommand(pw)
            } else {
                when (args[0]) {
                    "dwell" -> {
                        showDwellRipple()
                        if (statusBarStateController.isDozing) {
                            printAodDwellInfo(pw)
                        } else {
                            printLockScreenDwellInfo(pw)
                        }
                    }
                    "fingerprint" -> {
                        pw.println("fingerprint ripple sensorLocation=$fingerprintSensorLocation")
                        showRipple(BiometricSourceType.FINGERPRINT)
                    }
                    "face" -> {
                        pw.println("face ripple sensorLocation=$faceSensorLocation")
                        showRipple(BiometricSourceType.FACE)
                    }
                    "custom" -> {
                        if (args.size != 3 ||
                            args[1].toFloatOrNull() == null ||
                            args[2].toFloatOrNull() == null) {
                            invalidCommand(pw)
                            return
                        }
                        pw.println("custom ripple sensorLocation=" + args[1].toFloat() + ", " +
                            args[2].toFloat())
                        mView.setSensorLocation(PointF(args[1].toFloat(), args[2].toFloat()))
                        showUnlockedRipple()
                    }
                    else -> invalidCommand(pw)
                }
            }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar auth-ripple <command>")
            pw.println("Available commands:")
            pw.println("  dwell")
            pw.println("  fingerprint")
            pw.println("  face")
            pw.println("  custom <x-location: int> <y-location: int>")
        }

        fun invalidCommand(pw: PrintWriter) {
            pw.println("invalid command")
            help(pw)
        }
    }
}
