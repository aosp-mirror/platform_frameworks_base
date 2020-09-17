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


import androidx.annotation.Nullable;

import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSection;

/**
 * Abstract superclass for top-level entries, i.e. things that can appear in the final notification
 * list shown to users. In practice, this means either GroupEntries or NotificationEntries.
 */
public abstract class ListEntry {
    private final String mKey;

    int mFirstAddedIteration = -1;

    private final ListAttachState mPreviousAttachState = ListAttachState.create();
    private final ListAttachState mAttachState = ListAttachState.create();

    ListEntry(String key) {
        mKey = key;
    }

    public String getKey() {
        return mKey;
    }

    /**
     * Should return the "representative entry" for this ListEntry. For NotificationEntries, its
     * the entry itself. For groups, it should be the summary (but if a summary doesn't exist,
     * this can return null). This method exists to interface with
     * legacy code that expects groups to also be NotificationEntries.
     */
    public abstract @Nullable NotificationEntry getRepresentativeEntry();

    @Nullable public GroupEntry getParent() {
        return mAttachState.getParent();
    }

    void setParent(@Nullable GroupEntry parent) {
        mAttachState.setParent(parent);
    }

    @Nullable public GroupEntry getPreviousParent() {
        return mPreviousAttachState.getParent();
    }

    /** The section this notification was assigned to (0 to N-1, where N is number of sections). */
    public int getSection() {
        return mAttachState.getSectionIndex();
    }

    @Nullable public NotifSection getNotifSection() {
        return mAttachState.getSection();
    }

    ListAttachState getAttachState() {
        return mAttachState;
    }

    ListAttachState getPreviousAttachState() {
        return mPreviousAttachState;
    }

    /**
     * Stores the current attach state into {@link #getPreviousAttachState()}} and then starts a
     * fresh attach state (all entries will be null/default-initialized).
     */
    void beginNewAttachState() {
        mPreviousAttachState.clone(mAttachState);
        mAttachState.reset();
    }
}
