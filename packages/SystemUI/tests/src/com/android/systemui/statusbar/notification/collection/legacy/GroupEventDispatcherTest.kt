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
package com.android.systemui.statusbar.notification.collection.legacy

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy.GroupEventDispatcher
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy.NotificationGroup
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy.OnGroupChangeListener
import com.android.systemui.statusbar.phone.NotificationGroupTestHelper
import com.android.systemui.util.mockito.mock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class GroupEventDispatcherTest : SysuiTestCase() {
    val groupMap = mutableMapOf<String, NotificationGroup>()
    val groupTestHelper = NotificationGroupTestHelper(mContext)

    private val dispatcher = GroupEventDispatcher(groupMap::get)
    private val listener: OnGroupChangeListener = mock()

    @Before
    fun setup() {
        dispatcher.registerGroupChangeListener(listener)
    }

    @Test
    fun testOnGroupsChangedUnbuffered() {
        dispatcher.notifyGroupsChanged()
        verify(listener).onGroupsChanged()
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testOnGroupsChangedBuffered() {
        dispatcher.openBufferScope()
        dispatcher.notifyGroupsChanged()
        verifyNoMoreInteractions(listener)
        dispatcher.closeBufferScope()
        verify(listener).onGroupsChanged()
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testOnGroupsChangedDoubleBuffered() {
        dispatcher.openBufferScope()
        dispatcher.notifyGroupsChanged()
        dispatcher.openBufferScope() // open a nested buffer scope
        dispatcher.notifyGroupsChanged()
        dispatcher.closeBufferScope() // should NOT flush events
        dispatcher.notifyGroupsChanged()
        verifyNoMoreInteractions(listener)
        dispatcher.closeBufferScope() // this SHOULD flush events
        verify(listener).onGroupsChanged()
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testOnGroupsChangedBufferCoalesces() {
        dispatcher.openBufferScope()
        dispatcher.notifyGroupsChanged()
        dispatcher.notifyGroupsChanged()
        verifyNoMoreInteractions(listener)
        dispatcher.closeBufferScope()
        verify(listener).onGroupsChanged()
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testOnGroupCreatedIsNeverBuffered() {
        val group = addGroup(1)

        dispatcher.openBufferScope()
        dispatcher.notifyGroupCreated(group)
        verify(listener).onGroupCreated(group, group.groupKey)
        verifyNoMoreInteractions(listener)

        dispatcher.closeBufferScope()
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testOnGroupRemovedIsNeverBuffered() {
        val group = addGroup(1)

        dispatcher.openBufferScope()
        dispatcher.notifyGroupRemoved(group)
        verify(listener).onGroupRemoved(group, group.groupKey)
        verifyNoMoreInteractions(listener)

        dispatcher.closeBufferScope()
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testAlertOverrideAddedUnbuffered() {
        val group = addGroup(1)
        val newAlertEntry = groupTestHelper.createChildNotification()
        group.alertOverride = newAlertEntry
        dispatcher.notifyAlertOverrideChanged(group, null)
        verify(listener).onGroupAlertOverrideChanged(group, null, newAlertEntry)
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testAlertOverrideRemovedUnbuffered() {
        val group = addGroup(1)
        val oldAlertEntry = groupTestHelper.createChildNotification()
        dispatcher.notifyAlertOverrideChanged(group, oldAlertEntry)
        verify(listener).onGroupAlertOverrideChanged(group, oldAlertEntry, null)
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testAlertOverrideChangedUnbuffered() {
        val group = addGroup(1)
        val oldAlertEntry = groupTestHelper.createChildNotification()
        val newAlertEntry = groupTestHelper.createChildNotification()
        group.alertOverride = newAlertEntry
        dispatcher.notifyAlertOverrideChanged(group, oldAlertEntry)
        verify(listener).onGroupAlertOverrideChanged(group, oldAlertEntry, newAlertEntry)
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testAlertOverrideChangedBuffered() {
        dispatcher.openBufferScope()
        val group = addGroup(1)
        val oldAlertEntry = groupTestHelper.createChildNotification()
        val newAlertEntry = groupTestHelper.createChildNotification()
        group.alertOverride = newAlertEntry
        dispatcher.notifyAlertOverrideChanged(group, oldAlertEntry)
        verifyNoMoreInteractions(listener)
        dispatcher.closeBufferScope()
        verify(listener).onGroupAlertOverrideChanged(group, oldAlertEntry, newAlertEntry)
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testAlertOverrideIgnoredIfRemoved() {
        dispatcher.openBufferScope()
        val group = addGroup(1)
        val oldAlertEntry = groupTestHelper.createChildNotification()
        val newAlertEntry = groupTestHelper.createChildNotification()
        group.alertOverride = newAlertEntry
        dispatcher.notifyAlertOverrideChanged(group, oldAlertEntry)
        verifyNoMoreInteractions(listener)
        groupMap.clear()
        dispatcher.closeBufferScope()
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testAlertOverrideMultipleChangesBuffered() {
        dispatcher.openBufferScope()
        val group = addGroup(1)
        val oldAlertEntry = groupTestHelper.createChildNotification()
        val newAlertEntry = groupTestHelper.createChildNotification()
        group.alertOverride = null
        dispatcher.notifyAlertOverrideChanged(group, oldAlertEntry)
        group.alertOverride = newAlertEntry
        dispatcher.notifyAlertOverrideChanged(group, null)
        verifyNoMoreInteractions(listener)
        dispatcher.closeBufferScope()
        verify(listener).onGroupAlertOverrideChanged(group, oldAlertEntry, newAlertEntry)
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testAlertOverrideTemporaryValueSwallowed() {
        dispatcher.openBufferScope()
        val group = addGroup(1)
        val stableAlertEntry = groupTestHelper.createChildNotification()
        group.alertOverride = null
        dispatcher.notifyAlertOverrideChanged(group, stableAlertEntry)
        group.alertOverride = stableAlertEntry
        dispatcher.notifyAlertOverrideChanged(group, null)
        verifyNoMoreInteractions(listener)
        dispatcher.closeBufferScope()
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testAlertOverrideTemporaryNullSwallowed() {
        dispatcher.openBufferScope()
        val group = addGroup(1)
        val temporaryAlertEntry = groupTestHelper.createChildNotification()
        group.alertOverride = temporaryAlertEntry
        dispatcher.notifyAlertOverrideChanged(group, null)
        group.alertOverride = null
        dispatcher.notifyAlertOverrideChanged(group, temporaryAlertEntry)
        verifyNoMoreInteractions(listener)
        dispatcher.closeBufferScope()
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testSuppressOnUnbuffered() {
        val group = addGroup(1)
        group.suppressed = true
        dispatcher.notifySuppressedChanged(group)
        verify(listener).onGroupSuppressionChanged(group, true)
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testSuppressOffUnbuffered() {
        val group = addGroup(1)
        group.suppressed = false
        dispatcher.notifySuppressedChanged(group)
        verify(listener).onGroupSuppressionChanged(group, false)
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testSuppressOnBuffered() {
        dispatcher.openBufferScope()
        val group = addGroup(1)
        group.suppressed = false
        dispatcher.notifySuppressedChanged(group)
        verifyNoMoreInteractions(listener)
        dispatcher.closeBufferScope()
        verify(listener).onGroupSuppressionChanged(group, false)
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testSuppressOnIgnoredIfRemoved() {
        dispatcher.openBufferScope()
        val group = addGroup(1)
        group.suppressed = false
        dispatcher.notifySuppressedChanged(group)
        verifyNoMoreInteractions(listener)
        groupMap.clear()
        dispatcher.closeBufferScope()
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testSuppressOnOffBuffered() {
        dispatcher.openBufferScope()
        val group = addGroup(1)
        group.suppressed = true
        dispatcher.notifySuppressedChanged(group)
        group.suppressed = false
        dispatcher.notifySuppressedChanged(group)
        verifyNoMoreInteractions(listener)
        dispatcher.closeBufferScope()
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testSuppressOnOffOnBuffered() {
        dispatcher.openBufferScope()
        val group = addGroup(1)
        group.suppressed = true
        dispatcher.notifySuppressedChanged(group)
        group.suppressed = false
        dispatcher.notifySuppressedChanged(group)
        group.suppressed = true
        dispatcher.notifySuppressedChanged(group)
        verifyNoMoreInteractions(listener)
        dispatcher.closeBufferScope()
        verify(listener).onGroupSuppressionChanged(group, true)
        verifyNoMoreInteractions(listener)
    }

    private fun addGroup(id: Int): NotificationGroup {
        val group = NotificationGroup("group:$id")
        groupMap[group.groupKey] = group
        return group
    }
}
