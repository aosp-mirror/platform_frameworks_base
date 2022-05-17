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

import android.media.AudioAttributes
import android.os.VibrationEffect
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Used to simulate haptics that may be used for udfps authentication.
 */
@SysUISingleton
class UdfpsHapticsSimulator @Inject constructor(
    commandRegistry: CommandRegistry,
    val vibrator: VibratorHelper,
    val keyguardUpdateMonitor: KeyguardUpdateMonitor
) : Command {
    val sonificationEffects =
        AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build()
    var udfpsController: UdfpsController? = null

    init {
        commandRegistry.registerCommand("udfps-haptic") { this }
    }

    override fun execute(pw: PrintWriter, args: List<String>) {
        if (args.isEmpty()) {
            invalidCommand(pw)
        } else {
            when (args[0]) {
                "start" -> {
                    udfpsController?.playStartHaptic()
                }
                "success" -> {
                    // needs to be kept up to date with AcquisitionClient#SUCCESS_VIBRATION_EFFECT
                    vibrator.vibrate(
                        VibrationEffect.get(VibrationEffect.EFFECT_CLICK),
                        sonificationEffects)
                }
                "error" -> {
                    // needs to be kept up to date with AcquisitionClient#ERROR_VIBRATION_EFFECT
                    vibrator.vibrate(
                        VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK),
                        sonificationEffects)
                }
                else -> invalidCommand(pw)
            }
        }
    }

    override fun help(pw: PrintWriter) {
        pw.println("Usage: adb shell cmd statusbar udfps-haptic <haptic>")
        pw.println("Available commands:")
        pw.println("  start")
        pw.println("  success, always plays CLICK haptic")
        pw.println("  error, always plays DOUBLE_CLICK haptic")
    }

    fun invalidCommand(pw: PrintWriter) {
        pw.println("invalid command")
        help(pw)
    }
}