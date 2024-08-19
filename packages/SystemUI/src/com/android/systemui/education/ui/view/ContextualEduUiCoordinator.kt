/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.ui.view

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.education.ui.viewmodel.ContextualEduNotificationViewModel
import com.android.systemui.education.ui.viewmodel.ContextualEduToastViewModel
import com.android.systemui.education.ui.viewmodel.ContextualEduViewModel
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A class to show contextual education on UI based on the edu produced from
 * [ContextualEduViewModel]
 */
@SysUISingleton
class ContextualEduUiCoordinator
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val viewModel: ContextualEduViewModel,
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val createToast: (String) -> Toast
) : CoreStartable {

    companion object {
        private const val CHANNEL_ID = "ContextualEduNotificationChannel"
        private const val TAG = "ContextualEduUiCoordinator"
        private const val NOTIFICATION_ID = 1000
    }

    @Inject
    constructor(
        @Application applicationScope: CoroutineScope,
        context: Context,
        viewModel: ContextualEduViewModel,
        notificationManager: NotificationManager,
    ) : this(
        applicationScope,
        viewModel,
        context,
        notificationManager,
        createToast = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG) }
    )

    override fun start() {
        createEduNotificationChannel()
        applicationScope.launch {
            viewModel.eduContent.collect { contentModel ->
                when (contentModel) {
                    is ContextualEduToastViewModel -> showToast(contentModel)
                    is ContextualEduNotificationViewModel -> showNotification(contentModel)
                }
            }
        }
    }

    private fun createEduNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(com.android.internal.R.string.android_system_label),
                // Make it as silent notification
                NotificationManager.IMPORTANCE_LOW
            )
        notificationManager.createNotificationChannel(channel)
    }

    private fun showToast(model: ContextualEduToastViewModel) {
        val toast = createToast(model.message)
        toast.show()
    }

    private fun showNotification(model: ContextualEduNotificationViewModel) {
        // Replace "System UI" app name with "Android System"
        val extras = Bundle()
        extras.putString(
            Notification.EXTRA_SUBSTITUTE_APP_NAME,
            context.getString(com.android.internal.R.string.android_system_label)
        )

        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_settings)
                .setContentTitle(model.title)
                .setContentText(model.message)
                .setContentIntent(createPendingIntent())
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .addExtras(extras)
                .build()
        notificationManager.notifyAsUser(
            TAG,
            NOTIFICATION_ID,
            notification,
            UserHandle.of(model.userId)
        )
    }

    private fun createPendingIntent(): PendingIntent {
        val intent =
            Intent(context, KeyboardTouchpadTutorialActivity::class.java).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        return PendingIntent.getActivity(
            context,
            /* requestCode= */ 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
    }
}
