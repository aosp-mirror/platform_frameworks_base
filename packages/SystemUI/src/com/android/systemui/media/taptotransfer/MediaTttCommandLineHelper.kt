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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.MediaRoute2Info
import android.os.IBinder
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.taptotransfer.receiver.MediaTttChipControllerReceiver
import com.android.systemui.media.taptotransfer.receiver.ChipStateReceiver
import com.android.systemui.media.taptotransfer.sender.MediaTttChipControllerSender
import com.android.systemui.media.taptotransfer.sender.MediaTttSenderService
import com.android.systemui.media.taptotransfer.sender.MoveCloserToEndCast
import com.android.systemui.media.taptotransfer.sender.MoveCloserToStartCast
import com.android.systemui.media.taptotransfer.sender.TransferFailed
import com.android.systemui.media.taptotransfer.sender.TransferToReceiverTriggered
import com.android.systemui.media.taptotransfer.sender.TransferToThisDeviceTriggered
import com.android.systemui.media.taptotransfer.sender.TransferToReceiverSucceeded
import com.android.systemui.shared.mediattt.DeviceInfo
import com.android.systemui.shared.mediattt.IDeviceSenderService
import com.android.systemui.shared.mediattt.IUndoTransferCallback
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import javax.inject.Inject

/**
 * A helper class to test the media tap-to-transfer chip via the command line. See inner classes for
 * command usages.
 */
@SysUISingleton
class MediaTttCommandLineHelper @Inject constructor(
    commandRegistry: CommandRegistry,
    private val context: Context,
    private val mediaTttChipControllerSender: MediaTttChipControllerSender,
    private val mediaTttChipControllerReceiver: MediaTttChipControllerReceiver,
) {
    private var senderService: IDeviceSenderService? = null
    private val senderServiceConnection = SenderServiceConnection()

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
            val mediaInfo = MediaRoute2Info.Builder("id", "Test Name")
                .addFeature("feature")
                .build()
            val otherDeviceInfo = DeviceInfo(otherDeviceName)

            when (args[1]) {
                MOVE_CLOSER_TO_START_CAST_COMMAND_NAME -> {
                    runOnService { senderService ->
                        senderService.closeToReceiverToStartCast(mediaInfo, otherDeviceInfo)
                    }
                }
                MOVE_CLOSER_TO_END_CAST_COMMAND_NAME -> {
                    runOnService { senderService ->
                        senderService.closeToReceiverToEndCast(mediaInfo, otherDeviceInfo)
                    }
                }
                TRANSFER_TO_RECEIVER_TRIGGERED_COMMAND_NAME -> {
                    runOnService { senderService ->
                        senderService.transferToReceiverTriggered(mediaInfo, otherDeviceInfo)
                    }
                }
                TRANSFER_TO_THIS_DEVICE_TRIGGERED_COMMAND_NAME -> {
                    runOnService { senderService ->
                        senderService.transferToThisDeviceTriggered(mediaInfo, otherDeviceInfo)
                    }
                }
                TRANSFER_TO_RECEIVER_SUCCEEDED_COMMAND_NAME -> {
                    val undoCallback = object : IUndoTransferCallback.Stub() {
                        override fun onUndoTriggered() {
                            Log.i(TAG, "Undo callback triggered")
                            // The external services that implement this callback would kick off a
                            // transfer back to this device, so mimic that here.
                            runOnService { senderService ->
                                senderService
                                    .transferToThisDeviceTriggered(mediaInfo, otherDeviceInfo)
                            }
                        }
                    }
                    runOnService { senderService ->
                        senderService
                            .transferToReceiverSucceeded(mediaInfo, otherDeviceInfo, undoCallback)
                    }
                }
                TRANSFER_FAILED_COMMAND_NAME -> {
                    runOnService { senderService ->
                        senderService.transferFailed(mediaInfo, otherDeviceInfo)
                    }
                }
                else -> {
                    pw.println("Chip type must be one of " +
                            "$MOVE_CLOSER_TO_START_CAST_COMMAND_NAME, " +
                            "$MOVE_CLOSER_TO_END_CAST_COMMAND_NAME, " +
                            "$TRANSFER_TO_RECEIVER_TRIGGERED_COMMAND_NAME, " +
                            "$TRANSFER_TO_THIS_DEVICE_TRIGGERED_COMMAND_NAME, " +
                            "$TRANSFER_TO_RECEIVER_SUCCEEDED_COMMAND_NAME, " +
                            TRANSFER_FAILED_COMMAND_NAME
                    )
                }
            }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar " +
                    "$ADD_CHIP_COMMAND_SENDER_TAG <deviceName> <chipStatus>"
            )
        }

        private fun runOnService(command: SenderServiceCommand) {
            val currentService = senderService
            if (currentService != null) {
                command.run(currentService)
            } else {
                bindService(command)
            }
        }

        private fun bindService(command: SenderServiceCommand) {
            senderServiceConnection.pendingCommand = command
            val binding = context.bindService(
                Intent(context, MediaTttSenderService::class.java),
                senderServiceConnection,
                Context.BIND_AUTO_CREATE
            )
            Log.i(TAG, "Starting service binding? $binding")
        }
    }

    /** A command to REMOVE the media ttt chip on the SENDER device. */
    inner class RemoveChipCommandSender : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            mediaTttChipControllerSender.removeChip()
            if (senderService != null) {
                context.unbindService(senderServiceConnection)
            }
        }
        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $REMOVE_CHIP_COMMAND_SENDER_TAG")
        }
    }

    /** A command to DISPLAY the media ttt chip on the RECEIVER device. */
    inner class AddChipCommandReceiver : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            mediaTttChipControllerReceiver.displayChip(
                ChipStateReceiver(appIconDrawable, APP_ICON_CONTENT_DESCRIPTION)
            )
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

    /** A service connection for [IDeviceSenderService]. */
    private inner class SenderServiceConnection : ServiceConnection {
        // A command that should be run when the service gets connected.
        var pendingCommand: SenderServiceCommand? = null

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val newCallback = IDeviceSenderService.Stub.asInterface(service)
            senderService = newCallback
            pendingCommand?.run(newCallback)
            pendingCommand = null
        }

        override fun onServiceDisconnected(className: ComponentName) {
            senderService = null
        }
    }

    /** An interface defining a command that should be run on the sender service. */
    private fun interface SenderServiceCommand {
        /** Runs the command on the provided [senderService]. */
        fun run(senderService: IDeviceSenderService)
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
val MOVE_CLOSER_TO_START_CAST_COMMAND_NAME = MoveCloserToStartCast::class.simpleName!!
@VisibleForTesting
val MOVE_CLOSER_TO_END_CAST_COMMAND_NAME = MoveCloserToEndCast::class.simpleName!!
@VisibleForTesting
val TRANSFER_TO_RECEIVER_TRIGGERED_COMMAND_NAME = TransferToReceiverTriggered::class.simpleName!!
@VisibleForTesting
val TRANSFER_TO_THIS_DEVICE_TRIGGERED_COMMAND_NAME =
    TransferToThisDeviceTriggered::class.simpleName!!
@VisibleForTesting
val TRANSFER_TO_RECEIVER_SUCCEEDED_COMMAND_NAME = TransferToReceiverSucceeded::class.simpleName!!
@VisibleForTesting
val TRANSFER_FAILED_COMMAND_NAME = TransferFailed::class.simpleName!!

private const val APP_ICON_CONTENT_DESCRIPTION = "Fake media app icon"
private const val TAG = "MediaTapToTransferCli"
