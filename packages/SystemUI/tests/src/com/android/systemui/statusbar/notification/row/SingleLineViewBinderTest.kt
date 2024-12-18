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
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_PUBLIC_SINGLE_LINE
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_SINGLE_LINE
import com.android.systemui.statusbar.notification.row.SingleLineViewInflater.inflatePrivateSingleLineView
import com.android.systemui.statusbar.notification.row.SingleLineViewInflater.inflatePublicSingleLineView
import com.android.systemui.statusbar.notification.row.shared.AsyncHybridViewInflation
import com.android.systemui.statusbar.notification.row.ui.viewbinder.SingleLineViewBinder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class SingleLineViewBinderTest : SysuiTestCase() {
    private lateinit var notificationBuilder: Notification.Builder
    private lateinit var helper: NotificationTestHelper

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        helper = NotificationTestHelper(mContext, mDependency, TestableLooper.get(this))
        notificationBuilder = Notification.Builder(mContext, CHANNEL_ID)
        notificationBuilder
            .setSmallIcon(R.drawable.ic_corp_icon)
            .setContentTitle(CONTENT_TITLE)
            .setContentText(CONTENT_TEXT)
    }

    @Test
    @EnableFlags(AsyncHybridViewInflation.FLAG_NAME)
    fun bindNonConversationSingleLineView() {
        // GIVEN: a row with bigText style notification
        val style = Notification.BigTextStyle().bigText(CONTENT_TEXT)
        notificationBuilder.setStyle(style)
        val notification = notificationBuilder.build()
        val row: ExpandableNotificationRow = helper.createRow(notification)

        val view =
            inflatePrivateSingleLineView(
                isConversation = false,
                reinflateFlags = FLAG_CONTENT_VIEW_SINGLE_LINE,
                entry = row.entry,
                context = context,
                logger = mock(),
            )

        val publicView =
            inflatePublicSingleLineView(
                isConversation = false,
                reinflateFlags = FLAG_CONTENT_VIEW_PUBLIC_SINGLE_LINE,
                entry = row.entry,
                context = context,
                logger = mock(),
            )
        assertNotNull(publicView)

        val viewModel =
            SingleLineViewInflater.inflateSingleLineViewModel(
                notification = notification,
                messagingStyle = null,
                builder = notificationBuilder,
                systemUiContext = context,
            )

        // WHEN: binds the viewHolder
        SingleLineViewBinder.bind(viewModel, view)

        // THEN: the single-line view should be bind with viewModel's title and content text
        assertEquals(viewModel.titleText, view?.titleView?.text)
        assertEquals(viewModel.contentText, view?.textView?.text)
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
                    Person.Builder().setName("user2").build(),
                )
                .setGroupConversation(true)
        notificationBuilder.setStyle(style).setShortcutId(SHORTCUT_ID)
        val notification = notificationBuilder.build()
        val row = helper.createRow(notification)

        val view =
            inflatePrivateSingleLineView(
                isConversation = true,
                reinflateFlags = FLAG_CONTENT_VIEW_SINGLE_LINE,
                entry = row.entry,
                context = context,
                logger = mock(),
            )
                as HybridConversationNotificationView

        val publicView =
            inflatePublicSingleLineView(
                isConversation = true,
                reinflateFlags = FLAG_CONTENT_VIEW_PUBLIC_SINGLE_LINE,
                entry = row.entry,
                context = context,
                logger = mock(),
            )
                as HybridConversationNotificationView
        assertNotNull(publicView)

        val viewModel =
            SingleLineViewInflater.inflateSingleLineViewModel(
                notification = notification,
                messagingStyle = style,
                builder = notificationBuilder,
                systemUiContext = context,
            )
        // WHEN: binds the view
        SingleLineViewBinder.bind(viewModel, view)

        // THEN: the single-line conversation view should be bound with view model's corresponding
        // fields
        assertEquals(viewModel.titleText, view.titleView.text)
        assertEquals(viewModel.contentText, view.textView.text)
        assertEquals(
            viewModel.conversationData?.conversationSenderName,
            view.conversationSenderNameView.text,
        )
    }

    @Test
    @EnableFlags(AsyncHybridViewInflation.FLAG_NAME)
    fun bindConversationSingleLineView_nonConversationViewModel() {
        // GIVEN: a ConversationSingleLineView, and a nonConversationViewModel
        val style = Notification.BigTextStyle().bigText(CONTENT_TEXT)
        notificationBuilder.setStyle(style)
        val notification = notificationBuilder.build()
        val row: ExpandableNotificationRow = helper.createRow(notification)

        val view =
            inflatePrivateSingleLineView(
                isConversation = true,
                reinflateFlags = FLAG_CONTENT_VIEW_SINGLE_LINE,
                entry = row.entry,
                context = context,
                logger = mock(),
            )

        val publicView =
            inflatePublicSingleLineView(
                isConversation = true,
                reinflateFlags = FLAG_CONTENT_VIEW_PUBLIC_SINGLE_LINE,
                entry = row.entry,
                context = context,
                logger = mock(),
            )
        assertNotNull(publicView)

        val viewModel =
            SingleLineViewInflater.inflateSingleLineViewModel(
                notification = notification,
                messagingStyle = null,
                builder = notificationBuilder,
                systemUiContext = context,
            )
        // WHEN: binds the view with the view model
        SingleLineViewBinder.bind(viewModel, view)

        // THEN: the single-line view should be bound with view model's corresponding
        // fields as a normal non-conversation single-line view
        assertEquals(viewModel.titleText, view?.titleView?.text)
        assertEquals(viewModel.contentText, view?.textView?.text)
        assertNull(viewModel.conversationData)
    }

    private companion object {
        const val CHANNEL_ID = "CHANNEL_ID"
        const val CONTENT_TITLE = "A Cool New Feature"
        const val CONTENT_TEXT = "Checkout out new feature!"
        const val USER_NAME = "USER_NAME"
        const val MESSAGE_TEXT = "MESSAGE_TEXT"
        const val SHORTCUT_ID = "Shortcut"
    }
}
