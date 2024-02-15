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
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class GroupMembershipManagerTest : SysuiTestCase() {
    private var underTest = GroupMembershipManagerImpl()

    @Test
    fun isChildInGroup_topLevel() {
        val topLevelEntry = NotificationEntryBuilder().setParent(GroupEntry.ROOT_ENTRY).build()
        assertThat(underTest.isChildInGroup(topLevelEntry)).isFalse()
    }

    @Test
    fun isChildInGroup_noParent() {
        val noParentEntry = NotificationEntryBuilder().setParent(null).build()
        assertThat(underTest.isChildInGroup(noParentEntry)).isFalse()
    }

    @Test
    fun isChildInGroup_summary() {
        val groupKey = "group"
        val summary =
            NotificationEntryBuilder()
                .setGroup(mContext, groupKey)
                .setGroupSummary(mContext, true)
                .build()
        GroupEntryBuilder().setKey(groupKey).setSummary(summary).build()

        assertThat(underTest.isChildInGroup(summary)).isFalse()
    }

    @Test
    fun isGroupSummary_topLevelEntry() {
        val entry = NotificationEntryBuilder().setParent(GroupEntry.ROOT_ENTRY).build()
        assertThat(underTest.isGroupSummary(entry)).isFalse()
    }

    @Test
    fun isGroupSummary_summary() {
        val groupKey = "group"
        val summary =
            NotificationEntryBuilder()
                .setGroup(mContext, groupKey)
                .setGroupSummary(mContext, true)
                .build()
        GroupEntryBuilder().setKey(groupKey).setSummary(summary).build()

        assertThat(underTest.isGroupSummary(summary)).isTrue()
    }

    @Test
    fun isGroupSummary_child() {
        val groupKey = "group"
        val summary =
            NotificationEntryBuilder()
                .setGroup(mContext, groupKey)
                .setGroupSummary(mContext, true)
                .build()
        val entry = NotificationEntryBuilder().setGroup(mContext, groupKey).build()
        GroupEntryBuilder().setKey(groupKey).setSummary(summary).addChild(entry).build()

        assertThat(underTest.isGroupSummary(entry)).isFalse()
    }

    @Test
    fun getGroupSummary_topLevelEntry() {
        val entry = NotificationEntryBuilder().setParent(GroupEntry.ROOT_ENTRY).build()
        assertThat(underTest.getGroupSummary(entry)).isNull()
    }

    @Test
    fun getGroupSummary_summary() {
        val groupKey = "group"
        val summary =
            NotificationEntryBuilder()
                .setGroup(mContext, groupKey)
                .setGroupSummary(mContext, true)
                .build()
        GroupEntryBuilder().setKey(groupKey).setSummary(summary).build()

        assertThat(underTest.getGroupSummary(summary)).isEqualTo(summary)
    }

    @Test
    fun getGroupSummary_child() {
        val groupKey = "group"
        val summary =
            NotificationEntryBuilder()
                .setGroup(mContext, groupKey)
                .setGroupSummary(mContext, true)
                .build()
        val entry = NotificationEntryBuilder().setGroup(mContext, groupKey).build()
        GroupEntryBuilder().setKey(groupKey).setSummary(summary).addChild(entry).build()

        assertThat(underTest.getGroupSummary(entry)).isEqualTo(summary)
    }
}
