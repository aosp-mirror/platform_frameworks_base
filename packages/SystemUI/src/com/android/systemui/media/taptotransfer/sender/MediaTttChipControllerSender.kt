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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import com.android.internal.statusbar.IUndoMediaTransferCallback
import com.android.systemui.R
import com.android.systemui.animation.Interpolators
import com.android.systemui.animation.ViewHierarchyAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.taptotransfer.common.MediaTttLogger
import com.android.systemui.media.taptotransfer.common.MediaTttUtils
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.temporarydisplay.TemporaryDisplayRemovalReason
import com.android.systemui.temporarydisplay.TemporaryViewDisplayController
import com.android.systemui.temporarydisplay.TemporaryViewInfo
import com.android.systemui.util.concurrency.DelayableExecutor
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
        @Main mainExecutor: DelayableExecutor,
        accessibilityManager: AccessibilityManager,
        configurationController: ConfigurationController,
        powerManager: PowerManager,
        private val uiEventLogger: MediaTttSenderUiEventLogger
) : TemporaryViewDisplayController<ChipSenderInfo, MediaTttLogger>(
        context,
        logger,
        windowManager,
        mainExecutor,
        accessibilityManager,
        configurationController,
        powerManager,
        R.layout.media_ttt_chip,
        MediaTttUtils.WINDOW_TITLE,
        MediaTttUtils.WAKE_REASON,
) {
    override val windowLayoutParams = commonWindowLayoutParams.apply {
        gravity = Gravity.TOP.or(Gravity.CENTER_HORIZONTAL)
    }

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
        logger.logStateChange(stateName, routeInfo.id, routeInfo.clientPackageName)

        if (chipState == null) {
            Log.e(SENDER_TAG, "Unhandled MediaTransferSenderState $displayState")
            return
        }
        uiEventLogger.logSenderStateChange(chipState)

        if (chipState == ChipStateSender.FAR_FROM_RECEIVER) {
            removeView(removalReason = ChipStateSender.FAR_FROM_RECEIVER.name)
        } else {
            displayView(ChipSenderInfo(chipState, routeInfo, undoCallback))
        }
    }

    override fun updateView(
        newInfo: ChipSenderInfo,
        currentView: ViewGroup
    ) {
        super.updateView(newInfo, currentView)

        val chipState = newInfo.state

        // App icon
        val iconInfo = MediaTttUtils.getIconInfoFromPackageName(
            context, newInfo.routeInfo.clientPackageName, logger
        )
        MediaTttUtils.setIcon(
            currentView.requireViewById(R.id.app_icon),
            iconInfo.drawable,
            iconInfo.contentDescription
        )

        // Text
        val otherDeviceName = newInfo.routeInfo.name.toString()
        val chipText = chipState.getChipTextString(context, otherDeviceName)
        currentView.requireViewById<TextView>(R.id.text).text = chipText

        // Loading
        currentView.requireViewById<View>(R.id.loading).visibility =
            (chipState.transferStatus == TransferStatus.IN_PROGRESS).visibleIfTrue()

        // Undo
        val undoView = currentView.requireViewById<View>(R.id.undo)
        val undoClickListener = chipState.undoClickListener(
                this, newInfo.routeInfo, newInfo.undoCallback, uiEventLogger
        )
        undoView.setOnClickListener(undoClickListener)
        undoView.visibility = (undoClickListener != null).visibleIfTrue()

        // Failure
        currentView.requireViewById<View>(R.id.failure_icon).visibility =
            (chipState.transferStatus == TransferStatus.FAILED).visibleIfTrue()

        // For accessibility
        currentView.requireViewById<ViewGroup>(
                R.id.media_ttt_sender_chip_inner
        ).contentDescription = "${iconInfo.contentDescription} $chipText"
    }

    override fun animateViewIn(view: ViewGroup) {
        val chipInnerView = view.requireViewById<ViewGroup>(R.id.media_ttt_sender_chip_inner)
        ViewHierarchyAnimator.animateAddition(
            chipInnerView,
            ViewHierarchyAnimator.Hotspot.TOP,
            Interpolators.EMPHASIZED_DECELERATE,
            duration = ANIMATION_DURATION,
            includeMargins = true,
            includeFadeIn = true,
            // We can only request focus once the animation finishes.
            onAnimationEnd = { chipInnerView.requestAccessibilityFocus() },
        )
    }

    override fun removeView(removalReason: String) {
        // Don't remove the chip if we're in progress or succeeded, since the user should still be
        // able to see the status of the transfer. (But do remove it if it's finally timed out.)
        val transferStatus = info?.state?.transferStatus
        if (
            (transferStatus == TransferStatus.IN_PROGRESS ||
                transferStatus == TransferStatus.SUCCEEDED) &&
            removalReason != TemporaryDisplayRemovalReason.REASON_TIMEOUT
        ) {
            logger.logRemovalBypass(
                removalReason, bypassReason = "transferStatus=${transferStatus.name}"
            )
            return
        }
        super.removeView(removalReason)
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
) : TemporaryViewInfo {
    override fun getTimeoutMs() = state.timeout
}

const val SENDER_TAG = "MediaTapToTransferSender"
private const val ANIMATION_DURATION = 500L
