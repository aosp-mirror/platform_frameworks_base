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

package com.android.systemui.media.taptotransfer.receiver

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaRoute2Info
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.taptotransfer.common.MediaTttLogger
import com.android.systemui.media.taptotransfer.common.MediaTttUtils
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.temporarydisplay.DEFAULT_TIMEOUT_MILLIS
import com.android.systemui.temporarydisplay.TemporaryViewDisplayController
import com.android.systemui.temporarydisplay.TemporaryViewInfo
import com.android.systemui.util.animation.AnimationUtil.Companion.frames
import com.android.systemui.util.concurrency.DelayableExecutor
import javax.inject.Inject

/**
 * A controller to display and hide the Media Tap-To-Transfer chip on the **receiving** device.
 *
 * This chip is shown when a user is transferring media to/from a sending device and this device.
 */
@SysUISingleton
class MediaTttChipControllerReceiver @Inject constructor(
        commandQueue: CommandQueue,
        context: Context,
        @MediaTttReceiverLogger logger: MediaTttLogger,
        windowManager: WindowManager,
        mainExecutor: DelayableExecutor,
        accessibilityManager: AccessibilityManager,
        configurationController: ConfigurationController,
        powerManager: PowerManager,
        @Main private val mainHandler: Handler,
        private val uiEventLogger: MediaTttReceiverUiEventLogger,
) : TemporaryViewDisplayController<ChipReceiverInfo, MediaTttLogger>(
        context,
        logger,
        windowManager,
        mainExecutor,
        accessibilityManager,
        configurationController,
        powerManager,
        R.layout.media_ttt_chip_receiver,
        MediaTttUtils.WINDOW_TITLE,
        MediaTttUtils.WAKE_REASON,
) {
    @SuppressLint("WrongConstant") // We're allowed to use LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    override val windowLayoutParams = commonWindowLayoutParams.apply {
        gravity = Gravity.BOTTOM.or(Gravity.CENTER_HORIZONTAL)
        // Params below are needed for the ripple to work correctly
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0 // Ignore insets from all system bars
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    private val commandQueueCallbacks = object : CommandQueue.Callbacks {
        override fun updateMediaTapToTransferReceiverDisplay(
            @StatusBarManager.MediaTransferReceiverState displayState: Int,
            routeInfo: MediaRoute2Info,
            appIcon: Icon?,
            appName: CharSequence?
        ) {
            this@MediaTttChipControllerReceiver.updateMediaTapToTransferReceiverDisplay(
                displayState, routeInfo, appIcon, appName
            )
        }
    }

    init {
        commandQueue.addCallback(commandQueueCallbacks)
    }

    private fun updateMediaTapToTransferReceiverDisplay(
        @StatusBarManager.MediaTransferReceiverState displayState: Int,
        routeInfo: MediaRoute2Info,
        appIcon: Icon?,
        appName: CharSequence?
    ) {
        val chipState: ChipStateReceiver? = ChipStateReceiver.getReceiverStateFromId(displayState)
        val stateName = chipState?.name ?: "Invalid"
        logger.logStateChange(stateName, routeInfo.id, routeInfo.clientPackageName)

        if (chipState == null) {
            Log.e(RECEIVER_TAG, "Unhandled MediaTransferReceiverState $displayState")
            return
        }
        uiEventLogger.logReceiverStateChange(chipState)

        if (chipState == ChipStateReceiver.FAR_FROM_SENDER) {
            removeView(removalReason = ChipStateReceiver.FAR_FROM_SENDER::class.simpleName!!)
            return
        }
        if (appIcon == null) {
            displayView(ChipReceiverInfo(routeInfo, appIconDrawableOverride = null, appName))
            return
        }

        appIcon.loadDrawableAsync(
                context,
                Icon.OnDrawableLoadedListener { drawable ->
                    displayView(ChipReceiverInfo(routeInfo, drawable, appName))
                },
                // Notify the listener on the main handler since the listener will update
                // the UI.
                mainHandler
        )
    }

    override fun updateView(newInfo: ChipReceiverInfo, currentView: ViewGroup) {
        super.updateView(newInfo, currentView)

        val iconInfo = MediaTttUtils.getIconInfoFromPackageName(
            context, newInfo.routeInfo.clientPackageName, logger
        )
        val iconDrawable = newInfo.appIconDrawableOverride ?: iconInfo.drawable
        val iconContentDescription = newInfo.appNameOverride ?: iconInfo.contentDescription
        val iconSize = context.resources.getDimensionPixelSize(
            if (iconInfo.isAppIcon) {
                R.dimen.media_ttt_icon_size_receiver
            } else {
                R.dimen.media_ttt_generic_icon_size_receiver
            }
        )

        MediaTttUtils.setIcon(
            currentView.requireViewById(R.id.app_icon),
            iconDrawable,
            iconContentDescription,
            iconSize,
        )
    }

    override fun animateViewIn(view: ViewGroup) {
        val appIconView = view.requireViewById<View>(R.id.app_icon)
        appIconView.animate()
                .translationYBy(-1 * getTranslationAmount().toFloat())
                .setDuration(30.frames)
                .start()
        appIconView.animate()
                .alpha(1f)
                .setDuration(5.frames)
                .start()
        // Using withEndAction{} doesn't apply a11y focus when screen is unlocked.
        appIconView.postOnAnimation { view.requestAccessibilityFocus() }
        startRipple(view.requireViewById(R.id.ripple))
    }

    /** Returns the amount that the chip will be translated by in its intro animation. */
    private fun getTranslationAmount(): Int {
        return context.resources.getDimensionPixelSize(R.dimen.media_ttt_receiver_vert_translation)
    }

    private fun startRipple(rippleView: ReceiverChipRippleView) {
        if (rippleView.rippleInProgress) {
            // Skip if ripple is still playing
            return
        }
        rippleView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View?) {}

            override fun onViewAttachedToWindow(view: View?) {
                if (view == null) {
                    return
                }
                val attachedRippleView = view as ReceiverChipRippleView
                layoutRipple(attachedRippleView)
                attachedRippleView.startRipple()
                attachedRippleView.removeOnAttachStateChangeListener(this)
            }
        })
    }

    private fun layoutRipple(rippleView: ReceiverChipRippleView) {
        val windowBounds = windowManager.currentWindowMetrics.bounds
        val height = windowBounds.height()
        val width = windowBounds.width()

        val maxDiameter = height / 2.5f
        rippleView.setMaxSize(maxDiameter, maxDiameter)
        // Center the ripple on the bottom of the screen in the middle.
        rippleView.setCenter(width * 0.5f, height.toFloat())
        val color = Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColorAccent)
        rippleView.setColor(color, 70)
    }
}

data class ChipReceiverInfo(
    val routeInfo: MediaRoute2Info,
    val appIconDrawableOverride: Drawable?,
    val appNameOverride: CharSequence?
) : TemporaryViewInfo {
    override fun getTimeoutMs() = DEFAULT_TIMEOUT_MILLIS
}

private const val RECEIVER_TAG = "MediaTapToTransferRcvr"
