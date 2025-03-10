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

package com.android.systemui.statusbar.notification.promoted

import android.app.Notification
import android.app.Notification.BigPictureStyle
import android.app.Notification.BigTextStyle
import android.app.Notification.CallStyle
import android.app.Notification.EXTRA_CHRONOMETER_COUNT_DOWN
import android.app.Notification.EXTRA_SUB_TEXT
import android.app.Notification.EXTRA_TEXT
import android.app.Notification.EXTRA_TITLE
import android.app.Notification.ProgressStyle
import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel.Style
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel.When
import javax.inject.Inject

@SysUISingleton
class PromotedNotificationContentExtractor
@Inject
constructor(
    private val promotedNotificationsProvider: PromotedNotificationsProvider,
    @ShadeDisplayAware private val context: Context,
    private val logger: PromotedNotificationLogger,
) {
    fun extractContent(
        entry: NotificationEntry,
        recoveredBuilder: Notification.Builder,
    ): PromotedNotificationContentModel? {
        if (!PromotedNotificationContentModel.featureFlagEnabled()) {
            logger.logExtractionSkipped(entry, "feature flags disabled")
            return null
        }

        if (!promotedNotificationsProvider.shouldPromote(entry)) {
            logger.logExtractionSkipped(entry, "shouldPromote returned false")
            return null
        }

        val notification = entry.sbn.notification
        if (notification == null) {
            logger.logExtractionFailed(entry, "entry.sbn.notification is null")
            return null
        }

        val contentBuilder = PromotedNotificationContentModel.Builder(entry.key)

        // TODO: Pitch a fit if style is unsupported or mandatory fields are missing once
        // FLAG_PROMOTED_ONGOING is set reliably and we're not testing status bar chips.

        contentBuilder.skeletonSmallIcon = entry.icons.aodIcon?.sourceIcon
        contentBuilder.appName = notification.loadHeaderAppName(context)
        contentBuilder.subText = notification.subText()
        contentBuilder.time = notification.extractWhen()
        contentBuilder.lastAudiblyAlertedMs = entry.lastAudiblyAlertedMs
        contentBuilder.profileBadgeResId = null // TODO
        contentBuilder.title = notification.title()
        contentBuilder.text = notification.text()
        contentBuilder.skeletonLargeIcon = null // TODO

        recoveredBuilder.style?.extractContent(contentBuilder)
            ?: run { contentBuilder.style = Style.Ineligible }

        return contentBuilder.build().also { logger.logExtractionSucceeded(entry, it) }
    }
}

private fun Notification.title(): CharSequence? = extras?.getCharSequence(EXTRA_TITLE)

private fun Notification.text(): CharSequence? = extras?.getCharSequence(EXTRA_TEXT)

private fun Notification.subText(): String? = extras?.getString(EXTRA_SUB_TEXT)

private fun Notification.chronometerCountDown(): Boolean =
    extras?.getBoolean(EXTRA_CHRONOMETER_COUNT_DOWN, /* defaultValue= */ false) ?: false

private fun Notification.extractWhen(): When? {
    val time = `when`
    val showsTime = showsTime()
    val showsChronometer = showsChronometer()
    val countDown = chronometerCountDown()

    return when {
        showsTime -> When(time, When.Mode.Absolute)
        showsChronometer -> When(time, if (countDown) When.Mode.CountDown else When.Mode.CountUp)
        else -> null
    }
}

private fun Notification.Style.extractContent(
    contentBuilder: PromotedNotificationContentModel.Builder
) {
    contentBuilder.style =
        when (this) {
            is BigPictureStyle -> {
                extractContent(contentBuilder)
                Style.BigPicture
            }

            is BigTextStyle -> {
                extractContent(contentBuilder)
                Style.BigText
            }

            is CallStyle -> {
                extractContent(contentBuilder)
                Style.Call
            }

            is ProgressStyle -> {
                extractContent(contentBuilder)
                Style.Progress
            }

            else -> Style.Ineligible
        }
}

private fun BigPictureStyle.extractContent(
    contentBuilder: PromotedNotificationContentModel.Builder
) {
    // TODO?
}

private fun BigTextStyle.extractContent(contentBuilder: PromotedNotificationContentModel.Builder) {
    // TODO?
}

private fun CallStyle.extractContent(contentBuilder: PromotedNotificationContentModel.Builder) {
    contentBuilder.personIcon = null // TODO
    contentBuilder.personName = null // TODO
    contentBuilder.verificationIcon = null // TODO
    contentBuilder.verificationText = null // TODO
}

private fun ProgressStyle.extractContent(contentBuilder: PromotedNotificationContentModel.Builder) {
    // TODO: Create NotificationProgressModel.toSkeleton, or something similar.
    contentBuilder.progress = createProgressModel(0xffffffff.toInt(), 0x00000000)
}
