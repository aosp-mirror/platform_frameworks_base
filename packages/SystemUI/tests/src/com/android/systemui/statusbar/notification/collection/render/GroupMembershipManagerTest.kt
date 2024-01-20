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
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

@SmallTest
class GroupMembershipManagerTest : SysuiTestCase() {
    private lateinit var gmm: GroupMembershipManagerImpl

    private val featureFlags = FakeFeatureFlagsClassic()

    @Before
    fun setUp() {
        gmm = GroupMembershipManagerImpl(featureFlags)
    }

    @Test
    fun testIsChildInGroup_topLevel() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, false)
        val topLevelEntry = NotificationEntryBuilder().setParent(GroupEntry.ROOT_ENTRY).build()
        assertThat(gmm.isChildInGroup(topLevelEntry)).isFalse()
    }

    @Test
    fun testIsChildInGroup_noParent_old() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, false)
        val noParentEntry = NotificationEntryBuilder().setParent(null).build()
        assertThat(gmm.isChildInGroup(noParentEntry)).isTrue()
    }

    @Test
    fun testIsChildInGroup_noParent_new() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, true)
        val noParentEntry = NotificationEntryBuilder().setParent(null).build()
        assertThat(gmm.isChildInGroup(noParentEntry)).isFalse()
    }
    @Test
    fun testIsChildInGroup_summary_old() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, false)

        val groupKey = "group"
        val summary =
            NotificationEntryBuilder()
                .setGroup(mContext, groupKey)
                .setGroupSummary(mContext, true)
                .build()
        GroupEntryBuilder().setKey(groupKey).setSummary(summary).build()

        assertThat(gmm.isChildInGroup(summary)).isTrue()
    }

    @Test
    fun testIsChildInGroup_summary_new() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, true)

        val groupKey = "group"
        val summary =
            NotificationEntryBuilder()
                .setGroup(mContext, groupKey)
                .setGroupSummary(mContext, true)
                .build()
        GroupEntryBuilder().setKey(groupKey).setSummary(summary).build()

        assertThat(gmm.isChildInGroup(summary)).isFalse()
    }

    @Test
    fun testIsChildInGroup_child() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, false)
        val childEntry = NotificationEntryBuilder().build()
        assertThat(gmm.isChildInGroup(childEntry)).isTrue()
    }

    @Test
    fun testIsGroupSummary_topLevelEntry() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, true)
        val entry = NotificationEntryBuilder().setParent(GroupEntry.ROOT_ENTRY).build()
        assertThat(gmm.isGroupSummary(entry)).isFalse()
    }

    @Test
    fun testIsGroupSummary_summary() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, true)

        val groupKey = "group"
        val summary =
            NotificationEntryBuilder()
                .setGroup(mContext, groupKey)
                .setGroupSummary(mContext, true)
                .build()
        GroupEntryBuilder().setKey(groupKey).setSummary(summary).build()

        assertThat(gmm.isGroupSummary(summary)).isTrue()
    }

    @Test
    fun testIsGroupSummary_child() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, true)

        val groupKey = "group"
        val summary =
            NotificationEntryBuilder()
                .setGroup(mContext, groupKey)
                .setGroupSummary(mContext, true)
                .build()
        val entry = NotificationEntryBuilder().setGroup(mContext, groupKey).build()
        GroupEntryBuilder().setKey(groupKey).setSummary(summary).addChild(entry).build()

        assertThat(gmm.isGroupSummary(entry)).isFalse()
    }

    @Test
    fun testGetGroupSummary_topLevelEntry() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, true)
        val entry = NotificationEntryBuilder().setParent(GroupEntry.ROOT_ENTRY).build()
        assertThat(gmm.getGroupSummary(entry)).isNull()
    }

    @Test
    fun testGetGroupSummary_summary() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, true)

        val groupKey = "group"
        val summary =
            NotificationEntryBuilder()
                .setGroup(mContext, groupKey)
                .setGroupSummary(mContext, true)
                .build()
        GroupEntryBuilder().setKey(groupKey).setSummary(summary).build()

        assertThat(gmm.getGroupSummary(summary)).isEqualTo(summary)
    }

    @Test
    fun testGetGroupSummary_child() {
        featureFlags.set(Flags.NOTIFICATION_GROUP_EXPANSION_CHANGE, true)

        val groupKey = "group"
        val summary =
            NotificationEntryBuilder()
                .setGroup(mContext, groupKey)
                .setGroupSummary(mContext, true)
                .build()
        val entry = NotificationEntryBuilder().setGroup(mContext, groupKey).build()
        GroupEntryBuilder().setKey(groupKey).setSummary(summary).addChild(entry).build()

        assertThat(gmm.getGroupSummary(entry)).isEqualTo(summary)
    }
}
