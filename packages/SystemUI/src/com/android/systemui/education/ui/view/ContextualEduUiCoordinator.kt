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

import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationCompat
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.contextualeducation.GestureType.ALL_APPS
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.contextualeducation.GestureType.HOME
import com.android.systemui.contextualeducation.GestureType.OVERVIEW
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.education.ui.viewmodel.ContextualEduNotificationViewModel
import com.android.systemui.education.ui.viewmodel.ContextualEduToastViewModel
import com.android.systemui.education.ui.viewmodel.ContextualEduViewModel
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_ENTRY_POINT_CONTEXTUAL_EDU
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_ENTRY_POINT_KEY
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_SCOPE_KEY
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_SCOPE_KEYBOARD
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_SCOPE_TOUCHPAD_BACK
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_SCOPE_TOUCHPAD_HOME
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

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
    private val createDialog: (ContextualEduToastViewModel) -> Dialog,
) : CoreStartable {

    companion object {
        private const val CHANNEL_ID = "ContextualEduNotificationChannel"
        private const val TAG = "ContextualEduUiCoordinator"
        private const val NOTIFICATION_ID = 1000
        private const val TUTORIAL_ACTION: String = "com.android.systemui.action.TOUCHPAD_TUTORIAL"
        private const val SYSTEMUI_PACKAGE_NAME: String = "com.android.systemui"
    }

    @Inject
    constructor(
        @Application applicationScope: CoroutineScope,
        context: Context,
        viewModel: ContextualEduViewModel,
        notificationManager: NotificationManager,
        accessibilityManager: AccessibilityManager,
    ) : this(
        applicationScope,
        viewModel,
        context,
        notificationManager,
        createDialog = { model -> ContextualEduDialog(context, model, accessibilityManager) },
    )

    var dialog: Dialog? = null

    override fun start() {
        createEduNotificationChannel()
        applicationScope.launch {
            viewModel.eduContent.collect { contentModel ->
                if (contentModel != null) {
                    when (contentModel) {
                        is ContextualEduToastViewModel -> showDialog(contentModel)
                        is ContextualEduNotificationViewModel -> showNotification(contentModel)
                    }
                } else {
                    dialog?.dismiss()
                    dialog = null
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
                NotificationManager.IMPORTANCE_LOW,
            )
        notificationManager.createNotificationChannel(channel)
    }

    private fun showDialog(model: ContextualEduToastViewModel) {
        dialog?.dismiss()
        dialog = createDialog(model)
        dialog?.show()
    }

    private fun showNotification(model: ContextualEduNotificationViewModel) {
        // Replace "System UI" app name with "Android System"
        val extras = Bundle()
        extras.putString(
            Notification.EXTRA_SUBSTITUTE_APP_NAME,
            context.getString(com.android.internal.R.string.android_system_label),
        )

        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_settings)
                .setContentTitle(model.title)
                .setContentText(model.message)
                .setContentIntent(createPendingIntent(model.gestureType))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .addExtras(extras)
                .build()
        notificationManager.notifyAsUser(
            TAG,
            NOTIFICATION_ID,
            notification,
            UserHandle.of(model.userId),
        )
    }

    private fun createPendingIntent(gestureType: GestureType): PendingIntent {
        val intent =
            when (gestureType) {
                BACK -> createKeyboardTouchpadTutorialIntent(INTENT_TUTORIAL_SCOPE_TOUCHPAD_BACK)
                HOME -> createKeyboardTouchpadTutorialIntent(INTENT_TUTORIAL_SCOPE_TOUCHPAD_HOME)
                ALL_APPS -> createKeyboardTouchpadTutorialIntent(INTENT_TUTORIAL_SCOPE_KEYBOARD)
                OVERVIEW -> createTouchpadTutorialIntent()
            }

        return PendingIntent.getActivity(
            context,
            /* requestCode= */ 0,
            intent,
            // FLAG_UPDATE_CURRENT to avoid caching of intent extras and always use latest values
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createKeyboardTouchpadTutorialIntent(tutorialType: String): Intent {
        return Intent(context, KeyboardTouchpadTutorialActivity::class.java).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(INTENT_TUTORIAL_SCOPE_KEY, tutorialType)
            putExtra(INTENT_TUTORIAL_ENTRY_POINT_KEY, INTENT_TUTORIAL_ENTRY_POINT_CONTEXTUAL_EDU)
        }
    }

    private fun createTouchpadTutorialIntent(): Intent {
        return Intent(TUTORIAL_ACTION).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setPackage(SYSTEMUI_PACKAGE_NAME)
        }
    }
}
