/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.screenshot

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserHandle
import android.view.Display
import com.android.internal.R
import com.android.internal.messages.nano.SystemMessageProto
import com.android.systemui.SystemUIApplication
import com.android.systemui.util.NotificationChannels
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Convenience class to handle showing and hiding notifications while taking a screenshot. */
class ScreenshotNotificationsController
@AssistedInject
internal constructor(
    @Assisted private val displayId: Int,
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val devicePolicyManager: DevicePolicyManager,
) {
    private val res = context.resources

    /**
     * Sends a notification that the screenshot capture has failed.
     *
     * Errors for the non-default display are shown in a unique separate notification.
     */
    fun notifyScreenshotError(msgResId: Int) {
        val displayErrorString =
            if (displayId != Display.DEFAULT_DISPLAY) {
                " ($externalDisplayString)"
            } else {
                ""
            }
        val errorMsg = res.getString(msgResId) + displayErrorString

        // Repurpose the existing notification or create a new one
        val builder =
            Notification.Builder(context, NotificationChannels.ALERTS)
                .setTicker(res.getString(com.android.systemui.res.R.string.screenshot_failed_title))
                .setContentTitle(
                    res.getString(com.android.systemui.res.R.string.screenshot_failed_title)
                )
                .setContentText(errorMsg)
                .setSmallIcon(com.android.systemui.res.R.drawable.stat_notify_image_error)
                .setWhen(System.currentTimeMillis())
                .setVisibility(Notification.VISIBILITY_PUBLIC) // ok to show outside lockscreen
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setColor(context.getColor(R.color.system_notification_accent_color))
        val intent =
            devicePolicyManager.createAdminSupportIntent(
                DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE
            )
        if (intent != null) {
            val pendingIntent =
                PendingIntent.getActivityAsUser(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE,
                    null,
                    UserHandle.CURRENT
                )
            builder.setContentIntent(pendingIntent)
        }
        SystemUIApplication.overrideNotificationAppName(context, builder, true)
        val notification = Notification.BigTextStyle(builder).bigText(errorMsg).build()
        // A different id for external displays to keep the 2 error notifications separated.
        val id =
            if (displayId == Display.DEFAULT_DISPLAY) {
                SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT
            } else {
                SystemMessageProto.SystemMessage.NOTE_GLOBAL_SCREENSHOT_EXTERNAL_DISPLAY
            }
        notificationManager.notify(id, notification)
    }

    private val externalDisplayString: String
        get() =
            res.getString(
                com.android.systemui.res.R.string.screenshot_failed_external_display_indication
            )

    /** Factory for [ScreenshotNotificationsController]. */
    @AssistedFactory
    fun interface Factory {
        fun create(displayId: Int): ScreenshotNotificationsController
    }
}
