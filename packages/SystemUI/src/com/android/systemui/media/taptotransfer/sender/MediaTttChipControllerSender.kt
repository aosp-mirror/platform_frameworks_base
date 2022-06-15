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
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.android.internal.statusbar.IUndoMediaTransferCallback
import com.android.systemui.R
import com.android.systemui.animation.Interpolators
import com.android.systemui.animation.ViewHierarchyAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.taptotransfer.common.ChipInfoCommon
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
    powerManager: PowerManager,
    private val uiEventLogger: MediaTttSenderUiEventLogger
) : MediaTttChipControllerCommon<ChipSenderInfo>(
    context,
    logger,
    windowManager,
    viewUtil,
    mainExecutor,
    tapGestureDetector,
    powerManager,
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
        val chipState: ChipStateSender? = ChipStateSender.getSenderStateFromId(displayState)
        val stateName = chipState?.name ?: "Invalid"
        logger.logStateChange(stateName, routeInfo.id)

        if (chipState == null) {
            Log.e(SENDER_TAG, "Unhandled MediaTransferSenderState $displayState")
            return
        }
        uiEventLogger.logSenderStateChange(chipState)

        if (chipState == ChipStateSender.FAR_FROM_RECEIVER) {
            removeChip(removalReason = ChipStateSender.FAR_FROM_RECEIVER::class.simpleName!!)
        } else {
            displayChip(ChipSenderInfo(chipState, routeInfo, undoCallback))
        }
    }

    /** Displays the chip view for the given state. */
    override fun updateChipView(
            chipInfo: ChipSenderInfo,
            currentChipView: ViewGroup) {
        val chipState = chipInfo.state
        currentlyDisplayedChipState = chipState

        // App icon
        setIcon(currentChipView, chipInfo.routeInfo.packageName)

        // Text
        val otherDeviceName = chipInfo.routeInfo.name.toString()
        currentChipView.requireViewById<TextView>(R.id.text).apply {
            text = chipState.getChipTextString(context, otherDeviceName)
        }

        // Loading
        currentChipView.requireViewById<View>(R.id.loading).visibility =
            chipState.isMidTransfer.visibleIfTrue()

        // Undo
        val undoView = currentChipView.requireViewById<View>(R.id.undo)
        val undoClickListener = chipState.undoClickListener(
                this, chipInfo.routeInfo, chipInfo.undoCallback, uiEventLogger
        )
        undoView.setOnClickListener(undoClickListener)
        undoView.visibility = (undoClickListener != null).visibleIfTrue()

        // Failure
        currentChipView.requireViewById<View>(R.id.failure_icon).visibility =
            chipState.isTransferFailure.visibleIfTrue()
    }

    override fun animateChipIn(chipView: ViewGroup) {
        ViewHierarchyAnimator.animateAddition(
            chipView.requireViewById<ViewGroup>(R.id.media_ttt_sender_chip_inner),
            ViewHierarchyAnimator.Hotspot.TOP,
            Interpolators.EMPHASIZED_DECELERATE,
            duration = 500L,
            includeMargins = true,
            includeFadeIn = true,
        )
    }

    override fun removeChip(removalReason: String) {
        // Don't remove the chip if we're mid-transfer since the user should still be able to
        // see the status of the transfer. (But do remove it if it's finally timed out.)
        if (currentlyDisplayedChipState?.isMidTransfer == true
                && removalReason != MediaTttRemovalReason.REASON_TIMEOUT) {
            return
        }
        super.removeChip(removalReason)
        currentlyDisplayedChipState = null
    }

    private fun Boolean.visibleIfTrue(): Int {
        return if (this) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }
}

data class ChipSenderInfo(
    val state: ChipStateSender,
    val routeInfo: MediaRoute2Info,
    val undoCallback: IUndoMediaTransferCallback? = null
) : ChipInfoCommon {
    override fun getTimeoutMs() = state.timeout
}

const val SENDER_TAG = "MediaTapToTransferSender"
