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

import android.app.StatusBarManager
import android.content.Context
import android.media.MediaRoute2Info
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.taptotransfer.sender.AlmostCloseToEndCast
import com.android.systemui.media.taptotransfer.sender.AlmostCloseToStartCast
import com.android.systemui.media.taptotransfer.sender.TransferFailed
import com.android.systemui.media.taptotransfer.sender.TransferToReceiverTriggered
import com.android.systemui.media.taptotransfer.sender.TransferToThisDeviceSucceeded
import com.android.systemui.media.taptotransfer.sender.TransferToThisDeviceTriggered
import com.android.systemui.media.taptotransfer.sender.TransferToReceiverSucceeded
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * A helper class to test the media tap-to-transfer chip via the command line. See inner classes for
 * command usages.
 */
@SysUISingleton
class MediaTttCommandLineHelper @Inject constructor(
    commandRegistry: CommandRegistry,
    private val context: Context,
    @Main private val mainExecutor: Executor
) {
    /**
     * A map from a display state string typed in the command line to the display int it represents.
     */
    private val stateStringToStateInt: Map<String, Int> = mapOf(
        AlmostCloseToStartCast::class.simpleName!!
                to StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
        AlmostCloseToEndCast::class.simpleName!!
                to StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
        TransferToReceiverTriggered::class.simpleName!!
                to StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED,
        TransferToThisDeviceTriggered::class.simpleName!!
                to StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
        TransferToReceiverSucceeded::class.simpleName!!
                to StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
        TransferToThisDeviceSucceeded::class.simpleName!!
                to StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
        TransferFailed::class.simpleName!!
                to StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_FAILED,
        FAR_FROM_RECEIVER_STATE
                to StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER
    )

    init {
        commandRegistry.registerCommand(SENDER_COMMAND) { SenderCommand() }
        commandRegistry.registerCommand(RECEIVER_COMMAND) { ReceiverCommand() }
    }

    /** All commands for the sender device. */
    inner class SenderCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            val routeInfo = MediaRoute2Info.Builder("id", args[0])
                    .addFeature("feature")
                    .build()

            val commandName = args[1]
            @StatusBarManager.MediaTransferSenderState
            val displayState = stateStringToStateInt[commandName]
            if (displayState == null) {
                pw.println("Invalid command name $commandName")
                return
            }

            val statusBarManager = context.getSystemService(Context.STATUS_BAR_SERVICE)
                    as StatusBarManager
            statusBarManager.updateMediaTapToTransferSenderDisplay(
                    displayState,
                    routeInfo,
                getUndoExecutor(displayState),
                getUndoCallback(displayState)
            )
        }

        private fun getUndoExecutor(
            @StatusBarManager.MediaTransferSenderState displayState: Int
        ): Executor? {
            return if (isSucceededState(displayState)) {
                mainExecutor
            } else {
                null
            }
        }

        private fun getUndoCallback(
            @StatusBarManager.MediaTransferSenderState displayState: Int
        ): Runnable? {
            return if (isSucceededState(displayState)) {
                Runnable { Log.i(CLI_TAG, "Undo triggered for $displayState") }
            } else {
                null
            }
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
            pw.println("Usage: adb shell cmd statusbar $SENDER_COMMAND <deviceName> <chipState>")
        }
    }

    /** All commands for the receiver device. */
    inner class ReceiverCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            val statusBarManager = context.getSystemService(Context.STATUS_BAR_SERVICE)
                    as StatusBarManager
            when(val commandName = args[0]) {
                CLOSE_TO_SENDER_STATE ->
                    statusBarManager.updateMediaTapToTransferReceiverDisplay(
                        StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER,
                        routeInfo
                    )
                FAR_FROM_SENDER_STATE ->
                    statusBarManager.updateMediaTapToTransferReceiverDisplay(
                        StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_FAR_FROM_SENDER,
                        routeInfo
                    )
                else ->
                    pw.println("Invalid command name $commandName")
            }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $RECEIVER_COMMAND <chipState>")
        }
    }
}

@VisibleForTesting
const val SENDER_COMMAND = "media-ttt-chip-sender"
@VisibleForTesting
const val RECEIVER_COMMAND = "media-ttt-chip-receiver"
@VisibleForTesting
const val FAR_FROM_RECEIVER_STATE = "FarFromReceiver"
@VisibleForTesting
const val CLOSE_TO_SENDER_STATE = "CloseToSender"
@VisibleForTesting
const val FAR_FROM_SENDER_STATE = "FarFromSender"
private const val CLI_TAG = "MediaTransferCli"

private val routeInfo = MediaRoute2Info.Builder("id", "Test Name")
    .addFeature("feature")
    .build()