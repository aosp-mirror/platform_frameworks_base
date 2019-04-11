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


import android.view.LayoutInflater;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Encapsulates the data and UI elements of a bubble.
 */
class Bubble {

    private final String mKey;
    private final BubbleExpandedView.OnBubbleBlockedListener mListener;

    private boolean mInflated;

    public BubbleView iconView;
    public BubbleExpandedView expandedView;
    public NotificationEntry entry;

    Bubble(NotificationEntry e, BubbleExpandedView.OnBubbleBlockedListener listener) {
        entry = e;
        mKey = e.key;
        mListener = listener;
    }

    /** @deprecated use the other constructor to defer View creation. */
    @Deprecated
    Bubble(NotificationEntry e, LayoutInflater inflater, BubbleStackView stackView,
            BubbleExpandedView.OnBubbleBlockedListener listener) {
        this(e, listener);
        inflate(inflater, stackView);
    }

    public String getKey() {
        return mKey;
    }

    boolean isInflated() {
        return mInflated;
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

    void setEntry(NotificationEntry entry) {
        if (mInflated) {
            iconView.update(entry);
            expandedView.update(entry);
        }
    }
}
