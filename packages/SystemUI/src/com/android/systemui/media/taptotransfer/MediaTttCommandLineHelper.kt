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

package com.android.systemui.media.taptotransfer

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.Context
import android.media.MediaRoute2Info
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.taptotransfer.receiver.ChipStateReceiver
import com.android.systemui.media.taptotransfer.sender.ChipStateSender
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import java.lang.IllegalArgumentException
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * A helper class to test the media tap-to-transfer chip via the command line. See inner classes for
 * command usages.
 */
@SysUISingleton
class MediaTttCommandLineHelper @Inject constructor(
    private val commandRegistry: CommandRegistry,
    private val context: Context,
    @Main private val mainExecutor: Executor
) : CoreStartable {

    /** All commands for the sender device. */
    inner class SenderCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            if (args.size < 2) {
                help(pw)
                return
            }

            val senderArgs = processArgs(args)

            @StatusBarManager.MediaTransferSenderState
            val displayState: Int?
            try {
                displayState = ChipStateSender.getSenderStateIdFromName(senderArgs.commandName)
            } catch (ex: IllegalArgumentException) {
                pw.println("Invalid command name ${senderArgs.commandName}")
                return
            }

            @SuppressLint("WrongConstant") // sysui allowed to call STATUS_BAR_SERVICE
            val statusBarManager = context.getSystemService(Context.STATUS_BAR_SERVICE)
                    as StatusBarManager
            val routeInfo = MediaRoute2Info.Builder(senderArgs.id, senderArgs.deviceName)
                    .addFeature("feature")
            if (senderArgs.useAppIcon) {
                routeInfo.setClientPackageName(TEST_PACKAGE_NAME)
            }

            var undoExecutor: Executor? = null
            var undoRunnable: Runnable? = null
            if (isSucceededState(displayState) && senderArgs.showUndo) {
                undoExecutor = mainExecutor
                undoRunnable = Runnable { Log.i(CLI_TAG, "Undo triggered for $displayState") }
            }

            statusBarManager.updateMediaTapToTransferSenderDisplay(
                displayState,
                routeInfo.build(),
                undoExecutor,
                undoRunnable,
            )
        }

        private fun processArgs(args: List<String>): SenderArgs {
            val senderArgs = SenderArgs(
                deviceName = args[0],
                commandName = args[1],
            )

            if (args.size == 2) {
                return senderArgs
            }

            // Process any optional arguments
            args.subList(2, args.size).forEach {
                when {
                    it == "useAppIcon=false" -> senderArgs.useAppIcon = false
                    it == "showUndo=false" -> senderArgs.showUndo = false
                    it.substring(0, 3) == "id=" -> senderArgs.id = it.substring(3)
                }
            }

            return senderArgs
        }

        private fun isSucceededState(
            @StatusBarManager.MediaTransferSenderState displayState: Int
        ): Boolean {
            return displayState ==
                    StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED ||
                    displayState ==
                    StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED
        }

        override fun help(pw: PrintWriter) {
            pw.println(
                "Usage: adb shell cmd statusbar $SENDER_COMMAND " +
                "<deviceName> <chipState> " +
                "useAppIcon=[true|false] id=<id> showUndo=[true|false]"
            )
            pw.println("Note: useAppIcon, id, and showUndo are optional additional commands.")
        }
    }

    private data class SenderArgs(
        val deviceName: String,
        val commandName: String,
        var id: String = "id",
        var useAppIcon: Boolean = true,
        var showUndo: Boolean = true,
    )

    /** All commands for the receiver device. */
    inner class ReceiverCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            if (args.isEmpty()) {
                help(pw)
                return
            }

            val commandName = args[0]
            @StatusBarManager.MediaTransferReceiverState
            val displayState: Int?
            try {
                displayState = ChipStateReceiver.getReceiverStateIdFromName(commandName)
            } catch (ex: IllegalArgumentException) {
                pw.println("Invalid command name $commandName")
                return
            }

            @SuppressLint("WrongConstant") // sysui is allowed to call STATUS_BAR_SERVICE
            val statusBarManager = context.getSystemService(Context.STATUS_BAR_SERVICE)
                    as StatusBarManager
            val routeInfo = MediaRoute2Info.Builder(
                if (args.size >= 3) args[2] else "id",
                "Test Name"
            ).addFeature("feature")
            val useAppIcon = !(args.size >= 2 && args[1] == "useAppIcon=false")
            if (useAppIcon) {
                routeInfo.setClientPackageName(TEST_PACKAGE_NAME)
            }

            statusBarManager.updateMediaTapToTransferReceiverDisplay(
                    displayState,
                    routeInfo.build(),
                    null,
                    null
                )
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $RECEIVER_COMMAND " +
                    "<chipState> useAppIcon=[true|false] <id>")
        }
    }

    override fun start() {
        commandRegistry.registerCommand(SENDER_COMMAND) { SenderCommand() }
        commandRegistry.registerCommand(RECEIVER_COMMAND) { ReceiverCommand() }
    }
}

@VisibleForTesting
const val SENDER_COMMAND = "media-ttt-chip-sender"
@VisibleForTesting
const val RECEIVER_COMMAND = "media-ttt-chip-receiver"
private const val CLI_TAG = "MediaTransferCli"
private const val TEST_PACKAGE_NAME = "com.android.systemui"
