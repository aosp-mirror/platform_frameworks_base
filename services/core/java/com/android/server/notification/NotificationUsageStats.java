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

import static android.app.NotificationManager.IMPORTANCE_HIGH;

import android.app.Notification;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteFullException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.logging.MetricsLogger;
import com.android.server.notification.NotificationManagerService.DumpFilter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.lang.Math;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
    private ArraySet<String> mStatExpiredkeys = new ArraySet<>();
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
    public synchronized float getAppEnqueueRate(String packageName) {
        AggregatedStats stats = getOrCreateAggregatedStatsLocked(packageName);
        if (stats != null) {
            return stats.getEnqueueRate(SystemClock.elapsedRealtime());
        } else {
            return 0f;
        }
    }

    /**
     * Called when a notification wants to alert.
     */
    public synchronized boolean isAlertRateLimited(String packageName) {
        AggregatedStats stats = getOrCreateAggregatedStatsLocked(packageName);
        if (stats != null) {
            return stats.isAlertRateLimited();
        } else {
            return false;
        }
    }

    /**
     * Called when a notification is tentatively enqueued by an app, before rate checking.
     */
    public synchronized void registerEnqueuedByApp(String packageName) {
        AggregatedStats[] aggregatedStatsArray = getAggregatedStatsLocked(packageName);
        for (AggregatedStats stats : aggregatedStatsArray) {
            stats.numEnqueuedByApp++;
        }
        releaseAggregatedStatsLocked(aggregatedStatsArray);
    }

    /**
     * Called when a notification has been posted.
     */
    public synchronized void registerPostedByApp(NotificationRecord notification) {
        final long now = SystemClock.elapsedRealtime();
        notification.stats.posttimeElapsedMs = now;

        AggregatedStats[] aggregatedStatsArray = getAggregatedStatsLocked(notification);
        for (AggregatedStats stats : aggregatedStatsArray) {
            stats.numPostedByApp++;
            stats.updateInterarrivalEstimate(now);
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
    public synchronized void registerUpdatedByApp(NotificationRecord notification,
            NotificationRecord old) {
        notification.stats.updateFrom(old.stats);
        AggregatedStats[] aggregatedStatsArray = getAggregatedStatsLocked(notification);
        for (AggregatedStats stats : aggregatedStatsArray) {
            stats.numUpdatedByApp++;
            stats.updateInterarrivalEstimate(SystemClock.elapsedRealtime());
            stats.countApiUse(notification);
        }
        releaseAggregatedStatsLocked(aggregatedStatsArray);
        if (ENABLE_SQLITE_LOG) {
            mSQLiteLog.logPosted(notification);
        }
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

    public synchronized void registerSuspendedByAdmin(NotificationRecord notification) {
        AggregatedStats[] aggregatedStatsArray = getAggregatedStatsLocked(notification);
        for (AggregatedStats stats : aggregatedStatsArray) {
            stats.numSuspendedByAdmin++;
        }
        releaseAggregatedStatsLocked(aggregatedStatsArray);
    }

    public synchronized void registerOverRateQuota(String packageName) {
        AggregatedStats[] aggregatedStatsArray = getAggregatedStatsLocked(packageName);
        for (AggregatedStats stats : aggregatedStatsArray) {
            stats.numRateViolations++;
        }
    }

    public synchronized void registerOverCountQuota(String packageName) {
        AggregatedStats[] aggregatedStatsArray = getAggregatedStatsLocked(packageName);
        for (AggregatedStats stats : aggregatedStatsArray) {
            stats.numQuotaViolations++;
        }
    }

    // Locked by this.
    private AggregatedStats[] getAggregatedStatsLocked(NotificationRecord record) {
        return getAggregatedStatsLocked(record.sbn.getPackageName());
    }

    // Locked by this.
    private AggregatedStats[] getAggregatedStatsLocked(String packageName) {
        if (!ENABLE_AGGREGATED_IN_MEMORY_STATS) {
            return EMPTY_AGGREGATED_STATS;
        }

        AggregatedStats[] array = mStatsArrays.poll();
        if (array == null) {
            array = new AggregatedStats[2];
        }
        array[0] = getOrCreateAggregatedStatsLocked(DEVICE_GLOBAL_STATS);
        array[1] = getOrCreateAggregatedStatsLocked(packageName);
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
        result.mLastAccessTime = SystemClock.elapsedRealtime();
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
            pw.println(indent + "mStats.size(): " + mStats.size());
        }
        if (ENABLE_SQLITE_LOG) {
            mSQLiteLog.dump(pw, indent, filter);
        }
    }

    public synchronized void emit() {
        AggregatedStats stats = getOrCreateAggregatedStatsLocked(DEVICE_GLOBAL_STATS);
        stats.emit();
        mHandler.removeMessages(MSG_EMIT);
        mHandler.sendEmptyMessageDelayed(MSG_EMIT, EMIT_PERIOD);
        for(String key: mStats.keySet()) {
            if (mStats.get(key).mLastAccessTime < mLastEmitTime) {
                mStatExpiredkeys.add(key);
            }
        }
        for(String key: mStatExpiredkeys) {
            mStats.remove(key);
        }
        mStatExpiredkeys.clear();
        mLastEmitTime = SystemClock.elapsedRealtime();
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
        public int numEnqueuedByApp;
        public int numPostedByApp;
        public int numUpdatedByApp;
        public int numRemovedByApp;
        public int numPeopleCacheHit;
        public int numPeopleCacheMiss;;
        public int numWithStaredPeople;
        public int numWithValidPeople;
        public int numBlocked;
        public int numSuspendedByAdmin;
        public int numWithActions;
        public int numPrivate;
        public int numSecret;
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
        public ImportanceHistogram noisyImportance;
        public ImportanceHistogram quietImportance;
        public ImportanceHistogram finalImportance;
        public RateEstimator enqueueRate;
        public AlertRateLimiter alertRate;
        public int numRateViolations;
        public int numAlertViolations;
        public int numQuotaViolations;
        public long mLastAccessTime;

        public AggregatedStats(Context context, String key) {
            this.key = key;
            mContext = context;
            mCreated = SystemClock.elapsedRealtime();
            noisyImportance = new ImportanceHistogram(context, "note_imp_noisy_");
            quietImportance = new ImportanceHistogram(context, "note_imp_quiet_");
            finalImportance = new ImportanceHistogram(context, "note_importance_");
            enqueueRate = new RateEstimator();
            alertRate = new AlertRateLimiter();
        }

        public AggregatedStats getPrevious() {
            if (mPrevious == null) {
                mPrevious = new AggregatedStats(mContext, key);
            }
            return mPrevious;
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

            if (record.stats.isNoisy) {
                noisyImportance.increment(record.stats.requestedImportance);
            } else {
                quietImportance.increment(record.stats.requestedImportance);
            }
            finalImportance.increment(record.getImportance());

            final Set<String> names = n.extras.keySet();
            if (names.contains(Notification.EXTRA_BIG_TEXT)) {
                numWithBigText++;
            }
            if (names.contains(Notification.EXTRA_PICTURE)) {
                numWithBigPicture++;
            }
            if (names.contains(Notification.EXTRA_LARGE_ICON)) {
                numWithLargeIcon++;
            }
            if (names.contains(Notification.EXTRA_TEXT_LINES)) {
                numWithInbox++;
            }
            if (names.contains(Notification.EXTRA_MEDIA_SESSION)) {
                numWithMediaSession++;
            }
            if (names.contains(Notification.EXTRA_TITLE) &&
                    !TextUtils.isEmpty(n.extras.getCharSequence(Notification.EXTRA_TITLE))) {
                numWithTitle++;
            }
            if (names.contains(Notification.EXTRA_TEXT) &&
                    !TextUtils.isEmpty(n.extras.getCharSequence(Notification.EXTRA_TEXT))) {
                numWithText++;
            }
            if (names.contains(Notification.EXTRA_SUB_TEXT) &&
                    !TextUtils.isEmpty(n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))) {
                numWithSubText++;
            }
            if (names.contains(Notification.EXTRA_INFO_TEXT) &&
                    !TextUtils.isEmpty(n.extras.getCharSequence(Notification.EXTRA_INFO_TEXT))) {
                numWithInfoText++;
            }
        }

        public void emit() {
            AggregatedStats previous = getPrevious();
            maybeCount("note_enqueued", (numEnqueuedByApp - previous.numEnqueuedByApp));
            maybeCount("note_post", (numPostedByApp - previous.numPostedByApp));
            maybeCount("note_update", (numUpdatedByApp - previous.numUpdatedByApp));
            maybeCount("note_remove", (numRemovedByApp - previous.numRemovedByApp));
            maybeCount("note_with_people", (numWithValidPeople - previous.numWithValidPeople));
            maybeCount("note_with_stars", (numWithStaredPeople - previous.numWithStaredPeople));
            maybeCount("people_cache_hit", (numPeopleCacheHit - previous.numPeopleCacheHit));
            maybeCount("people_cache_miss", (numPeopleCacheMiss - previous.numPeopleCacheMiss));
            maybeCount("note_blocked", (numBlocked - previous.numBlocked));
            maybeCount("note_suspended", (numSuspendedByAdmin - previous.numSuspendedByAdmin));
            maybeCount("note_with_actions", (numWithActions - previous.numWithActions));
            maybeCount("note_private", (numPrivate - previous.numPrivate));
            maybeCount("note_secret", (numSecret - previous.numSecret));
            maybeCount("note_interupt", (numInterrupt - previous.numInterrupt));
            maybeCount("note_big_text", (numWithBigText - previous.numWithBigText));
            maybeCount("note_big_pic", (numWithBigPicture - previous.numWithBigPicture));
            maybeCount("note_fg", (numForegroundService - previous.numForegroundService));
            maybeCount("note_ongoing", (numOngoing - previous.numOngoing));
            maybeCount("note_auto", (numAutoCancel - previous.numAutoCancel));
            maybeCount("note_large_icon", (numWithLargeIcon - previous.numWithLargeIcon));
            maybeCount("note_inbox", (numWithInbox - previous.numWithInbox));
            maybeCount("note_media", (numWithMediaSession - previous.numWithMediaSession));
            maybeCount("note_title", (numWithTitle - previous.numWithTitle));
            maybeCount("note_text", (numWithText - previous.numWithText));
            maybeCount("note_sub_text", (numWithSubText - previous.numWithSubText));
            maybeCount("note_info_text", (numWithInfoText - previous.numWithInfoText));
            maybeCount("note_over_rate", (numRateViolations - previous.numRateViolations));
            maybeCount("note_over_alert_rate", (numAlertViolations - previous.numAlertViolations));
            maybeCount("note_over_quota", (numQuotaViolations - previous.numQuotaViolations));
            noisyImportance.maybeCount(previous.noisyImportance);
            quietImportance.maybeCount(previous.quietImportance);
            finalImportance.maybeCount(previous.finalImportance);

            previous.numEnqueuedByApp = numEnqueuedByApp;
            previous.numPostedByApp = numPostedByApp;
            previous.numUpdatedByApp = numUpdatedByApp;
            previous.numRemovedByApp = numRemovedByApp;
            previous.numPeopleCacheHit = numPeopleCacheHit;
            previous.numPeopleCacheMiss = numPeopleCacheMiss;
            previous.numWithStaredPeople = numWithStaredPeople;
            previous.numWithValidPeople = numWithValidPeople;
            previous.numBlocked = numBlocked;
            previous.numSuspendedByAdmin = numSuspendedByAdmin;
            previous.numWithActions = numWithActions;
            previous.numPrivate = numPrivate;
            previous.numSecret = numSecret;
            previous.numInterrupt = numInterrupt;
            previous.numWithBigText = numWithBigText;
            previous.numWithBigPicture = numWithBigPicture;
            previous.numForegroundService = numForegroundService;
            previous.numOngoing = numOngoing;
            previous.numAutoCancel = numAutoCancel;
            previous.numWithLargeIcon = numWithLargeIcon;
            previous.numWithInbox = numWithInbox;
            previous.numWithMediaSession = numWithMediaSession;
            previous.numWithTitle = numWithTitle;
            previous.numWithText = numWithText;
            previous.numWithSubText = numWithSubText;
            previous.numWithInfoText = numWithInfoText;
            previous.numRateViolations = numRateViolations;
            previous.numAlertViolations = numAlertViolations;
            previous.numQuotaViolations = numQuotaViolations;
            noisyImportance.update(previous.noisyImportance);
            quietImportance.update(previous.quietImportance);
            finalImportance.update(previous.finalImportance);
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

        /** @return the enqueue rate if there were a new enqueue event right now. */
        public float getEnqueueRate() {
            return getEnqueueRate(SystemClock.elapsedRealtime());
        }

        public float getEnqueueRate(long now) {
            return enqueueRate.getRate(now);
        }

        public void updateInterarrivalEstimate(long now) {
            enqueueRate.update(now);
        }

        public boolean isAlertRateLimited() {
            boolean limited = alertRate.shouldRateLimitAlert(SystemClock.elapsedRealtime());
            if (limited) {
                numAlertViolations++;
            }
            return limited;
        }

        private String toStringWithIndent(String indent) {
            StringBuilder output = new StringBuilder();
            output.append(indent).append("AggregatedStats{\n");
            String indentPlusTwo = indent + "  ";
            output.append(indentPlusTwo);
            output.append("key='").append(key).append("',\n");
            output.append(indentPlusTwo);
            output.append("numEnqueuedByApp=").append(numEnqueuedByApp).append(",\n");
            output.append(indentPlusTwo);
            output.append("numPostedByApp=").append(numPostedByApp).append(",\n");
            output.append(indentPlusTwo);
            output.append("numUpdatedByApp=").append(numUpdatedByApp).append(",\n");
            output.append(indentPlusTwo);
            output.append("numRemovedByApp=").append(numRemovedByApp).append(",\n");
            output.append(indentPlusTwo);
            output.append("numPeopleCacheHit=").append(numPeopleCacheHit).append(",\n");
            output.append(indentPlusTwo);
            output.append("numWithStaredPeople=").append(numWithStaredPeople).append(",\n");
            output.append(indentPlusTwo);
            output.append("numWithValidPeople=").append(numWithValidPeople).append(",\n");
            output.append(indentPlusTwo);
            output.append("numPeopleCacheMiss=").append(numPeopleCacheMiss).append(",\n");
            output.append(indentPlusTwo);
            output.append("numBlocked=").append(numBlocked).append(",\n");
            output.append(indentPlusTwo);
            output.append("numSuspendedByAdmin=").append(numSuspendedByAdmin).append(",\n");
            output.append(indentPlusTwo);
            output.append("numWithActions=").append(numWithActions).append(",\n");
            output.append(indentPlusTwo);
            output.append("numPrivate=").append(numPrivate).append(",\n");
            output.append(indentPlusTwo);
            output.append("numSecret=").append(numSecret).append(",\n");
            output.append(indentPlusTwo);
            output.append("numInterrupt=").append(numInterrupt).append(",\n");
            output.append(indentPlusTwo);
            output.append("numWithBigText=").append(numWithBigText).append(",\n");
            output.append(indentPlusTwo);
            output.append("numWithBigPicture=").append(numWithBigPicture).append("\n");
            output.append(indentPlusTwo);
            output.append("numForegroundService=").append(numForegroundService).append("\n");
            output.append(indentPlusTwo);
            output.append("numOngoing=").append(numOngoing).append("\n");
            output.append(indentPlusTwo);
            output.append("numAutoCancel=").append(numAutoCancel).append("\n");
            output.append(indentPlusTwo);
            output.append("numWithLargeIcon=").append(numWithLargeIcon).append("\n");
            output.append(indentPlusTwo);
            output.append("numWithInbox=").append(numWithInbox).append("\n");
            output.append(indentPlusTwo);
            output.append("numWithMediaSession=").append(numWithMediaSession).append("\n");
            output.append(indentPlusTwo);
            output.append("numWithTitle=").append(numWithTitle).append("\n");
            output.append(indentPlusTwo);
            output.append("numWithText=").append(numWithText).append("\n");
            output.append(indentPlusTwo);
            output.append("numWithSubText=").append(numWithSubText).append("\n");
            output.append(indentPlusTwo);
            output.append("numWithInfoText=").append(numWithInfoText).append("\n");
            output.append(indentPlusTwo);
            output.append("numRateViolations=").append(numRateViolations).append("\n");
            output.append(indentPlusTwo);
            output.append("numAlertViolations=").append(numAlertViolations).append("\n");
            output.append(indentPlusTwo);
            output.append("numQuotaViolations=").append(numQuotaViolations).append("\n");
            output.append(indentPlusTwo).append(noisyImportance.toString()).append("\n");
            output.append(indentPlusTwo).append(quietImportance.toString()).append("\n");
            output.append(indentPlusTwo).append(finalImportance.toString()).append("\n");
            output.append(indent).append("}");
            return output.toString();
        }

        public JSONObject dumpJson() throws JSONException {
            AggregatedStats previous = getPrevious();
            JSONObject dump = new JSONObject();
            dump.put("key", key);
            dump.put("duration", SystemClock.elapsedRealtime() - mCreated);
            maybePut(dump, "numEnqueuedByApp", numEnqueuedByApp);
            maybePut(dump, "numPostedByApp", numPostedByApp);
            maybePut(dump, "numUpdatedByApp", numUpdatedByApp);
            maybePut(dump, "numRemovedByApp", numRemovedByApp);
            maybePut(dump, "numPeopleCacheHit", numPeopleCacheHit);
            maybePut(dump, "numPeopleCacheMiss", numPeopleCacheMiss);
            maybePut(dump, "numWithStaredPeople", numWithStaredPeople);
            maybePut(dump, "numWithValidPeople", numWithValidPeople);
            maybePut(dump, "numBlocked", numBlocked);
            maybePut(dump, "numSuspendedByAdmin", numSuspendedByAdmin);
            maybePut(dump, "numWithActions", numWithActions);
            maybePut(dump, "numPrivate", numPrivate);
            maybePut(dump, "numSecret", numSecret);
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
            maybePut(dump, "numRateViolations", numRateViolations);
            maybePut(dump, "numQuotaLViolations", numQuotaViolations);
            maybePut(dump, "notificationEnqueueRate", getEnqueueRate());
            maybePut(dump, "numAlertViolations", numAlertViolations);
            noisyImportance.maybePut(dump, previous.noisyImportance);
            quietImportance.maybePut(dump, previous.quietImportance);
            finalImportance.maybePut(dump, previous.finalImportance);

            return dump;
        }

        private void maybePut(JSONObject dump, String name, int value) throws JSONException {
            if (value > 0) {
                dump.put(name, value);
            }
        }

        private void maybePut(JSONObject dump, String name, float value) throws JSONException {
            if (value > 0.0) {
                dump.put(name, value);
            }
        }
    }

    private static class ImportanceHistogram {
        // TODO define these somewhere else
        private static final int NUM_IMPORTANCES = 6;
        private static final String[] IMPORTANCE_NAMES =
                {"none", "min", "low", "default", "high", "max"};
        private final Context mContext;
        private final String[] mCounterNames;
        private final String mPrefix;
        private int[] mCount;

        ImportanceHistogram(Context context, String prefix) {
            mContext = context;
            mCount = new int[NUM_IMPORTANCES];
            mCounterNames = new String[NUM_IMPORTANCES];
            mPrefix = prefix;
            for (int i = 0; i < NUM_IMPORTANCES; i++) {
                mCounterNames[i] = mPrefix + IMPORTANCE_NAMES[i];
            }
        }

        void increment(int imp) {
            imp = Math.max(0, Math.min(imp, mCount.length - 1));
            mCount[imp]++;
        }

        void maybeCount(ImportanceHistogram prev) {
            for (int i = 0; i < NUM_IMPORTANCES; i++) {
                final int value = mCount[i] - prev.mCount[i];
                if (value > 0) {
                    MetricsLogger.count(mContext, mCounterNames[i], value);
                }
            }
        }

        void update(ImportanceHistogram that) {
            for (int i = 0; i < NUM_IMPORTANCES; i++) {
                mCount[i] = that.mCount[i];
            }
        }

        public void maybePut(JSONObject dump, ImportanceHistogram prev)
                throws JSONException {
            dump.put(mPrefix, new JSONArray(mCount));
        }

        @Override
        public String toString() {
            StringBuilder output = new StringBuilder();
            output.append(mPrefix).append(": [");
            for (int i = 0; i < NUM_IMPORTANCES; i++) {
                output.append(mCount[i]);
                if (i < (NUM_IMPORTANCES-1)) {
                    output.append(", ");
                }
            }
            output.append("]");
            return output.toString();
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
        /** Importance directly requested by the app. */
        public int requestedImportance;
        /** Did the app include sound or vibration on the notificaiton. */
        public boolean isNoisy;
        /** Importance after initial filtering for noise and other features */
        public int naturalImportance;

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

        /**
         * Returns whether this notification has been visible and expanded at the same.
         */
        public boolean hasBeenVisiblyExpanded() {
            return posttimeToFirstVisibleExpansionMs >= 0;
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
            StringBuilder output = new StringBuilder();
            output.append("SingleNotificationStats{");

            output.append("posttimeElapsedMs=").append(posttimeElapsedMs).append(", ");
            output.append("posttimeToFirstClickMs=").append(posttimeToFirstClickMs).append(", ");
            output.append("posttimeToDismissMs=").append(posttimeToDismissMs).append(", ");
            output.append("airtimeCount=").append(airtimeCount).append(", ");
            output.append("airtimeMs=").append(airtimeMs).append(", ");
            output.append("currentAirtimeStartElapsedMs=").append(currentAirtimeStartElapsedMs)
                    .append(", ");
            output.append("airtimeExpandedMs=").append(airtimeExpandedMs).append(", ");
            output.append("posttimeToFirstVisibleExpansionMs=")
                    .append(posttimeToFirstVisibleExpansionMs).append(", ");
            output.append("currentAirtimeExpandedStartElapsedMs=")
                    .append(currentAirtimeExpandedStartElapsedMs).append(", ");
            output.append("requestedImportance=").append(requestedImportance).append(", ");
            output.append("naturalImportance=").append(naturalImportance).append(", ");
            output.append("isNoisy=").append(isNoisy);
            output.append('}');
            return output.toString();
        }

        /** Copy useful information out of the stats from the pre-update notifications. */
        public void updateFrom(SingleNotificationStats old) {
            posttimeElapsedMs = old.posttimeElapsedMs;
            posttimeToFirstClickMs = old.posttimeToFirstClickMs;
            airtimeCount = old.airtimeCount;
            posttimeToFirstAirtimeMs = old.posttimeToFirstAirtimeMs;
            currentAirtimeStartElapsedMs = old.currentAirtimeStartElapsedMs;
            airtimeMs = old.airtimeMs;
            posttimeToFirstVisibleExpansionMs = old.posttimeToFirstVisibleExpansionMs;
            currentAirtimeExpandedStartElapsedMs = old.currentAirtimeExpandedStartElapsedMs;
            airtimeExpandedMs = old.airtimeExpandedMs;
            userExpansionCount = old.userExpansionCount;
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
        private static final int DB_VERSION = 5;

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
        private static final String COL_IMPORTANCE_REQ = "importance_request";
        private static final String COL_IMPORTANCE_FINAL = "importance_final";
        private static final String COL_NOISY = "noisy";
        private static final String COL_MUTED = "muted";
        private static final String COL_DEMOTED = "demoted";
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

        private static final int IDLE_CONNECTION_TIMEOUT_MS = 30000;

        private static long sLastPruneMs;

        private static long sNumWrites;
        private final SQLiteOpenHelper mHelper;

        private final Handler mWriteHandler;
        private static final long DAY_MS = 24 * 60 * 60 * 1000;
        private static final String STATS_QUERY = "SELECT " +
                COL_EVENT_USER_ID + ", " +
                COL_PKG + ", " +
                // Bucket by day by looking at 'floor((midnight - eventTimeMs) / dayMs)'
                "CAST(((%d - " + COL_EVENT_TIME + ") / " + DAY_MS + ") AS int) " +
                "AS day, " +
                "COUNT(*) AS cnt, " +
                "SUM(" + COL_MUTED + ") as muted, " +
                "SUM(" + COL_NOISY + ") as noisy, " +
                "SUM(" + COL_DEMOTED + ") as demoted " +
                "FROM " + TAB_LOG + " " +
                "WHERE " +
                COL_EVENT_TYPE + "=" + EVENT_TYPE_POST +
                " AND " + COL_EVENT_TIME + " > %d " +
                " GROUP BY " + COL_EVENT_USER_ID + ", day, " + COL_PKG;

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
                            COL_IMPORTANCE_REQ + " INT," +
                            COL_IMPORTANCE_FINAL + " INT," +
                            COL_NOISY + " INT," +
                            COL_MUTED + " INT," +
                            COL_DEMOTED + " INT," +
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
                public void onConfigure(SQLiteDatabase db) {
                    // Memory optimization - close idle connections after 30s of inactivity
                    setIdleConnectionTimeout(IDLE_CONNECTION_TIMEOUT_MS);
                }

                @Override
                public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                    if (oldVersion != newVersion) {
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

        private JSONArray jsonPostFrequencies(DumpFilter filter) throws JSONException {
            JSONArray frequencies = new JSONArray();
            SQLiteDatabase db = mHelper.getReadableDatabase();
            long midnight = getMidnightMs();
            String q = String.format(STATS_QUERY, midnight, filter.since);
            Cursor cursor = db.rawQuery(q, null);
            try {
                for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                    int userId = cursor.getInt(0);
                    String pkg = cursor.getString(1);
                    if (filter != null && !filter.matches(pkg)) continue;
                    int day = cursor.getInt(2);
                    int count = cursor.getInt(3);
                    int muted = cursor.getInt(4);
                    int noisy = cursor.getInt(5);
                    int demoted = cursor.getInt(6);
                    JSONObject row = new JSONObject();
                    row.put("user_id", userId);
                    row.put("package", pkg);
                    row.put("day", day);
                    row.put("count", count);
                    row.put("noisy", noisy);
                    row.put("muted", muted);
                    row.put("demoted", demoted);
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
            String q = String.format(STATS_QUERY, midnight, filter.since);
            Cursor cursor = db.rawQuery(q, null);
            try {
                for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                    int userId = cursor.getInt(0);
                    String pkg = cursor.getString(1);
                    if (filter != null && !filter.matches(pkg)) continue;
                    int day = cursor.getInt(2);
                    int count = cursor.getInt(3);
                    int muted = cursor.getInt(4);
                    int noisy = cursor.getInt(5);
                    int demoted = cursor.getInt(6);
                    pw.println(indent + "post_frequency{user_id=" + userId + ",pkg=" + pkg +
                            ",day=" + day + ",count=" + count + ",muted=" + muted + "/" + noisy +
                            ",demoted=" + demoted + "}");
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
                try {
                    int deletedRows = db.delete(TAB_LOG, COL_EVENT_TIME + " < ?",
                            new String[]{String.valueOf(horizonStartMs)});
                    Log.d(TAG, "Pruned event entries: " + deletedRows);
                } catch (SQLiteFullException e) {
                    Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
                }
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
            final int before = r.stats.requestedImportance;
            final int after = r.getImportance();
            final boolean noisy = r.stats.isNoisy;
            outCv.put(COL_IMPORTANCE_REQ, before);
            outCv.put(COL_IMPORTANCE_FINAL, after);
            outCv.put(COL_DEMOTED, after < before ? 1 : 0);
            outCv.put(COL_NOISY, noisy);
            if (noisy && after < IMPORTANCE_HIGH) {
                outCv.put(COL_MUTED, 1);
            } else {
                outCv.put(COL_MUTED, 0);
            }
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
                dump.put("post_frequency", jsonPostFrequencies(filter));
                dump.put("since", filter.since);
                dump.put("now", System.currentTimeMillis());
            } catch (JSONException e) {
                // pass
            }
            return dump;
        }
    }
}
