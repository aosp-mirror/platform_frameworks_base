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
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_SINGLE_LINE
import com.android.systemui.statusbar.notification.row.SingleLineViewInflater.inflateSingleLineViewHolder
import com.android.systemui.statusbar.notification.row.shared.AsyncHybridViewInflation
import com.android.systemui.statusbar.notification.row.ui.viewbinder.SingleLineViewBinder
import com.android.systemui.util.mockito.mock
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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

        val viewHolder =
            inflateSingleLineViewHolder(
                isConversation = false,
                reinflateFlags = FLAG_CONTENT_VIEW_SINGLE_LINE,
                entry = row.entry,
                context = context,
                logger = mock()
            )
        val viewModel =
            SingleLineViewInflater.inflateSingleLineViewModel(
                notification = notification,
                messagingStyle = null,
                builder = notificationBuilder,
                systemUiContext = context,
            )

        // WHEN: binds the viewHolder
        SingleLineViewBinder.bind(viewModel, viewHolder)

        // THEN: the single-line view should be bind with viewModel's title and content text
        Assert.assertEquals(viewModel.titleText, viewHolder?.titleView?.text)
        Assert.assertEquals(viewModel.contentText, viewHolder?.textView?.text)
    }

    private companion object {
        const val CHANNEL_ID = "CHANNEL_ID"
        const val CONTENT_TITLE = "A Cool New Feature"
        const val CONTENT_TEXT = "Checkout out new feature!"
    }
}
