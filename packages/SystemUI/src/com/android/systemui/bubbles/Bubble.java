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
package com.android.systemui.bubbles;


import android.os.UserHandle;
import android.view.LayoutInflater;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.Objects;

/**
 * Encapsulates the data and UI elements of a bubble.
 */
class Bubble {

    private static final boolean DEBUG = false;
    private static final String TAG = "Bubble";

    private final String mKey;
    private final String mGroupId;
    private final BubbleExpandedView.OnBubbleBlockedListener mListener;

    private boolean mInflated;
    public NotificationEntry entry;
    BubbleView iconView;
    BubbleExpandedView expandedView;

    private static String groupId(NotificationEntry entry) {
        UserHandle user = entry.notification.getUser();
        return user.getIdentifier() + '|' + entry.notification.getPackageName();
    }

    Bubble(NotificationEntry e, BubbleExpandedView.OnBubbleBlockedListener listener) {
        entry = e;
        mKey = e.key;
        mGroupId = groupId(e);
        mListener = listener;
    }

    public String getKey() {
        return mKey;
    }

    public String getGroupId() {
        return mGroupId;
    }

    public String getPackageName() {
        return entry.notification.getPackageName();
    }

    boolean isInflated() {
        return mInflated;
    }

    public void updateDotVisibility() {
        if (iconView != null) {
            iconView.updateDotVisibility();
        }
    }

    void inflate(LayoutInflater inflater, BubbleStackView stackView) {
        if (mInflated) {
            return;
        }
        iconView = (BubbleView) inflater.inflate(
                R.layout.bubble_view, stackView, false /* attachToRoot */);
        iconView.setNotif(entry);

        expandedView = (BubbleExpandedView) inflater.inflate(
                R.layout.bubble_expanded_view, stackView, false /* attachToRoot */);
        expandedView.setEntry(entry, stackView);

        expandedView.setOnBlockedListener(mListener);
        mInflated = true;
    }

    void setDismissed() {
        entry.setBubbleDismissed(true);
        // TODO: move this somewhere where it can be guaranteed not to run until safe from flicker
        if (expandedView != null) {
            expandedView.cleanUpExpandedState();
        }
    }

    void setEntry(NotificationEntry entry) {
        this.entry = entry;
        if (mInflated) {
            iconView.update(entry);
            expandedView.update(entry);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Bubble)) return false;
        Bubble bubble = (Bubble) o;
        return Objects.equals(mKey, bubble.mKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mKey);
    }
}
