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

package com.android.systemui.inputdevice.tutorial.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.inputdevice.tutorial.domain.interactor.TutorialSchedulerInteractor
import com.android.systemui.inputdevice.tutorial.domain.interactor.TutorialSchedulerInteractor.Companion.TAG
import com.android.systemui.inputdevice.tutorial.domain.interactor.TutorialSchedulerInteractor.TutorialType
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_TYPE_BOTH
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_TYPE_KEY
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_TYPE_KEYBOARD
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_TYPE_TOUCHPAD
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** When the scheduler is due, show a notification to launch tutorial */
@SysUISingleton
class TutorialNotificationCoordinator
@Inject
constructor(
    @Background private val backgroundScope: CoroutineScope,
    @Application private val context: Context,
    private val tutorialSchedulerInteractor: TutorialSchedulerInteractor,
    private val notificationManager: NotificationManager
) {
    fun start() {
        backgroundScope.launch {
            tutorialSchedulerInteractor.tutorials.collect { showNotification(it) }
        }
    }

    // By sharing the same tag and id, we update the content of existing notification instead of
    // creating multiple notifications
    private fun showNotification(tutorialType: TutorialType) {
        if (tutorialType == TutorialType.NONE) return

        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null)
            createNotificationChannel()

        // Replace "System UI" app name with "Android System"
        val extras = Bundle()
        extras.putString(
            Notification.EXTRA_SUBSTITUTE_APP_NAME,
            context.getString(com.android.internal.R.string.android_system_label)
        )

        val info = getNotificationInfo(tutorialType)!!
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_settings)
                .setContentTitle(info.title)
                .setContentText(info.text)
                .setContentIntent(createPendingIntent(info.type))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .addExtras(extras)
                .build()

        notificationManager.notify(TAG, NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(com.android.internal.R.string.android_system_label),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        notificationManager.createNotificationChannel(channel)
    }

    private fun createPendingIntent(tutorialType: String): PendingIntent {
        val intent =
            Intent(context, KeyboardTouchpadTutorialActivity::class.java).apply {
                putExtra(INTENT_TUTORIAL_TYPE_KEY, tutorialType)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        return PendingIntent.getActivity(
            context,
            /* requestCode= */ 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    private data class NotificationInfo(val title: String, val text: String, val type: String)

    private fun getNotificationInfo(tutorialType: TutorialType): NotificationInfo? =
        when (tutorialType) {
            TutorialType.KEYBOARD ->
                NotificationInfo(
                    context.getString(R.string.launch_keyboard_tutorial_notification_title),
                    context.getString(R.string.launch_keyboard_tutorial_notification_content),
                    INTENT_TUTORIAL_TYPE_KEYBOARD
                )
            TutorialType.TOUCHPAD ->
                NotificationInfo(
                    context.getString(R.string.launch_touchpad_tutorial_notification_title),
                    context.getString(R.string.launch_touchpad_tutorial_notification_content),
                    INTENT_TUTORIAL_TYPE_TOUCHPAD
                )
            TutorialType.BOTH ->
                NotificationInfo(
                    context.getString(
                        R.string.launch_keyboard_touchpad_tutorial_notification_title
                    ),
                    context.getString(
                        R.string.launch_keyboard_touchpad_tutorial_notification_content
                    ),
                    INTENT_TUTORIAL_TYPE_BOTH
                )
            TutorialType.NONE -> null
        }

    companion object {
        private const val CHANNEL_ID = "TutorialSchedulerNotificationChannel"
        private const val NOTIFICATION_ID = 5566
    }
}
