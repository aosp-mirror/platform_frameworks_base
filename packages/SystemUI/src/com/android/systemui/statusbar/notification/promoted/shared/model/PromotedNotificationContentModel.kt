/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.promoted.shared.model

import android.annotation.DrawableRes
import android.app.Notification
import android.app.Notification.FLAG_PROMOTED_ONGOING
import android.graphics.drawable.Icon
import androidx.annotation.ColorInt
import com.android.internal.widget.NotificationProgressModel
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.notification.promoted.PromotedNotificationUi

/**
 * The content needed to render a promoted notification to surfaces besides the notification stack,
 * like the skeleton view on AOD or the status bar chip.
 */
data class PromotedNotificationContentModel(
    val identity: Identity,

    // for all styles:
    /**
     * True if this notification was automatically promoted - see [AutomaticPromotionCoordinator].
     */
    val wasPromotedAutomatically: Boolean,
    val skeletonSmallIcon: Icon?, // TODO(b/377568176): Make into an IconModel.
    val appName: CharSequence?,
    val subText: CharSequence?,
    val shortCriticalText: String?,
    /**
     * The timestamp associated with the notification. Null if the timestamp should not be
     * displayed.
     */
    val time: When?,
    val lastAudiblyAlertedMs: Long,
    @DrawableRes val profileBadgeResId: Int?,
    val title: CharSequence?,
    val text: CharSequence?,
    val skeletonLargeIcon: Icon?, // TODO(b/377568176): Make into an IconModel.
    val oldProgress: OldProgress?,
    val colors: Colors,
    val style: Style,

    // for CallStyle:
    val personIcon: Icon?, // TODO(b/377568176): Make into an IconModel.
    val personName: CharSequence?,
    val verificationIcon: Icon?, // TODO(b/377568176): Make into an IconModel.
    val verificationText: CharSequence?,

    // for ProgressStyle:
    val newProgress: NotificationProgressModel?,
) {
    class Builder(val key: String) {
        var wasPromotedAutomatically: Boolean = false
        var skeletonSmallIcon: Icon? = null
        var appName: CharSequence? = null
        var subText: CharSequence? = null
        var time: When? = null
        var shortCriticalText: String? = null
        var lastAudiblyAlertedMs: Long = 0L
        @DrawableRes var profileBadgeResId: Int? = null
        var title: CharSequence? = null
        var text: CharSequence? = null
        var skeletonLargeIcon: Icon? = null
        var oldProgress: OldProgress? = null
        var style: Style = Style.Ineligible
        var colors: Colors = Colors(backgroundColor = 0, primaryTextColor = 0)

        // for CallStyle:
        var personIcon: Icon? = null
        var personName: CharSequence? = null
        var verificationIcon: Icon? = null
        var verificationText: CharSequence? = null

        // for ProgressStyle:
        var newProgress: NotificationProgressModel? = null

        fun build() =
            PromotedNotificationContentModel(
                identity = Identity(key, style),
                wasPromotedAutomatically = wasPromotedAutomatically,
                skeletonSmallIcon = skeletonSmallIcon,
                appName = appName,
                subText = subText,
                shortCriticalText = shortCriticalText,
                time = time,
                lastAudiblyAlertedMs = lastAudiblyAlertedMs,
                profileBadgeResId = profileBadgeResId,
                title = title,
                text = text,
                skeletonLargeIcon = skeletonLargeIcon,
                oldProgress = oldProgress,
                colors = colors,
                style = style,
                personIcon = personIcon,
                personName = personName,
                verificationIcon = verificationIcon,
                verificationText = verificationText,
                newProgress = newProgress,
            )
    }

    data class Identity(val key: String, val style: Style)

    /** The timestamp associated with a notification, along with the mode used to display it. */
    data class When(val time: Long, val mode: Mode) {
        /** The mode used to display a notification's `when` value. */
        enum class Mode {
            /** No custom mode requested by the notification. */
            BasicTime,
            /** Show the notification's time as a chronometer that counts down to [time]. */
            CountDown,
            /** Show the notification's time as a chronometer that counts up from [time]. */
            CountUp,
        }
    }

    /** The colors used to display the notification. */
    data class Colors(@ColorInt val backgroundColor: Int, @ColorInt val primaryTextColor: Int)

    /** The fields needed to render the old-style progress bar. */
    data class OldProgress(val progress: Int, val max: Int, val isIndeterminate: Boolean)

    /** The promotion-eligible style of a notification, or [Style.Ineligible] if not. */
    enum class Style {
        Base, // style == null
        BigPicture,
        BigText,
        Call,
        Progress,
        Ineligible,
    }

    companion object {
        @JvmStatic
        fun featureFlagEnabled(): Boolean =
            PromotedNotificationUi.isEnabled || StatusBarNotifChips.isEnabled

        /**
         * Returns true if the given notification should be considered promoted when deciding
         * whether or not to show the status bar chip UI.
         */
        @JvmStatic
        fun isPromotedForStatusBarChip(notification: Notification): Boolean {
            // Notification.isPromotedOngoing checks the ui_rich_ongoing flag, but we want the
            // status bar chip to be ready before all the features behind the ui_rich_ongoing flag
            // are ready.
            val isPromotedForStatusBarChip =
                StatusBarNotifChips.isEnabled && (notification.flags and FLAG_PROMOTED_ONGOING) != 0
            return notification.isPromotedOngoing() || isPromotedForStatusBarChip
        }
    }
}
