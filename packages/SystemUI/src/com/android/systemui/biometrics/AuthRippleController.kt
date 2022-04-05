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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PointF
import android.hardware.biometrics.BiometricFingerprintConstants
import android.hardware.biometrics.BiometricSourceType
import android.util.DisplayMetrics
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.animation.Interpolators
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.CircleReveal
import com.android.systemui.statusbar.LiftReveal
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent.CentralSurfacesScope
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.ViewController
import com.android.systemui.util.leak.RotationUtils
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Provider

/***
 * Controls two ripple effects:
 *   1. Unlocked ripple: shows when authentication is successful
 *   2. UDFPS dwell ripple: shows when the user has their finger down on the UDFPS area and reacts
 *   to errors and successes
 *
 * The ripple uses the accent color of the current theme.
 */
@CentralSurfacesScope
class AuthRippleController @Inject constructor(
    private val centralSurfaces: CentralSurfaces,
    private val sysuiContext: Context,
    private val authController: AuthController,
    private val configurationController: ConfigurationController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val keyguardStateController: KeyguardStateController,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val commandRegistry: CommandRegistry,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val bypassController: KeyguardBypassController,
    private val biometricUnlockController: BiometricUnlockController,
    private val udfpsControllerProvider: Provider<UdfpsController>,
    private val statusBarStateController: StatusBarStateController,
    rippleView: AuthRippleView?
) : ViewController<AuthRippleView>(rippleView), KeyguardStateController.Callback,
    WakefulnessLifecycle.Observer {

    @VisibleForTesting
    internal var startLightRevealScrimOnKeyguardFadingAway = false
    var fingerprintSensorLocation: PointF? = null
    private var faceSensorLocation: PointF? = null
    private var circleReveal: LightRevealEffect? = null

    private var udfpsController: UdfpsController? = null
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
        keyguardStateController.addCallback(this)
        wakefulnessLifecycle.addObserver(this)
        commandRegistry.registerCommand("auth-ripple") { AuthRippleCommand() }
    }

    @VisibleForTesting
    public override fun onViewDetached() {
        udfpsController?.removeCallback(udfpsControllerCallback)
        authController.removeCallback(authControllerCallback)
        keyguardUpdateMonitor.removeCallback(keyguardUpdateMonitorCallback)
        configurationController.removeCallback(configurationChangedListener)
        keyguardStateController.removeCallback(this)
        wakefulnessLifecycle.removeObserver(this)
        commandRegistry.unregisterCommand("auth-ripple")

        notificationShadeWindowController.setForcePluginOpen(false, this)
    }

    fun showUnlockRipple(biometricSourceType: BiometricSourceType?) {
        if (!(keyguardUpdateMonitor.isKeyguardVisible || keyguardUpdateMonitor.isDreaming) ||
            keyguardUpdateMonitor.userNeedsStrongAuth()) {
            return
        }

        updateSensorLocation()
        if (biometricSourceType == BiometricSourceType.FINGERPRINT &&
            fingerprintSensorLocation != null) {
            mView.setFingerprintSensorLocation(fingerprintSensorLocation!!, udfpsRadius)
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
        val lightRevealScrim = centralSurfaces.lightRevealScrim
        if (statusBarStateController.isDozing || biometricUnlockController.isWakeAndUnlock) {
            circleReveal?.let {
                lightRevealScrim?.revealEffect = it
                startLightRevealScrimOnKeyguardFadingAway = true
            }
        }

        mView.startUnlockedRipple(
            /* end runnable */
            Runnable {
                notificationShadeWindowController.setForcePluginOpen(false, this)
            }
        )
    }

    override fun onKeyguardFadingAwayChanged() {
        if (keyguardStateController.isKeyguardFadingAway) {
            val lightRevealScrim = centralSurfaces.lightRevealScrim
            if (startLightRevealScrimOnKeyguardFadingAway && lightRevealScrim != null) {
                ValueAnimator.ofFloat(.1f, 1f).apply {
                    interpolator = Interpolators.LINEAR_OUT_SLOW_IN
                    duration = RIPPLE_ANIMATION_DURATION
                    startDelay = keyguardStateController.keyguardFadingAwayDelay
                    addUpdateListener { animator ->
                        if (lightRevealScrim.revealEffect != circleReveal) {
                            // if something else took over the reveal, let's do nothing.
                            return@addUpdateListener
                        }
                        lightRevealScrim.revealAmount = animator.animatedValue as Float
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator?) {
                            // Reset light reveal scrim to the default, so the CentralSurfaces
                            // can handle any subsequent light reveal changes
                            // (ie: from dozing changes)
                            if (lightRevealScrim.revealEffect == circleReveal) {
                                lightRevealScrim.revealEffect = LiftReveal
                            }
                        }
                    })
                    start()
                }
                startLightRevealScrimOnKeyguardFadingAway = false
            }
        }
    }

    override fun onStartedGoingToSleep() {
        // reset the light reveal start in case we were pending an unlock
        startLightRevealScrimOnKeyguardFadingAway = false
    }

    fun updateSensorLocation() {
        updateFingerprintLocation()
        faceSensorLocation = authController.faceAuthSensorLocation
        fingerprintSensorLocation?.let {
            circleReveal = CircleReveal(
                it.x,
                it.y,
                0f,
                Math.max(
                    Math.max(it.x, centralSurfaces.displayWidth - it.x),
                    Math.max(it.y, centralSurfaces.displayHeight - it.y)
                )
            )
        }
    }

    private fun updateFingerprintLocation() {
        val displayMetrics = DisplayMetrics()
        sysuiContext.display?.getRealMetrics(displayMetrics)
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        authController.fingerprintSensorLocation?.let {
            fingerprintSensorLocation = when (RotationUtils.getRotation(sysuiContext)) {
                RotationUtils.ROTATION_LANDSCAPE -> {
                    val normalizedYPos: Float = it.y / width
                    val normalizedXPos: Float = it.x / height
                    PointF(width * normalizedYPos, height * (1 - normalizedXPos))
                }
                RotationUtils.ROTATION_UPSIDE_DOWN -> {
                    PointF(width - it.x, height - it.y)
                }
                RotationUtils.ROTATION_SEASCAPE -> {
                    val normalizedYPos: Float = it.y / width
                    val normalizedXPos: Float = it.x / height
                    PointF(width * (1 - normalizedYPos), height * normalizedXPos)
                }
                else -> {
                    // ROTATION_NONE
                    PointF(it.x, it.y)
                }
            }
        }
    }

    private fun updateRippleColor() {
        mView.setLockScreenColor(Utils.getColorAttrDefaultColor(sysuiContext,
                R.attr.wallpaperTextColorAccent))
    }

    private fun showDwellRipple() {
        mView.startDwellRipple(statusBarStateController.isDozing)
    }

    private val keyguardUpdateMonitorCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onBiometricAuthenticated(
                userId: Int,
                biometricSourceType: BiometricSourceType?,
                isStrongBiometric: Boolean
            ) {
                if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                    mView.fadeDwellRipple()
                }
                showUnlockRipple(biometricSourceType)
            }

        override fun onBiometricAuthFailed(biometricSourceType: BiometricSourceType?) {
            if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                mView.retractDwellRipple()
            }
        }

        override fun onBiometricAcquired(
            biometricSourceType: BiometricSourceType?,
            acquireInfo: Int
        ) {
            if (biometricSourceType == BiometricSourceType.FINGERPRINT &&
                    BiometricFingerprintConstants.shouldTurnOffHbm(acquireInfo) &&
                    acquireInfo != BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD) {
                // received an 'acquiredBad' message, so immediately retract
                mView.retractDwellRipple()
            }
        }

        override fun onKeyguardBouncerStateChanged(bouncerIsOrWillBeShowing: Boolean) {
            if (bouncerIsOrWillBeShowing) {
                mView.fadeDwellRipple()
            }
        }
    }

    private val configurationChangedListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onUiModeChanged() {
                updateRippleColor()
            }
            override fun onThemeChanged() {
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

                mView.setFingerprintSensorLocation(fingerprintSensorLocation!!, udfpsRadius)
                showDwellRipple()
            }

            override fun onFingerUp() {
                mView.retractDwellRipple()
            }
        }

    private val authControllerCallback =
        object : AuthController.Callback {
            override fun onAllAuthenticatorsRegistered() {
                updateUdfpsDependentParams()
                updateSensorLocation()
            }

            override fun onEnrollmentsChanged() {
            }
        }

    private fun updateUdfpsDependentParams() {
        authController.udfpsProps?.let {
            if (it.size > 0) {
                udfpsRadius = it[0].location.sensorRadius.toFloat()
                udfpsController = udfpsControllerProvider.get()

                if (mView.isAttachedToWindow) {
                    udfpsController?.addCallback(udfpsControllerCallback)
                }
            }
        }
    }

    inner class AuthRippleCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            if (args.isEmpty()) {
                invalidCommand(pw)
            } else {
                when (args[0]) {
                    "dwell" -> {
                        showDwellRipple()
                        pw.println("lock screen dwell ripple: " +
                                "\n\tsensorLocation=$fingerprintSensorLocation" +
                                "\n\tudfpsRadius=$udfpsRadius")
                    }
                    "fingerprint" -> {
                        updateSensorLocation()
                        pw.println("fingerprint ripple sensorLocation=$fingerprintSensorLocation")
                        showUnlockRipple(BiometricSourceType.FINGERPRINT)
                    }
                    "face" -> {
                        updateSensorLocation()
                        pw.println("face ripple sensorLocation=$faceSensorLocation")
                        showUnlockRipple(BiometricSourceType.FACE)
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

    companion object {
        const val RIPPLE_ANIMATION_DURATION: Long = 1533
    }
}
