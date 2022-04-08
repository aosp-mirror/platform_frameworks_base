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

package com.android.systemui.statusbar.notification.collection.coalescer;

import static java.util.Objects.requireNonNull;

import android.annotation.MainThread;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.time.SystemClock;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

/**
 * An attempt to make posting notification groups an atomic process
 *
 * Due to the nature of the groups API, individual members of a group are posted to system server
 * one at a time. This means that whenever a group member is posted, we don't know if there are any
 * more members soon to be posted.
 *
 * The Coalescer sits between the NotificationListenerService and the NotifCollection. It clusters
 * new notifications that are members of groups and delays their posting until any of the following
 * criteria are met:
 *
 * - A few milliseconds pass (see groupLingerDuration on the constructor)
 * - Any notification in the delayed group is updated
 * - Any notification in the delayed group is retracted
 *
 * Once we cross this threshold, all members of the group in question are posted atomically to the
 * NotifCollection. If this process was triggered by an update or removal, then that event is then
 * passed along to the NotifCollection.
 */
@MainThread
public class GroupCoalescer implements Dumpable {
    private final DelayableExecutor mMainExecutor;
    private final SystemClock mClock;
    private final GroupCoalescerLogger mLogger;
    private final long mMinGroupLingerDuration;
    private final long mMaxGroupLingerDuration;

    private BatchableNotificationHandler mHandler;

    private final Map<String, CoalescedEvent> mCoalescedEvents = new ArrayMap<>();
    private final Map<String, EventBatch> mBatches = new ArrayMap<>();

    @Inject
    public GroupCoalescer(
            @Main DelayableExecutor mainExecutor,
            SystemClock clock,
            GroupCoalescerLogger logger) {
        this(mainExecutor, clock, logger, MIN_GROUP_LINGER_DURATION, MAX_GROUP_LINGER_DURATION);
    }

    /**
     * @param minGroupLingerDuration How long, in ms, to wait for another notification from the same
     *                               group to arrive before emitting all pending events for that
     *                               group. Each subsequent arrival of a group member resets the
     *                               timer for that group.
     * @param maxGroupLingerDuration The maximum time, in ms, that a group can linger in the
     *                               coalescer before it's force-emitted.
     */
    GroupCoalescer(
            @Main DelayableExecutor mainExecutor,
            SystemClock clock,
            GroupCoalescerLogger logger,
            long minGroupLingerDuration,
            long maxGroupLingerDuration) {
        mMainExecutor = mainExecutor;
        mClock = clock;
        mLogger = logger;
        mMinGroupLingerDuration = minGroupLingerDuration;
        mMaxGroupLingerDuration = maxGroupLingerDuration;
    }

    /**
     * Attaches the coalescer to the pipeline, making it ready to receive events. Should only be
     * called once.
     */
    public void attach(NotificationListener listenerService) {
        listenerService.addNotificationHandler(mListener);
    }

    public void setNotificationHandler(BatchableNotificationHandler handler) {
        mHandler = handler;
    }

    private final NotificationHandler mListener = new NotificationHandler() {
        @Override
        public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
            maybeEmitBatch(sbn);
            applyRanking(rankingMap);

            final boolean shouldCoalesce = handleNotificationPosted(sbn, rankingMap);

            if (shouldCoalesce) {
                mLogger.logEventCoalesced(sbn.getKey());
                mHandler.onNotificationRankingUpdate(rankingMap);
            } else {
                mHandler.onNotificationPosted(sbn, rankingMap);
            }
        }

