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
import android.content.res.Configuration
import android.graphics.PointF
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroupOverlay
import com.android.internal.annotations.VisibleForTesting
import com.android.settingslib.Utils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.io.PrintWriter
import java.lang.Integer.max
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
    private val context: Context,
    private val keyguardStateController: KeyguardStateController
) {
    private var charging: Boolean? = null
    private val rippleEnabled: Boolean = featureFlags.isChargingRippleEnabled
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
                if (wasCharging == false && nowCharging && keyguardStateController.isShowing) {
                    rippleView.startRipple()
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
            override fun onConfigChanged(newConfig: Configuration?) {
                layoutRippleView()
            }
        }
        configurationController.addCallback(configurationChangedListener)

        commandRegistry.registerCommand("charging-ripple") { ChargingRippleCommand() }
    }

    fun setViewHost(viewHost: View) {
        // Add the ripple view as an overlay of the root view so that it always
        // shows on top.
        viewHost.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View?) {}

            override fun onViewAttachedToWindow(view: View?) {
                (viewHost.viewRootImpl.view.overlay as ViewGroupOverlay).add(rippleView)
                layoutRippleView()
                viewHost.removeOnAttachStateChangeListener(this)
            }
        })

        updateRippleColor()
    }

    private fun layoutRippleView() {
        // Overlays are not auto measured and laid out so we do it manually here.
        val displayMetrics = DisplayMetrics()
        context.display.getRealMetrics(displayMetrics)
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        if (width != rippleView.width || height != rippleView.height) {
            rippleView.apply {
                measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                layout(0, 0, width, height)
                origin = PointF(width / 2f, height.toFloat())
                radius = max(width, height).toFloat()
            }
        }
    }

    private fun updateRippleColor() {
        rippleView.setColor(
                Utils.getColorAttr(context, android.R.attr.colorAccent).defaultColor)
    }

    inner class ChargingRippleCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            rippleView.startRipple()
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar charging-ripple")
        }
    }
}
