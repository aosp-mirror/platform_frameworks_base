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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification

import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationContentDescriptionTest : SysuiTestCase() {

    private val TITLE = "this is a title"
    private val TEXT = "this is text"
    private val TICKER = "this is a ticker"

    @Test
    fun notificationWithAllDifferentFields_descriptionIsTitle() {
        val n = createNotification(TITLE, TEXT, TICKER)
        val description = contentDescForNotification(context, n)
        assertThat(description).isEqualTo(createDescriptionText(n, TITLE))
    }

    @Test
    fun notificationWithAllDifferentFields_titleMatchesAppName_descriptionIsText() {
        val n = createNotification(getTestAppName(), TEXT, TICKER)
        val description = contentDescForNotification(context, n)
        assertThat(description).isEqualTo(createDescriptionText(n, TEXT))
    }

    @Test
    fun notificationWithAllDifferentFields_titleMatchesAppNameNoText_descriptionIsTicker() {
        val n = createNotification(getTestAppName(), null, TICKER)
        val description = contentDescForNotification(context, n)
        assertThat(description).isEqualTo(createDescriptionText(n, TICKER))
    }

    @Test
    fun notificationWithAllDifferentFields_titleMatchesAppNameNoTextNoTicker_descriptionEmpty() {
        val appName = getTestAppName()
        val n = createNotification(appName, null, null)
        val description = contentDescForNotification(context, n)
        assertThat(description).isEqualTo(createDescriptionText(n, ""))
    }

    private fun createNotification(
        title: String? = null,
        text: String? = null,
        ticker: String? = null
    ): Notification =
        Notification.Builder(context, "channel")
            .setContentTitle(title)
            .setContentText(text)
            .setTicker(ticker)
            .build()

    private fun getTestAppName(): String {
        return createNotification("", "", "").loadHeaderAppName(mContext)
    }

    private fun createDescriptionText(n: Notification?, desc: String?): String {
        val appName = n?.loadHeaderAppName(mContext)
        return context.getString(R.string.accessibility_desc_notification_icon, appName, desc)
    }
}
