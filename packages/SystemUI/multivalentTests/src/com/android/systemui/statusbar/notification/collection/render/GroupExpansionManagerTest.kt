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

package com.android.systemui.statusbar.notification.collection.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager.OnGroupExpansionChangeListener
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class GroupExpansionManagerTest : SysuiTestCase() {
    private lateinit var underTest: GroupExpansionManagerImpl

    private val dumpManager: DumpManager = mock()
    private val groupMembershipManager: GroupMembershipManager = mock()

    private val pipeline: NotifPipeline = mock()
    private lateinit var beforeRenderListListener: OnBeforeRenderListListener

    private val summary1 = notificationEntry("foo", 1)
    private val summary2 = notificationEntry("bar", 1)
    private val entries =
        listOf<ListEntry>(
            GroupEntryBuilder()
                .setSummary(summary1)
                .setChildren(
                    listOf(
                        notificationEntry("foo", 2),
                        notificationEntry("foo", 3),
                        notificationEntry("foo", 4)
                    )
                )
                .build(),
            GroupEntryBuilder()
                .setSummary(summary2)
                .setChildren(
                    listOf(
                        notificationEntry("bar", 2),
                        notificationEntry("bar", 3),
                        notificationEntry("bar", 4)
                    )
                )
                .build(),
            notificationEntry("baz", 1)
        )

    private fun notificationEntry(pkg: String, id: Int) =
        NotificationEntryBuilder().setPkg(pkg).setId(id).build().apply { row = mock() }

    @Before
    fun setUp() {
        whenever(groupMembershipManager.getGroupSummary(summary1)).thenReturn(summary1)
        whenever(groupMembershipManager.getGroupSummary(summary2)).thenReturn(summary2)

        underTest = GroupExpansionManagerImpl(dumpManager, groupMembershipManager)
    }

    @Test
    fun notifyOnlyOnChange() {
        var listenerCalledCount = 0
        underTest.registerGroupExpansionChangeListener { _, _ -> listenerCalledCount++ }

        underTest.setGroupExpanded(summary1, false)
        assertThat(listenerCalledCount).isEqualTo(0)
        underTest.setGroupExpanded(summary1, true)
        assertThat(listenerCalledCount).isEqualTo(1)
        underTest.setGroupExpanded(summary2, true)
        assertThat(listenerCalledCount).isEqualTo(2)
        underTest.setGroupExpanded(summary1, true)
        assertThat(listenerCalledCount).isEqualTo(2)
        underTest.setGroupExpanded(summary2, false)
        assertThat(listenerCalledCount).isEqualTo(3)
    }

    @Test
    fun expandUnattachedEntry() {
        // First, expand the entry when it is attached.
        underTest.setGroupExpanded(summary1, true)
        assertThat(underTest.isGroupExpanded(summary1)).isTrue()

        // Un-attach it, and un-expand it.
        NotificationEntryBuilder.setNewParent(summary1, null)
        underTest.setGroupExpanded(summary1, false)

        // Expanding again should throw.
        assertThrows(IllegalArgumentException::class.java) {
            underTest.setGroupExpanded(summary1, true)
        }
    }

    @Test
    fun syncWithPipeline() {
        underTest.attach(pipeline)
        beforeRenderListListener = withArgCaptor {
            verify(pipeline).addOnBeforeRenderListListener(capture())
        }

        val listener: OnGroupExpansionChangeListener = mock()
        underTest.registerGroupExpansionChangeListener(listener)

        beforeRenderListListener.onBeforeRenderList(entries)
        verify(listener, never()).onGroupExpansionChange(any(), any())

        // Expand one of the groups.
        underTest.setGroupExpanded(summary1, true)
        verify(listener).onGroupExpansionChange(summary1.row, true)

        // Empty the pipeline list and verify that the group is no longer expanded.
        beforeRenderListListener.onBeforeRenderList(emptyList())
        verify(listener).onGroupExpansionChange(summary1.row, false)
        verifyNoMoreInteractions(listener)
    }
}
