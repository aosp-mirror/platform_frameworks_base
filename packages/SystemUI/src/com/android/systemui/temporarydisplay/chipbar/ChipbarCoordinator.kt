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

package com.android.systemui.temporarydisplay.chipbar

import android.content.Context
import android.graphics.Rect
import android.media.MediaRoute2Info
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import com.android.internal.statusbar.IUndoMediaTransferCallback
import com.android.internal.widget.CachingIconView
import com.android.systemui.Gefingerpoken
import com.android.systemui.R
import com.android.systemui.animation.Interpolators
import com.android.systemui.animation.ViewHierarchyAnimator
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.taptotransfer.common.MediaTttLogger
import com.android.systemui.media.taptotransfer.common.MediaTttUtils
import com.android.systemui.media.taptotransfer.sender.ChipStateSender
import com.android.systemui.media.taptotransfer.sender.MediaTttSenderLogger
import com.android.systemui.media.taptotransfer.sender.MediaTttSenderUiEventLogger
import com.android.systemui.media.taptotransfer.sender.TransferStatus
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.temporarydisplay.TemporaryViewDisplayController
import com.android.systemui.temporarydisplay.TemporaryViewInfo
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.view.ViewUtil
import javax.inject.Inject

/**
 * A coordinator for showing/hiding the chipbar.
 *
 * The chipbar is a UI element that displays on top of all content. It appears at the top of the
 * screen and consists of an icon, one line of text, and an optional end icon or action. It will
 * auto-dismiss after some amount of seconds. The user is *not* able to manually dismiss the
 * chipbar.
 *
 * It should be only be used for critical and temporary information that the user *must* be aware
 * of. In general, prefer using heads-up notifications, since they are dismissable and will remain
 * in the list of notifications until the user dismisses them.
 *
 * Only one chipbar may be shown at a time.
 * TODO(b/245610654): Should we just display whichever chipbar was most recently requested, or do we
 *   need to maintain a priority ordering?
 *
 * TODO(b/245610654): Remove all media-related items from this class so it's just for generic
 *   chipbars.
 */
@SysUISingleton
open class ChipbarCoordinator @Inject constructor(
        context: Context,
        @MediaTttSenderLogger logger: MediaTttLogger,
        windowManager: WindowManager,
        @Main mainExecutor: DelayableExecutor,
        accessibilityManager: AccessibilityManager,
        configurationController: ConfigurationController,
        powerManager: PowerManager,
        private val uiEventLogger: MediaTttSenderUiEventLogger,
        private val falsingManager: FalsingManager,
        private val falsingCollector: FalsingCollector,
        private val viewUtil: ViewUtil,
) : TemporaryViewDisplayController<ChipSenderInfo, MediaTttLogger>(
        context,
        logger,
        windowManager,
        mainExecutor,
        accessibilityManager,
        configurationController,
        powerManager,
        R.layout.chipbar,
        MediaTttUtils.WINDOW_TITLE,
        MediaTttUtils.WAKE_REASON,
) {

    private lateinit var parent: ChipbarRootView

    override val windowLayoutParams = commonWindowLayoutParams.apply {
        gravity = Gravity.TOP.or(Gravity.CENTER_HORIZONTAL)
    }

    override fun start() {}

    override fun updateView(
        newInfo: ChipSenderInfo,
        currentView: ViewGroup
    ) {
        // TODO(b/245610654): Adding logging here.

        val chipState = newInfo.state

        // Detect falsing touches on the chip.
        parent = currentView.requireViewById(R.id.media_ttt_sender_chip)
        parent.touchHandler = object : Gefingerpoken {
            override fun onTouchEvent(ev: MotionEvent?): Boolean {
                falsingCollector.onTouchEvent(ev)
                return false
            }
        }

        // App icon
        val iconInfo = MediaTttUtils.getIconInfoFromPackageName(
            context, newInfo.routeInfo.clientPackageName, logger
        )
        val iconView = currentView.requireViewById<CachingIconView>(R.id.app_icon)
        iconView.setImageDrawable(iconInfo.drawable)
        iconView.contentDescription = iconInfo.contentDescription

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
                this,
                newInfo.routeInfo,
                newInfo.undoCallback,
                uiEventLogger,
                falsingManager,
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

    override fun animateViewOut(view: ViewGroup, onAnimationEnd: Runnable) {
        ViewHierarchyAnimator.animateRemoval(
            view.requireViewById<ViewGroup>(R.id.media_ttt_sender_chip_inner),
            ViewHierarchyAnimator.Hotspot.TOP,
            Interpolators.EMPHASIZED_ACCELERATE,
            ANIMATION_DURATION,
            includeMargins = true,
            onAnimationEnd,
        )
    }

    override fun getTouchableRegion(view: View, outRect: Rect) {
        viewUtil.setRectToViewWindowLocation(view, outRect)
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
