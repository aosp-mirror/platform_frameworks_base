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

import android.app.Notification;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.logging.MetricsLogger;
import com.android.server.notification.NotificationManagerService.DumpFilter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.GregorianCalendar;
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
    private static final String TAG = "NotificationUsageStats";

    private static final boolean ENABLE_AGGREGATED_IN_MEMORY_STATS = true;
    private static final boolean ENABLE_SQLITE_LOG = true;
    private static final AggregatedStats[] EMPTY_AGGREGATED_STATS = new AggregatedStats[0];
    private static final String DEVICE_GLOBAL_STATS = "__global"; // packages start with letters
    private static final int MSG_EMIT = 1;

    private static final boolean DEBUG = false;
    public static final int TEN_SECONDS = 1000 * 10;
    public static final int FOUR_HOURS = 1000 * 60 * 60 * 4;
    private static final long EMIT_PERIOD = DEBUG ? TEN_SECONDS : FOUR_HOURS;

    // Guarded by synchronized(this).
    private final Map<String, AggregatedStats> mStats = new HashMap<>();
    private final ArrayDeque<AggregatedStats[]> mStatsArrays = new ArrayDeque<>();
    private final SQLiteLog mSQLiteLog;
    private final Context mContext;
    private final Handler mHandler;
    private long mLastEmitTime;

    public NotificationUsageStats(Context context) {
        mContext = context;
        mLastEmitTime = SystemClock.elapsedRealtime();
        mSQLiteLog = ENABLE_SQLITE_LOG ? new SQLiteLog(context) : null;
        mHandler = new Handler(mContext.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_EMIT:
                        emit();
                        break;
                    default:
                        Log.wtf(TAG, "Unknown message type: " + msg.what);
                        break;
                }
            }
        };
        mHandler.sendEmptyMessageDelayed(MSG_EMIT, EMIT_PERIOD);
    }

    /**
     * Called when a notification has been posted.
     */
    public synchronized void registerPostedByApp(NotificationRecord notification) {
        notification.stats = new SingleNotificationStats();
        notification.stats.posttimeElapsedMs = SystemClock.elapsedRealtime();

        AggregatedStats[] aggregatedStatsArray = getAggregatedStatsLocked(notification);
        for (AggregatedStats stats : aggregatedStatsArray) {
            stats.numPostedByApp++;
            stats.countApiUse(notification);
        }
        releaseAggregatedStatsLocked(aggregatedStatsArray);
        if (ENABLE_SQLITE_LOG) {
            mSQLiteLog.logPosted(notification);
        }
    }

    /**
     * Called when a notification has been updated.
     */
    public void registerUpdatedByApp(NotificationRecord notification, NotificationRecord old) {
        notification.stats = old.stats;
        AggregatedStats[] aggregatedStatsArray = getAggregatedStatsLocked(notification);
        for (AggregatedStats stats : aggregatedStatsArray) {
            stats.numUpdatedByApp++;
            stats.countApiUse(notification);
        }
        releaseAggregatedStatsLocked(aggregatedStatsArray);
    }

    /**
     * Called when the originating app removed the notification programmatically.
     */
    public synchronized void registerRemovedByApp(NotificationRecord notification) {
        notification.stats.onRemoved();
        AggregatedStats[] aggregatedStatsArray = getAggregatedStatsLocked(notification);
        for (AggregatedStats stats : aggregatedStatsArray) {
            stats.numRemovedByApp++;
        }
        releaseAggregatedStatsLocked(aggregatedStatsArray);
        if (ENABLE_SQLITE_LOG) {
            mSQLiteLog.logRemoved(notification);
        }
    }

    /**
     * Called when the user dismissed the notification via the UI.
     */
    public synchronized void registerDismissedByUser(NotificationRecord notification) {
        MetricsLogger.histogram(mContext, "note_dismiss_longevity",
                (int) (System.currentTimeMillis() - notification.getRankingTimeMs()) / (60 * 1000));
        notification.stats.onDismiss();
        if (ENABLE_SQLITE_LOG) {
            mSQLiteLog.logDismissed(notification);
        }
    }

    /**
     * Called when the user clicked the notification in the UI.
     */
    public synchronized void registerClickedByUser(NotificationRecord notification) {
        MetricsLogger.histogram(mContext, "note_click_longevity",
                (int) (System.currentTimeMillis() - notification.getRankingTimeMs()) / (60 * 1000));
        notification.stats.onClick();
        if (ENABLE_SQLITE_LOG) {
            mSQLiteLog.logClicked(notification);
        }
    }

    public synchronized void registerPeopleAffinity(NotificationRecord notification, boolean valid,
            boolean starred, boolean cached) {
        AggregatedStats[] aggregatedStatsArray = getAggregatedStatsLocked(notification);
        for (AggregatedStats stats : aggregatedStatsArray) {
            if (valid) {
                stats.numWithValidPeople++;
            }
            if (starred) {
                stats.numWithStaredPeople++;
            }
            if (cached) {
                stats.numPeopleCacheHit++;
            } else {
                stats.numPeopleCacheMiss++;
            }
        }
        releaseAggregatedStatsLocked(aggregatedStatsArray);
    }

    public synchronized void registerBlocked(NotificationRecord notification) {
        AggregatedStats[] aggregatedStatsArray = getAggregatedStatsLocked(notification);
        for (AggregatedStats stats : aggregatedStatsArray) {
            stats.numBlocked++;
        }
        releaseAggregatedStatsLocked(aggregatedStatsArray);
    }

    // Locked by this.
    private AggregatedStats[] getAggregatedStatsLocked(NotificationRecord record) {
        if (!ENABLE_AGGREGATED_IN_MEMORY_STATS) {
            return EMPTY_AGGREGATED_STATS;
        }

        // TODO: expand to package-level counts in the future.
        AggregatedStats[] array = mStatsArrays.poll();
        if (array == null) {
            array = new AggregatedStats[1];
        }
        array[0] = getOrCreateAggregatedStatsLocked(DEVICE_GLOBAL_STATS);
        return array;
    }

    // Locked by this.
    private void releaseAggregatedStatsLocked(AggregatedStats[] array) {
        for(int i = 0; i < array.length; i++) {
            array[i] = null;
        }
        mStatsArrays.offer(array);
    }

    // Locked by this.
    private AggregatedStats getOrCreateAggregatedStatsLocked(String key) {
        AggregatedStats result = mStats.get(key);
        if (result == null) {
            result = new AggregatedStats(mContext, key);
            mStats.put(key, result);
        }
        return result;
    }

    public synchronized JSONObject dumpJson(DumpFilter filter) {
        JSONObject dump = new JSONObject();
        if (ENABLE_AGGREGATED_IN_MEMORY_STATS) {
            try {
                JSONArray aggregatedStats = new JSONArray();
                for (AggregatedStats as : mStats.values()) {
                    if (filter != null && !filter.matches(as.key))
                        continue;
                    aggregatedStats.put(as.dumpJson());
                }
                dump.put("current", aggregatedStats);
            } catch (JSONException e) {
                // pass
            }
        }
        if (ENABLE_SQLITE_LOG) {
            try {
                dump.put("historical", mSQLiteLog.dumpJson(filter));
            } catch (JSONException e) {
                // pass
            }
        }
        return dump;
    }

    public synchronized void dump(PrintWriter pw, String indent, DumpFilter filter) {
        if (ENABLE_AGGREGATED_IN_MEMORY_STATS) {
            for (AggregatedStats as : mStats.values()) {
                if (filter != null && !filter.matches(as.key))
                    continue;
                as.dump(pw, indent);
            }
            pw.println(indent + "mStatsArrays.size(): " + mStatsArrays.size());
        }
        if (ENABLE_SQLITE_LOG) {
            mSQLiteLog.dump(pw, indent, filter);
        }
    }

    public synchronized void emit() {
        // TODO: expand to package-level counts in the future.
        AggregatedStats stats = getOrCreateAggregatedStatsLocked(DEVICE_GLOBAL_STATS);
        stats.emit();
        mLastEmitTime = SystemClock.elapsedRealtime();
        mHandler.removeMessages(MSG_EMIT);
        mHandler.sendEmptyMessageDelayed(MSG_EMIT, EMIT_PERIOD);
    }

    /**
     * Aggregated notification stats.
     */
    private static class AggregatedStats {

        private final Context mContext;
        public final String key;
        private final long mCreated;
        private AggregatedStats mPrevious;

        // ---- Updated as the respective events occur.
        public int numPostedByApp;
        public int numUpdatedByApp;
        public int numRemovedByApp;
        public int numPeopleCacheHit;
        public int numPeopleCacheMiss;;
        public int numWithStaredPeople;
        public int numWithValidPeople;
        public int numBlocked;
        public int numWithActions;
        public int numPrivate;
        public int numSecret;
        public int numPriorityMax;
        public int numPriorityHigh;
        public int numPriorityLow;
        public int numPriorityMin;
        public int numWithBigText;
        public int numWithBigPicture;
        public int numForegroundService;
        public int numOngoing;
        public int numAutoCancel;
        public int numWithLargeIcon;
        public int numWithInbox;
        public int numWithMediaSession;
        public int numWithTitle;
        public int numWithText;
        public int numWithSubText;
        public int numWithInfoText;
        public int numInterrupt;

        public AggregatedStats(Context context, String key) {
            this.key = key;
            mContext = context;
            mCreated = SystemClock.elapsedRealtime();
        }

        public void countApiUse(NotificationRecord record) {
            final Notification n = record.getNotification();
            if (n.actions != null) {
                numWithActions++;
            }

            if ((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                numForegroundService++;
            }

            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                numOngoing++;
            }

            if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0) {
                numAutoCancel++;
            }

            if ((n.defaults & Notification.DEFAULT_SOUND) != 0 ||
                    (n.defaults & Notification.DEFAULT_VIBRATE) != 0 ||
                    n.sound != null || n.vibrate != null) {
                numInterrupt++;
            }

            switch (n.visibility) {
                case Notification.VISIBILITY_PRIVATE:
                    numPrivate++;
                    break;
                case Notification.VISIBILITY_SECRET:
                    numSecret++;
                    break;
            }

            switch (n.priority) {
                case Notification.PRIORITY_MAX:
                    numPriorityMax++;
                    break;
                case Notification.PRIORITY_HIGH:
                    numPriorityHigh++;
                    break;
                case Notification.PRIORITY_LOW:
                    numPriorityLow++;
                    break;
                case Notification.PRIORITY_MIN:
                    numPriorityMin++;
                    break;
            }

            for (String Key : n.extras.keySet()) {
                if (Notification.EXTRA_BIG_TEXT.equals(key)) {
                    numWithBigText++;
                } else if (Notification.EXTRA_PICTURE.equals(key)) {
                    numWithBigPicture++;
                } else if (Notification.EXTRA_LARGE_ICON.equals(key)) {
                    numWithLargeIcon++;
                } else if (Notification.EXTRA_TEXT_LINES.equals(key)) {
                    numWithInbox++;
                } else if (Notification.EXTRA_MEDIA_SESSION.equals(key)) {
                    numWithMediaSession++;
                } else if (Notification.EXTRA_TITLE.equals(key)) {
                    numWithTitle++;
                } else if (Notification.EXTRA_TEXT.equals(key)) {
                    numWithText++;
                } else if (Notification.EXTRA_SUB_TEXT.equals(key)) {
                    numWithSubText++;
                } else if (Notification.EXTRA_INFO_TEXT.equals(key)) {
                    numWithInfoText++;
                }
            }
        }

        public void emit() {
            if (mPrevious == null) {
                mPrevious = new AggregatedStats(null, key);
            }

            maybeCount("note_post", (numPostedByApp - mPrevious.numPostedByApp));
            maybeCount("note_update", (numUpdatedByApp - mPrevious.numUpdatedByApp));
            maybeCount("note_remove", (numRemovedByApp - mPrevious.numRemovedByApp));
            maybeCount("note_with_people", (numWithValidPeople - mPrevious.numWithValidPeople));
            maybeCount("note_with_stars", (numWithStaredPeople - mPrevious.numWithStaredPeople));
            maybeCount("people_cache_hit", (numPeopleCacheHit - mPrevious.numPeopleCacheHit));
            maybeCount("people_cache_miss", (numPeopleCacheMiss - mPrevious.numPeopleCacheMiss));
            maybeCount("note_blocked", (numBlocked - mPrevious.numBlocked));
            maybeCount("note_with_actions", (numWithActions - mPrevious.numWithActions));
            maybeCount("note_private", (numPrivate - mPrevious.numPrivate));
            maybeCount("note_secret", (numSecret - mPrevious.numSecret));
            maybeCount("note_prio_max", (numPriorityMax - mPrevious.numPriorityMax));
            maybeCount("note_prio_high", (numPriorityHigh - mPrevious.numPriorityHigh));
            maybeCount("note_prio_low", (numPriorityLow - mPrevious.numPriorityLow));
            maybeCount("note_prio_min", (numPriorityMin - mPrevious.numPriorityMin));
            maybeCount("note_interupt", (numInterrupt - mPrevious.numInterrupt));
            maybeCount("note_big_text", (numWithBigText - mPrevious.numWithBigText));
            maybeCount("note_big_pic", (numWithBigPicture - mPrevious.numWithBigPicture));
            maybeCount("note_fg", (numForegroundService - mPrevious.numForegroundService));
            maybeCount("note_ongoing", (numOngoing - mPrevious.numOngoing));
            maybeCount("note_auto", (numAutoCancel - mPrevious.numAutoCancel));
            maybeCount("note_large_icon", (numWithLargeIcon - mPrevious.numWithLargeIcon));
            maybeCount("note_inbox", (numWithInbox - mPrevious.numWithInbox));
            maybeCount("note_media", (numWithMediaSession - mPrevious.numWithMediaSession));
            maybeCount("note_title", (numWithTitle - mPrevious.numWithTitle));
            maybeCount("note_text", (numWithText - mPrevious.numWithText));
            maybeCount("note_sub_text", (numWithSubText - mPrevious.numWithSubText));
            maybeCount("note_info_text", (numWithInfoText - mPrevious.numWithInfoText));

            mPrevious.numPostedByApp = numPostedByApp;
            mPrevious.numUpdatedByApp = numUpdatedByApp;
            mPrevious.numRemovedByApp = numRemovedByApp;
            mPrevious.numPeopleCacheHit = numPeopleCacheHit;
            mPrevious.numPeopleCacheMiss = numPeopleCacheMiss;
            mPrevious.numWithStaredPeople = numWithStaredPeople;
            mPrevious.numWithValidPeople = numWithValidPeople;
            mPrevious.numBlocked = numBlocked;
            mPrevious.numWithActions = numWithActions;
            mPrevious.numPrivate = numPrivate;
            mPrevious.numSecret = numSecret;
            mPrevious.numPriorityMax = numPriorityMax;
            mPrevious.numPriorityHigh = numPriorityHigh;
            mPrevious.numPriorityLow = numPriorityLow;
            mPrevious.numPriorityMin = numPriorityMin;
            mPrevious.numInterrupt = numInterrupt;
            mPrevious.numWithBigText = numWithBigText;
            mPrevious.numWithBigPicture = numWithBigPicture;
            mPrevious.numForegroundService = numForegroundService;
            mPrevious.numOngoing = numOngoing;
            mPrevious.numAutoCancel = numAutoCancel;
            mPrevious.numWithLargeIcon = numWithLargeIcon;
            mPrevious.numWithInbox = numWithInbox;
            mPrevious.numWithMediaSession = numWithMediaSession;
            mPrevious.numWithTitle = numWithTitle;
            mPrevious.numWithText = numWithText;
            mPrevious.numWithSubText = numWithSubText;
            mPrevious.numWithInfoText = numWithInfoText;
        }

        void maybeCount(String name, int value) {
            if (value > 0) {
                MetricsLogger.count(mContext, name, value);
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
                    indent + "  numPeopleCacheHit=" + numPeopleCacheHit + ",\n" +
                    indent + "  numWithStaredPeople=" + numWithStaredPeople + ",\n" +
                    indent + "  numWithValidPeople=" + numWithValidPeople + ",\n" +
                    indent + "  numPeopleCacheMiss=" + numPeopleCacheMiss + ",\n" +
                    indent + "  numBlocked=" + numBlocked + ",\n" +
                    indent + "}";
        }

        public JSONObject dumpJson() throws JSONException {
            JSONObject dump = new JSONObject();
            dump.put("key", key);
            dump.put("duration", SystemClock.elapsedRealtime() - mCreated);
            maybePut(dump, "numPostedByApp", numPostedByApp);
            maybePut(dump, "numUpdatedByApp", numUpdatedByApp);
            maybePut(dump, "numRemovedByApp", numRemovedByApp);
            maybePut(dump, "numPeopleCacheHit", numPeopleCacheHit);
            maybePut(dump, "numPeopleCacheMiss", numPeopleCacheMiss);
            maybePut(dump, "numWithStaredPeople", numWithStaredPeople);
            maybePut(dump, "numWithValidPeople", numWithValidPeople);
            maybePut(dump, "numBlocked", numBlocked);
            maybePut(dump, "numWithActions", numWithActions);
            maybePut(dump, "numPrivate", numPrivate);
            maybePut(dump, "numSecret", numSecret);
            maybePut(dump, "numPriorityMax", numPriorityMax);
            maybePut(dump, "numPriorityHigh", numPriorityHigh);
            maybePut(dump, "numPriorityLow", numPriorityLow);
            maybePut(dump, "numPriorityMin", numPriorityMin);
            maybePut(dump, "numInterrupt", numInterrupt);
            maybePut(dump, "numWithBigText", numWithBigText);
            maybePut(dump, "numWithBigPicture", numWithBigPicture);
            maybePut(dump, "numForegroundService", numForegroundService);
            maybePut(dump, "numOngoing", numOngoing);
            maybePut(dump, "numAutoCancel", numAutoCancel);
            maybePut(dump, "numWithLargeIcon", numWithLargeIcon);
            maybePut(dump, "numWithInbox", numWithInbox);
            maybePut(dump, "numWithMediaSession", numWithMediaSession);
            maybePut(dump, "numWithTitle", numWithTitle);
            maybePut(dump, "numWithText", numWithText);
            maybePut(dump, "numWithSubText", numWithSubText);
            maybePut(dump, "numWithInfoText", numWithInfoText);
            return dump;
        }

        private void maybePut(JSONObject dump, String name, int value) throws JSONException {
            if (value > 0) {
                dump.put(name, value);
            }
        }
    }

    /**
     * Tracks usage of an individual notification that is currently active.
     */
    public static class SingleNotificationStats {
        private boolean isVisible = false;
        private boolean isExpanded = false;
        /** SystemClock.elapsedRealtime() when the notification was posted. */
        public long posttimeElapsedMs = -1;
        /** Elapsed time since the notification was posted until it was first clicked, or -1. */
        public long posttimeToFirstClickMs = -1;
        /** Elpased time since the notification was posted until it was dismissed by the user. */
        public long posttimeToDismissMs = -1;
        /** Number of times the notification has been made visible. */
        public long airtimeCount = 0;
        /** Time in ms between the notification was posted and first shown; -1 if never shown. */
        public long posttimeToFirstAirtimeMs = -1;
        /**
         * If currently visible, SystemClock.elapsedRealtime() when the notification was made
         * visible; -1 otherwise.
         */
        public long currentAirtimeStartElapsedMs = -1;
        /** Accumulated visible time. */
        public long airtimeMs = 0;
        /**
         * Time in ms between the notification being posted and when it first
         * became visible and expanded; -1 if it was never visibly expanded.
         */
        public long posttimeToFirstVisibleExpansionMs = -1;
        /**
         * If currently visible, SystemClock.elapsedRealtime() when the notification was made
         * visible; -1 otherwise.
         */
        public long currentAirtimeExpandedStartElapsedMs = -1;
        /** Accumulated visible expanded time. */
        public long airtimeExpandedMs = 0;
        /** Number of times the notification has been expanded by the user. */
        public long userExpansionCount = 0;

        public long getCurrentPosttimeMs() {
            if (posttimeElapsedMs < 0) {
                return 0;
            }
            return SystemClock.elapsedRealtime() - posttimeElapsedMs;
        }

        public long getCurrentAirtimeMs() {
            long result = airtimeMs;
            // Add incomplete airtime if currently shown.
            if (currentAirtimeStartElapsedMs >= 0) {
                result += (SystemClock.elapsedRealtime() - currentAirtimeStartElapsedMs);
            }
            return result;
        }

        public long getCurrentAirtimeExpandedMs() {
            long result = airtimeExpandedMs;
            // Add incomplete expanded airtime if currently shown.
            if (currentAirtimeExpandedStartElapsedMs >= 0) {
                result += (SystemClock.elapsedRealtime() - currentAirtimeExpandedStartElapsedMs);
            }
            return result;
        }

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
            finish();
        }

        public void onCancel() {
            finish();
        }

        public void onRemoved() {
            finish();
        }

        public void onVisibilityChanged(boolean visible) {
            long elapsedNowMs = SystemClock.elapsedRealtime();
            final boolean wasVisible = isVisible;
            isVisible = visible;
            if (visible) {
                if (currentAirtimeStartElapsedMs < 0) {
                    airtimeCount++;
                    currentAirtimeStartElapsedMs = elapsedNowMs;
                }
                if (posttimeToFirstAirtimeMs < 0) {
                    posttimeToFirstAirtimeMs = elapsedNowMs - posttimeElapsedMs;
                }
            } else {
                if (currentAirtimeStartElapsedMs >= 0) {
                    airtimeMs += (elapsedNowMs - currentAirtimeStartElapsedMs);
                    currentAirtimeStartElapsedMs = -1;
                }
            }

            if (wasVisible != isVisible) {
                updateVisiblyExpandedStats();
            }
        }

        public void onExpansionChanged(boolean userAction, boolean expanded) {
            isExpanded = expanded;
            if (isExpanded && userAction) {
                userExpansionCount++;
            }
            updateVisiblyExpandedStats();
        }

        private void updateVisiblyExpandedStats() {
            long elapsedNowMs = SystemClock.elapsedRealtime();
            if (isExpanded && isVisible) {
                // expanded and visible
                if (currentAirtimeExpandedStartElapsedMs < 0) {
                    currentAirtimeExpandedStartElapsedMs = elapsedNowMs;
                }
                if (posttimeToFirstVisibleExpansionMs < 0) {
                    posttimeToFirstVisibleExpansionMs = elapsedNowMs - posttimeElapsedMs;
                }
            } else {
                // not-expanded or not-visible
                if (currentAirtimeExpandedStartElapsedMs >= 0) {
                    airtimeExpandedMs += (elapsedNowMs - currentAirtimeExpandedStartElapsedMs);
                    currentAirtimeExpandedStartElapsedMs = -1;
                }
            }
        }

        /** The notification is leaving the system. Finalize. */
        public void finish() {
            onVisibilityChanged(false);
        }

        @Override
        public String toString() {
            return "SingleNotificationStats{" +
                    "posttimeElapsedMs=" + posttimeElapsedMs +
                    ", posttimeToFirstClickMs=" + posttimeToFirstClickMs +
                    ", posttimeToDismissMs=" + posttimeToDismissMs +
                    ", airtimeCount=" + airtimeCount +
                    ", airtimeMs=" + airtimeMs +
                    ", currentAirtimeStartElapsedMs=" + currentAirtimeStartElapsedMs +
                    ", airtimeExpandedMs=" + airtimeExpandedMs +
                    ", posttimeToFirstVisibleExpansionMs=" + posttimeToFirstVisibleExpansionMs +
                    ", currentAirtimeExpandedSEMs=" + currentAirtimeExpandedStartElapsedMs +
                    '}';
        }
    }

    /**
     * Aggregates long samples to sum and averages.
     */
    public static class Aggregate {
        long numSamples;
        double avg;
        double sum2;
        double var;

        public void addSample(long sample) {
            // Welford's "Method for Calculating Corrected Sums of Squares"
            // http://www.jstor.org/stable/1266577?seq=2
            numSamples++;
            final double n = numSamples;
            final double delta = sample - avg;
            avg += (1.0 / n) * delta;
            sum2 += ((n - 1) / n) * delta * delta;
            final double divisor = numSamples == 1 ? 1.0 : n - 1.0;
            var = sum2 / divisor;
        }

        @Override
        public String toString() {
            return "Aggregate{" +
                    "numSamples=" + numSamples +
                    ", avg=" + avg +
                    ", var=" + var +
                    '}';
        }
    }

    private static class SQLiteLog {
        private static final String TAG = "NotificationSQLiteLog";

        // Message types passed to the background handler.
        private static final int MSG_POST = 1;
        private static final int MSG_CLICK = 2;
        private static final int MSG_REMOVE = 3;
        private static final int MSG_DISMISS = 4;

        private static final String DB_NAME = "notification_log.db";
        private static final int DB_VERSION = 4;

        /** Age in ms after which events are pruned from the DB. */
        private static final long HORIZON_MS = 7 * 24 * 60 * 60 * 1000L;  // 1 week
        /** Delay between pruning the DB. Used to throttle pruning. */
        private static final long PRUNE_MIN_DELAY_MS = 6 * 60 * 60 * 1000L;  // 6 hours
        /** Mininum number of writes between pruning the DB. Used to throttle pruning. */
        private static final long PRUNE_MIN_WRITES = 1024;

        // Table 'log'
        private static final String TAB_LOG = "log";
        private static final String COL_EVENT_USER_ID = "event_user_id";
        private static final String COL_EVENT_TYPE = "event_type";
        private static final String COL_EVENT_TIME = "event_time_ms";
        private static final String COL_KEY = "key";
        private static final String COL_PKG = "pkg";
        private static final String COL_NOTIFICATION_ID = "nid";
        private static final String COL_TAG = "tag";
        private static final String COL_WHEN_MS = "when_ms";
        private static final String COL_DEFAULTS = "defaults";
        private static final String COL_FLAGS = "flags";
        private static final String COL_PRIORITY = "priority";
        private static final String COL_CATEGORY = "category";
        private static final String COL_ACTION_COUNT = "action_count";
        private static final String COL_POSTTIME_MS = "posttime_ms";
        private static final String COL_AIRTIME_MS = "airtime_ms";
        private static final String COL_FIRST_EXPANSIONTIME_MS = "first_expansion_time_ms";
        private static final String COL_AIRTIME_EXPANDED_MS = "expansion_airtime_ms";
        private static final String COL_EXPAND_COUNT = "expansion_count";


        private static final int EVENT_TYPE_POST = 1;
        private static final int EVENT_TYPE_CLICK = 2;
        private static final int EVENT_TYPE_REMOVE = 3;
        private static final int EVENT_TYPE_DISMISS = 4;

        private static long sLastPruneMs;
        private static long sNumWrites;

        private final SQLiteOpenHelper mHelper;
        private final Handler mWriteHandler;

        private static final long DAY_MS = 24 * 60 * 60 * 1000;

        public SQLiteLog(Context context) {
            HandlerThread backgroundThread = new HandlerThread("notification-sqlite-log",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            backgroundThread.start();
            mWriteHandler = new Handler(backgroundThread.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    NotificationRecord r = (NotificationRecord) msg.obj;
                    long nowMs = System.currentTimeMillis();
                    switch (msg.what) {
                        case MSG_POST:
                            writeEvent(r.sbn.getPostTime(), EVENT_TYPE_POST, r);
                            break;
                        case MSG_CLICK:
                            writeEvent(nowMs, EVENT_TYPE_CLICK, r);
                            break;
                        case MSG_REMOVE:
                            writeEvent(nowMs, EVENT_TYPE_REMOVE, r);
                            break;
                        case MSG_DISMISS:
                            writeEvent(nowMs, EVENT_TYPE_DISMISS, r);
                            break;
                        default:
                            Log.wtf(TAG, "Unknown message type: " + msg.what);
                            break;
                    }
                }
            };
            mHelper = new SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
                @Override
                public void onCreate(SQLiteDatabase db) {
                    db.execSQL("CREATE TABLE " + TAB_LOG + " (" +
                            "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            COL_EVENT_USER_ID + " INT," +
                            COL_EVENT_TYPE + " INT," +
                            COL_EVENT_TIME + " INT," +
                            COL_KEY + " TEXT," +
                            COL_PKG + " TEXT," +
                            COL_NOTIFICATION_ID + " INT," +
                            COL_TAG + " TEXT," +
                            COL_WHEN_MS + " INT," +
                            COL_DEFAULTS + " INT," +
                            COL_FLAGS + " INT," +
                            COL_PRIORITY + " INT," +
                            COL_CATEGORY + " TEXT," +
                            COL_ACTION_COUNT + " INT," +
                            COL_POSTTIME_MS + " INT," +
                            COL_AIRTIME_MS + " INT," +
                            COL_FIRST_EXPANSIONTIME_MS + " INT," +
                            COL_AIRTIME_EXPANDED_MS + " INT," +
                            COL_EXPAND_COUNT + " INT" +
                            ")");
                }

                @Override
                public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                    if (oldVersion <= 3) {
                        // Version 3 creation left 'log' in a weird state. Just reset for now.
                        db.execSQL("DROP TABLE IF EXISTS " + TAB_LOG);
                        onCreate(db);
                    }
                }
            };
        }

        public void logPosted(NotificationRecord notification) {
            mWriteHandler.sendMessage(mWriteHandler.obtainMessage(MSG_POST, notification));
        }

        public void logClicked(NotificationRecord notification) {
            mWriteHandler.sendMessage(mWriteHandler.obtainMessage(MSG_CLICK, notification));
        }

        public void logRemoved(NotificationRecord notification) {
            mWriteHandler.sendMessage(mWriteHandler.obtainMessage(MSG_REMOVE, notification));
        }

        public void logDismissed(NotificationRecord notification) {
            mWriteHandler.sendMessage(mWriteHandler.obtainMessage(MSG_DISMISS, notification));
        }

        private JSONArray JsonPostFrequencies(DumpFilter filter) throws JSONException {
            JSONArray frequencies = new JSONArray();
            SQLiteDatabase db = mHelper.getReadableDatabase();
            long midnight = getMidnightMs();
            String q = "SELECT " +
                    COL_EVENT_USER_ID + ", " +
                    COL_PKG + ", " +
                    // Bucket by day by looking at 'floor((midnight - eventTimeMs) / dayMs)'
                    "CAST(((" + midnight + " - " + COL_EVENT_TIME + ") / " + DAY_MS + ") AS int) " +
                    "AS day, " +
                    "COUNT(*) AS cnt " +
                    "FROM " + TAB_LOG + " " +
                    "WHERE " +
                    COL_EVENT_TYPE + "=" + EVENT_TYPE_POST +
                    " AND " + COL_EVENT_TIME + " > " + filter.since +
                    " GROUP BY " + COL_EVENT_USER_ID + ", day, " + COL_PKG;
            Cursor cursor = db.rawQuery(q, null);
            try {
                for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                    int userId = cursor.getInt(0);
                    String pkg = cursor.getString(1);
                    if (filter != null && !filter.matches(pkg)) continue;
                    int day = cursor.getInt(2);
                    int count = cursor.getInt(3);
                    JSONObject row = new JSONObject();
                    row.put("user_id", userId);
                    row.put("package", pkg);
                    row.put("day", day);
                    row.put("count", count);
                    frequencies.put(row);
                }
            } finally {
                cursor.close();
            }
            return frequencies;
        }

        public void printPostFrequencies(PrintWriter pw, String indent, DumpFilter filter) {
            SQLiteDatabase db = mHelper.getReadableDatabase();
            long midnight = getMidnightMs();
            String q = "SELECT " +
                    COL_EVENT_USER_ID + ", " +
                    COL_PKG + ", " +
                    // Bucket by day by looking at 'floor((midnight - eventTimeMs) / dayMs)'
                    "CAST(((" + midnight + " - " + COL_EVENT_TIME + ") / " + DAY_MS + ") AS int) " +
                        "AS day, " +
                    "COUNT(*) AS cnt " +
                    "FROM " + TAB_LOG + " " +
                    "WHERE " +
                    COL_EVENT_TYPE + "=" + EVENT_TYPE_POST + " " +
                    "GROUP BY " + COL_EVENT_USER_ID + ", day, " + COL_PKG;
            Cursor cursor = db.rawQuery(q, null);
            try {
                for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                    int userId = cursor.getInt(0);
                    String pkg = cursor.getString(1);
                    if (filter != null && !filter.matches(pkg)) continue;
                    int day = cursor.getInt(2);
                    int count = cursor.getInt(3);
                    pw.println(indent + "post_frequency{user_id=" + userId + ",pkg=" + pkg +
                            ",day=" + day + ",count=" + count + "}");
                }
            } finally {
                cursor.close();
            }
        }

        private long getMidnightMs() {
            GregorianCalendar midnight = new GregorianCalendar();
            midnight.set(midnight.get(Calendar.YEAR), midnight.get(Calendar.MONTH),
                    midnight.get(Calendar.DATE), 23, 59, 59);
            return midnight.getTimeInMillis();
        }

        private void writeEvent(long eventTimeMs, int eventType, NotificationRecord r) {
            ContentValues cv = new ContentValues();
            cv.put(COL_EVENT_USER_ID, r.sbn.getUser().getIdentifier());
            cv.put(COL_EVENT_TIME, eventTimeMs);
            cv.put(COL_EVENT_TYPE, eventType);
            putNotificationIdentifiers(r, cv);
            if (eventType == EVENT_TYPE_POST) {
                putNotificationDetails(r, cv);
            } else {
                putPosttimeVisibility(r, cv);
            }
            SQLiteDatabase db = mHelper.getWritableDatabase();
            if (db.insert(TAB_LOG, null, cv) < 0) {
                Log.wtf(TAG, "Error while trying to insert values: " + cv);
            }
            sNumWrites++;
            pruneIfNecessary(db);
        }

        private void pruneIfNecessary(SQLiteDatabase db) {
            // Prune if we haven't in a while.
            long nowMs = System.currentTimeMillis();
            if (sNumWrites > PRUNE_MIN_WRITES ||
                    nowMs - sLastPruneMs > PRUNE_MIN_DELAY_MS) {
                sNumWrites = 0;
                sLastPruneMs = nowMs;
                long horizonStartMs = nowMs - HORIZON_MS;
                int deletedRows = db.delete(TAB_LOG, COL_EVENT_TIME + " < ?",
                        new String[] { String.valueOf(horizonStartMs) });
                Log.d(TAG, "Pruned event entries: " + deletedRows);
            }
        }

        private static void putNotificationIdentifiers(NotificationRecord r, ContentValues outCv) {
            outCv.put(COL_KEY, r.sbn.getKey());
            outCv.put(COL_PKG, r.sbn.getPackageName());
        }

        private static void putNotificationDetails(NotificationRecord r, ContentValues outCv) {
            outCv.put(COL_NOTIFICATION_ID, r.sbn.getId());
            if (r.sbn.getTag() != null) {
                outCv.put(COL_TAG, r.sbn.getTag());
            }
            outCv.put(COL_WHEN_MS, r.sbn.getPostTime());
            outCv.put(COL_FLAGS, r.getNotification().flags);
            outCv.put(COL_PRIORITY, r.getNotification().priority);
            if (r.getNotification().category != null) {
                outCv.put(COL_CATEGORY, r.getNotification().category);
            }
            outCv.put(COL_ACTION_COUNT, r.getNotification().actions != null ?
                    r.getNotification().actions.length : 0);
        }

        private static void putPosttimeVisibility(NotificationRecord r, ContentValues outCv) {
            outCv.put(COL_POSTTIME_MS, r.stats.getCurrentPosttimeMs());
            outCv.put(COL_AIRTIME_MS, r.stats.getCurrentAirtimeMs());
            outCv.put(COL_EXPAND_COUNT, r.stats.userExpansionCount);
            outCv.put(COL_AIRTIME_EXPANDED_MS, r.stats.getCurrentAirtimeExpandedMs());
            outCv.put(COL_FIRST_EXPANSIONTIME_MS, r.stats.posttimeToFirstVisibleExpansionMs);
        }

        public void dump(PrintWriter pw, String indent, DumpFilter filter) {
            printPostFrequencies(pw, indent, filter);
        }

        public JSONObject dumpJson(DumpFilter filter) {
            JSONObject dump = new JSONObject();
            try {
                dump.put("post_frequency", JsonPostFrequencies(filter));
            } catch (JSONException e) {
                // pass
            }
            return dump;
        }
    }
}
