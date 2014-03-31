/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.notification;

import com.android.server.notification.NotificationManagerService.NotificationRecord;

import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps track of notification activity, display, and user interaction.
 *
 * <p>This class receives signals from NoMan and keeps running stats of
 * notification usage. Some metrics are updated as events occur. Others, namely
 * those involving durations, are updated as the notification is canceled.</p>
 *
 * <p>This class is thread-safe.</p>
 *
 * {@hide}
 */
public class NotificationUsageStats {

    // Guarded by synchronized(this).
    private final Map<String, AggregatedStats> mStats = new HashMap<String, AggregatedStats>();

    /**
     * Called when a notification has been posted.
     */
    public synchronized void registerPostedByApp(NotificationRecord notification) {
        notification.stats.posttimeElapsedMs = SystemClock.elapsedRealtime();
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.numPostedByApp++;
        }
    }

    /**
     * Called when a notification has been updated.
     */
    public void registerUpdatedByApp(NotificationRecord notification) {
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.numUpdatedByApp++;
        }
    }

    /**
     * Called when the originating app removed the notification programmatically.
     */
    public synchronized void registerRemovedByApp(NotificationRecord notification) {
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.numRemovedByApp++;
            stats.collect(notification.stats);
        }
    }

    /**
     * Called when the user dismissed the notification via the UI.
     */
    public synchronized void registerDismissedByUser(NotificationRecord notification) {
        notification.stats.onDismiss();
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.numDismissedByUser++;
            stats.collect(notification.stats);
        }
    }

    /**
     * Called when the user clicked the notification in the UI.
     */
    public synchronized void registerClickedByUser(NotificationRecord notification) {
        notification.stats.onClick();
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.numClickedByUser++;
        }
    }

    /**
     * Called when the notification is canceled because the user clicked it.
     *
     * <p>Called after {@link #registerClickedByUser(NotificationRecord)}.</p>
     */
    public synchronized void registerCancelDueToClick(NotificationRecord notification) {
        // No explicit stats for this (the click has already been registered in
        // registerClickedByUser), just make sure the single notification stats
        // are folded up into aggregated stats.
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.collect(notification.stats);
        }
    }

    /**
     * Called when the notification is canceled due to unknown reasons.
     *
     * <p>Called for notifications of apps being uninstalled, for example.</p>
     */
    public synchronized void registerCancelUnknown(NotificationRecord notification) {
        // Fold up individual stats.
        for (AggregatedStats stats : getAggregatedStatsLocked(notification)) {
            stats.collect(notification.stats);
        }
    }

    // Locked by this.
    private AggregatedStats[] getAggregatedStatsLocked(NotificationRecord record) {
        StatusBarNotification n = record.sbn;

        String user = String.valueOf(n.getUserId());
        String userPackage = user + ":" + n.getPackageName();

        // TODO: Use pool of arrays.
        return new AggregatedStats[] {
                getOrCreateAggregatedStatsLocked(user),
                getOrCreateAggregatedStatsLocked(userPackage),
                getOrCreateAggregatedStatsLocked(n.getKey()),
        };
    }

    // Locked by this.
    private AggregatedStats getOrCreateAggregatedStatsLocked(String key) {
        AggregatedStats result = mStats.get(key);
        if (result == null) {
            result = new AggregatedStats(key);
            mStats.put(key, result);
        }
        return result;
    }

    public synchronized void dump(PrintWriter pw, String indent) {
        for (AggregatedStats as : mStats.values()) {
            as.dump(pw, indent);
        }
    }

    /**
     * Aggregated notification stats.
     */
    private static class AggregatedStats {
        public final String key;

        // ---- Updated as the respective events occur.
        public int numPostedByApp;
        public int numUpdatedByApp;
        public int numRemovedByApp;
        public int numClickedByUser;
        public int numDismissedByUser;

        // ----  Updated when a notification is canceled.
        public final Aggregate posttimeMs = new Aggregate();
        public final Aggregate posttimeToDismissMs = new Aggregate();
        public final Aggregate posttimeToFirstClickMs = new Aggregate();

        public AggregatedStats(String key) {
            this.key = key;
        }

        public void collect(SingleNotificationStats singleNotificationStats) {
            posttimeMs.addSample(
	            SystemClock.elapsedRealtime() - singleNotificationStats.posttimeElapsedMs);
            if (singleNotificationStats.posttimeToDismissMs >= 0) {
                posttimeToDismissMs.addSample(singleNotificationStats.posttimeToDismissMs);
            }
            if (singleNotificationStats.posttimeToFirstClickMs >= 0) {
                posttimeToFirstClickMs.addSample(singleNotificationStats.posttimeToFirstClickMs);
            }
        }

        public void dump(PrintWriter pw, String indent) {
            pw.println(toStringWithIndent(indent));
        }

        @Override
        public String toString() {
            return toStringWithIndent("");
        }

        private String toStringWithIndent(String indent) {
            return indent + "AggregatedStats{\n" +
                    indent + "  key='" + key + "',\n" +
                    indent + "  numPostedByApp=" + numPostedByApp + ",\n" +
                    indent + "  numUpdatedByApp=" + numUpdatedByApp + ",\n" +
                    indent + "  numRemovedByApp=" + numRemovedByApp + ",\n" +
                    indent + "  numClickedByUser=" + numClickedByUser + ",\n" +
                    indent + "  numDismissedByUser=" + numDismissedByUser + ",\n" +
                    indent + "  posttimeMs=" + posttimeMs + ",\n" +
                    indent + "  posttimeToDismissMs=" + posttimeToDismissMs + ",\n" +
                    indent + "  posttimeToFirstClickMs=" + posttimeToFirstClickMs + ",\n" +
                    indent + "}";
        }
    }

    /**
     * Tracks usage of an individual notification that is currently active.
     */
    public static class SingleNotificationStats {
        /** SystemClock.elapsedRealtime() when the notification was posted. */
        public long posttimeElapsedMs = -1;
        /** Elapsed time since the notification was posted until it was first clicked, or -1. */
        public long posttimeToFirstClickMs = -1;
        /** Elpased time since the notification was posted until it was dismissed by the user. */
        public long posttimeToDismissMs = -1;

        /**
         * Called when the user clicked the notification.
         */
        public void onClick() {
            if (posttimeToFirstClickMs < 0) {
                posttimeToFirstClickMs = SystemClock.elapsedRealtime() - posttimeElapsedMs;
            }
        }

        /**
         * Called when the user removed the notification.
         */
        public void onDismiss() {
            if (posttimeToDismissMs < 0) {
                posttimeToDismissMs = SystemClock.elapsedRealtime() - posttimeElapsedMs;
            }
        }

        @Override
        public String toString() {
            return "SingleNotificationStats{" +
                    "posttimeElapsedMs=" + posttimeElapsedMs +
                    ", posttimeToFirstClickMs=" + posttimeToFirstClickMs +
                    ", posttimeToDismissMs=" + posttimeToDismissMs +
                    '}';
        }
    }

    /**
     * Aggregates long samples to sum and averages.
     */
    public static class Aggregate {
        long numSamples;
        long sum;
        long avg;

        public void addSample(long sample) {
            numSamples++;
            sum += sample;
            avg = sum / numSamples;
        }

        @Override
        public String toString() {
            return "Aggregate{" +
                    "numSamples=" + numSamples +
                    ", sum=" + sum +
                    ", avg=" + avg +
                    '}';
        }
    }
}
