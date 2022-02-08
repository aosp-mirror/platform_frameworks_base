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
import android.media.MediaRoute2Info
import android.util.Log
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
    commandQueue: CommandQueue,
    context: Context,
    windowManager: WindowManager,
) : MediaTttChipControllerCommon<ChipStateSender>(
    context, windowManager, R.layout.media_ttt_chip
) {
    private val commandQueueCallbacks = object : CommandQueue.Callbacks {
        override fun updateMediaTapToTransferSenderDisplay(
                @StatusBarManager.MediaTransferSenderState displayState: Int,
                routeInfo: MediaRoute2Info,
                undoCallback: IUndoMediaTransferCallback?
        ) {
            this@MediaTttChipControllerSender.updateMediaTapToTransferSenderDisplay(
                displayState, routeInfo, undoCallback
            )
        }
    }

    init {
        commandQueue.addCallback(commandQueueCallbacks)
    }

    private fun updateMediaTapToTransferSenderDisplay(
        @StatusBarManager.MediaTransferSenderState displayState: Int,
        routeInfo: MediaRoute2Info,
        undoCallback: IUndoMediaTransferCallback?
    ) {
        val appPackageName = routeInfo.packageName
        val otherDeviceName = routeInfo.name.toString()
        val chipState = when(displayState) {
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST ->
                AlmostCloseToStartCast(appPackageName, otherDeviceName)
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST ->
                AlmostCloseToEndCast(appPackageName, otherDeviceName)
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED ->
                TransferToReceiverTriggered(appPackageName, otherDeviceName)
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED ->
                TransferToThisDeviceTriggered(appPackageName)
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED ->
                TransferToReceiverSucceeded(appPackageName, otherDeviceName, undoCallback)
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED ->
                TransferToThisDeviceSucceeded(appPackageName, otherDeviceName, undoCallback)
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_FAILED,
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_FAILED ->
                TransferFailed(appPackageName)
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER -> {
                removeChip()
                null
            }
            else -> {
                Log.e(SENDER_TAG, "Unhandled MediaTransferSenderState $displayState")
                null
            }
        }

        chipState?.let {
            displayChip(it)
        }
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

const val SENDER_TAG = "MediaTapToTransferSender"