        @Override
        public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
            maybeEmitBatch(sbn);
            applyRanking(rankingMap);
            mHandler.onNotificationRemoved(sbn, rankingMap);
        }

        @Override
        public void onNotificationRemoved(
                StatusBarNotification sbn,
                RankingMap rankingMap,
                int reason) {
            maybeEmitBatch(sbn);
            applyRanking(rankingMap);
            mHandler.onNotificationRemoved(sbn, rankingMap, reason);
        }

        @Override
        public void onNotificationRankingUpdate(RankingMap rankingMap) {
            applyRanking(rankingMap);
            mHandler.onNotificationRankingUpdate(rankingMap);
        }

        @Override
        public void onNotificationsInitialized() {
            mHandler.onNotificationsInitialized();
        }
    };

    private void maybeEmitBatch(StatusBarNotification sbn) {
        final CoalescedEvent event = mCoalescedEvents.get(sbn.getKey());
        final EventBatch batch = mBatches.get(sbn.getGroupKey());
        if (event != null) {
            mLogger.logEarlyEmit(sbn.getKey(), requireNonNull(event.getBatch()).mGroupKey);
            emitBatch(requireNonNull(event.getBatch()));
        } else if (batch != null
                && mClock.uptimeMillis() - batch.mCreatedTimestamp >= mMaxGroupLingerDuration) {
            mLogger.logMaxBatchTimeout(sbn.getKey(), batch.mGroupKey);
            emitBatch(batch);
        }
    }

    /**
     * @return True if the notification was coalesced and false otherwise.
     */
    private boolean handleNotificationPosted(
            StatusBarNotification sbn,
            RankingMap rankingMap) {

        if (mCoalescedEvents.containsKey(sbn.getKey())) {
            throw new IllegalStateException(
                    "Notification has already been coalesced: " + sbn.getKey());
        }

        if (sbn.isGroup()) {
            final EventBatch batch = getOrBuildBatch(sbn.getGroupKey());

            CoalescedEvent event =
                    new CoalescedEvent(
                            sbn.getKey(),
                            batch.mMembers.size(),
                            sbn,
                            requireRanking(rankingMap, sbn.getKey()),
                            batch);
            mCoalescedEvents.put(event.getKey(), event);

            batch.mMembers.add(event);
            resetShortTimeout(batch);

            return true;
        } else {
            return false;
        }
    }

    private EventBatch getOrBuildBatch(final String groupKey) {
        EventBatch batch = mBatches.get(groupKey);
        if (batch == null) {
            batch = new EventBatch(mClock.uptimeMillis(), groupKey);
            mBatches.put(groupKey, batch);
        }
        return batch;
    }

    private void resetShortTimeout(EventBatch batch) {
        if (batch.mCancelShortTimeout != null) {
            batch.mCancelShortTimeout.run();
        }
        batch.mCancelShortTimeout =
                mMainExecutor.executeDelayed(
                        () -> {
                            batch.mCancelShortTimeout = null;
                            emitBatch(batch);
                        },
                        mMinGroupLingerDuration);
    }

    private void emitBatch(EventBatch batch) {
        if (batch != mBatches.get(batch.mGroupKey)) {
            throw new IllegalStateException("Cannot emit out-of-date batch " + batch.mGroupKey);
        }
        if (batch.mMembers.isEmpty()) {
            throw new IllegalStateException("Batch " + batch.mGroupKey + " cannot be empty");
        }
        if (batch.mCancelShortTimeout != null) {
            batch.mCancelShortTimeout.run();
            batch.mCancelShortTimeout = null;
        }

        mBatches.remove(batch.mGroupKey);

        final List<CoalescedEvent> events = new ArrayList<>(batch.mMembers);
        for (CoalescedEvent event : events) {
            mCoalescedEvents.remove(event.getKey());
            event.setBatch(null);
        }
        events.sort(mEventComparator);

        mLogger.logEmitBatch(batch.mGroupKey);

        mHandler.onNotificationBatchPosted(events);
    }

    private Ranking requireRanking(RankingMap rankingMap, String key) {
        Ranking ranking = new Ranking();
        if (!rankingMap.getRanking(key, ranking)) {
            throw new IllegalArgumentException("Ranking map does not contain key " + key);
        }
        return ranking;
    }

    private void applyRanking(RankingMap rankingMap) {
        for (CoalescedEvent event : mCoalescedEvents.values()) {
            Ranking ranking = new Ranking();
            if (rankingMap.getRanking(event.getKey(), ranking)) {
                event.setRanking(ranking);
            } else {
                // TODO: (b/148791039) We should crash if we are ever handed a ranking with
                //  incomplete entries. Right now, there's a race condition in NotificationListener
                //  that means this might occur when SystemUI is starting up.
                mLogger.logMissingRanking(event.getKey());
            }
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        long now = mClock.uptimeMillis();

        int eventCount = 0;

        pw.println();
        pw.println("Coalesced notifications:");
        for (EventBatch batch : mBatches.values()) {
            pw.println("   Batch " + batch.mGroupKey + ":");
            pw.println("       Created " + (now - batch.mCreatedTimestamp) + "ms ago");
            for (CoalescedEvent event : batch.mMembers) {
                pw.println("       " + event.getKey());
                eventCount++;
            }
        }

        if (eventCount != mCoalescedEvents.size()) {
            pw.println("    ERROR: batches contain " + mCoalescedEvents.size() + " events but"
                    + " am tracking " + mCoalescedEvents.size() + " total events");
            pw.println("    All tracked events:");
            for (CoalescedEvent event : mCoalescedEvents.values()) {
                pw.println("        " + event.getKey());
            }
        }
    }

    private final Comparator<CoalescedEvent> mEventComparator = (o1, o2) -> {
        int cmp = Boolean.compare(
                o2.getSbn().getNotification().isGroupSummary(),
                o1.getSbn().getNotification().isGroupSummary());
        if (cmp == 0) {
            cmp = o1.getPosition() - o2.getPosition();
        }
        return cmp;
    };

    /**
     * Extension of {@link NotificationListener.NotificationHandler} to include notification
     * groups.
     */
    public interface BatchableNotificationHandler extends NotificationHandler {
        /**
         * Fired whenever the coalescer needs to emit a batch of multiple post events. This is
         * usually the addition of a new group, but can contain just a single event, or just an
         * update to a subset of an existing group.
         */
        void onNotificationBatchPosted(List<CoalescedEvent> events);
    }

    private static final int MIN_GROUP_LINGER_DURATION = 50;
    private static final int MAX_GROUP_LINGER_DURATION = 500;
}
