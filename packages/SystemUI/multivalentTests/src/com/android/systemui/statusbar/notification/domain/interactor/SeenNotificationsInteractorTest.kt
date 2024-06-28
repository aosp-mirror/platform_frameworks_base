/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.domain.interactor

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.shared.NotificationMinimalismPrototype
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class SeenNotificationsInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val underTest
        get() = kosmos.seenNotificationsInteractor

    @Test
    fun testNoFilteredOutSeenNotifications() = runTest {
        val hasFilteredOutSeenNotifications by
            collectLastValue(underTest.hasFilteredOutSeenNotifications)

        underTest.setHasFilteredOutSeenNotifications(false)

        assertThat(hasFilteredOutSeenNotifications).isFalse()
    }

    @Test
    fun testHasFilteredOutSeenNotifications() = runTest {
        val hasFilteredOutSeenNotifications by
            collectLastValue(underTest.hasFilteredOutSeenNotifications)

        underTest.setHasFilteredOutSeenNotifications(true)

        assertThat(hasFilteredOutSeenNotifications).isTrue()
    }

    @Test
    @EnableFlags(NotificationMinimalismPrototype.FLAG_NAME)
    fun topOngoingAndUnseenNotification() = runTest {
        val entry1 = NotificationEntryBuilder().setTag("entry1").build()
        val entry2 = NotificationEntryBuilder().setTag("entry2").build()

        underTest.setTopOngoingNotification(null)
        underTest.setTopUnseenNotification(null)

        assertThat(underTest.isTopOngoingNotification(entry1)).isFalse()
        assertThat(underTest.isTopOngoingNotification(entry2)).isFalse()
        assertThat(underTest.isTopUnseenNotification(entry1)).isFalse()
        assertThat(underTest.isTopUnseenNotification(entry2)).isFalse()

        underTest.setTopOngoingNotification(entry1)
        underTest.setTopUnseenNotification(entry2)

        assertThat(underTest.isTopOngoingNotification(entry1)).isTrue()
        assertThat(underTest.isTopOngoingNotification(entry2)).isFalse()
        assertThat(underTest.isTopUnseenNotification(entry1)).isFalse()
        assertThat(underTest.isTopUnseenNotification(entry2)).isTrue()
    }

    fun testShowOnlyUnseenNotifsOnKeyguardSetting() = runTest {
        val settingEnabled by
            collectLastValue(underTest.isLockScreenShowOnlyUnseenNotificationsEnabled())

        kosmos.lockScreenShowOnlyUnseenNotificationsSetting = false
        testScheduler.runCurrent()
        assertThat(settingEnabled).isFalse()

        kosmos.lockScreenShowOnlyUnseenNotificationsSetting = true
        testScheduler.runCurrent()
        assertThat(settingEnabled).isTrue()
    }
}
