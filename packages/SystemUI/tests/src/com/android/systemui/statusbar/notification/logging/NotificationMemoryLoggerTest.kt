/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.logging

import android.app.Notification
import android.app.StatsManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.testing.AndroidTestingRunner
import android.util.StatsEvent
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.system.SysUiStatsLog
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class NotificationMemoryLoggerTest : SysuiTestCase() {

    private val bgExecutor = FakeExecutor(FakeSystemClock())
    private val immediate = Dispatchers.Main.immediate

    @Mock private lateinit var statsManager: StatsManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun onInit_registersCallback() {
        val logger = createLoggerWithNotifications(listOf())
        logger.init()
        verify(statsManager)
            .setPullAtomCallback(SysUiStatsLog.NOTIFICATION_MEMORY_USE, null, bgExecutor, logger)
    }

    @Test
    fun onPullAtom_wrongAtomId_returnsSkip() {
        val logger = createLoggerWithNotifications(listOf())
        val data: MutableList<StatsEvent> = mutableListOf()
        assertThat(logger.onPullAtom(111, data)).isEqualTo(StatsManager.PULL_SKIP)
        assertThat(data).isEmpty()
    }

    @Test
    fun onPullAtom_emptyNotifications_returnsZeros() {
        val logger = createLoggerWithNotifications(listOf())
        val data: MutableList<StatsEvent> = mutableListOf()
        assertThat(logger.onPullAtom(SysUiStatsLog.NOTIFICATION_MEMORY_USE, data))
            .isEqualTo(StatsManager.PULL_SUCCESS)
        assertThat(data).isEmpty()
    }

    @Test
    fun onPullAtom_notificationPassed_populatesData() {
        val icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888))
        val notification =
            Notification.Builder(context).setSmallIcon(icon).setContentTitle("title").build()
        val logger = createLoggerWithNotifications(listOf(notification))
        val data: MutableList<StatsEvent> = mutableListOf()

        assertThat(logger.onPullAtom(SysUiStatsLog.NOTIFICATION_MEMORY_USE, data))
            .isEqualTo(StatsManager.PULL_SUCCESS)
        assertThat(data).hasSize(1)
    }

    @Test
    fun onPullAtom_multipleNotificationsPassed_populatesData() {
        val icon = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888))
        val notification =
            Notification.Builder(context).setSmallIcon(icon).setContentTitle("title").build()
        val iconTwo = Icon.createWithBitmap(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888))

        val notificationTwo =
            Notification.Builder(context)
                .setStyle(Notification.BigTextStyle().bigText("text"))
                .setSmallIcon(iconTwo)
                .setContentTitle("titleTwo")
                .build()
        val logger = createLoggerWithNotifications(listOf(notification, notificationTwo))
        val data: MutableList<StatsEvent> = mutableListOf()

        assertThat(logger.onPullAtom(SysUiStatsLog.NOTIFICATION_MEMORY_USE, data))
            .isEqualTo(StatsManager.PULL_SUCCESS)
        assertThat(data).hasSize(2)
    }

    private fun createLoggerWithNotifications(
        notifications: List<Notification>
    ): NotificationMemoryLogger {
        val pipeline: NotifPipeline = mock()
        val notifications =
            notifications.map { notification ->
                NotificationEntryBuilder().setTag("test").setNotification(notification).build()
            }
        whenever(pipeline.allNotifs).thenReturn(notifications)
        return NotificationMemoryLogger(pipeline, statsManager, immediate, bgExecutor)
    }
}
