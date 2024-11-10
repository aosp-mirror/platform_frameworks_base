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

package com.android.systemui.statusbar.notification.promoted

import android.app.Notification
import android.app.Notification.FLAG_PROMOTED_ONGOING
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

@SmallTest
class PromotedNotificationsProviderTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private val underTest = kosmos.promotedNotificationsProvider

    @Test
    @DisableFlags(PromotedNotificationUi.FLAG_NAME)
    fun shouldPromote_uiFlagOff_false() {
        val entry = createNotification(FLAG_PROMOTED_ONGOING)

        assertThat(underTest.shouldPromote(entry)).isFalse()
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME)
    fun shouldPromote_uiFlagOn_notifDoesNotHaveFlag_false() {
        val entry = createNotification(flag = null)

        assertThat(underTest.shouldPromote(entry)).isFalse()
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME)
    fun shouldPromote_uiFlagOn_notifHasFlag_true() {
        val entry = createNotification(FLAG_PROMOTED_ONGOING)

        assertThat(underTest.shouldPromote(entry)).isTrue()
    }

    private fun createNotification(flag: Int? = null): NotificationEntry {
        val n = Notification.Builder(context, "a")
        if (flag != null) {
            n.setFlag(flag, true)
        }

        return NotificationEntryBuilder().setNotification(n.build()).build()
    }
}
