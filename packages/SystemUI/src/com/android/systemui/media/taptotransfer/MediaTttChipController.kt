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
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import java.io.PrintWriter
import javax.inject.Inject

/**
 * A controller to display and hide the Media Tap-To-Transfer chip. This chip is shown when a user
 * is currently playing media on a local "media cast sender" device (e.g. a phone) and gets close
 * enough to a "media cast receiver" device (e.g. a tablet). This chip encourages the user to
 * transfer the media from the sender device to the receiver device.
 */
@SysUISingleton
class MediaTttChipController @Inject constructor(
    commandRegistry: CommandRegistry,
    private val context: Context,
    private val windowManager: WindowManager,
) {
    init {
        commandRegistry.registerCommand(ADD_CHIP_COMMAND_TAG) { AddChipCommand() }
        commandRegistry.registerCommand(REMOVE_CHIP_COMMAND_TAG) { RemoveChipCommand() }
    }

    private val windowLayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP.or(Gravity.CENTER_HORIZONTAL)
        type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY
        title = "Media Tap-To-Transfer Chip View"
        flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        format = PixelFormat.TRANSLUCENT
        setTrustedOverlay()
    }

    /** The chip view currently being displayed. Null if the chip is not being displayed. */
    private var chipView: LinearLayout? = null

    private fun displayChip(chipType: ChipType, otherDeviceName: String) {
        val oldChipView = chipView
        if (chipView == null) {
            chipView = LayoutInflater
                .from(context)
                .inflate(R.layout.media_ttt_chip, null) as LinearLayout
        }
        val currentChipView = chipView!!

        // Text
        currentChipView.requireViewById<TextView>(R.id.text).apply {
            text = context.getString(chipType.chipText, otherDeviceName)
        }

        if (oldChipView == null) {
            windowManager.addView(chipView, windowLayoutParams)
        }
    }

    private fun removeChip() {
        if (chipView == null) { return }
        windowManager.removeView(chipView)
        chipView = null
    }

    @VisibleForTesting
    enum class ChipType(
        @StringRes internal val chipText: Int
    ) {
        MOVE_CLOSER_TO_TRANSFER(R.string.media_move_closer_to_transfer),
        TRANSFER_INITIATED(R.string.media_transfer_playing),
        TRANSFER_SUCCEEDED(R.string.media_transfer_playing),
    }

    inner class AddChipCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            val chipTypeArg = args[1]
            ChipType.values().forEach {
                if (it.name == chipTypeArg) {
                    displayChip(it, otherDeviceName = args[0])
                    return
                }
            }

            pw.println("Chip type must be one of " +
                    ChipType.values().map { it.name }.reduce { acc, s -> "$acc, $s" })
        }

        override fun help(pw: PrintWriter) {
            pw.println(
                "Usage: adb shell cmd statusbar $ADD_CHIP_COMMAND_TAG <deviceName> <chipType>"
            )
        }
    }

    inner class RemoveChipCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) = removeChip()
        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $REMOVE_CHIP_COMMAND_TAG")
        }
    }

    companion object {
        @VisibleForTesting
        const val ADD_CHIP_COMMAND_TAG = "media-ttt-chip-add"
        @VisibleForTesting
        const val REMOVE_CHIP_COMMAND_TAG = "media-ttt-chip-remove"
    }
}
