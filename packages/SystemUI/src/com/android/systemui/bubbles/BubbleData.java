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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.bubbles.BubbleController.DismissReason;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Keeps track of active bubbles.
 */
@Singleton
public class BubbleData {

    private static final String TAG = "BubbleData";

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
        void onBubbleRemoved(Bubble bubble, @DismissReason int reason);

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

    private final Context mContext;
    private final List<Bubble> mBubbles = new ArrayList<>();
    private Bubble mSelectedBubble;
    private boolean mExpanded;
    private Listener mListener;

    @VisibleForTesting
    @Inject
    public BubbleData(Context context) {
        mContext = context;
    }

    public boolean hasBubbles() {
        return !mBubbles.isEmpty();
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    public boolean hasBubbleWithKey(String key) {
        return getBubbleWithKey(key) != null;
    }

    public void setExpanded(boolean expanded) {
        if (setExpandedInternal(expanded)) {
            mListener.apply();
        }
    }

    public void setSelectedBubble(Bubble bubble) {
        if (setSelectedBubbleInternal(bubble)) {
            mListener.apply();
        }
    }

    public void notificationEntryUpdated(NotificationEntry entry) {
        Bubble bubble = getBubbleWithKey(entry.key);
        if (bubble == null) {
            // Create a new bubble
            bubble = new Bubble(entry, this::onBubbleBlocked);
            mBubbles.add(0, bubble); // TODO: reorder/group
            mListener.onBubbleAdded(bubble);
        } else {
            // Updates an existing bubble
            bubble.setEntry(entry);
            mListener.onBubbleUpdated(bubble);
        }
        if (shouldAutoExpand(entry)) {
            setSelectedBubbleInternal(bubble);
            if (!mExpanded) {
                setExpandedInternal(true);
            }
        } else if (mSelectedBubble == null) {
            setSelectedBubbleInternal(bubble);
        }
        // TODO: reorder/group
        mListener.apply();
    }

    public void notificationEntryRemoved(NotificationEntry entry, @DismissReason int reason) {
        int indexToRemove = indexForKey(entry.key);
        if (indexToRemove >= 0) {
            Bubble removed = mBubbles.remove(indexToRemove);
            removed.setDismissed();
            mListener.onBubbleRemoved(removed, reason);
            maybeSendDeleteIntent(reason, removed.entry);

            if (mBubbles.isEmpty()) {
                setExpandedInternal(false);
                setSelectedBubbleInternal(null);
            } else if (removed == mSelectedBubble) {
                int newIndex = Math.min(indexToRemove, mBubbles.size() - 1);
                Bubble newSelected = mBubbles.get(newIndex);
                setSelectedBubbleInternal(newSelected);
            }
            // TODO: reorder/group
            mListener.apply();
        }
    }

    public void dismissAll(@DismissReason int reason) {
        boolean changed = setExpandedInternal(false);
        while (!mBubbles.isEmpty()) {
            Bubble bubble = mBubbles.remove(0);
            bubble.setDismissed();
            maybeSendDeleteIntent(reason, bubble.entry);
            mListener.onBubbleRemoved(bubble, reason);
            changed = true;
        }
        if (setSelectedBubbleInternal(null)) {
            changed = true;
        }
        if (changed) {
            // TODO: reorder/group
            mListener.apply();
        }
    }

    /**
     * Requests a change to the selected bubble. Calls {@link Listener#onSelectionChanged} if
     * the value changes.
     *
     * @param bubble the new selected bubble
     * @return true if the state changed as a result
     */
    private boolean setSelectedBubbleInternal(Bubble bubble) {
        if (Objects.equals(bubble, mSelectedBubble)) {
            return false;
        }
        if (bubble != null && !mBubbles.contains(bubble)) {
            Log.e(TAG, "Cannot select bubble which doesn't exist!"
                    + " (" + bubble + ") bubbles=" + mBubbles);
            return false;
        }
        if (mExpanded) {
            // TODO: bubble.markAsActive() ?
            bubble.entry.setShowInShadeWhenBubble(false);
        }
        mSelectedBubble = bubble;
        mListener.onSelectionChanged(mSelectedBubble);
        return true;
    }


    /**
     * Requests a change to the expanded state. Calls {@link Listener#onExpandedChanged} if
     * the value changes.
     *
     * @param shouldExpand the new requested state
     * @return true if the state changed as a result
     */
    private boolean setExpandedInternal(boolean shouldExpand) {
        if (mExpanded == shouldExpand) {
            return false;
        }
        if (shouldExpand) {
            if (mBubbles.isEmpty()) {
                Log.e(TAG, "Attempt to expand stack when empty!");
                return false;
            }
            if (mSelectedBubble == null) {
                Log.e(TAG, "Attempt to expand stack without selected bubble!");
                return false;
            }
            // TODO: bubble.markAsActive() ?
            mSelectedBubble.entry.setShowInShadeWhenBubble(false);
        }
        // TODO: reorder/regroup
        mExpanded = shouldExpand;
        mListener.onExpandedChanged(mExpanded);
        return true;
    }

    private void maybeSendDeleteIntent(@DismissReason int reason, NotificationEntry entry) {
        if (reason == BubbleController.DISMISS_USER_GESTURE) {
            Notification.BubbleMetadata bubbleMetadata = entry.getBubbleMetadata();
            PendingIntent deleteIntent = bubbleMetadata != null
                    ? bubbleMetadata.getDeleteIntent()
                    : null;
            if (deleteIntent != null) {
                try {
                    deleteIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "Failed to send delete intent for bubble with key: " + entry.key);
                }
            }
        }
    }

    private void onBubbleBlocked(NotificationEntry entry) {
        boolean changed = false;
        final String blockedPackage = entry.notification.getPackageName();
        for (Iterator<Bubble> i = mBubbles.iterator(); i.hasNext(); ) {
            Bubble bubble = i.next();
            if (bubble.getPackageName().equals(blockedPackage)) {
                i.remove();
                mListener.onBubbleRemoved(bubble, BubbleController.DISMISS_BLOCKED);
                changed = true;
            }
        }
        if (changed) {
            // TODO: reorder/group
            mListener.apply();
        }
    }

    private int indexForKey(String key) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            if (bubble.getKey().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private Bubble removeBubbleWithKey(String key) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            if (bubble.getKey().equals(key)) {
                mBubbles.remove(i);
                return bubble;
            }
        }
        return null;
    }

    /**
     * The set of bubbles.
     *
     * @deprecated
     */
    @Deprecated
    public Collection<Bubble> getBubbles() {
        return Collections.unmodifiableList(mBubbles);
    }

    @VisibleForTesting(visibility = PRIVATE)
    Bubble getBubbleWithKey(String key) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubble = mBubbles.get(i);
            if (bubble.getKey().equals(key)) {
                return bubble;
            }
        }
        return null;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    boolean shouldAutoExpand(NotificationEntry entry) {
        Notification.BubbleMetadata metadata = entry.getBubbleMetadata();
        return metadata != null && metadata.getAutoExpandBubble()
                && isForegroundApp(entry.notification.getPackageName());
    }

    /**
     * Return true if the applications with the package name is running in foreground.
     *
     * @param pkgName application package name.
     */
    boolean isForegroundApp(String pkgName) {
        ActivityManager am = mContext.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1 /* maxNum */);
        return !tasks.isEmpty() && pkgName.equals(tasks.get(0).topActivity.getPackageName());
    }
}