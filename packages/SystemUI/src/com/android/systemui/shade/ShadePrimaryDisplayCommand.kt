/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.shade

import android.provider.Settings.Global.DEVELOPMENT_SHADE_DISPLAY_AWARENESS
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.shade.display.ShadeDisplayPolicy
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.settings.GlobalSettings
import java.io.PrintWriter
import javax.inject.Inject

@SysUISingleton
class ShadePrimaryDisplayCommand
@Inject
constructor(
    private val globalSettings: GlobalSettings,
    private val commandRegistry: CommandRegistry,
    private val displaysRepository: DisplayRepository,
    private val positionRepository: ShadeDisplaysRepository,
    private val policies: Set<@JvmSuppressWildcards ShadeDisplayPolicy>,
    private val defaultPolicy: ShadeDisplayPolicy,
) : Command, CoreStartable {

    override fun start() {
        commandRegistry.registerCommand("shade_display_override") { this }
    }

    override fun help(pw: PrintWriter) {
        pw.println("shade_display_override <policyName> ")
        pw.println("Set the display which is holding the shade, or the policy that defines it.")
        pw.println()
        pw.println("shade_display_override policies")
        pw.println("Lists available policies")
        pw.println()
        pw.println("shade_display_override reset ")
        pw.println("Reset the display which is holding the shade.")
        pw.println()
        pw.println("shade_display_override (list|status) ")
        pw.println("Lists available displays and which has the shade")
    }

    override fun execute(pw: PrintWriter, args: List<String>) {
        CommandHandler(pw, args).execute()
    }

    /** Wrapper class to avoid propagating [PrintWriter] to all methods. */
    private inner class CommandHandler(
        private val pw: PrintWriter,
        private val args: List<String>,
    ) {

        fun execute() {
            when (val command = args.getOrNull(0)?.lowercase()) {
                "reset" -> reset()
                "policies" -> printPolicies()
                "list",
                "status" -> printStatus()
                null -> help(pw)
                else -> parsePolicy(command)
            }
        }

        private fun parsePolicy(policyIdentifier: String) {
            if (policies.any { it.name == policyIdentifier }) {
                globalSettings.putString(DEVELOPMENT_SHADE_DISPLAY_AWARENESS, policyIdentifier)
            } else {
                help(pw)
            }
        }

        private fun reset() {
            globalSettings.putString(DEVELOPMENT_SHADE_DISPLAY_AWARENESS, defaultPolicy.name)
            pw.println("Reset shade display policy to default policy: ${defaultPolicy.name}")
        }

        private fun printStatus() {
            val displays = displaysRepository.displays.value
            val shadeDisplay = positionRepository.displayId.value
            pw.println("Available displays: ")
            displays.forEach {
                pw.print(" - ${it.displayId}")
                pw.println(if (it.displayId == shadeDisplay) " (Shade window is here)" else "")
            }
        }

        private fun printPolicies() {
            val currentPolicyName = positionRepository.currentPolicy.name
            pw.println("Available policies: ")
            policies.forEach {
                pw.print(" - ${it.name}")
                pw.println(if (currentPolicyName == it.name) " (Current policy)" else "")
            }
        }
    }
}
