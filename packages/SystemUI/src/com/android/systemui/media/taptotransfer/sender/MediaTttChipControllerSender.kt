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

package com.android.systemui.media.taptotransfer.sender

import android.app.StatusBarManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.MediaRoute2Info
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.android.internal.statusbar.IUndoMediaTransferCallback
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.taptotransfer.common.MediaTttChipControllerCommon
import com.android.systemui.statusbar.CommandQueue
import javax.inject.Inject

/**
 * A controller to display and hide the Media Tap-To-Transfer chip on the **sending** device. This
 * chip is shown when a user is transferring media to/from this device and a receiver device.
 */
@SysUISingleton
class MediaTttChipControllerSender @Inject constructor(
    context: Context,
    windowManager: WindowManager,
    private val commandQueue: CommandQueue
) : MediaTttChipControllerCommon<ChipStateSender>(
    context, windowManager, R.layout.media_ttt_chip
) {
    // TODO(b/216141276): Use app icon from media route info instead of this fake one.
    private val fakeAppIconDrawable =
        Icon.createWithResource(context, R.drawable.ic_avatar_user).loadDrawable(context).also {
            it.setTint(Color.YELLOW)
        }

    private val commandQueueCallback = object : CommandQueue.Callbacks {
        override fun updateMediaTapToTransferSenderDisplay(
                @StatusBarManager.MediaTransferSenderState displayState: Int,
                routeInfo: MediaRoute2Info,
                undoCallback: IUndoMediaTransferCallback?
        ) {
            // TODO(b/216318437): Trigger displayChip with the right state based on displayState.
            displayChip(
                MoveCloserToStartCast(
                    // TODO(b/217418566): This app icon content description is incorrect --
                    //   routeInfo.name is the name of the device, not the name of the app.
                    fakeAppIconDrawable, routeInfo.name.toString(), routeInfo.name.toString()
                )
            )
        }
    }

    init {
        commandQueue.addCallback(commandQueueCallback)
    }

    /** Displays the chip view for the given state. */
    override fun updateChipView(chipState: ChipStateSender, currentChipView: ViewGroup) {
        // App icon
        setIcon(chipState, currentChipView)

        // Text
        currentChipView.requireViewById<TextView>(R.id.text).apply {
            text = chipState.getChipTextString(context)
        }

        // Loading
        currentChipView.requireViewById<View>(R.id.loading).visibility =
            if (chipState.showLoading()) { View.VISIBLE } else { View.GONE }

        // Undo
        val undoView = currentChipView.requireViewById<View>(R.id.undo)
        val undoClickListener = chipState.undoClickListener(this)
        undoView.setOnClickListener(undoClickListener)
        undoView.visibility = if (undoClickListener != null) { View.VISIBLE } else { View.GONE }

        // Failure
        val showFailure = chipState is TransferFailed
        currentChipView.requireViewById<View>(R.id.failure_icon).visibility =
            if (showFailure) { View.VISIBLE } else { View.GONE }
    }
}
