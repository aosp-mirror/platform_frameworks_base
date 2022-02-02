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
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.MediaRoute2Info
import androidx.annotation.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.taptotransfer.receiver.MediaTttChipControllerReceiver
import com.android.systemui.media.taptotransfer.receiver.ChipStateReceiver
import com.android.systemui.media.taptotransfer.sender.MoveCloserToEndCast
import com.android.systemui.media.taptotransfer.sender.MoveCloserToStartCast
import com.android.systemui.media.taptotransfer.sender.TransferFailed
import com.android.systemui.media.taptotransfer.sender.TransferToReceiverTriggered
import com.android.systemui.media.taptotransfer.sender.TransferToThisDeviceSucceeded
import com.android.systemui.media.taptotransfer.sender.TransferToThisDeviceTriggered
import com.android.systemui.media.taptotransfer.sender.TransferToReceiverSucceeded
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
    private val mediaTttChipControllerReceiver: MediaTttChipControllerReceiver,
) {
   private val appIconDrawable =
        Icon.createWithResource(context, R.drawable.ic_avatar_user).loadDrawable(context).also {
            it.setTint(Color.YELLOW)
        }

    init {
        commandRegistry.registerCommand(SENDER_COMMAND) { SenderCommand() }
        commandRegistry.registerCommand(
            ADD_CHIP_COMMAND_RECEIVER_TAG) { AddChipCommandReceiver() }
        commandRegistry.registerCommand(
            REMOVE_CHIP_COMMAND_RECEIVER_TAG) { RemoveChipCommandReceiver() }
    }

    /** All commands for the sender device. */
    inner class SenderCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            val routeInfo = MediaRoute2Info.Builder("id", args[0])
                    .addFeature("feature")
                    .build()

            val statusBarManager = context.getSystemService(Context.STATUS_BAR_SERVICE)
                    as StatusBarManager
            statusBarManager.updateMediaTapToTransferSenderDisplay(
                    StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
                    routeInfo,
                    /* undoExecutor= */ null,
                    /* undoCallback= */ null
            )
            // TODO(b/216318437): Migrate the rest of the callbacks to StatusBarManager.
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $SENDER_COMMAND <deviceName> <chipStatus>")
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
}

@VisibleForTesting
const val SENDER_COMMAND = "media-ttt-chip-sender"
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
val TRANSFER_TO_THIS_DEVICE_SUCCEEDED_COMMAND_NAME =
    TransferToThisDeviceSucceeded::class.simpleName!!
@VisibleForTesting
val TRANSFER_FAILED_COMMAND_NAME = TransferFailed::class.simpleName!!
@VisibleForTesting
val NO_LONGER_CLOSE_TO_RECEIVER_COMMAND_NAME = "NoLongerCloseToReceiver"

private const val APP_ICON_CONTENT_DESCRIPTION = "Fake media app icon"
private const val TAG = "MediaTapToTransferCli"
