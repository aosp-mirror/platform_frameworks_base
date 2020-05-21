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

package com.android.server.people.data;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.LocusId;
import android.text.format.DateUtils;
import android.util.ArrayMap;

import com.android.server.LocalServices;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** A helper class that queries {@link UsageStatsManagerInternal}. */
class UsageStatsQueryHelper {

    private final UsageStatsManagerInternal mUsageStatsManagerInternal;
    private final int mUserId;
    private final Function<String, PackageData> mPackageDataGetter;
    // Activity name -> Conversation start event (LOCUS_ID_SET)
    private final Map<ComponentName, UsageEvents.Event> mConvoStartEvents = new ArrayMap<>();
    private long mLastEventTimestamp;

    /**
     * @param userId The user whose events are to be queried.
     * @param packageDataGetter The function to get {@link PackageData} with a package name.
     */
    UsageStatsQueryHelper(@UserIdInt int userId,
            Function<String, PackageData> packageDataGetter) {
        mUsageStatsManagerInternal = getUsageStatsManagerInternal();
        mUserId = userId;
        mPackageDataGetter = packageDataGetter;
    }

    /**
     * Queries {@link UsageStatsManagerInternal} for the recent events occurred since {@code
     * sinceTime} and adds the derived {@link Event}s into the corresponding package's event store,
     *
     * @return true if the query runs successfully and at least one event is found.
     */
    boolean querySince(long sinceTime) {
        UsageEvents usageEvents = mUsageStatsManagerInternal.queryEventsForUser(
                mUserId, sinceTime, System.currentTimeMillis(), UsageEvents.SHOW_ALL_EVENT_DATA);
        if (usageEvents == null) {
            return false;
        }
        boolean hasEvents = false;
        while (usageEvents.hasNextEvent()) {
            UsageEvents.Event e = new UsageEvents.Event();
            usageEvents.getNextEvent(e);

            hasEvents = true;
            mLastEventTimestamp = Math.max(mLastEventTimestamp, e.getTimeStamp());
            String packageName = e.getPackageName();
            PackageData packageData = mPackageDataGetter.apply(packageName);
            if (packageData == null) {
                continue;
            }
            switch (e.getEventType()) {
                case UsageEvents.Event.SHORTCUT_INVOCATION:
                    addEventByShortcutId(packageData, e.getShortcutId(),
                            new Event(e.getTimeStamp(), Event.TYPE_SHORTCUT_INVOCATION));
                    break;
                case UsageEvents.Event.LOCUS_ID_SET:
                    onInAppConversationEnded(packageData, e);
                    LocusId locusId = e.getLocusId() != null ? new LocusId(e.getLocusId()) : null;
                    if (locusId != null) {
                        if (packageData.getConversationStore().getConversationByLocusId(locusId)
                                != null) {
                            ComponentName activityName =
                                    new ComponentName(packageName, e.getClassName());
                            mConvoStartEvents.put(activityName, e);
                        }
                    }
                    break;
                case UsageEvents.Event.ACTIVITY_PAUSED:
                case UsageEvents.Event.ACTIVITY_STOPPED:
                case UsageEvents.Event.ACTIVITY_DESTROYED:
                    onInAppConversationEnded(packageData, e);
                    break;
            }
        }
        return hasEvents;
    }

    long getLastEventTimestamp() {
        return mLastEventTimestamp;
    }

