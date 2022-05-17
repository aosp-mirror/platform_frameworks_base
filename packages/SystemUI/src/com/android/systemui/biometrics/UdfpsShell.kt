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

package com.android.systemui.biometrics

import android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_BP
import android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_OTHER
import android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_SETTINGS
import android.hardware.biometrics.BiometricOverlayConstants.REASON_ENROLL_ENROLLING
import android.hardware.biometrics.BiometricOverlayConstants.REASON_ENROLL_FIND_SENSOR
import android.hardware.biometrics.BiometricOverlayConstants.REASON_UNKNOWN
import android.hardware.fingerprint.IUdfpsOverlayController
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import javax.inject.Inject

private const val TAG = "UdfpsShell"
private const val REQUEST_ID = 2L
private const val SENSOR_ID = 0

/**
 * Used to show and hide the UDFPS overlay with statusbar commands.
 */
@SysUISingleton
class UdfpsShell @Inject constructor(
    commandRegistry: CommandRegistry
) : Command {

    /**
     * Set in [UdfpsController.java] constructor, used to show and hide the UDFPS overlay.
     * TODO: inject after b/229290039 is resolved
     */
    var udfpsOverlayController: IUdfpsOverlayController? = null

    init {
        commandRegistry.registerCommand("udfps") { this }
    }

    override fun execute(pw: PrintWriter, args: List<String>) {
        if (args.size == 1 && args[0] == "hide") {
            hideOverlay()
        } else if (args.size == 2 && args[0] == "show") {
            showOverlay(getEnrollmentReason(args[1]))
        } else {
            invalidCommand(pw)
        }
    }

    override fun help(pw: PrintWriter) {
        pw.println("Usage: adb shell cmd statusbar udfps <cmd>")
        pw.println("Supported commands:")
        pw.println("  - show <reason>")
        pw.println("    -> supported reasons: [enroll-find-sensor, enroll-enrolling, auth-bp, " +
                            "auth-keyguard, auth-other, auth-settings]")
        pw.println("    -> reason otherwise defaults to unknown")
        pw.println("  - hide")
    }

    private fun invalidCommand(pw: PrintWriter) {
        pw.println("invalid command")
        help(pw)
    }

    private fun getEnrollmentReason(reason: String): Int {
        return when (reason) {
            "enroll-find-sensor" -> REASON_ENROLL_FIND_SENSOR
            "enroll-enrolling" -> REASON_ENROLL_ENROLLING
            "auth-bp" -> REASON_AUTH_BP
            "auth-keyguard" -> REASON_AUTH_KEYGUARD
            "auth-other" -> REASON_AUTH_OTHER
            "auth-settings" -> REASON_AUTH_SETTINGS
            else -> REASON_UNKNOWN
        }
    }

    private fun showOverlay(reason: Int) {
        udfpsOverlayController?.showUdfpsOverlay(
                REQUEST_ID,
                SENSOR_ID,
                reason,
                object : IUdfpsOverlayControllerCallback.Stub() {
                    override fun onUserCanceled() {
                        Log.e(TAG, "User cancelled")
                    }
                }
        )
    }

    private fun hideOverlay() {
        udfpsOverlayController?.hideUdfpsOverlay(SENSOR_ID)
    }
}