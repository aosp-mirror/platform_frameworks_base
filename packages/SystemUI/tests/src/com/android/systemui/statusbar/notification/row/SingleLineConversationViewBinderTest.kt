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
package com.android.systemui.statusbar.notification.row

import android.app.Notification
import android.app.Person
import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_SINGLE_LINE
import com.android.systemui.statusbar.notification.row.SingleLineViewInflater.inflateSingleLineViewHolder
import com.android.systemui.statusbar.notification.row.shared.AsyncHybridViewInflation
import com.android.systemui.statusbar.notification.row.ui.viewbinder.SingleLineConversationViewBinder
import com.android.systemui.util.mockito.mock
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class SingleLineConversationViewBinderTest : SysuiTestCase() {
    private lateinit var notificationBuilder: Notification.Builder
    private lateinit var helper: NotificationTestHelper

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        helper = NotificationTestHelper(context, mDependency, TestableLooper.get(this))
        notificationBuilder = Notification.Builder(context, CHANNEL_ID)
        notificationBuilder
            .setSmallIcon(R.drawable.ic_corp_icon)
            .setContentTitle(CONTENT_TITLE)
            .setContentText(CONTENT_TEXT)
    }

    @Test
    @EnableFlags(AsyncHybridViewInflation.FLAG_NAME)
    fun bindGroupConversationSingleLineView() {
        // GIVEN a row with a group conversation notification
        val user =
            Person.Builder()
                //                .setIcon(Icon.createWithResource(mContext,
                // R.drawable.ic_account_circle))
                .setName(USER_NAME)
                .build()
        val style =
            Notification.MessagingStyle(user)
                .addMessage(MESSAGE_TEXT, System.currentTimeMillis(), user)
                .addMessage(
                    "How about lunch?",
                    System.currentTimeMillis(),
                    Person.Builder().setName("user2").build()
                )
                .setGroupConversation(true)
        notificationBuilder.setStyle(style).setShortcutId(SHORTCUT_ID)
        val notification = notificationBuilder.build()
        val row = helper.createRow(notification)

        val viewHolder =
            inflateSingleLineViewHolder(
                isConversation = true,
                reinflateFlags = FLAG_CONTENT_VIEW_SINGLE_LINE,
                entry = row.entry,
                context = context,
                logger = mock()
            )
                as HybridConversationNotificationView
        val viewModel =
            SingleLineViewInflater.inflateSingleLineViewModel(
                notification = notification,
                messagingStyle = style,
                builder = notificationBuilder,
                systemUiContext = context,
            )
        // WHEN: binds the viewHolder
        SingleLineConversationViewBinder.bind(
            viewModel,
            viewHolder,
        )

        // THEN: the single-line conversation view should be bind with view model's corresponding
        // fields
        assertEquals(viewModel.titleText, viewHolder.titleView.text)
        assertEquals(viewModel.contentText, viewHolder.textView.text)
        assertEquals(
            viewModel.conversationData?.conversationSenderName,
            viewHolder.conversationSenderNameView.text
        )
    }

    private companion object {
        const val CHANNEL_ID = "CHANNEL_ID"
        const val CONTENT_TITLE = "CONTENT_TITLE"
        const val CONTENT_TEXT = "CONTENT_TEXT"
        const val USER_NAME = "USER_NAME"
        const val MESSAGE_TEXT = "MESSAGE_TEXT"
        const val SHORTCUT_ID = "Shortcut"
    }
}