    /**
     * Queries {@link UsageStatsManagerInternal} events for moving app to foreground between
     * {@code startTime} and {@code endTime}.
     *
     * @return a list containing events moving app to foreground.
     */
    static List<UsageEvents.Event> queryAppMovingToForegroundEvents(@UserIdInt int userId,
            long startTime, long endTime) {
        List<UsageEvents.Event> res = new ArrayList<>();
        UsageEvents usageEvents = getUsageStatsManagerInternal().queryEventsForUser(userId,
                startTime, endTime,
                UsageEvents.HIDE_SHORTCUT_EVENTS | UsageEvents.HIDE_LOCUS_EVENTS);
        if (usageEvents == null) {
            return res;
        }
        while (usageEvents.hasNextEvent()) {
            UsageEvents.Event e = new UsageEvents.Event();
            usageEvents.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                res.add(e);
            }
        }
        return res;
    }

    /**
     * Queries {@link UsageStatsManagerInternal} for usage stats of apps within {@code
     * packageNameFilter} between {@code startTime} and {@code endTime}.
     *
     * @return a map which keys are package names and values are {@link AppUsageStatsData}.
     */
    static Map<String, AppUsageStatsData> queryAppUsageStats(@UserIdInt int userId, long startTime,
            long endTime, Set<String> packageNameFilter) {
        List<UsageStats> stats = getUsageStatsManagerInternal().queryUsageStatsForUser(userId,
                UsageStatsManager.INTERVAL_BEST, startTime, endTime,
                /* obfuscateInstantApps= */ false);
        Map<String, AppUsageStatsData> aggregatedStats = new ArrayMap<>();
        for (UsageStats stat : stats) {
            String packageName = stat.getPackageName();
            if (packageNameFilter.contains(packageName)) {
                AppUsageStatsData packageStats = aggregatedStats.computeIfAbsent(packageName,
                        (key) -> new AppUsageStatsData());
                packageStats.incrementChosenCountBy(sumChooserCounts(stat.mChooserCounts));
                packageStats.incrementLaunchCountBy(stat.getAppLaunchCount());
            }
        }
        return aggregatedStats;
    }

    private static int sumChooserCounts(ArrayMap<String, ArrayMap<String, Integer>> chooserCounts) {
        int sum = 0;
        if (chooserCounts == null) {
            return sum;
        }
        int chooserCountsSize = chooserCounts.size();
        for (int i = 0; i < chooserCountsSize; i++) {
            ArrayMap<String, Integer> counts = chooserCounts.valueAt(i);
            if (counts == null) {
                continue;
            }
            final int annotationSize = counts.size();
            for (int j = 0; j < annotationSize; j++) {
                sum += counts.valueAt(j);
            }
        }
        return sum;
    }

    private void onInAppConversationEnded(@NonNull PackageData packageData,
            @NonNull UsageEvents.Event endEvent) {
        ComponentName activityName =
                new ComponentName(endEvent.getPackageName(), endEvent.getClassName());
        UsageEvents.Event startEvent = mConvoStartEvents.remove(activityName);
        if (startEvent == null || startEvent.getTimeStamp() >= endEvent.getTimeStamp()) {
            return;
        }
        long durationMillis = endEvent.getTimeStamp() - startEvent.getTimeStamp();
        Event event = new Event.Builder(startEvent.getTimeStamp(), Event.TYPE_IN_APP_CONVERSATION)
                .setDurationSeconds((int) (durationMillis / DateUtils.SECOND_IN_MILLIS))
                .build();
        addEventByLocusId(packageData, new LocusId(startEvent.getLocusId()), event);
    }

    private void addEventByShortcutId(PackageData packageData, String shortcutId, Event event) {
        if (packageData.getConversationStore().getConversation(shortcutId) == null) {
            return;
        }
        EventHistoryImpl eventHistory = packageData.getEventStore().getOrCreateEventHistory(
                EventStore.CATEGORY_SHORTCUT_BASED, shortcutId);
        eventHistory.addEvent(event);
    }

    private void addEventByLocusId(PackageData packageData, LocusId locusId, Event event) {
        if (packageData.getConversationStore().getConversationByLocusId(locusId) == null) {
            return;
        }
        EventHistoryImpl eventHistory = packageData.getEventStore().getOrCreateEventHistory(
                EventStore.CATEGORY_LOCUS_ID_BASED, locusId.getId());
        eventHistory.addEvent(event);
    }

    private static UsageStatsManagerInternal getUsageStatsManagerInternal() {
        return LocalServices.getService(UsageStatsManagerInternal.class);
    }
}
