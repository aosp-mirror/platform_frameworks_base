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
import android.util.Pair;

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

    private static final Comparator<Bubble> BUBBLES_BY_SORT_KEY_DESCENDING =
            Comparator.comparing(BubbleData::sortKey).reversed();

    private static final Comparator<Map.Entry<String, Long>> GROUPS_BY_MAX_SORT_KEY_DESCENDING =
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

    // State tracked during an operation -- keeps track of what listener events to dispatch.
    private boolean mExpandedChanged;
    private boolean mOrderChanged;
    private boolean mSelectionChanged;
    private Bubble mUpdatedBubble;
    private Bubble mAddedBubble;
    private final List<Pair<Bubble, Integer>> mRemovedBubbles = new ArrayList<>();

    private TimeSource mTimeSource = System::currentTimeMillis;

    @Nullable
    private Listener mListener;

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
        if (DEBUG) {
            Log.d(TAG, "setExpanded: " + expanded);
        }
        setExpandedInternal(expanded);
        dispatchPendingChanges();
    }

    public void setSelectedBubble(Bubble bubble) {
        if (DEBUG) {
            Log.d(TAG, "setSelectedBubble: " + bubble);
        }
        setSelectedBubbleInternal(bubble);
        dispatchPendingChanges();
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
            trim();
        } else {
            // Updates an existing bubble
            bubble.setEntry(entry);
            doUpdate(bubble);
            mUpdatedBubble = bubble;
        }
        if (shouldAutoExpand(entry)) {
            setSelectedBubbleInternal(bubble);
            if (!mExpanded) {
                setExpandedInternal(true);
            }
        } else if (mSelectedBubble == null) {
            setSelectedBubbleInternal(bubble);
        }
        dispatchPendingChanges();
    }

    public void notificationEntryRemoved(NotificationEntry entry, @DismissReason int reason) {
        if (DEBUG) {
            Log.d(TAG, "notificationEntryRemoved: entry=" + entry + " reason=" + reason);
        }
        doRemove(entry.key, reason);
        dispatchPendingChanges();
    }

    private void doAdd(Bubble bubble) {
        if (DEBUG) {
            Log.d(TAG, "doAdd: " + bubble);
        }
        int minInsertPoint = 0;
        boolean newGroup = !hasBubbleWithGroupId(bubble.getGroupId());
        if (isExpanded()) {
            // first bubble of a group goes to the beginning, otherwise within the existing group
            minInsertPoint = newGroup ? 0 : findFirstIndexForGroup(bubble.getGroupId());
        }
        if (insertBubble(minInsertPoint, bubble) < mBubbles.size() - 1) {
            mOrderChanged = true;
        }
        mAddedBubble = bubble;
        if (!isExpanded()) {
            mOrderChanged |= packGroup(findFirstIndexForGroup(bubble.getGroupId()));
            // Top bubble becomes selected.
            setSelectedBubbleInternal(mBubbles.get(0));
        }
    }

    private void trim() {
        if (mBubbles.size() > MAX_BUBBLES) {
            mBubbles.stream()
                    // sort oldest first (ascending lastActivity)
                    .sorted(Comparator.comparingLong(Bubble::getLastActivity))
                    // skip the selected bubble
                    .filter((b) -> !b.equals(mSelectedBubble))
                    .findFirst()
                    .ifPresent((b) -> doRemove(b.getKey(), BubbleController.DISMISS_AGED));
        }
    }

    private void doUpdate(Bubble bubble) {
        if (DEBUG) {
            Log.d(TAG, "doUpdate: " + bubble);
        }
        if (!isExpanded()) {
            // while collapsed, update causes re-pack
            int prevPos = mBubbles.indexOf(bubble);
            mBubbles.remove(bubble);
            int newPos = insertBubble(0, bubble);
            if (prevPos != newPos) {
                packGroup(newPos);
                mOrderChanged = true;
            }
            setSelectedBubbleInternal(mBubbles.get(0));
        }
    }

    private void doRemove(String key, @DismissReason int reason) {
        int indexToRemove = indexForKey(key);
        if (indexToRemove == -1) {
            return;
        }
        Bubble bubbleToRemove = mBubbles.get(indexToRemove);
        if (mBubbles.size() == 1) {
            // Going to become empty, handle specially.
            setExpandedInternal(false);
            setSelectedBubbleInternal(null);
        }
        if (indexToRemove < mBubbles.size() - 1) {
            // Removing anything but the last bubble means positions will change.
            mOrderChanged = true;
        }
        mBubbles.remove(indexToRemove);
        mRemovedBubbles.add(Pair.create(bubbleToRemove, reason));
        if (!isExpanded()) {
            mOrderChanged |= repackAll();
        }

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
            mRemovedBubbles.add(Pair.create(bubble, reason));
        }
        dispatchPendingChanges();
    }


    private void dispatchPendingChanges() {
        if (mListener == null) {
            mExpandedChanged = false;
            mAddedBubble = null;
            mSelectionChanged = false;
            mRemovedBubbles.clear();
            mUpdatedBubble = null;
            mOrderChanged = false;
            return;
        }
        boolean anythingChanged = false;

        if (mAddedBubble != null) {
            mListener.onBubbleAdded(mAddedBubble);
            mAddedBubble = null;
            anythingChanged = true;
        }

        // Compat workaround: Always collapse first.
        if (mExpandedChanged && !mExpanded) {
            mListener.onExpandedChanged(mExpanded);
            mExpandedChanged = false;
            anythingChanged = true;
        }

        if (mSelectionChanged) {
            mListener.onSelectionChanged(mSelectedBubble);
            mSelectionChanged = false;
            anythingChanged = true;
        }

        if (!mRemovedBubbles.isEmpty()) {
            for (Pair<Bubble, Integer> removed : mRemovedBubbles) {
                mListener.onBubbleRemoved(removed.first, removed.second);
            }
            mRemovedBubbles.clear();
            anythingChanged = true;
        }

        if (mUpdatedBubble != null) {
            mListener.onBubbleUpdated(mUpdatedBubble);
            mUpdatedBubble = null;
            anythingChanged = true;
        }

        if (mOrderChanged) {
            mListener.onOrderChanged(mBubbles);
            mOrderChanged = false;
            anythingChanged = true;
        }

        if (mExpandedChanged) {
            mListener.onExpandedChanged(mExpanded);
            mExpandedChanged = false;
            anythingChanged = true;
        }

        if (anythingChanged) {
            mListener.apply();
        }
    }

    /**
     * Requests a change to the selected bubble. Calls {@link Listener#onSelectionChanged} if
     * the value changes.
     *
     * @param bubble the new selected bubble
     */
    private void setSelectedBubbleInternal(@Nullable Bubble bubble) {
        if (DEBUG) {
            Log.d(TAG, "setSelectedBubbleInternal: " + bubble);
        }
        if (Objects.equals(bubble, mSelectedBubble)) {
            return;
        }
        if (bubble != null && !mBubbles.contains(bubble)) {
            Log.e(TAG, "Cannot select bubble which doesn't exist!"
                    + " (" + bubble + ") bubbles=" + mBubbles);
            return;
        }
        if (mExpanded && bubble != null) {
            bubble.markAsAccessedAt(mTimeSource.currentTimeMillis());
        }
        mSelectedBubble = bubble;
        mSelectionChanged = true;
        return;
    }

    /**
     * Requests a change to the expanded state. Calls {@link Listener#onExpandedChanged} if
     * the value changes.
     *
     * @param shouldExpand the new requested state
     */
    private void setExpandedInternal(boolean shouldExpand) {
        if (DEBUG) {
            Log.d(TAG, "setExpandedInternal: shouldExpand=" + shouldExpand);
        }
        if (mExpanded == shouldExpand) {
            return;
        }
        if (shouldExpand) {
            if (mBubbles.isEmpty()) {
                Log.e(TAG, "Attempt to expand stack when empty!");
                return;
            }
            if (mSelectedBubble == null) {
                Log.e(TAG, "Attempt to expand stack without selected bubble!");
                return;
            }
            mSelectedBubble.markAsAccessedAt(mTimeSource.currentTimeMillis());
            mOrderChanged |= repackAll();
        } else if (!mBubbles.isEmpty()) {
            // Apply ordering and grouping rules from expanded -> collapsed, then save
            // the result.
            mOrderChanged |= repackAll();
            // Save the state which should be returned to when expanded (with no other changes)

            if (mBubbles.indexOf(mSelectedBubble) > 0) {
                // Move the selected bubble to the top while collapsed.
                if (!mSelectedBubble.isOngoing() && mBubbles.get(0).isOngoing()) {
                    // The selected bubble cannot be raised to the first position because
                    // there is an ongoing bubble there. Instead, force the top ongoing bubble
                    // to become selected.
                    setSelectedBubbleInternal(mBubbles.get(0));
                } else {
                    // Raise the selected bubble (and it's group) up to the front so the selected
                    // bubble remains on top.
                    mBubbles.remove(mSelectedBubble);
                    mBubbles.add(0, mSelectedBubble);
                    packGroup(0);
                }
            }
        }
        mExpanded = shouldExpand;
        mExpandedChanged = true;
    }

    private static long sortKey(Bubble bubble) {
        long key = bubble.getLastUpdateTime();
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
     * @return true if the position of any bubbles has changed as a result
     */
    private boolean packGroup(int position) {
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
        if (moving.isEmpty()) {
            return false;
        }
        mBubbles.removeAll(moving);
        mBubbles.addAll(position + 1, moving);
        return true;
    }

    /**
     * This applies a full sort and group pass to all existing bubbles. The bubbles are grouped
     * by groupId. Each group is then sorted by the max(lastUpdated) time of it's bubbles. Bubbles
     * within each group are then sorted by lastUpdated descending.
     *
     * @return true if the position of any bubbles changed as a result
     */
    private boolean repackAll() {
        if (DEBUG) {
            Log.d(TAG, "repackAll()");
        }
        if (mBubbles.isEmpty()) {
            return false;
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
                        .sorted(GROUPS_BY_MAX_SORT_KEY_DESCENDING)
                        .map(Map.Entry::getKey)
                        .collect(toList());

        List<Bubble> repacked = new ArrayList<>(mBubbles.size());

        // For each group, add bubbles, freshest to oldest
        for (String appId : groupsByMostRecentActivity) {
            mBubbles.stream()
                    .filter((b) -> b.getGroupId().equals(appId))
                    .sorted(BUBBLES_BY_SORT_KEY_DESCENDING)
                    .forEachOrdered(repacked::add);
        }
        if (repacked.equals(mBubbles)) {
            return false;
        }
        mBubbles = repacked;
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
        final String blockedGroupId = Bubble.groupId(entry);
        int selectedIndex = mBubbles.indexOf(mSelectedBubble);
        for (Iterator<Bubble> i = mBubbles.iterator(); i.hasNext(); ) {
            Bubble bubble = i.next();
            if (bubble.getGroupId().equals(blockedGroupId)) {
                mRemovedBubbles.add(Pair.create(bubble, BubbleController.DISMISS_BLOCKED));
                i.remove();
            }
        }
        if (mBubbles.isEmpty()) {
            setExpandedInternal(false);
            setSelectedBubbleInternal(null);
        } else if (!mBubbles.contains(mSelectedBubble)) {
            // choose a new one
            int newIndex = Math.min(selectedIndex, mBubbles.size() - 1);
            Bubble newSelected = mBubbles.get(newIndex);
            setSelectedBubbleInternal(newSelected);
        }
        dispatchPendingChanges();
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