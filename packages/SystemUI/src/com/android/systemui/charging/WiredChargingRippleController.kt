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

package com.android.systemui.charging

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.SystemProperties
import android.view.Surface
import android.view.View
import android.view.WindowManager
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.surfaceeffects.ripple.RippleView
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow

private const val MAX_DEBOUNCE_LEVEL = 3
private const val BASE_DEBOUNCE_TIME = 2000

/***
 * Controls the ripple effect that shows when wired charging begins.
 * The ripple uses the accent color of the current theme.
 */
@SysUISingleton
class WiredChargingRippleController @Inject constructor(
    commandRegistry: CommandRegistry,
    private val batteryController: BatteryController,
    private val configurationController: ConfigurationController,
    featureFlags: FeatureFlags,
    private val context: Context,
    private val windowManager: WindowManager,
    private val systemClock: SystemClock,
    private val uiEventLogger: UiEventLogger
) {
    private var pluggedIn: Boolean = false
    private val rippleEnabled: Boolean = featureFlags.isEnabled(Flags.CHARGING_RIPPLE) &&
            !SystemProperties.getBoolean("persist.debug.suppress-charging-ripple", false)
    private var normalizedPortPosX: Float = context.resources.getFloat(
            R.dimen.physical_charger_port_location_normalized_x)
    private var normalizedPortPosY: Float = context.resources.getFloat(
            R.dimen.physical_charger_port_location_normalized_y)
    private val windowLayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        format = PixelFormat.TRANSLUCENT
        type = WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG
        fitInsetsTypes = 0 // Ignore insets from all system bars
        title = "Wired Charging Animation"
        flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        setTrustedOverlay()
    }
    private var lastTriggerTime: Long? = null
    private var debounceLevel = 0

    @VisibleForTesting
    var rippleView: RippleView = RippleView(context, attrs = null).also { it.setupShader() }

    init {
        pluggedIn = batteryController.isPluggedIn
        commandRegistry.registerCommand("charging-ripple") { ChargingRippleCommand() }
        updateRippleColor()
    }

    fun registerCallbacks() {
        val batteryStateChangeCallback = object : BatteryController.BatteryStateChangeCallback {
            override fun onBatteryLevelChanged(
                level: Int,
                nowPluggedIn: Boolean,
                charging: Boolean
            ) {
                // Suppresses the ripple when the state change comes from wireless charging or
                // its dock.
                if (batteryController.isPluggedInWireless ||
                        batteryController.isChargingSourceDock) {
                    return
                }

                if (!pluggedIn && nowPluggedIn) {
                    startRippleWithDebounce()
                }
                pluggedIn = nowPluggedIn
            }
        }
        batteryController.addCallback(batteryStateChangeCallback)

        val configurationChangedListener = object : ConfigurationController.ConfigurationListener {
            override fun onUiModeChanged() {
                updateRippleColor()
            }
            override fun onThemeChanged() {
                updateRippleColor()
            }

            override fun onConfigChanged(newConfig: Configuration?) {
                normalizedPortPosX = context.resources.getFloat(
                        R.dimen.physical_charger_port_location_normalized_x)
                normalizedPortPosY = context.resources.getFloat(
                        R.dimen.physical_charger_port_location_normalized_y)
            }
        }
        configurationController.addCallback(configurationChangedListener)
    }

    // Lazily debounce ripple to avoid triggering ripple constantly (e.g. from flaky chargers).
    internal fun startRippleWithDebounce() {
        val now = systemClock.elapsedRealtime()
        // Debounce wait time = 2 ^ debounce level
        if (lastTriggerTime == null ||
                (now - lastTriggerTime!!) > BASE_DEBOUNCE_TIME * (2.0.pow(debounceLevel))) {
            // Not waiting for debounce. Start ripple.
            startRipple()
            debounceLevel = 0
        } else {
            // Still waiting for debounce. Ignore ripple and bump debounce level.
            debounceLevel = min(MAX_DEBOUNCE_LEVEL, debounceLevel + 1)
        }
        lastTriggerTime = now
    }

    fun startRipple() {
        if (rippleView.rippleInProgress() || rippleView.parent != null) {
            // Skip if ripple is still playing, or not playing but already added the parent
            // (which might happen just before the animation starts or right after
            // the animation ends.)
            return
        }
        windowLayoutParams.packageName = context.opPackageName
        rippleView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View?) {}

            override fun onViewAttachedToWindow(view: View?) {
                layoutRipple()
                rippleView.startRipple(Runnable {
                    windowManager.removeView(rippleView)
                })
                rippleView.removeOnAttachStateChangeListener(this)
            }
        })
        windowManager.addView(rippleView, windowLayoutParams)
        uiEventLogger.log(WiredChargingRippleEvent.CHARGING_RIPPLE_PLAYED)
    }

    private fun layoutRipple() {
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val maxDiameter = Integer.max(width, height) * 2f
        rippleView.setMaxSize(maxDiameter, maxDiameter)
        when (context.display.rotation) {
            Surface.ROTATION_0 -> {
                rippleView.setCenter(
                        width * normalizedPortPosX, height * normalizedPortPosY)
            }
            Surface.ROTATION_90 -> {
                rippleView.setCenter(
                        width * normalizedPortPosY, height * (1 - normalizedPortPosX))
            }
            Surface.ROTATION_180 -> {
                rippleView.setCenter(
                        width * (1 - normalizedPortPosX), height * (1 - normalizedPortPosY))
            }
            Surface.ROTATION_270 -> {
                rippleView.setCenter(
                        width * (1 - normalizedPortPosY), height * normalizedPortPosX)
            }
        }
    }

    private fun updateRippleColor() {
        rippleView.setColor(Utils.getColorAttr(context, android.R.attr.colorAccent).defaultColor)
    }

    inner class ChargingRippleCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            startRipple()
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar charging-ripple")
        }
    }

    enum class WiredChargingRippleEvent(private val _id: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Wired charging ripple effect played")
        CHARGING_RIPPLE_PLAYED(829);

        override fun getId() = _id
    }
}
