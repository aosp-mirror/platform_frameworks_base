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

package com.android.systemui.statusbar.charging

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.PointF
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import com.android.internal.annotations.VisibleForTesting
import com.android.settingslib.Utils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.leak.RotationUtils
import java.io.PrintWriter
import javax.inject.Inject

/***
 * Controls the ripple effect that shows when wired charging begins.
 * The ripple uses the accent color of the current theme.
 */
@SysUISingleton
class WiredChargingRippleController @Inject constructor(
    commandRegistry: CommandRegistry,
    batteryController: BatteryController,
    configurationController: ConfigurationController,
    featureFlags: FeatureFlags,
    private val context: Context
) {
    private var charging: Boolean? = null
    private val rippleEnabled: Boolean = featureFlags.isChargingRippleEnabled
    private val windowLayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        format = PixelFormat.TRANSLUCENT
        type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        fitInsetsTypes = 0 // Ignore insets from all system bars
        title = "Wired Charging Animation"
        flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    @VisibleForTesting
    var rippleView: ChargingRippleView = ChargingRippleView(context, attrs = null)

    init {
        val batteryStateChangeCallback = object : BatteryController.BatteryStateChangeCallback {
            override fun onBatteryLevelChanged(
                level: Int,
                pluggedIn: Boolean,
                nowCharging: Boolean
            ) {
                // Suppresses the ripple when it's disabled, or when the state change comes
                // from wireless charging.
                if (!rippleEnabled || batteryController.isWirelessCharging) {
                    return
                }
                val wasCharging = charging
                charging = nowCharging
                // Only triggers when the keyguard is active and the device is just plugged in.
                if ((wasCharging == null || !wasCharging) && nowCharging) {
                    startRipple()
                }
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
            override fun onOverlayChanged() {
                updateRippleColor()
            }
        }
        configurationController.addCallback(configurationChangedListener)

        commandRegistry.registerCommand("charging-ripple") { ChargingRippleCommand() }
        updateRippleColor()
    }

    fun startRipple() {
        if (rippleView.rippleInProgress) {
            return
        }
        val mWM = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowLayoutParams.packageName = context.opPackageName
        rippleView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View?) {}

            override fun onViewAttachedToWindow(view: View?) {
                layoutRipple()
                rippleView.startRipple(Runnable {
                    mWM.removeView(rippleView)
                })
                rippleView.removeOnAttachStateChangeListener(this)
            }
        })
        mWM.addView(rippleView, windowLayoutParams)
    }

    private fun layoutRipple() {
        val displayMetrics = DisplayMetrics()
        context.display.getRealMetrics(displayMetrics)
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        rippleView.radius = Integer.max(width, height).toFloat()

        // Always show the ripple from the charging cable location.
        // Currently assuming the charging cable is at the bottom of the screen.
        // TODO(shanh): Pull charging port location into configurations.
        rippleView.origin = when (RotationUtils.getRotation(context)) {
            RotationUtils.ROTATION_LANDSCAPE -> {
                PointF(width.toFloat(), height / 2f)
            }
            RotationUtils.ROTATION_UPSIDE_DOWN -> {
                PointF(width / 2f, 0f)
            }
            RotationUtils.ROTATION_SEASCAPE -> {
                PointF(0f, height / 2f)
            }
            else -> {
                // ROTATION_NONE
                PointF(width / 2f, height.toFloat())
            }
        }
    }

    private fun updateRippleColor() {
        rippleView.setColor(
                Utils.getColorAttr(context, android.R.attr.colorAccent).defaultColor)
    }

    inner class ChargingRippleCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            startRipple()
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar charging-ripple")
        }
    }
}
