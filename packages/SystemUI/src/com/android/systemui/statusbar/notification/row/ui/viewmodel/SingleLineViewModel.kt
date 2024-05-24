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

package com.android.systemui.statusbar.notification.row.ui.viewmodel

import android.annotation.ColorInt
import android.graphics.drawable.Drawable

/**
 * ViewModel for SingleLine Notification View.
 *
 * @property titleText the text of notification view title
 * @property contentText the text of view content
 * @property conversationData the data that is needed specifically for conversation single-line
 *   views. Null conversationData shows that the notification is not conversation. Legacy
 *   MessagingStyle Notifications doesn't have this member.
 */
data class SingleLineViewModel(
    var titleText: CharSequence?,
    var contentText: CharSequence?,
    var conversationData: ConversationData?,
) {
    fun isConversation(): Boolean {
        return conversationData != null
    }
}

/**
 * @property conversationSenderName the name of sender to show in the single-line view. Only group
 *   conversation single-line views show the sender name.
 * @property avatar the avatar to show for the conversation
 */
data class ConversationData(
    val conversationSenderName: CharSequence?,
    val avatar: ConversationAvatar,
)

/**
 * An avatar to show for a single-line conversation notification, it can be either a single icon or
 * a face pile.
 */
sealed class ConversationAvatar

data class SingleIcon(val iconDrawable: Drawable?) : ConversationAvatar()

/**
 * A kind of avatar to show for a group conversation notification view. It consists of two avatars
 * of the last two senders.
 */
data class FacePile(
    val topIconDrawable: Drawable?,
    val bottomIconDrawable: Drawable?,
    @ColorInt val bottomBackgroundColor: Int
) : ConversationAvatar()
