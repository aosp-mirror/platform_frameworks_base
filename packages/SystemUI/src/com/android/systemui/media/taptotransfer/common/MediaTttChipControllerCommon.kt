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

package com.android.systemui.media.taptotransfer.common

import android.annotation.LayoutRes
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_ICONS
import android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT
import android.widget.LinearLayout
import com.android.internal.widget.CachingIconView
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.gesture.TapGestureDetector
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.view.ViewUtil

/**
 * A superclass controller that provides common functionality for showing chips on the sender device
 * and the receiver device.
 *
 * Subclasses need to override and implement [updateChipView], which is where they can control what
 * gets displayed to the user.
 *
 * The generic type T is expected to contain all the information necessary for the subclasses to
 * display the chip in a certain state, since they receive <T> in [updateChipView].
 */
abstract class MediaTttChipControllerCommon<T : ChipInfoCommon>(
    internal val context: Context,
    internal val logger: MediaTttLogger,
    private val windowManager: WindowManager,
    private val viewUtil: ViewUtil,
    @Main private val mainExecutor: DelayableExecutor,
    private val accessibilityManager: AccessibilityManager,
    private val tapGestureDetector: TapGestureDetector,
    private val powerManager: PowerManager,
    @LayoutRes private val chipLayoutRes: Int
) {
    /** The window layout parameters we'll use when attaching the view to a window. */
    @SuppressLint("WrongConstant") // We're allowed to use TYPE_VOLUME_OVERLAY
    private val windowLayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP.or(Gravity.CENTER_HORIZONTAL)
        type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        title = WINDOW_TITLE
        format = PixelFormat.TRANSLUCENT
        setTrustedOverlay()
    }

    /** The chip view currently being displayed. Null if the chip is not being displayed. */
    private var chipView: ViewGroup? = null

    /** A [Runnable] that, when run, will cancel the pending timeout of the chip. */
    private var cancelChipViewTimeout: Runnable? = null

    /**
     * Displays the chip with the current state.
     *
     * This method handles inflating and attaching the view, then delegates to [updateChipView] to
     * display the correct information in the chip.
     */
    fun displayChip(chipInfo: T) {
        val oldChipView = chipView
        if (chipView == null) {
            chipView = LayoutInflater
                .from(context)
                .inflate(chipLayoutRes, null) as ViewGroup
        }
        val currentChipView = chipView!!

        updateChipView(chipInfo, currentChipView)

        // Add view if necessary
        if (oldChipView == null) {
            tapGestureDetector.addOnGestureDetectedCallback(TAG, this::onScreenTapped)
            windowManager.addView(chipView, windowLayoutParams)
            // Wake the screen so the user will see the chip
            powerManager.wakeUp(
                SystemClock.uptimeMillis(),
                PowerManager.WAKE_REASON_APPLICATION,
                "com.android.systemui:media_tap_to_transfer_activated"
            )
            animateChipIn(currentChipView)
        }

        // Cancel and re-set the chip timeout each time we get a new state.
        val timeout = accessibilityManager.getRecommendedTimeoutMillis(
            chipInfo.getTimeoutMs().toInt(),
            // Not all chips have controls so FLAG_CONTENT_CONTROLS might be superfluous, but
            // include it just to be safe.
            FLAG_CONTENT_ICONS or FLAG_CONTENT_TEXT or FLAG_CONTENT_CONTROLS
       )
        cancelChipViewTimeout?.run()
        cancelChipViewTimeout = mainExecutor.executeDelayed(
            { removeChip(MediaTttRemovalReason.REASON_TIMEOUT) },
            timeout.toLong()
        )
    }

    /**
     * Hides the chip.
     *
     * @param removalReason a short string describing why the chip was removed (timeout, state
     *     change, etc.)
     */
    open fun removeChip(removalReason: String) {
        if (chipView == null) { return }
        logger.logChipRemoval(removalReason)
        tapGestureDetector.removeOnGestureDetectedCallback(TAG)
        windowManager.removeView(chipView)
        chipView = null
        // No need to time the chip out since it's already gone
        cancelChipViewTimeout?.run()
    }

    /**
     * A method implemented by subclasses to update [currentChipView] based on [chipInfo].
     */
    abstract fun updateChipView(chipInfo: T, currentChipView: ViewGroup)

    /**
     * A method that can be implemented by subclcasses to do custom animations for when the chip
     * appears.
     */
    open fun animateChipIn(chipView: ViewGroup) {}

    /**
     * Returns the size that the icon should be, or null if no size override is needed.
     */
    open fun getIconSize(isAppIcon: Boolean): Int? = null

    /**
     * An internal method to set the icon on the view.
     *
     * This is in the common superclass since both the sender and the receiver show an icon.
     *
     * @param appPackageName the package name of the app playing the media. Will be used to fetch
     *   the app icon and app name if overrides aren't provided.
     */
    internal fun setIcon(
        currentChipView: ViewGroup,
        appPackageName: String?,
        appIconDrawableOverride: Drawable? = null,
        appNameOverride: CharSequence? = null,
    ) {
        val appIconView = currentChipView.requireViewById<CachingIconView>(R.id.app_icon)
        val iconInfo = getIconInfo(appPackageName)

        getIconSize(iconInfo.isAppIcon)?.let { size ->
            val lp = appIconView.layoutParams
            lp.width = size
            lp.height = size
            appIconView.layoutParams = lp
        }

        appIconView.contentDescription = appNameOverride ?: iconInfo.iconName
        appIconView.setImageDrawable(appIconDrawableOverride ?: iconInfo.icon)
    }

    /**
     * Returns the information needed to display the icon.
     *
     * The information will either contain app name and icon of the app playing media, or a default
     * name and icon if we can't find the app name/icon.
     */
    private fun getIconInfo(appPackageName: String?): IconInfo {
        if (appPackageName != null) {
            try {
                return IconInfo(
                    iconName = context.packageManager.getApplicationInfo(
                        appPackageName, PackageManager.ApplicationInfoFlags.of(0)
                    ).loadLabel(context.packageManager).toString(),
                    icon = context.packageManager.getApplicationIcon(appPackageName),
                    isAppIcon = true
                )
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Cannot find package $appPackageName", e)
            }
        }
        return IconInfo(
            iconName = context.getString(R.string.media_output_dialog_unknown_launch_app_name),
            icon = context.resources.getDrawable(R.drawable.ic_cast).apply {
                this.setTint(
                    Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary)
                )
            },
            isAppIcon = false
        )
    }

    private fun onScreenTapped(e: MotionEvent) {
        val view = chipView ?: return
        // If the tap is within the chip bounds, we shouldn't hide the chip (in case users think the
        // chip is tappable).
        if (!viewUtil.touchIsWithinView(view, e.x, e.y)) {
            removeChip(MediaTttRemovalReason.REASON_SCREEN_TAP)
        }
    }
}

// Used in CTS tests UpdateMediaTapToTransferSenderDisplayTest and
// UpdateMediaTapToTransferReceiverDisplayTest
private const val WINDOW_TITLE = "Media Transfer Chip View"
private val TAG = MediaTttChipControllerCommon::class.simpleName!!

object MediaTttRemovalReason {
    const val REASON_TIMEOUT = "TIMEOUT"
    const val REASON_SCREEN_TAP = "SCREEN_TAP"
}

private data class IconInfo(
    val iconName: String,
    val icon: Drawable,
    /** True if [icon] is the app's icon, and false if [icon] is some generic default icon. */
    val isAppIcon: Boolean
)
