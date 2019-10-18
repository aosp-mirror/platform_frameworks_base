/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.Nullable;

/**
 * Abstract superclass for top-level entries, i.e. things that can appear in the final notification
 * list shown to users. In practice, this means either GroupEntries or NotificationEntries.
 */
public abstract class ListEntry {
    private final String mKey;

    @Nullable private GroupEntry mParent;
    @Nullable private GroupEntry mPreviousParent;
    private int mSection;
    int mFirstAddedIteration = -1;

    ListEntry(String key) {
        mKey = key;
    }

    public String getKey() {
        return mKey;
    }

    /**
     * Should return the "representative entry" for this ListEntry. For NotificationEntries, its
     * the entry itself. For groups, it should be the summary. This method exists to interface with
     * legacy code that expects groups to also be NotificationEntries.
     */
    public abstract NotificationEntry getRepresentativeEntry();

    @Nullable public GroupEntry getParent() {
        return mParent;
    }

    void setParent(@Nullable GroupEntry parent) {
        mParent = parent;
    }

    @Nullable public GroupEntry getPreviousParent() {
        return mPreviousParent;
    }

    void setPreviousParent(@Nullable GroupEntry previousParent) {
        mPreviousParent = previousParent;
    }

    /** The section this notification was assigned to (0 to N-1, where N is number of sections). */
    public int getSection() {
        return mSection;
    }

    void setSection(int section) {
        mSection = section;
    }
}
