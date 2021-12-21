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

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Icon
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.taptotransfer.receiver.MediaTttChipControllerReceiver
import com.android.systemui.media.taptotransfer.receiver.ChipStateReceiver
import com.android.systemui.media.taptotransfer.sender.MediaTttChipControllerSender
import com.android.systemui.media.taptotransfer.sender.MoveCloserToTransfer
import com.android.systemui.media.taptotransfer.sender.TransferInitiated
import com.android.systemui.media.taptotransfer.sender.TransferSucceeded
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.PrintWriter
import java.util.concurrent.FutureTask
import javax.inject.Inject

/**
 * A helper class to test the media tap-to-transfer chip via the command line. See inner classes for
 * command usages.
 */
@SysUISingleton
class MediaTttCommandLineHelper @Inject constructor(
    commandRegistry: CommandRegistry,
    context: Context,
    private val mediaTttChipControllerSender: MediaTttChipControllerSender,
    private val mediaTttChipControllerReceiver: MediaTttChipControllerReceiver,
    @Main private val mainExecutor: DelayableExecutor,
) {
    private val appIconDrawable =
        Icon.createWithResource(context, R.drawable.ic_avatar_user).loadDrawable(context).also {
            it.setTint(Color.YELLOW)
        }

    init {
        commandRegistry.registerCommand(
            ADD_CHIP_COMMAND_SENDER_TAG) { AddChipCommandSender() }
        commandRegistry.registerCommand(
            REMOVE_CHIP_COMMAND_SENDER_TAG) { RemoveChipCommandSender() }
        commandRegistry.registerCommand(
            ADD_CHIP_COMMAND_RECEIVER_TAG) { AddChipCommandReceiver() }
        commandRegistry.registerCommand(
            REMOVE_CHIP_COMMAND_RECEIVER_TAG) { RemoveChipCommandReceiver() }
    }

    inner class AddChipCommandSender : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            val otherDeviceName = args[0]
            when (args[1]) {
                MOVE_CLOSER_TO_TRANSFER_COMMAND_NAME -> {
                    mediaTttChipControllerSender.displayChip(
                        MoveCloserToTransfer(appIconDrawable, otherDeviceName)
                    )
                }
                TRANSFER_INITIATED_COMMAND_NAME -> {
                    val futureTask = FutureTask { fakeUndoRunnable }
                    mediaTttChipControllerSender.displayChip(
                        TransferInitiated(appIconDrawable, otherDeviceName, futureTask)
                    )
                    mainExecutor.executeDelayed({ futureTask.run() }, FUTURE_WAIT_TIME)

                }
                TRANSFER_SUCCEEDED_COMMAND_NAME -> {
                    mediaTttChipControllerSender.displayChip(
                        TransferSucceeded(appIconDrawable, otherDeviceName, fakeUndoRunnable)
                    )
                }
                else -> {
                    pw.println("Chip type must be one of " +
                            "$MOVE_CLOSER_TO_TRANSFER_COMMAND_NAME, " +
                            "$TRANSFER_INITIATED_COMMAND_NAME, " +
                            TRANSFER_SUCCEEDED_COMMAND_NAME
                    )
                }
            }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar " +
                    "$ADD_CHIP_COMMAND_SENDER_TAG <deviceName> <chipStatus>"
            )
        }
    }

    /** A command to REMOVE the media ttt chip on the SENDER device. */
    inner class RemoveChipCommandSender : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            mediaTttChipControllerSender.removeChip()
        }
        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $REMOVE_CHIP_COMMAND_SENDER_TAG")
        }
    }


    /** A command to DISPLAY the media ttt chip on the RECEIVER device. */
    inner class AddChipCommandReceiver : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            mediaTttChipControllerReceiver.displayChip(ChipStateReceiver(appIconDrawable))
        }
        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $ADD_CHIP_COMMAND_RECEIVER_TAG")
        }
    }

    /** A command to REMOVE the media ttt chip on the RECEIVER device. */
    inner class RemoveChipCommandReceiver : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            mediaTttChipControllerReceiver.removeChip()
        }
        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $REMOVE_CHIP_COMMAND_RECEIVER_TAG")
        }
    }

    private val fakeUndoRunnable = Runnable {
        Log.i(TAG, "Undo runnable triggered")
    }
}

@VisibleForTesting
const val ADD_CHIP_COMMAND_SENDER_TAG = "media-ttt-chip-add-sender"
@VisibleForTesting
const val REMOVE_CHIP_COMMAND_SENDER_TAG = "media-ttt-chip-remove-sender"
@VisibleForTesting
const val ADD_CHIP_COMMAND_RECEIVER_TAG = "media-ttt-chip-add-receiver"
@VisibleForTesting
const val REMOVE_CHIP_COMMAND_RECEIVER_TAG = "media-ttt-chip-remove-receiver"
@VisibleForTesting
val MOVE_CLOSER_TO_TRANSFER_COMMAND_NAME = MoveCloserToTransfer::class.simpleName!!
@VisibleForTesting
val TRANSFER_INITIATED_COMMAND_NAME = TransferInitiated::class.simpleName!!
@VisibleForTesting
val TRANSFER_SUCCEEDED_COMMAND_NAME = TransferSucceeded::class.simpleName!!

private const val FUTURE_WAIT_TIME = 2000L
private const val TAG = "MediaTapToTransferCli"
