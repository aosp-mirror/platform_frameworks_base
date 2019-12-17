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

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.notification.collection.provider.DerivedMember;
import com.android.systemui.statusbar.notification.collection.provider.IsHighPriorityProvider;
import com.android.systemui.statusbar.phone.NotificationGroupManager;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Abstract superclass for top-level entries, i.e. things that can appear in the final notification
 * list shown to users. In practice, this means either GroupEntries or NotificationEntries.
 */
public abstract class ListEntry {
    private final String mKey;
    private final IsHighPriorityProvider mIsHighPriorityProvider = new IsHighPriorityProvider();
    private final List<DerivedMember> mDerivedMemberList = Arrays.asList(mIsHighPriorityProvider);

    @Nullable private GroupEntry mParent;
    @Nullable private GroupEntry mPreviousParent;
    private int mSection;
    int mFirstAddedIteration = -1;

    // TODO: (b/145659174) remove groupManager when moving to NewNotifPipeline. Logic
    //  replaced in GroupEntry and NotifListBuilderImpl
    private final NotificationGroupManager mGroupManager;

    ListEntry(String key) {
        mKey = key;

        // TODO: (b/145659174) remove
        mGroupManager = Dependency.get(NotificationGroupManager.class);
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

    @VisibleForTesting
    public void setParent(@Nullable GroupEntry parent) {
        if (!Objects.equals(mParent, parent)) {
            invalidateParent();
            mParent = parent;
            onGroupingUpdated();
        }
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

    /**
     * Resets the cached values of DerivedMembers.
     */
    void invalidateDerivedMembers() {
        for (int i = 0; i < mDerivedMemberList.size(); i++) {
            mDerivedMemberList.get(i).invalidate();
        }
    }

    /**
     * Whether this notification is shown to the user as a high priority notification: visible on
     * the lock screen/status bar and in the top section in the shade.
     */
    public boolean isHighPriority() {
        return mIsHighPriorityProvider.get(this);
    }

    private void invalidateParent() {
        // invalidate our parent (GroupEntry) since DerivedMembers may be dependent on children
        if (getParent() != null) {
            getParent().invalidateDerivedMembers();
        }

        // TODO: (b/145659174) remove
        final NotificationEntry notifEntry = getRepresentativeEntry();
        if (notifEntry != null && mGroupManager.isGroupChild(notifEntry.getSbn())) {
            NotificationEntry summary = mGroupManager.getLogicalGroupSummary(notifEntry.getSbn());
            if (summary != null) {
                summary.invalidateDerivedMembers();
            }
        }
    }

    void onGroupingUpdated() {
        for (int i = 0; i < mDerivedMemberList.size(); i++) {
            mDerivedMemberList.get(i).onGroupingUpdated();
        }
        invalidateParent();
    }

    void onSbnUpdated() {
        for (int i = 0; i < mDerivedMemberList.size(); i++) {
            mDerivedMemberList.get(i).onSbnUpdated();
        }
        invalidateParent();
    }

    void onRankingUpdated() {
        for (int i = 0; i < mDerivedMemberList.size(); i++) {
            mDerivedMemberList.get(i).onRankingUpdated();
        }
        invalidateParent();
    }
}
