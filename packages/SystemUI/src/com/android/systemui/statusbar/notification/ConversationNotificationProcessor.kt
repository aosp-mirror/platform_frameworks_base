/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification

import android.app.Notification
import android.content.pm.LauncherApps
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import javax.inject.Inject

class ConversationNotificationProcessor @Inject constructor(
    private val launcherApps: LauncherApps
) {
    fun processNotification(entry: NotificationEntry, recoveredBuilder: Notification.Builder) {
        val messagingStyle = recoveredBuilder.style as? Notification.MessagingStyle ?: return
        messagingStyle.conversationType =
                if (entry.ranking.channel.isImportantConversation)
                    Notification.MessagingStyle.CONVERSATION_TYPE_IMPORTANT
                else
                    Notification.MessagingStyle.CONVERSATION_TYPE_NORMAL
        entry.ranking.shortcutInfo?.let { shortcutInfo ->
            messagingStyle.shortcutIcon = launcherApps.getShortcutIcon(shortcutInfo)
            shortcutInfo.shortLabel?.let { shortLabel ->
                messagingStyle.conversationTitle = shortLabel
            }
        }
    }
}