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

import android.content.Context
import android.graphics.Rect
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_BP
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_OTHER
import android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_SETTINGS
import android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_ENROLLING
import android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_FIND_SENSOR
import android.hardware.biometrics.BiometricRequestConstants.REASON_UNKNOWN
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import javax.inject.Inject

private const val TAG = "UdfpsShell"
private const val REQUEST_ID = 2L
private const val SENSOR_ID = 0
private const val MINOR = 10F
private const val MAJOR = 10F

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
    var udfpsOverlayController: UdfpsController.UdfpsOverlayController? = null
    var context: Context? = null
    var inflater: LayoutInflater? = null

    init {
        commandRegistry.registerCommand("udfps") { this }
    }

    override fun execute(pw: PrintWriter, args: List<String>) {
        if (args.size == 1 && args[0] == "hide") {
            hideOverlay()
        } else if (args.size == 2 && args[0] == "show") {
            showOverlay(getEnrollmentReason(args[1]))
        } else if (args.size == 1 && args[0] == "onUiReady") {
            onUiReady()
        } else if (args.size == 1 && args[0] == "simFingerDown") {
            simFingerDown()
        } else if (args.size == 1 && args[0] == "simFingerUp") {
            simFingerUp()
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
        pw.println("  - onUiReady")
        pw.println("  - simFingerDown")
        pw.println("    -> Simulates onFingerDown on sensor")
        pw.println("  - simFingerUp")
        pw.println("    -> Simulates onFingerUp on sensor")
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


    @VisibleForTesting
    fun onUiReady() {
        udfpsOverlayController?.debugOnUiReady(SENSOR_ID)
    }

    @VisibleForTesting
    fun simFingerDown() {
        val sensorBounds: Rect = udfpsOverlayController!!.sensorBounds

        val downEvent: MotionEvent? = obtainMotionEvent(ACTION_DOWN, sensorBounds.exactCenterX(),
                sensorBounds.exactCenterY(), MINOR, MAJOR)
        udfpsOverlayController?.debugOnTouch(downEvent)

        val moveEvent: MotionEvent? = obtainMotionEvent(ACTION_MOVE, sensorBounds.exactCenterX(),
                sensorBounds.exactCenterY(), MINOR, MAJOR)
        udfpsOverlayController?.debugOnTouch(moveEvent)

        downEvent?.recycle()
        moveEvent?.recycle()
    }

    @VisibleForTesting
    fun simFingerUp() {
        val sensorBounds: Rect = udfpsOverlayController!!.sensorBounds

        val upEvent: MotionEvent? = obtainMotionEvent(ACTION_UP, sensorBounds.exactCenterX(),
                sensorBounds.exactCenterY(), MINOR, MAJOR)
        udfpsOverlayController?.debugOnTouch(upEvent)
        upEvent?.recycle()
    }

    private fun obtainMotionEvent(
            action: Int,
            x: Float,
            y: Float,
            minor: Float,
            major: Float
    ): MotionEvent? {
        val pp = MotionEvent.PointerProperties()
        pp.id = 1
        val pc = MotionEvent.PointerCoords()
        pc.x = x
        pc.y = y
        pc.touchMinor = minor
        pc.touchMajor = major
        return MotionEvent.obtain(0, 0, action, 1, arrayOf(pp), arrayOf(pc),
                0, 0, 1f, 1f, 0, 0, 0, 0)
    }
}
