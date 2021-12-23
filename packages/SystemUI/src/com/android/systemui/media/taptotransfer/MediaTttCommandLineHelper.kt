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
import android.graphics.drawable.Icon
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
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
    private val mediaTttChipController: MediaTttChipController,
    @Main private val mainExecutor: DelayableExecutor,
) {
    private val appIconDrawable =
        Icon.createWithResource(context, R.drawable.ic_cake).loadDrawable(context)

    init {
        commandRegistry.registerCommand(ADD_CHIP_COMMAND_TAG) { AddChipCommand() }
        commandRegistry.registerCommand(REMOVE_CHIP_COMMAND_TAG) { RemoveChipCommand() }
    }

    inner class AddChipCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            val otherDeviceName = args[0]
            when (args[1]) {
                MOVE_CLOSER_TO_TRANSFER_COMMAND_NAME -> {
                    mediaTttChipController.displayChip(
                        MoveCloserToTransfer(otherDeviceName, appIconDrawable)
                    )
                }
                TRANSFER_INITIATED_COMMAND_NAME -> {
                    val futureTask = FutureTask { fakeUndoRunnable }
                    mediaTttChipController.displayChip(
                        TransferInitiated(otherDeviceName, appIconDrawable, futureTask)
                    )
                    mainExecutor.executeDelayed({ futureTask.run() }, FUTURE_WAIT_TIME)

                }
                TRANSFER_SUCCEEDED_COMMAND_NAME -> {
                    mediaTttChipController.displayChip(
                        TransferSucceeded(otherDeviceName, appIconDrawable, fakeUndoRunnable)
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
            pw.println(
                "Usage: adb shell cmd statusbar $ADD_CHIP_COMMAND_TAG <deviceName> <chipStatus>"
            )
        }
    }

    inner class RemoveChipCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            mediaTttChipController.removeChip()
        }
        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $REMOVE_CHIP_COMMAND_TAG")
        }
    }

    private val fakeUndoRunnable = Runnable {
        Log.i(TAG, "Undo runnable triggered")
    }
}

@VisibleForTesting
const val ADD_CHIP_COMMAND_TAG = "media-ttt-chip-add"
@VisibleForTesting
const val REMOVE_CHIP_COMMAND_TAG = "media-ttt-chip-remove"
@VisibleForTesting
val MOVE_CLOSER_TO_TRANSFER_COMMAND_NAME = MoveCloserToTransfer::class.simpleName!!
@VisibleForTesting
val TRANSFER_INITIATED_COMMAND_NAME = TransferInitiated::class.simpleName!!
@VisibleForTesting
val TRANSFER_SUCCEEDED_COMMAND_NAME = TransferSucceeded::class.simpleName!!

private const val FUTURE_WAIT_TIME = 2000L
