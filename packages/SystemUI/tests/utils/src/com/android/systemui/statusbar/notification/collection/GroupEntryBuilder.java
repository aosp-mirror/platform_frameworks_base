/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection;

import androidx.annotation.Nullable;

import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder to construct instances of {@link GroupEntry} for tests.
 */
public class GroupEntryBuilder {
    private String mKey = "test_group_key";
    private long mCreationTime = 0;
    @Nullable private GroupEntry mParent = GroupEntry.ROOT_ENTRY;
    private NotifSection mNotifSection;
    @Nullable private NotificationEntry mSummary = null;
    private final List<NotificationEntry> mChildren = new ArrayList<>();

    /** Builds a new instance of GroupEntry */
    public GroupEntry build() {
        GroupEntry ge = new GroupEntry(mKey, mCreationTime);
        ge.setParent(mParent);
        ge.getAttachState().setSection(mNotifSection);

        ge.setSummary(mSummary);
        if (mSummary != null) {
            mSummary.setParent(ge);
        }

        for (NotificationEntry child : mChildren) {
            ge.addChild(child);
            child.setParent(ge);
        }
        return ge;
    }

    /** Sets the group key. */
    public GroupEntryBuilder setKey(String key) {
        mKey = key;
        return this;
    }

    /** Sets the creation time. */
    public GroupEntryBuilder setCreationTime(long creationTime) {
        mCreationTime = creationTime;
        return this;
    }

    /** Sets the parent entry of the group. */
    public GroupEntryBuilder setParent(@Nullable GroupEntry entry) {
        mParent = entry;
        return this;
    }

    /** Sets the section the group belongs to. */
    public GroupEntryBuilder setSection(@Nullable NotifSection section) {
        mNotifSection = section;
        return this;
    }

    /** Sets the group summary. */
    public GroupEntryBuilder setSummary(
            NotificationEntry summary) {
        mSummary = summary;
        return this;
    }

    /** Sets the group children. */
    public GroupEntryBuilder setChildren(List<NotificationEntry> children) {
        mChildren.clear();
        mChildren.addAll(children);
        return this;
    }

    /** Adds a child to the existing list of children */
    public GroupEntryBuilder addChild(NotificationEntry entry) {
        mChildren.add(entry);
        return this;
    }

    /** Get the group's internal children list. */
    public static List<NotificationEntry> getRawChildren(GroupEntry groupEntry) {
        return groupEntry.getRawChildren();
    }
}
