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
import android.graphics.drawable.Icon
import com.android.internal.widget.NotificationProgressModel
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.notification.promoted.PromotedNotificationUi

/**
 * The content needed to render a promoted notification to surfaces besides the notification stack,
 * like the skeleton view on AOD or the status bar chip.
 */
data class PromotedNotificationContentModel(
    val key: String,

    // for all styles:
    val skeletonSmallIcon: Icon?, // TODO(b/377568176): Make into an IconModel.
    val appName: CharSequence?,
    val subText: CharSequence?,
    val time: When?,
    val lastAudiblyAlertedMs: Long,
    @DrawableRes val profileBadgeResId: Int?,
    val title: CharSequence?,
    val text: CharSequence?,
    val skeletonLargeIcon: Icon?, // TODO(b/377568176): Make into an IconModel.
    val style: Style,

    // for CallStyle:
    val personIcon: Icon?, // TODO(b/377568176): Make into an IconModel.
    val personName: CharSequence?,
    val verificationIcon: Icon?, // TODO(b/377568176): Make into an IconModel.
    val verificationText: CharSequence?,

    // for ProgressStyle:
    val progress: NotificationProgressModel?,
) {
    class Builder(val key: String) {
        var skeletonSmallIcon: Icon? = null
        var appName: CharSequence? = null
        var subText: CharSequence? = null
        var time: When? = null
        var lastAudiblyAlertedMs: Long = 0L
        @DrawableRes var profileBadgeResId: Int? = null
        var title: CharSequence? = null
        var text: CharSequence? = null
        var skeletonLargeIcon: Icon? = null
        var style: Style = Style.Ineligible

        // for CallStyle:
        var personIcon: Icon? = null
        var personName: CharSequence? = null
        var verificationIcon: Icon? = null
        var verificationText: CharSequence? = null

        // for ProgressStyle:
        var progress: NotificationProgressModel? = null

        fun build() =
            PromotedNotificationContentModel(
                key = key,
                skeletonSmallIcon = skeletonSmallIcon,
                appName = appName,
                subText = subText,
                time = time,
                lastAudiblyAlertedMs = lastAudiblyAlertedMs,
                profileBadgeResId = profileBadgeResId,
                title = title,
                text = text,
                skeletonLargeIcon = skeletonLargeIcon,
                style = style,
                personIcon = personIcon,
                personName = personName,
                verificationIcon = verificationIcon,
                verificationText = verificationText,
                progress = progress,
            )
    }

    /** The timestamp associated with a notification, along with the mode used to display it. */
    data class When(val time: Long, val mode: Mode) {
        /** The mode used to display a notification's `when` value. */
        enum class Mode {
            Absolute,
            CountDown,
            CountUp,
        }
    }

    /** The promotion-eligible style of a notification, or [Style.Ineligible] if not. */
    enum class Style {
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
    }
}
