/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.NotifLiveDataStoreImpl
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderListListener
import com.android.systemui.statusbar.notification.collection.render.NotifStackController
import com.android.systemui.util.mockito.eq
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
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class DataStoreCoordinatorTest : SysuiTestCase() {
    private lateinit var coordinator: DataStoreCoordinator
    private lateinit var afterRenderListListener: OnAfterRenderListListener

    private lateinit var entry: NotificationEntry

    @Mock private lateinit var pipeline: NotifPipeline
    @Mock private lateinit var notifLiveDataStoreImpl: NotifLiveDataStoreImpl
    @Mock private lateinit var stackController: NotifStackController
    @Mock private lateinit var section: NotifSection

    @Before
    fun setUp() {
        initMocks(this)
        entry = NotificationEntryBuilder().setSection(section).build()
        coordinator = DataStoreCoordinator(notifLiveDataStoreImpl)
        coordinator.attach(pipeline)
        afterRenderListListener = withArgCaptor {
            verify(pipeline).addOnAfterRenderListListener(capture())
        }
    }

    @Test
    fun testUpdateDataStore_withOneEntry() {
        afterRenderListListener.onAfterRenderList(listOf(entry), stackController)
        verify(notifLiveDataStoreImpl).setActiveNotifList(eq(listOf(entry)))
        verifyNoMoreInteractions(notifLiveDataStoreImpl)
    }

    @Test
    fun testUpdateDataStore_withGroups() {
        afterRenderListListener.onAfterRenderList(
            listOf(
                notificationEntry("foo", 1),
                notificationEntry("foo", 2),
                GroupEntryBuilder().setSummary(
                    notificationEntry("bar", 1)
                ).setChildren(
                    listOf(
                        notificationEntry("bar", 2),
                        notificationEntry("bar", 3),
                        notificationEntry("bar", 4)
                    )
                ).setSection(section).build(),
                notificationEntry("baz", 1)
            ),
            stackController
        )
        val list: List<NotificationEntry> = withArgCaptor {
            verify(notifLiveDataStoreImpl).setActiveNotifList(capture())
        }
        assertThat(list.map { it.key }).containsExactly(
            "0|foo|1|null|0",
            "0|foo|2|null|0",
            "0|bar|1|null|0",
            "0|bar|2|null|0",
            "0|bar|3|null|0",
            "0|bar|4|null|0",
            "0|baz|1|null|0"
        ).inOrder()
        verifyNoMoreInteractions(notifLiveDataStoreImpl)
    }

    private fun notificationEntry(pkg: String, id: Int) =
        NotificationEntryBuilder().setPkg(pkg).setId(id).setSection(section).build()

    @Test
    fun testUpdateDataStore_withZeroEntries_whenNewPipelineEnabled() {
        afterRenderListListener.onAfterRenderList(listOf(), stackController)
        verify(notifLiveDataStoreImpl).setActiveNotifList(eq(listOf()))
        verifyNoMoreInteractions(notifLiveDataStoreImpl)
    }
}
