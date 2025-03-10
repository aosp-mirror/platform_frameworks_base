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

package com.android.systemui.statusbar.notification.icon.ui.viewbinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.domain.interactor.displayWindowPropertiesInteractor
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.statusbar.RankingBuilder
import com.android.systemui.statusbar.SbnBuilder
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifCollection
import com.android.systemui.statusbar.notification.collection.notifPipeline
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.icon.iconManager
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
class ConnectedDisplaysStatusBarNotificationIconViewStoreTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val underTest =
        ConnectedDisplaysStatusBarNotificationIconViewStore(
            TEST_DISPLAY_ID,
            kosmos.notifCollection,
            kosmos.iconManager,
            kosmos.displayWindowPropertiesInteractor,
            kosmos.notifPipeline,
        )

    private val notifCollectionListeners = mutableListOf<NotifCollectionListener>()

    @Before
    fun setupNoticCollectionListener() {
        whenever(kosmos.notifPipeline.addCollectionListener(any())).thenAnswer { invocation ->
            notifCollectionListeners.add(invocation.arguments[0] as NotifCollectionListener)
        }
    }

    @Before
    fun activate() {
        underTest.activateIn(kosmos.testScope)
    }

    @Test
    fun iconView_unknownKey_returnsNull() =
        kosmos.testScope.runTest {
            val unknownKey = "unknown key"

            assertThat(underTest.iconView(unknownKey)).isNull()
        }

    @Test
    fun iconView_knownKey_returnsNonNull() =
        kosmos.testScope.runTest {
            val entry = createEntry()

            whenever(kosmos.notifCollection.getEntry(entry.key)).thenReturn(entry)

            assertThat(underTest.iconView(entry.key)).isNotNull()
        }

    @Test
    fun iconView_knownKey_calledMultipleTimes_returnsSameInstance() =
        kosmos.testScope.runTest {
            val entry = createEntry()

            whenever(kosmos.notifCollection.getEntry(entry.key)).thenReturn(entry)

            val first = underTest.iconView(entry.key)
            val second = underTest.iconView(entry.key)

            assertThat(first).isSameInstanceAs(second)
        }

    @Test
    fun iconView_knownKey_afterNotificationRemoved_returnsNewInstance() =
        kosmos.testScope.runTest {
            val entry = createEntry()

            whenever(kosmos.notifCollection.getEntry(entry.key)).thenReturn(entry)

            val first = underTest.iconView(entry.key)

            notifCollectionListeners.forEach { it.onEntryRemoved(entry, /* reason= */ 0) }

            val second = underTest.iconView(entry.key)

            assertThat(first).isNotSameInstanceAs(second)
        }

    private fun createEntry(): NotificationEntry {
        val channelId = "channelId"
        val notificationChannel =
            NotificationChannel(channelId, "name", NotificationManager.IMPORTANCE_DEFAULT)
        val notification =
            Notification.Builder(context, channelId)
                .setContentTitle("Title")
                .setContentText("Content text")
                .setSmallIcon(com.android.systemui.res.R.drawable.icon)
                .build()
        val statusBarNotification = SbnBuilder().setNotification(notification).build()
        val ranking =
            RankingBuilder()
                .setChannel(notificationChannel)
                .setKey(statusBarNotification.key)
                .build()
        return NotificationEntry(
            /* sbn = */ statusBarNotification,
            /* ranking = */ ranking,
            /* creationTime = */ 1234L,
        )
    }

    private companion object {
        const val TEST_DISPLAY_ID = 1234
    }
}
