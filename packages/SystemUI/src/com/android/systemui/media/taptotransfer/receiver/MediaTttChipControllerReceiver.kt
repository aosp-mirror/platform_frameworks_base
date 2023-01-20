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
import com.android.systemui.R
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.ui.binder.TintedIconViewBinder
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.taptotransfer.MediaTttFlags
import com.android.systemui.media.taptotransfer.common.MediaTttIcon
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
        logger: MediaTttReceiverLogger,
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
        private val rippleController: MediaTttReceiverRippleController,
) : TemporaryViewDisplayController<ChipReceiverInfo, MediaTttReceiverLogger>(
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
        val packageName = newInfo.routeInfo.clientPackageName
        var iconInfo = MediaTttUtils.getIconInfoFromPackageName(context, packageName) {
            logger.logPackageNotFound(packageName)
        }

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
        val iconRippleView: ReceiverChipRippleView = view.requireViewById(R.id.icon_glow_ripple)
        val rippleView: ReceiverChipRippleView = view.requireViewById(R.id.ripple)
        animateViewTranslationAndFade(appIconView, -1 * getTranslationAmount(), 1f)
        animateViewTranslationAndFade(iconRippleView, -1 * getTranslationAmount(), 1f)
        // Using withEndAction{} doesn't apply a11y focus when screen is unlocked.
        appIconView.postOnAnimation { view.requestAccessibilityFocus() }
        rippleController.expandToInProgressState(rippleView, iconRippleView)
    }

    override fun animateViewOut(view: ViewGroup, removalReason: String?, onAnimationEnd: Runnable) {
        val appIconView = view.getAppIconView()
        val iconRippleView: ReceiverChipRippleView = view.requireViewById(R.id.icon_glow_ripple)
        val rippleView: ReceiverChipRippleView = view.requireViewById(R.id.ripple)
        if (removalReason == ChipStateReceiver.TRANSFER_TO_RECEIVER_SUCCEEDED.name &&
                mediaTttFlags.isMediaTttReceiverSuccessRippleEnabled()) {
            rippleController.expandToSuccessState(rippleView, onAnimationEnd)
            animateViewTranslationAndFade(
                iconRippleView,
                -1 * getTranslationAmount(),
                0f,
                translationDuration = ICON_TRANSLATION_SUCCEEDED_DURATION,
                alphaDuration = ICON_TRANSLATION_SUCCEEDED_DURATION,
            )
            animateViewTranslationAndFade(
                appIconView,
                -1 * getTranslationAmount(),
                0f,
                translationDuration = ICON_TRANSLATION_SUCCEEDED_DURATION,
                alphaDuration = ICON_TRANSLATION_SUCCEEDED_DURATION,
            )
        } else {
            rippleController.collapseRipple(rippleView, onAnimationEnd)
            animateViewTranslationAndFade(iconRippleView, getTranslationAmount(), 0f)
            animateViewTranslationAndFade(appIconView, getTranslationAmount(), 0f)
        }
    }

    override fun getTouchableRegion(view: View, outRect: Rect) {
        // Even though the app icon view isn't touchable, users might think it is. So, use it as the
        // touchable region to ensure that touches don't get passed to the window below.
        viewUtil.setRectToViewWindowLocation(view.getAppIconView(), outRect)
    }

    /** Animation of view translation and fading. */
    private fun animateViewTranslationAndFade(
        view: View,
        translationYBy: Float,
        alphaEndValue: Float,
        translationDuration: Long = ICON_TRANSLATION_ANIM_DURATION,
        alphaDuration: Long = ICON_ALPHA_ANIM_DURATION,
    ) {
        view.animate()
            .translationYBy(translationYBy)
            .setDuration(translationDuration)
            .start()
        view.animate()
            .alpha(alphaEndValue)
            .setDuration(alphaDuration)
            .start()
    }

    /** Returns the amount that the chip will be translated by in its intro animation. */
    private fun getTranslationAmount(): Float {
        return rippleController.getRippleSize() * 0.5f -
            rippleController.getReceiverIconSize()
    }

    private fun View.getAppIconView(): CachingIconView {
        return this.requireViewById(R.id.app_icon)
    }

    companion object {
        private const val ICON_TRANSLATION_ANIM_DURATION = 500L
        private const val ICON_TRANSLATION_SUCCEEDED_DURATION = 167L
        private val ICON_ALPHA_ANIM_DURATION = 5.frames
    }
}

data class ChipReceiverInfo(
    val routeInfo: MediaRoute2Info,
    val appIconDrawableOverride: Drawable?,
    val appNameOverride: CharSequence?,
    override val windowTitle: String = MediaTttUtils.WINDOW_TITLE_RECEIVER,
    override val wakeReason: String = MediaTttUtils.WAKE_REASON_RECEIVER,
    override val id: String,
    override val priority: ViewPriority = ViewPriority.NORMAL,
) : TemporaryViewInfo()
