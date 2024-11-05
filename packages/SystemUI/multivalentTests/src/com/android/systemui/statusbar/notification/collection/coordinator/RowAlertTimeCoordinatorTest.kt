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
package com.android.systemui.statusbar.notification.collection.coordinator

import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderEntryListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener
import com.android.systemui.statusbar.notification.collection.render.NotifRowController
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations.initMocks

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class RowAlertTimeCoordinatorTest : SysuiTestCase() {
    private lateinit var coordinator: RowAlertTimeCoordinator
    private lateinit var beforeFinalizeFilterListener: OnBeforeFinalizeFilterListener
    private lateinit var afterRenderEntryListener: OnAfterRenderEntryListener

    @Mock private lateinit var pipeline: NotifPipeline

    @Before
    fun setUp() {
        initMocks(this)
        coordinator = RowAlertTimeCoordinator()
        coordinator.attach(pipeline)
        beforeFinalizeFilterListener = withArgCaptor {
            verify(pipeline).addOnBeforeFinalizeFilterListener(capture())
        }
        afterRenderEntryListener = withArgCaptor {
            verify(pipeline).addOnAfterRenderEntryListener(capture())
        }
    }

    @Test
    fun testSetLastAudiblyAlerted() {
        val entry1 = NotificationEntryBuilder().setLastAudiblyAlertedMs(10).build()
        val entry2 = NotificationEntryBuilder().setLastAudiblyAlertedMs(20).build()
        val summary = NotificationEntryBuilder().setLastAudiblyAlertedMs(5).build()
        val child1 = NotificationEntryBuilder().setLastAudiblyAlertedMs(0).build()
        val child2 = NotificationEntryBuilder().setLastAudiblyAlertedMs(8).build()
        val group =
            GroupEntryBuilder()
                .setKey("group")
                .setSummary(summary)
                .addChild(child1)
                .addChild(child2)
                .build()

        val entries = listOf(entry1, summary, child1, child2, entry2)

        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(entry1, group, entry2))
        val actualTimesSet =
            entries.associateWith {
                val rowController = mock<NotifRowController>()
                afterRenderEntryListener.onAfterRenderEntry(it, rowController)
                withArgCaptor<Long> {
                    verify(rowController).setLastAudibleMs(capture())
                    verifyNoMoreInteractions(rowController)
                }
            }
        val expectedTimesSet =
            mapOf(
                entry1 to 10L,
                entry2 to 20L,
                summary to 8L,
                child1 to 0L,
                child2 to 8L,
            )
        assertThat(actualTimesSet).containsExactlyEntriesIn(expectedTimesSet)
    }
}
