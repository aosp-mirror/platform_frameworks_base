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

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Keeps track of active bubbles.
 */
@Singleton
public class BubbleData {

    /**
     * This interface reports changes to the state and appearance of bubbles which should be applied
     * as necessary to the UI.
     * <p>
     * Each operation is a report of a pending operation. Each should be considered in
     * combination, when {@link #apply()} is called. For example, both: onExpansionChanged,
     * and onOrderChanged
     */
    interface Listener {

        /**
         * A new Bubble has been added. A call to {@link #onOrderChanged(List)} will
         * follow, including the new Bubble in position
         */
        void onBubbleAdded(Bubble bubble);

        /**
         * A Bubble has been removed. A call to {@link #onOrderChanged(List)} will
         * follow.
         */
        void onBubbleRemoved(Bubble bubble, @BubbleController.DismissReason int reason);

        /**
         * An existing bubble has been updated.
         *
         * @param bubble the bubble which was updated
         */
        void onBubbleUpdated(Bubble bubble);

        /**
         * Indicates that one or more bubbles should change position. This may be result of insert,
         * or removal of a Bubble, in addition to re-sorting existing Bubbles.
         *
         * @param bubbles an immutable list of the bubbles in the new order
         */
        void onOrderChanged(List<Bubble> bubbles);

        /** Indicates the selected bubble changed. */
        void onSelectionChanged(Bubble selectedBubble);

        /**
         * The UI should transition to the given state, incorporating any pending changes during
         * the animation.
         */
        void onExpandedChanged(boolean expanded);

        /** Flyout text should animate in, showing the given text. */
        void showFlyoutText(Bubble bubble, String text);

        /** Commit any pending operations (since last call of apply()) */
        void apply();
    }

    private HashMap<String, Bubble> mBubbles = new HashMap<>();
    private Listener mListener;

    @VisibleForTesting
    @Inject
    public BubbleData() {}

    /**
     * The set of bubbles.
     */
    public Collection<Bubble> getBubbles() {
        return mBubbles.values();
    }

    @Nullable
    public Bubble getBubble(String key) {
        return mBubbles.get(key);
    }

    public void addBubble(Bubble b) {
        mBubbles.put(b.getKey(), b);
    }

    @Nullable
    public Bubble removeBubble(String key) {
        return mBubbles.remove(key);
    }

    public void updateBubble(String key, NotificationEntry newEntry) {
        Bubble oldBubble = mBubbles.get(key);
        if (oldBubble != null) {
            oldBubble.setEntry(newEntry);
        }
    }

    public void clear() {
        mBubbles.clear();
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }
}
