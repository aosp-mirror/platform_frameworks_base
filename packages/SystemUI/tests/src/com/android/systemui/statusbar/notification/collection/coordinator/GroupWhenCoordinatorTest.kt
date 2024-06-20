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
package com.android.systemui.statusbar.notification.collection.coordinator

import android.app.Notification
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.SbnBuilder
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderGroupListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener
import com.android.systemui.statusbar.notification.collection.render.NotifGroupController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.time.SystemClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations.initMocks

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class GroupWhenCoordinatorTest : SysuiTestCase() {

    private lateinit var beforeFinalizeFilterListener: OnBeforeFinalizeFilterListener
    private lateinit var afterRenderGroupListener: OnAfterRenderGroupListener

    @Mock private lateinit var pipeline: NotifPipeline

    @Mock private lateinit var delayableExecutor: DelayableExecutor

    @Mock private lateinit var groupController: NotifGroupController

    @Mock private lateinit var systemClock: SystemClock

    @InjectMocks private lateinit var coordinator: GroupWhenCoordinator

    @Before
    fun setUp() {
        initMocks(this)
        whenever(systemClock.currentTimeMillis()).thenReturn(NOW)
        coordinator.attach(pipeline)

        beforeFinalizeFilterListener = withArgCaptor {
            verify(pipeline).addOnBeforeFinalizeFilterListener(capture())
        }
        afterRenderGroupListener = withArgCaptor {
            verify(pipeline).addOnAfterRenderGroupListener(capture())
        }
    }

    @Test
    fun setNotificationGroupWhen_setClosestTimeByNow_whenAllNotificationsAreBeforeNow() {
        // GIVEN
        val summaryEntry = buildNotificationEntry(0, NOW)
        val childEntry1 = buildNotificationEntry(1, NOW - 10L)
        val childEntry2 = buildNotificationEntry(2, NOW - 100L)
        val groupEntry =
            GroupEntryBuilder()
                .setSummary(summaryEntry)
                .setChildren(listOf(childEntry1, childEntry2))
                .build()
        // WHEN
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))
        afterRenderGroupListener.onAfterRenderGroup(groupEntry, groupController)

        // THEN
        verify(groupController).setNotificationGroupWhen(eq(NOW - 10L))
    }

    @Test
    fun setNotificationGroupWhen_setClosestTimeByNow_whenAllNotificationsAreAfterNow() {
        // GIVEN
        val summaryEntry = buildNotificationEntry(0, NOW)
        val childEntry1 = buildNotificationEntry(1, NOW + 10L)
        val childEntry2 = buildNotificationEntry(2, NOW + 100L)

        val groupEntry =
            GroupEntryBuilder()
                .setSummary(summaryEntry)
                .setChildren(listOf(childEntry1, childEntry2))
                .build()

        // WHEN
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))
        afterRenderGroupListener.onAfterRenderGroup(groupEntry, groupController)

        // THEN
        verify(groupController).setNotificationGroupWhen(eq(NOW + 10L))
    }

    @Test
    fun setNotificationGroupWhen_setClosestFutureTimeByNow_whenThereAreBothBeforeAndAfterNow() {
        // GIVEN
        val summaryEntry = buildNotificationEntry(0, NOW)
        val childEntry1 = buildNotificationEntry(1, NOW + 100L)
        val childEntry2 = buildNotificationEntry(2, NOW + 10L)
        val childEntry3 = buildNotificationEntry(3, NOW - 100L)
        val childEntry4 = buildNotificationEntry(4, NOW - 9L)

        val groupEntry =
            GroupEntryBuilder()
                .setSummary(summaryEntry)
                .setChildren(listOf(childEntry1, childEntry2, childEntry3, childEntry4))
                .build()

        // WHEN
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))
        afterRenderGroupListener.onAfterRenderGroup(groupEntry, groupController)

        // THEN
        verify(groupController).setNotificationGroupWhen(eq(NOW + 10L))
    }

    @Test
    fun setNotificationGroupWhen_filterInvalidNotificationTimes() {
        // GIVEN
        val summaryEntry = buildNotificationEntry(0, NOW)
        val childEntry1 = buildNotificationEntry(1, NOW + 100L)
        val childEntry2 = buildNotificationEntry(2, -20000L)
        val childEntry3 = buildNotificationEntry(4, 0)

        val groupEntry =
            GroupEntryBuilder()
                .setSummary(summaryEntry)
                .setChildren(listOf(childEntry1, childEntry2, childEntry3))
                .build()

        // WHEN
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))
        afterRenderGroupListener.onAfterRenderGroup(groupEntry, groupController)

        // THEN
        verify(groupController).setNotificationGroupWhen(eq(NOW + 100))
    }

    @Test
    fun setNotificationGroupWhen_setSummaryTimeWhenAllNotificationTimesAreInvalid() {
        // GIVEN
        val summaryEntry = buildNotificationEntry(0, NOW)
        val childEntry1 = buildNotificationEntry(1, 0)
        val childEntry2 = buildNotificationEntry(2, -1)

        val groupEntry =
            GroupEntryBuilder()
                .setSummary(summaryEntry)
                .setChildren(listOf(childEntry1, childEntry2))
                .build()

        // WHEN
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))
        afterRenderGroupListener.onAfterRenderGroup(groupEntry, groupController)

        // THEN
        verify(groupController, never()).setNotificationGroupWhen(NOW)
    }

    @Test
    fun setNotificationGroupWhen_schedulePipelineInvalidationWhenAnyNotificationIsInTheFuture() {
        // GIVEN
        val summaryEntry = buildNotificationEntry(0, NOW)
        val childEntry1 = buildNotificationEntry(1, NOW + 1000L)
        val childEntry2 = buildNotificationEntry(2, NOW + 2000L)
        val childEntry3 = buildNotificationEntry(3, NOW - 100L)

        val groupEntry =
            GroupEntryBuilder()
                .setSummary(summaryEntry)
                .setChildren(listOf(childEntry1, childEntry2, childEntry3))
                .build()

        // WHEN
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))
        afterRenderGroupListener.onAfterRenderGroup(groupEntry, groupController)

        // THEN
        verify(delayableExecutor).executeDelayed(any(), eq(1000))
    }

    @Test
    fun setNotificationGroupWhen_cancelPrevPipelineInvalidation() {
        // GIVEN
        val summaryEntry = buildNotificationEntry(0, NOW)
        val childEntry1 = buildNotificationEntry(1, NOW + 1L)
        val prevInvalidation = mock<Runnable>()
        whenever(delayableExecutor.executeDelayed(any(), any())).thenReturn(prevInvalidation)

        val groupEntry =
            GroupEntryBuilder().setSummary(summaryEntry).setChildren(listOf(childEntry1)).build()

        // WHEN
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))
        afterRenderGroupListener.onAfterRenderGroup(groupEntry, groupController)

        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))

        // THEN
        verify(prevInvalidation).run()
    }

    private fun buildNotificationEntry(id: Int, timeMillis: Long): NotificationEntry {
        val notification = Notification.Builder(mContext).setWhen(timeMillis).build()
        val sbn = SbnBuilder().setNotification(notification).build()
        return NotificationEntryBuilder().setId(id).setSbn(sbn).build()
    }

    private companion object {
        private const val NOW = 1000L
    }
}
