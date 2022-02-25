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
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.taptotransfer.common.MediaTttChipControllerCommon
import com.android.systemui.media.taptotransfer.common.MediaTttLogger
import com.android.systemui.media.taptotransfer.common.MediaTttRemovalReason
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.gesture.TapGestureDetector
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.view.ViewUtil
import javax.inject.Inject

/**
 * A controller to display and hide the Media Tap-To-Transfer chip on the **sending** device. This
 * chip is shown when a user is transferring media to/from this device and a receiver device.
 */
@SysUISingleton
class MediaTttChipControllerSender @Inject constructor(
    commandQueue: CommandQueue,
    context: Context,
    @MediaTttSenderLogger logger: MediaTttLogger,
    windowManager: WindowManager,
    viewUtil: ViewUtil,
    @Main mainExecutor: DelayableExecutor,
    tapGestureDetector: TapGestureDetector,
) : MediaTttChipControllerCommon<ChipStateSender>(
    context,
    logger,
    windowManager,
    viewUtil,
    mainExecutor,
    tapGestureDetector,
    R.layout.media_ttt_chip
) {
    private var currentlyDisplayedChipState: ChipStateSender? = null

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
        logger.logStateChange(stateIntToString(displayState), routeInfo.id)
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
                removeChip(removalReason = FAR_FROM_RECEIVER)
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
        currentlyDisplayedChipState = chipState

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

    override fun removeChip(removalReason: String) {
        // Don't remove the chip if we're mid-transfer since the user should still be able to
        // see the status of the transfer. (But do remove it if it's finally timed out.)
        if ((currentlyDisplayedChipState is TransferToReceiverTriggered ||
                currentlyDisplayedChipState is TransferToThisDeviceTriggered)
            && removalReason != MediaTttRemovalReason.REASON_TIMEOUT) {
            return
        }
        super.removeChip(removalReason)
        currentlyDisplayedChipState = null
    }

    private fun stateIntToString(@StatusBarManager.MediaTransferSenderState state: Int): String {
        return when(state) {
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST ->
                "ALMOST_CLOSE_TO_START_CAST"
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST ->
                "ALMOST_CLOSE_TO_END_CAST"
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED ->
                "TRANSFER_TO_RECEIVER_TRIGGERED"
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED ->
                "TRANSFER_TO_THIS_DEVICE_TRIGGERED"
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED ->
                "TRANSFER_TO_RECEIVER_SUCCEEDED"
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED ->
                "TRANSFER_TO_THIS_DEVICE_SUCCEEDED"
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_FAILED ->
                "TRANSFER_TO_RECEIVER_FAILED"
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_FAILED ->
                "TRANSFER_TO_THIS_DEVICE_FAILED"
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER ->
                FAR_FROM_RECEIVER
            else -> "INVALID: $state"
        }
    }
}

const val SENDER_TAG = "MediaTapToTransferSender"
private const val FAR_FROM_RECEIVER = "FAR_FROM_RECEIVER"
