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
import android.hardware.biometrics.BiometricSourceType
import android.view.View
import android.view.ViewGroup
import com.android.internal.annotations.VisibleForTesting
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.settingslib.Utils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.ConfigurationController
import java.io.PrintWriter
import javax.inject.Inject

/***
 * Controls the ripple effect that shows when authentication is successful.
 * The ripple uses the accent color of the current theme.
 */
@SysUISingleton
class AuthRippleController @Inject constructor(
    commandRegistry: CommandRegistry,
    configurationController: ConfigurationController,
    private val context: Context,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor
) {
    @VisibleForTesting
    var rippleView: AuthRippleView = AuthRippleView(context, attrs = null)

    val keyguardUpdateMonitorCallback = object : KeyguardUpdateMonitorCallback() {
        override fun onBiometricAuthenticated(
            userId: Int,
            biometricSourceType: BiometricSourceType?,
            isStrongBiometric: Boolean
        ) {
            if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                rippleView.startRipple()
            }
        }
    }

    init {
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

        commandRegistry.registerCommand("auth-ripple") { AuthRippleCommand() }
    }

    fun setSensorLocation(x: Float, y: Float) {
        rippleView.setSensorLocation(x, y)
    }

    fun setViewHost(viewHost: View) {
        // Add the ripple view to its host layout
        viewHost.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View?) {}

            override fun onViewAttachedToWindow(view: View?) {
                (viewHost as ViewGroup).addView(rippleView)
                keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
                viewHost.removeOnAttachStateChangeListener(this)
            }
        })

        updateRippleColor()
    }

    private fun updateRippleColor() {
        rippleView.setColor(
            Utils.getColorAttr(context, android.R.attr.colorAccent).defaultColor)
    }

    inner class AuthRippleCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            rippleView.startRipple()
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar auth-ripple")
        }
    }
}
