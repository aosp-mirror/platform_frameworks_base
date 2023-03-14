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
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaRoute2Info
import android.os.Handler
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import com.android.internal.widget.CachingIconView
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.ui.binder.TintedIconViewBinder
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.taptotransfer.MediaTttFlags
import com.android.systemui.media.taptotransfer.common.MediaTttIcon
import com.android.systemui.media.taptotransfer.common.MediaTttLogger
import com.android.systemui.media.taptotransfer.common.MediaTttUtils
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.temporarydisplay.TemporaryViewDisplayController
import com.android.systemui.temporarydisplay.TemporaryViewInfo
import com.android.systemui.temporarydisplay.ViewPriority
import com.android.systemui.util.animation.AnimationUtil.Companion.frames
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.view.ViewUtil
import com.android.systemui.util.wakelock.WakeLock
import javax.inject.Inject

/**
 * A controller to display and hide the Media Tap-To-Transfer chip on the **receiving** device.
 *
 * This chip is shown when a user is transferring media to/from a sending device and this device.
 *
 * TODO(b/245610654): Re-name this to be MediaTttReceiverCoordinator.
 */
@SysUISingleton
open class MediaTttChipControllerReceiver @Inject constructor(
        private val commandQueue: CommandQueue,
        context: Context,
        @MediaTttReceiverLogger logger: MediaTttLogger<ChipReceiverInfo>,
        windowManager: WindowManager,
        mainExecutor: DelayableExecutor,
        accessibilityManager: AccessibilityManager,
        configurationController: ConfigurationController,
        dumpManager: DumpManager,
        powerManager: PowerManager,
        @Main private val mainHandler: Handler,
        private val mediaTttFlags: MediaTttFlags,
        private val uiEventLogger: MediaTttReceiverUiEventLogger,
        private val viewUtil: ViewUtil,
        wakeLockBuilder: WakeLock.Builder,
        systemClock: SystemClock,
) : TemporaryViewDisplayController<ChipReceiverInfo, MediaTttLogger<ChipReceiverInfo>>(
        context,
        logger,
        windowManager,
        mainExecutor,
        accessibilityManager,
        configurationController,
        dumpManager,
        powerManager,
        R.layout.media_ttt_chip_receiver,
        wakeLockBuilder,
        systemClock,
) {
    @SuppressLint("WrongConstant") // We're allowed to use LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    override val windowLayoutParams = commonWindowLayoutParams.apply {
        gravity = Gravity.BOTTOM.or(Gravity.CENTER_HORIZONTAL)
        // Params below are needed for the ripple to work correctly
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0 // Ignore insets from all system bars
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

    private var maxRippleWidth: Float = 0f
    private var maxRippleHeight: Float = 0f

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
            logger.logStateChangeError(displayState)
            return
        }
        uiEventLogger.logReceiverStateChange(chipState)

        if (chipState != ChipStateReceiver.CLOSE_TO_SENDER) {
            removeView(routeInfo.id, removalReason = chipState.name)
            return
        }
        if (appIcon == null) {
            displayView(
                ChipReceiverInfo(
                    routeInfo,
                    appIconDrawableOverride = null,
                    appName,
                    id = routeInfo.id,
                )
            )
            return
        }

        appIcon.loadDrawableAsync(
                context,
                Icon.OnDrawableLoadedListener { drawable ->
                    displayView(
                        ChipReceiverInfo(
                            routeInfo,
                            drawable,
                            appName,
                            id = routeInfo.id,
                        )
                    )
                },
                // Notify the listener on the main handler since the listener will update
                // the UI.
                mainHandler
        )
    }

    override fun start() {
        super.start()
        if (mediaTttFlags.isMediaTttEnabled()) {
            commandQueue.addCallback(commandQueueCallbacks)
        }
    }

    override fun updateView(newInfo: ChipReceiverInfo, currentView: ViewGroup) {
        var iconInfo = MediaTttUtils.getIconInfoFromPackageName(
            context, newInfo.routeInfo.clientPackageName, logger
        )

        if (newInfo.appNameOverride != null) {
            iconInfo = iconInfo.copy(
                contentDescription = ContentDescription.Loaded(newInfo.appNameOverride.toString())
            )
        }

        if (newInfo.appIconDrawableOverride != null) {
            iconInfo = iconInfo.copy(
                icon = MediaTttIcon.Loaded(newInfo.appIconDrawableOverride),
                isAppIcon = true,
            )
        }

        val iconPadding =
            if (iconInfo.isAppIcon) {
                0
            } else {
                context.resources.getDimensionPixelSize(R.dimen.media_ttt_generic_icon_padding)
            }

        val iconView = currentView.getAppIconView()
        iconView.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        TintedIconViewBinder.bind(iconInfo.toTintedIcon(), iconView)
    }

    override fun animateViewIn(view: ViewGroup) {
        val appIconView = view.getAppIconView()
        appIconView.animate()
                .translationYBy(-1 * getTranslationAmount().toFloat())
                .setDuration(ICON_TRANSLATION_ANIM_DURATION)
                .start()
        appIconView.animate()
                .alpha(1f)
                .setDuration(ICON_ALPHA_ANIM_DURATION)
                .start()
        // Using withEndAction{} doesn't apply a11y focus when screen is unlocked.
        appIconView.postOnAnimation { view.requestAccessibilityFocus() }
        expandRipple(view.requireViewById(R.id.ripple))
    }

    override fun animateViewOut(view: ViewGroup, removalReason: String?, onAnimationEnd: Runnable) {
        val appIconView = view.getAppIconView()
        appIconView.animate()
                .translationYBy(getTranslationAmount().toFloat())
                .setDuration(ICON_TRANSLATION_ANIM_DURATION)
                .start()
        appIconView.animate()
                .alpha(0f)
                .setDuration(ICON_ALPHA_ANIM_DURATION)
                .start()

        val rippleView: ReceiverChipRippleView = view.requireViewById(R.id.ripple)
        if (removalReason == ChipStateReceiver.TRANSFER_TO_RECEIVER_SUCCEEDED.name &&
                mediaTttFlags.isMediaTttReceiverSuccessRippleEnabled()) {
            expandRippleToFull(rippleView, onAnimationEnd)
        } else {
            rippleView.collapseRipple(onAnimationEnd)
        }
    }

    override fun getTouchableRegion(view: View, outRect: Rect) {
        // Even though the app icon view isn't touchable, users might think it is. So, use it as the
        // touchable region to ensure that touches don't get passed to the window below.
        viewUtil.setRectToViewWindowLocation(view.getAppIconView(), outRect)
    }

    /** Returns the amount that the chip will be translated by in its intro animation. */
    private fun getTranslationAmount(): Int {
        return context.resources.getDimensionPixelSize(R.dimen.media_ttt_receiver_vert_translation)
    }

    private fun expandRipple(rippleView: ReceiverChipRippleView) {
        if (rippleView.rippleInProgress()) {
            // Skip if ripple is still playing
            return
        }

        // In case the device orientation changes, we need to reset the layout.
        rippleView.addOnLayoutChangeListener (
            View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v == null) return@OnLayoutChangeListener

                val layoutChangedRippleView = v as ReceiverChipRippleView
                layoutRipple(layoutChangedRippleView)
                layoutChangedRippleView.invalidate()
            }
        )
        rippleView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View?) {}

            override fun onViewAttachedToWindow(view: View?) {
                if (view == null) {
                    return
                }
                val attachedRippleView = view as ReceiverChipRippleView
                layoutRipple(attachedRippleView)
                attachedRippleView.expandRipple()
                attachedRippleView.removeOnAttachStateChangeListener(this)
            }
        })
    }

    private fun layoutRipple(rippleView: ReceiverChipRippleView, isFullScreen: Boolean = false) {
        val windowBounds = windowManager.currentWindowMetrics.bounds
        val height = windowBounds.height().toFloat()
        val width = windowBounds.width().toFloat()

        if (isFullScreen) {
            maxRippleHeight = height * 2f
            maxRippleWidth = width * 2f
        } else {
            maxRippleHeight = height / 2f
            maxRippleWidth = width / 2f
        }
        rippleView.setMaxSize(maxRippleWidth, maxRippleHeight)
        // Center the ripple on the bottom of the screen in the middle.
        rippleView.setCenter(width * 0.5f, height)
        val color = Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColorAccent)
        rippleView.setColor(color, 70)
    }

    private fun View.getAppIconView(): CachingIconView {
        return this.requireViewById(R.id.app_icon)
    }

    private fun expandRippleToFull(rippleView: ReceiverChipRippleView, onAnimationEnd: Runnable?) {
        layoutRipple(rippleView, true)
        rippleView.expandToFull(maxRippleHeight, onAnimationEnd)
    }
}

val ICON_TRANSLATION_ANIM_DURATION = 30.frames
val ICON_ALPHA_ANIM_DURATION = 5.frames

data class ChipReceiverInfo(
    val routeInfo: MediaRoute2Info,
    val appIconDrawableOverride: Drawable?,
    val appNameOverride: CharSequence?,
    override val windowTitle: String = MediaTttUtils.WINDOW_TITLE_RECEIVER,
    override val wakeReason: String = MediaTttUtils.WAKE_REASON_RECEIVER,
    override val id: String,
    override val priority: ViewPriority = ViewPriority.NORMAL,
) : TemporaryViewInfo()
