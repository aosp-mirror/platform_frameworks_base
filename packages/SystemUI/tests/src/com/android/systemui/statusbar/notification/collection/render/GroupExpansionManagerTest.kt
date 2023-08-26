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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.util.mockito.mock
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when` as whenever

@SmallTest
class GroupExpansionManagerTest : SysuiTestCase() {
    private lateinit var gem: GroupExpansionManagerImpl

    private val dumpManager: DumpManager = mock()
    private val groupMembershipManager: GroupMembershipManager = mock()
    private val featureFlags = FakeFeatureFlags()

    private val entry1 = NotificationEntryBuilder().build()
    private val entry2 = NotificationEntryBuilder().build()

    @Before
    fun setUp() {
        whenever(groupMembershipManager.getGroupSummary(entry1)).thenReturn(entry1)
        whenever(groupMembershipManager.getGroupSummary(entry2)).thenReturn(entry2)

        gem = GroupExpansionManagerImpl(dumpManager, groupMembershipManager, featureFlags)
    }

    @Test
    fun testNotifyOnlyOnChange_enabled() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, true)

        var listenerCalledCount = 0
        gem.registerGroupExpansionChangeListener { _, _ -> listenerCalledCount++ }

        gem.setGroupExpanded(entry1, false)
        Assert.assertEquals(0, listenerCalledCount)
        gem.setGroupExpanded(entry1, true)
        Assert.assertEquals(1, listenerCalledCount)
        gem.setGroupExpanded(entry2, true)
        Assert.assertEquals(2, listenerCalledCount)
        gem.setGroupExpanded(entry1, true)
        Assert.assertEquals(2, listenerCalledCount)
        gem.setGroupExpanded(entry2, false)
        Assert.assertEquals(3, listenerCalledCount)
    }

    @Test
    fun testNotifyOnlyOnChange_disabled() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, false)

        var listenerCalledCount = 0
        gem.registerGroupExpansionChangeListener { _, _ -> listenerCalledCount++ }

        gem.setGroupExpanded(entry1, false)
        Assert.assertEquals(1, listenerCalledCount)
        gem.setGroupExpanded(entry1, true)
        Assert.assertEquals(2, listenerCalledCount)
        gem.setGroupExpanded(entry2, true)
        Assert.assertEquals(3, listenerCalledCount)
        gem.setGroupExpanded(entry1, true)
        Assert.assertEquals(4, listenerCalledCount)
        gem.setGroupExpanded(entry2, false)
        Assert.assertEquals(5, listenerCalledCount)
    }
}
