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

package com.android.systemui.statusbar.notification.collection.provider

import android.os.Build
import android.util.Log
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.util.Assert
import com.android.systemui.util.ListenerSet
import com.android.systemui.util.isNotEmpty
import java.io.PrintWriter
import javax.inject.Inject

/**
 * A debug mode provider which is used by both the legacy and new notification pipelines to
 * block unwanted notifications from appearing to the user, primarily for integration testing.
 *
 * The only configuration is a list of allowed packages.  When this list is empty, the feature is
 * disabled.  When SystemUI starts up, this feature is disabled.
 *
 * To enabled filtering, provide the space-separated list of packages using the command:
 *
 * `$ adb shell cmd statusbar notif-filter allowed-pkgs <package> ...`
 *
 * To disable filtering, send the command without any packages, or explicitly reset:
 *
 * `$ adb shell cmd statusbar notif-filter reset`
 *
 * NOTE: this feature only works on debug builds, and when the broadcaster is root.
 */
@SysUISingleton
class DebugModeFilterProvider @Inject constructor(
    private val commandRegistry: CommandRegistry,
    dumpManager: DumpManager
) : Dumpable {
    private var allowedPackages: List<String> = emptyList()
    private val listeners = ListenerSet<Runnable>()

    init {
        dumpManager.registerDumpable(this)
    }

    /**
     * Register a runnable to be invoked when the allowed packages changes, which would mean the
     * result of [shouldFilterOut] may have changed for some entries.
     */
    fun registerInvalidationListener(listener: Runnable) {
        Assert.isMainThread()
        if (!Build.isDebuggable()) {
            return
        }
        val needsInitialization = listeners.isEmpty()
        listeners.addIfAbsent(listener)
        if (needsInitialization) {
            commandRegistry.registerCommand("notif-filter") { NotifFilterCommand() }
            Log.d(TAG, "Registered notif-filter command")
        }
    }

    /**
     * Determine if the given entry should be hidden from the user in debug mode.
     * Will always return false in release.
     */
    fun shouldFilterOut(entry: NotificationEntry): Boolean {
        if (allowedPackages.isEmpty()) {
            return false
        }
        return entry.sbn.packageName !in allowedPackages
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("initialized: ${listeners.isNotEmpty()}")
        pw.println("allowedPackages: ${allowedPackages.size}")
        allowedPackages.forEachIndexed { i, pkg ->
            pw.println("  [$i]: $pkg")
        }
    }

    companion object {
        private const val TAG = "DebugModeFilterProvider"
    }

    inner class NotifFilterCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            when (args.firstOrNull()) {
                "reset" -> {
                    if (args.size > 1) {
                        return invalidCommand(pw, "Unexpected arguments for 'reset' command")
                    }
                    allowedPackages = emptyList()
                }
                "allowed-pkgs" -> {
                    allowedPackages = args.drop(1)
                }
                null -> return invalidCommand(pw, "Missing command")
                else -> return invalidCommand(pw, "Unknown command: ${args.firstOrNull()}")
            }
            Log.d(TAG, "Updated allowedPackages: $allowedPackages")
            if (allowedPackages.isEmpty()) {
                pw.print("Resetting allowedPackages ... ")
            } else {
                pw.print("Updating allowedPackages: $allowedPackages ... ")
            }
            listeners.forEach(Runnable::run)
            pw.println("DONE")
        }

        private fun invalidCommand(pw: PrintWriter, reason: String) {
            pw.println("Error: $reason")
            pw.println()
            help(pw)
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar notif-filter <command>")
            pw.println("Available commands:")
            pw.println("  reset")
            pw.println("     Restore the default system behavior.")
            pw.println("  allowed-pkgs <package> ...")
            pw.println("     Hide all notification except from packages listed here.")
            pw.println("     Providing no packages is treated as a reset.")
        }
    }
}
