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

import static java.util.stream.Collectors.toList;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.bubbles.BubbleController.DismissReason;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Keeps track of active bubbles.
 */
@Singleton
public class BubbleData {

    private static final String TAG = "BubbleData";
    private static final boolean DEBUG = false;

    private static final int MAX_BUBBLES = 5;

    private static final Comparator<Bubble> BUBBLES_BY_LAST_ACTIVITY_DESCENDING =
            Comparator.comparing(Bubble::getLastActivity).reversed();

    private static final Comparator<Map.Entry<String, Long>> GROUPS_BY_LAST_ACTIVITY_DESCENDING =
            Comparator.<Map.Entry<String, Long>, Long>comparing(Map.Entry::getValue).reversed();

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
        void onSelectionChanged(@Nullable Bubble selectedBubble);

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

    interface TimeSource {
        long currentTimeMillis();
    }

    private final Context mContext;
    private List<Bubble> mBubbles;
    private Bubble mSelectedBubble;
    private boolean mExpanded;

    // TODO: ensure this is invalidated at the appropriate time
    private int mSelectedBubbleExpandedPosition = -1;

    private TimeSource mTimeSource = System::currentTimeMillis;

    @Nullable
    private Listener mListener;

    @VisibleForTesting
    @Inject
    public BubbleData(Context context) {
        mContext = context;
        mBubbles = new ArrayList<>();
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

    @Nullable
    public Bubble getSelectedBubble() {
        return mSelectedBubble;
    }

    public void setExpanded(boolean expanded) {
        if (setExpandedInternal(expanded)) {
            dispatchApply();
        }
    }

    public void setSelectedBubble(Bubble bubble) {
        if (DEBUG) {
            Log.d(TAG, "setSelectedBubble: " + bubble);
        }
        if (setSelectedBubbleInternal(bubble)) {
            dispatchApply();
        }
    }

    public void notificationEntryUpdated(NotificationEntry entry) {
        if (DEBUG) {
            Log.d(TAG, "notificationEntryUpdated: " + entry);
        }
        Bubble bubble = getBubbleWithKey(entry.key);
        if (bubble == null) {
            // Create a new bubble
            bubble = new Bubble(entry, this::onBubbleBlocked);
            doAdd(bubble);
            dispatchOnBubbleAdded(bubble);
        } else {
            // Updates an existing bubble
            bubble.setEntry(entry);
            doUpdate(bubble);
            dispatchOnBubbleUpdated(bubble);
        }
        if (shouldAutoExpand(entry)) {
            setSelectedBubbleInternal(bubble);
            if (!mExpanded) {
                setExpandedInternal(true);
            }
        } else if (mSelectedBubble == null) {
            setSelectedBubbleInternal(bubble);
        }
        dispatchApply();
    }

    private void doAdd(Bubble bubble) {
        if (DEBUG) {
            Log.d(TAG, "doAdd: " + bubble);
        }
        int minInsertPoint = 0;
        boolean newGroup = !hasBubbleWithGroupId(bubble.getGroupId());
        if (isExpanded()) {
            // first bubble of a group goes to the end, otherwise it goes within the existing group
            minInsertPoint =
                    newGroup ? mBubbles.size() : findFirstIndexForGroup(bubble.getGroupId());
        }
        insertBubble(minInsertPoint, bubble);
        if (!isExpanded()) {
            packGroup(findFirstIndexForGroup(bubble.getGroupId()));
        }
        if (mBubbles.size() > MAX_BUBBLES) {
            mBubbles.stream()
                    // sort oldest first (ascending lastActivity)
                    .sorted(Comparator.comparingLong(Bubble::getLastActivity))
                    // skip the selected bubble
                    .filter((b) -> !b.equals(mSelectedBubble))
                    .findFirst()
                    .ifPresent((b) -> {
                        doRemove(b.getKey(), BubbleController.DISMISS_AGED);
                        dispatchApply();
                    });
        }
    }

    private void doUpdate(Bubble bubble) {
        if (DEBUG) {
            Log.d(TAG, "doUpdate: " + bubble);
        }
        if (!isExpanded()) {
            // while collapsed, update causes re-sort
            mBubbles.remove(bubble);
            insertBubble(0, bubble);
            packGroup(findFirstIndexForGroup(bubble.getGroupId()));
        }
    }

    public void notificationEntryRemoved(NotificationEntry entry, @DismissReason int reason) {
        if (DEBUG) {
            Log.d(TAG, "notificationEntryRemoved: entry=" + entry + " reason=" + reason);
        }
        doRemove(entry.key, reason);
        dispatchApply();
    }

    private void doRemove(String key, @DismissReason int reason) {
        int indexToRemove = indexForKey(key);
        if (indexToRemove >= 0) {
            Bubble bubbleToRemove = mBubbles.get(indexToRemove);
            if (mBubbles.size() == 1) {
                // Going to become empty, handle specially.
                setExpandedInternal(false);
                setSelectedBubbleInternal(null);
            }
            mBubbles.remove(indexToRemove);
            dispatchOnBubbleRemoved(bubbleToRemove, reason);

            // Note: If mBubbles.isEmpty(), then mSelectedBubble is now null.
            if (Objects.equals(mSelectedBubble, bubbleToRemove)) {
                // Move selection to the new bubble at the same position.
                int newIndex = Math.min(indexToRemove, mBubbles.size() - 1);
                Bubble newSelected = mBubbles.get(newIndex);
                setSelectedBubbleInternal(newSelected);
            }
            bubbleToRemove.setDismissed();
            maybeSendDeleteIntent(reason, bubbleToRemove.entry);
        }
    }

    public void dismissAll(@DismissReason int reason) {
        if (DEBUG) {
            Log.d(TAG, "dismissAll: reason=" + reason);
        }
        if (mBubbles.isEmpty()) {
            return;
        }
        setExpandedInternal(false);
        setSelectedBubbleInternal(null);
        while (!mBubbles.isEmpty()) {
            Bubble bubble = mBubbles.remove(0);
            bubble.setDismissed();
            maybeSendDeleteIntent(reason, bubble.entry);
            dispatchOnBubbleRemoved(bubble, reason);
        }
        dispatchApply();
    }

    private void dispatchApply() {
        if (mListener != null) {
            mListener.apply();
        }
    }

    private void dispatchOnBubbleAdded(Bubble bubble) {
        if (mListener != null) {
            mListener.onBubbleAdded(bubble);
        }
    }

    private void dispatchOnBubbleRemoved(Bubble bubble, @DismissReason int reason) {
        if (mListener != null) {
            mListener.onBubbleRemoved(bubble, reason);
        }
    }

    private void dispatchOnExpandedChanged(boolean expanded) {
        if (mListener != null) {
            mListener.onExpandedChanged(expanded);
        }
    }

    private void dispatchOnSelectionChanged(@Nullable Bubble bubble) {
        if (mListener != null) {
            mListener.onSelectionChanged(bubble);
        }
    }

    private void dispatchOnBubbleUpdated(Bubble bubble) {
        if (mListener != null) {
            mListener.onBubbleUpdated(bubble);
        }
    }

    private void dispatchOnOrderChanged(List<Bubble> bubbles) {
        if (mListener != null) {
            mListener.onOrderChanged(bubbles);
        }
    }

    private void dispatchShowFlyoutText(Bubble bubble, String text) {
        if (mListener != null) {
            mListener.showFlyoutText(bubble, text);
        }
    }

    /**
     * Requests a change to the selected bubble. Calls {@link Listener#onSelectionChanged} if
     * the value changes.
     *
     * @param bubble the new selected bubble
     * @return true if the state changed as a result
     */
    private boolean setSelectedBubbleInternal(@Nullable Bubble bubble) {
        if (DEBUG) {
            Log.d(TAG, "setSelectedBubbleInternal: " + bubble);
        }
        if (Objects.equals(bubble, mSelectedBubble)) {
            return false;
        }
        if (bubble != null && !mBubbles.contains(bubble)) {
            Log.e(TAG, "Cannot select bubble which doesn't exist!"
                    + " (" + bubble + ") bubbles=" + mBubbles);
            return false;
        }
        if (mExpanded && bubble != null) {
            bubble.markAsAccessedAt(mTimeSource.currentTimeMillis());
        }
        mSelectedBubble = bubble;
        dispatchOnSelectionChanged(mSelectedBubble);
        if (!mExpanded || mSelectedBubble == null) {
            mSelectedBubbleExpandedPosition = -1;
        }
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
        if (DEBUG) {
            Log.d(TAG, "setExpandedInternal: shouldExpand=" + shouldExpand);
        }
        if (mExpanded == shouldExpand) {
            return false;
        }
        if (mSelectedBubble != null) {
            mSelectedBubble.markAsAccessedAt(mTimeSource.currentTimeMillis());
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
        } else {
            repackAll();
        }
        mExpanded = shouldExpand;
        dispatchOnExpandedChanged(mExpanded);
        return true;
    }

    private static long sortKey(Bubble bubble) {
        long key = bubble.getLastActivity();
        if (bubble.isOngoing()) {
            // Set 2nd highest bit (signed long int), to partition between ongoing and regular
            key |= 0x4000000000000000L;
        }
        return key;
    }

    /**
     * Locates and inserts the bubble into a sorted position. The is inserted
     * based on sort key, groupId is not considered. A call to {@link #packGroup(int)} may be
     * required to keep grouping intact.
     *
     * @param minPosition the first insert point to consider
     * @param newBubble the bubble to insert
     * @return the position where the bubble was inserted
     */
    private int insertBubble(int minPosition, Bubble newBubble) {
        long newBubbleSortKey = sortKey(newBubble);
        String previousGroupId = null;

        for (int pos = minPosition; pos < mBubbles.size(); pos++) {
            Bubble bubbleAtPos = mBubbles.get(pos);
            String groupIdAtPos = bubbleAtPos.getGroupId();
            boolean atStartOfGroup = !groupIdAtPos.equals(previousGroupId);

            if (atStartOfGroup && newBubbleSortKey > sortKey(bubbleAtPos)) {
                // Insert before the start of first group which has older bubbles.
                mBubbles.add(pos, newBubble);
                return pos;
            }
            previousGroupId = groupIdAtPos;
        }
        mBubbles.add(newBubble);
        return mBubbles.size() - 1;
    }

    private boolean hasBubbleWithGroupId(String groupId) {
        return mBubbles.stream().anyMatch(b -> b.getGroupId().equals(groupId));
    }

    private int findFirstIndexForGroup(String appId) {
        for (int i = 0; i < mBubbles.size(); i++) {
            Bubble bubbleAtPos = mBubbles.get(i);
            if (bubbleAtPos.getGroupId().equals(appId)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Starting at the given position, moves all bubbles with the same group id to follow. Bubbles
     * at positions lower than {@code position} are unchanged. Relative order within the group
     * unchanged. Relative order of any other bubbles are also unchanged.
     *
     * @param position the position of the first bubble for the group
     */
    private void packGroup(int position) {
        if (DEBUG) {
            Log.d(TAG, "packGroup: position=" + position);
        }
        Bubble groupStart = mBubbles.get(position);
        final String groupAppId = groupStart.getGroupId();
        List<Bubble> moving = new ArrayList<>();

        // Walk backward, collect bubbles within the group
        for (int i = mBubbles.size() - 1; i > position; i--) {
            if (mBubbles.get(i).getGroupId().equals(groupAppId)) {
                moving.add(0, mBubbles.get(i));
            }
        }
        mBubbles.removeAll(moving);
        mBubbles.addAll(position + 1, moving);
    }

    private void repackAll() {
        if (DEBUG) {
            Log.d(TAG, "repackAll()");
        }
        if (mBubbles.isEmpty()) {
            return;
        }
        Map<String, Long> groupLastActivity = new HashMap<>();
        for (Bubble bubble : mBubbles) {
            long maxSortKeyForGroup = groupLastActivity.getOrDefault(bubble.getGroupId(), 0L);
            long sortKeyForBubble = sortKey(bubble);
            if (sortKeyForBubble > maxSortKeyForGroup) {
                groupLastActivity.put(bubble.getGroupId(), sortKeyForBubble);
            }
        }

        // Sort groups by their most recently active bubble
        List<String> groupsByMostRecentActivity =
                groupLastActivity.entrySet().stream()
                        .sorted(GROUPS_BY_LAST_ACTIVITY_DESCENDING)
                        .map(Map.Entry::getKey)
                        .collect(toList());

        List<Bubble> repacked = new ArrayList<>(mBubbles.size());

        // For each group, add bubbles, freshest to oldest
        for (String appId : groupsByMostRecentActivity) {
            mBubbles.stream()
                    .filter((b) -> b.getGroupId().equals(appId))
                    .sorted(BUBBLES_BY_LAST_ACTIVITY_DESCENDING)
                    .forEachOrdered(repacked::add);
        }
        mBubbles = repacked;
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
                // TODO: handle removal of selected bubble, and collapse safely if emptied (see
                //  dismissAll)
                dispatchOnBubbleRemoved(bubble, BubbleController.DISMISS_BLOCKED);
                changed = true;
            }
        }
        if (changed) {
            dispatchApply();
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

    /**
     * The set of bubbles.
     */
    @VisibleForTesting(visibility = PRIVATE)
    public List<Bubble> getBubbles() {
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

    @VisibleForTesting(visibility = PRIVATE)
    void setTimeSource(TimeSource timeSource) {
        mTimeSource = timeSource;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    boolean shouldAutoExpand(NotificationEntry entry) {
        Notification.BubbleMetadata metadata = entry.getBubbleMetadata();
        return metadata != null && metadata.getAutoExpandBubble()
                && BubbleController.isForegroundApp(mContext, entry.notification.getPackageName());
    }
}