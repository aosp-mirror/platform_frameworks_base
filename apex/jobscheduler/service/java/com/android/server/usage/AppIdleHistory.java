/**
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.server.usage;

import static android.app.usage.UsageStatsManager.REASON_MAIN_DEFAULT;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_USER;
import static android.app.usage.UsageStatsManager.REASON_MAIN_MASK;
import static android.app.usage.UsageStatsManager.REASON_MAIN_PREDICTED;
import static android.app.usage.UsageStatsManager.REASON_MAIN_TIMEOUT;
import static android.app.usage.UsageStatsManager.REASON_MAIN_USAGE;
import static android.app.usage.UsageStatsManager.REASON_SUB_MASK;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_USER_INTERACTION;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_NEVER;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET;

import static com.android.server.usage.AppStandbyController.isUserUsage;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.app.usage.AppStandbyInfo;
import android.app.usage.UsageStatsManager;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseLongArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps track of recent active state changes in apps.
 * Access should be guarded by a lock by the caller.
 */
public class AppIdleHistory {

    private static final String TAG = "AppIdleHistory";

    private static final boolean DEBUG = AppStandbyController.DEBUG;

    // History for all users and all packages
    private SparseArray<ArrayMap<String,AppUsageHistory>> mIdleHistory = new SparseArray<>();
    private static final long ONE_MINUTE = 60 * 1000;

    static final int STANDBY_BUCKET_UNKNOWN = -1;

    /**
     * The bucket beyond which apps are considered idle. Any apps in this bucket or lower are
     * considered idle while those in higher buckets are not considered idle.
     */
    static final int IDLE_BUCKET_CUTOFF = STANDBY_BUCKET_RARE;

    /** Initial version of the xml containing the app idle stats ({@link #APP_IDLE_FILENAME}). */
    private static final int XML_VERSION_INITIAL = 0;
    /**
     * Allowed writing expiry times for any standby bucket instead of only active and working set.
     * In previous version, we used to specify expiry times for active and working set as
     * attributes:
     * <pre>
     *     <package activeTimeoutTime="..." workingSetTimeoutTime="..." />
     * </pre>
     * In this version, it is changed to:
     * <pre>
     *     <package>
     *         <expiryTimes>
     *             <item bucket="..." expiry="..." />
     *             <item bucket="..." expiry="..." />
     *         </expiryTimes>
     *     </package>
     * </pre>
     */
    private static final int XML_VERSION_ADD_BUCKET_EXPIRY_TIMES = 1;
    /** Current version */
    private static final int XML_VERSION_CURRENT = XML_VERSION_ADD_BUCKET_EXPIRY_TIMES;

    @VisibleForTesting
    static final String APP_IDLE_FILENAME = "app_idle_stats.xml";
    private static final String TAG_PACKAGES = "packages";
    private static final String TAG_PACKAGE = "package";
    private static final String TAG_BUCKET_EXPIRY_TIMES = "expiryTimes";
    private static final String TAG_ITEM = "item";
    private static final String ATTR_NAME = "name";
    // Screen on timebase time when app was last used
    private static final String ATTR_SCREEN_IDLE = "screenIdleTime";
    // Elapsed timebase time when app was last used
    private static final String ATTR_ELAPSED_IDLE = "elapsedIdleTime";
    // Elapsed timebase time when app was last used by the user
    private static final String ATTR_LAST_USED_BY_USER_ELAPSED = "lastUsedByUserElapsedTime";
    // Elapsed timebase time when the app bucket was last predicted externally
    private static final String ATTR_LAST_PREDICTED_TIME = "lastPredictedTime";
    // The standby bucket for the app
    private static final String ATTR_CURRENT_BUCKET = "appLimitBucket";
    // The reason the app was put in the above bucket
    private static final String ATTR_BUCKETING_REASON = "bucketReason";
    // The last time a job was run for this app
    private static final String ATTR_LAST_RUN_JOB_TIME = "lastJobRunTime";
    // The time when the forced active state can be overridden.
    private static final String ATTR_BUCKET_ACTIVE_TIMEOUT_TIME = "activeTimeoutTime";
    // The time when the forced working_set state can be overridden.
    private static final String ATTR_BUCKET_WORKING_SET_TIMEOUT_TIME = "workingSetTimeoutTime";
    // The standby bucket value
    private static final String ATTR_BUCKET = "bucket";
    // The time when the forced bucket state can be overridde.
    private static final String ATTR_EXPIRY_TIME = "expiry";
    // Elapsed timebase time when the app was last marked for restriction.
    private static final String ATTR_LAST_RESTRICTION_ATTEMPT_ELAPSED =
            "lastRestrictionAttemptElapsedTime";
    // Reason why the app was last marked for restriction.
    private static final String ATTR_LAST_RESTRICTION_ATTEMPT_REASON =
            "lastRestrictionAttemptReason";
    // The next estimated launch time of the app, in ms since epoch.
    private static final String ATTR_NEXT_ESTIMATED_APP_LAUNCH_TIME = "nextEstimatedAppLaunchTime";
    // Version of the xml file.
    private static final String ATTR_VERSION = "version";

    // device on time = mElapsedDuration + (timeNow - mElapsedSnapshot)
    private long mElapsedSnapshot; // Elapsed time snapshot when last write of mDeviceOnDuration
    private long mElapsedDuration; // Total device on duration since device was "born"

    // screen on time = mScreenOnDuration + (timeNow - mScreenOnSnapshot)
    private long mScreenOnSnapshot; // Elapsed time snapshot when last write of mScreenOnDuration
    private long mScreenOnDuration; // Total screen on duration since device was "born"

    private final File mStorageDir;

    private boolean mScreenOn;

    static class AppUsageHistory {
        // Last used time (including system usage), using elapsed timebase
        long lastUsedElapsedTime;
        // Last time the user used the app, using elapsed timebase
        long lastUsedByUserElapsedTime;
        // Last used time using screen_on timebase
        long lastUsedScreenTime;
        // Last predicted time using elapsed timebase
        long lastPredictedTime;
        // Last predicted bucket
        @UsageStatsManager.StandbyBuckets
        int lastPredictedBucket = STANDBY_BUCKET_UNKNOWN;
        // Standby bucket
        @UsageStatsManager.StandbyBuckets
        int currentBucket;
        // Reason for setting the standby bucket. The value here is a combination of
        // one of UsageStatsManager.REASON_MAIN_* and one (or none) of
        // UsageStatsManager.REASON_SUB_*. Also see REASON_MAIN_MASK and REASON_SUB_MASK.
        int bucketingReason;
        // In-memory only, last bucket for which the listeners were informed
        int lastInformedBucket;
        // The last time a job was run for this app, using elapsed timebase
        long lastJobRunTime;
        // The estimated time the app will be launched next, in milliseconds since epoch.
        @CurrentTimeMillisLong
        long nextEstimatedLaunchTime;
        // Contains standby buckets that apps were forced into and the corresponding expiry times
        // (in elapsed timebase) for each bucket state. App will stay in the highest bucket until
        // it's expiry time is elapsed and will be moved to the next highest bucket.
        SparseLongArray bucketExpiryTimesMs;
        // The last time an agent attempted to put the app into the RESTRICTED bucket.
        long lastRestrictAttemptElapsedTime;
        // The last reason the app was marked to be put into the RESTRICTED bucket.
        int lastRestrictReason;
    }

    AppIdleHistory(File storageDir, long elapsedRealtime) {
        mElapsedSnapshot = elapsedRealtime;
        mScreenOnSnapshot = elapsedRealtime;
        mStorageDir = storageDir;
        readScreenOnTime();
    }

    public void updateDisplay(boolean screenOn, long elapsedRealtime) {
        if (screenOn == mScreenOn) return;

        mScreenOn = screenOn;
        if (mScreenOn) {
            mScreenOnSnapshot = elapsedRealtime;
        } else {
            mScreenOnDuration += elapsedRealtime - mScreenOnSnapshot;
            mElapsedDuration += elapsedRealtime - mElapsedSnapshot;
            mElapsedSnapshot = elapsedRealtime;
        }
        if (DEBUG) Slog.d(TAG, "mScreenOnSnapshot=" + mScreenOnSnapshot
                + ", mScreenOnDuration=" + mScreenOnDuration
                + ", mScreenOn=" + mScreenOn);
    }

    public long getScreenOnTime(long elapsedRealtime) {
        long screenOnTime = mScreenOnDuration;
        if (mScreenOn) {
            screenOnTime += elapsedRealtime - mScreenOnSnapshot;
        }
        return screenOnTime;
    }

    @VisibleForTesting
    File getScreenOnTimeFile() {
        return new File(mStorageDir, "screen_on_time");
    }

    private void readScreenOnTime() {
        File screenOnTimeFile = getScreenOnTimeFile();
        if (screenOnTimeFile.exists()) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(screenOnTimeFile));
                mScreenOnDuration = Long.parseLong(reader.readLine());
                mElapsedDuration = Long.parseLong(reader.readLine());
                reader.close();
            } catch (IOException | NumberFormatException e) {
            }
        } else {
            writeScreenOnTime();
        }
    }

    private void writeScreenOnTime() {
        AtomicFile screenOnTimeFile = new AtomicFile(getScreenOnTimeFile());
        FileOutputStream fos = null;
        try {
            fos = screenOnTimeFile.startWrite();
            fos.write((Long.toString(mScreenOnDuration) + "\n"
                    + Long.toString(mElapsedDuration) + "\n").getBytes());
            screenOnTimeFile.finishWrite(fos);
        } catch (IOException ioe) {
            screenOnTimeFile.failWrite(fos);
        }
    }

    /**
     * To be called periodically to keep track of elapsed time when app idle times are written
     */
    public void writeAppIdleDurations() {
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        // Only bump up and snapshot the elapsed time. Don't change screen on duration.
        mElapsedDuration += elapsedRealtime - mElapsedSnapshot;
        mElapsedSnapshot = elapsedRealtime;
        writeScreenOnTime();
    }

    /**
     * Mark the app as used and update the bucket if necessary. If there is a expiry time specified
     * that's in the future, then the usage event is temporary and keeps the app in the specified
     * bucket at least until the expiry time is reached. This can be used to keep the app in an
     * elevated bucket for a while until some important task gets to run.
     *
     * @param appUsageHistory the usage record for the app being updated
     * @param packageName name of the app being updated, for logging purposes
     * @param newBucket the bucket to set the app to
     * @param usageReason the sub-reason for usage, one of REASON_SUB_USAGE_*
     * @param nowElapsedRealtimeMs mark as used time if non-zero (in
     *                          {@link SystemClock#elapsedRealtime()} time base)
     * @param expiryElapsedRealtimeMs the expiry time for the specified bucket (in
     *                         {@link SystemClock#elapsedRealtime()} time base)
     * @return {@code appUsageHistory}
     */
    AppUsageHistory reportUsage(AppUsageHistory appUsageHistory, String packageName, int userId,
            int newBucket, int usageReason,
            long nowElapsedRealtimeMs, long expiryElapsedRealtimeMs) {
        int bucketingReason = REASON_MAIN_USAGE | usageReason;
        final boolean isUserUsage = isUserUsage(bucketingReason);

        if (appUsageHistory.currentBucket == STANDBY_BUCKET_RESTRICTED && !isUserUsage
                && (appUsageHistory.bucketingReason & REASON_MAIN_MASK) != REASON_MAIN_TIMEOUT) {
            // Only user usage should bring an app out of the RESTRICTED bucket, unless the app
            // just timed out into RESTRICTED.
            newBucket = STANDBY_BUCKET_RESTRICTED;
            bucketingReason = appUsageHistory.bucketingReason;
        } else {
            // Set the expiry time if applicable
            if (expiryElapsedRealtimeMs > nowElapsedRealtimeMs) {
                // Convert to elapsed timebase
                final long expiryTimeMs = getElapsedTime(expiryElapsedRealtimeMs);
                if (appUsageHistory.bucketExpiryTimesMs == null) {
                    appUsageHistory.bucketExpiryTimesMs = new SparseLongArray();
                }
                final long currentExpiryTimeMs = appUsageHistory.bucketExpiryTimesMs.get(newBucket);
                appUsageHistory.bucketExpiryTimesMs.put(newBucket,
                        Math.max(expiryTimeMs, currentExpiryTimeMs));
                removeElapsedExpiryTimes(appUsageHistory, getElapsedTime(nowElapsedRealtimeMs));
            }
        }

        if (nowElapsedRealtimeMs != 0) {
            appUsageHistory.lastUsedElapsedTime = mElapsedDuration
                    + (nowElapsedRealtimeMs - mElapsedSnapshot);
            if (isUserUsage) {
                appUsageHistory.lastUsedByUserElapsedTime = appUsageHistory.lastUsedElapsedTime;
            }
            appUsageHistory.lastUsedScreenTime = getScreenOnTime(nowElapsedRealtimeMs);
        }

        if (appUsageHistory.currentBucket >= newBucket) {
            if (appUsageHistory.currentBucket > newBucket) {
                appUsageHistory.currentBucket = newBucket;
                logAppStandbyBucketChanged(packageName, userId, newBucket, bucketingReason);
            }
            appUsageHistory.bucketingReason = bucketingReason;
        }

        return appUsageHistory;
    }

    private void removeElapsedExpiryTimes(AppUsageHistory appUsageHistory, long elapsedTimeMs) {
        if (appUsageHistory.bucketExpiryTimesMs == null) {
            return;
        }
        for (int i = appUsageHistory.bucketExpiryTimesMs.size() - 1; i >= 0; --i) {
            if (appUsageHistory.bucketExpiryTimesMs.valueAt(i) < elapsedTimeMs) {
                appUsageHistory.bucketExpiryTimesMs.removeAt(i);
            }
        }
    }

    /**
     * Mark the app as used and update the bucket if necessary. If there is a expiry time specified
     * that's in the future, then the usage event is temporary and keeps the app in the specified
     * bucket at least until the expiry time is reached. This can be used to keep the app in an
     * elevated bucket for a while until some important task gets to run.
     *
     * @param packageName package name of the app the usage is reported for
     * @param userId user that the app is running in
     * @param newBucket the bucket to set the app to
     * @param usageReason sub reason for usage
     * @param nowElapsedRealtimeMs mark as used time if non-zero (in
     *                             {@link SystemClock#elapsedRealtime()} time base).
     * @param expiryElapsedRealtimeMs the expiry time for the specified bucket (in
     *                         {@link SystemClock#elapsedRealtime()} time base).
     * @return the {@link AppUsageHistory} corresponding to the {@code packageName}
     *         and {@code userId}.
     */
    public AppUsageHistory reportUsage(String packageName, int userId, int newBucket,
            int usageReason, long nowElapsedRealtimeMs, long expiryElapsedRealtimeMs) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory history = getPackageHistory(userHistory, packageName,
                nowElapsedRealtimeMs, true);
        return reportUsage(history, packageName, userId, newBucket, usageReason,
                nowElapsedRealtimeMs, expiryElapsedRealtimeMs);
    }

    private ArrayMap<String, AppUsageHistory> getUserHistory(int userId) {
        ArrayMap<String, AppUsageHistory> userHistory = mIdleHistory.get(userId);
        if (userHistory == null) {
            userHistory = new ArrayMap<>();
            mIdleHistory.put(userId, userHistory);
            readAppIdleTimes(userId, userHistory);
        }
        return userHistory;
    }

    // TODO (206518483): Remove unused parameter 'elapsedRealtime'.
    private AppUsageHistory getPackageHistory(ArrayMap<String, AppUsageHistory> userHistory,
            String packageName, long elapsedRealtime, boolean create) {
        AppUsageHistory appUsageHistory = userHistory.get(packageName);
        if (appUsageHistory == null && create) {
            appUsageHistory = new AppUsageHistory();
            appUsageHistory.lastUsedByUserElapsedTime = Integer.MIN_VALUE;
            appUsageHistory.lastUsedElapsedTime = Integer.MIN_VALUE;
            appUsageHistory.lastUsedScreenTime = Integer.MIN_VALUE;
            appUsageHistory.lastPredictedTime = Integer.MIN_VALUE;
            appUsageHistory.currentBucket = STANDBY_BUCKET_NEVER;
            appUsageHistory.bucketingReason = REASON_MAIN_DEFAULT;
            appUsageHistory.lastInformedBucket = -1;
            appUsageHistory.lastJobRunTime = Long.MIN_VALUE; // long long time ago
            userHistory.put(packageName, appUsageHistory);
        }
        return appUsageHistory;
    }

    public void onUserRemoved(int userId) {
        mIdleHistory.remove(userId);
    }

    public boolean isIdle(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, true);
        return appUsageHistory.currentBucket >= IDLE_BUCKET_CUTOFF;
    }

    public AppUsageHistory getAppUsageHistory(String packageName, int userId,
            long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, true);
        return appUsageHistory;
    }

    public void setAppStandbyBucket(String packageName, int userId, long elapsedRealtime,
            int bucket, int reason) {
        setAppStandbyBucket(packageName, userId, elapsedRealtime, bucket, reason, false);
    }

    public void setAppStandbyBucket(String packageName, int userId, long elapsedRealtime,
            int bucket, int reason, boolean resetExpiryTimes) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, true);
        final boolean changed = appUsageHistory.currentBucket != bucket;
        appUsageHistory.currentBucket = bucket;
        appUsageHistory.bucketingReason = reason;

        final long elapsed = getElapsedTime(elapsedRealtime);

        if ((reason & REASON_MAIN_MASK) == REASON_MAIN_PREDICTED) {
            appUsageHistory.lastPredictedTime = elapsed;
            appUsageHistory.lastPredictedBucket = bucket;
        }
        if (resetExpiryTimes && appUsageHistory.bucketExpiryTimesMs != null) {
            appUsageHistory.bucketExpiryTimesMs.clear();
        }
        if (changed) {
            logAppStandbyBucketChanged(packageName, userId, bucket, reason);
        }
    }

    /**
     * Update the prediction for the app but don't change the actual bucket
     * @param app The app for which the prediction was made
     * @param elapsedTimeAdjusted The elapsed time in the elapsed duration timebase
     * @param bucket The predicted bucket
     */
    public void updateLastPrediction(AppUsageHistory app, long elapsedTimeAdjusted, int bucket) {
        app.lastPredictedTime = elapsedTimeAdjusted;
        app.lastPredictedBucket = bucket;
    }

    /**
     * Marks the next time the app is expected to be launched, in the current millis timebase.
     */
    public void setEstimatedLaunchTime(String packageName, int userId,
            @ElapsedRealtimeLong long nowElapsed, @CurrentTimeMillisLong long launchTime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, nowElapsed, true);
        appUsageHistory.nextEstimatedLaunchTime = launchTime;
    }

    /**
     * Marks the last time a job was run, with the given elapsedRealtime. The time stored is
     * based on the elapsed timebase.
     * @param packageName
     * @param userId
     * @param elapsedRealtime
     */
    public void setLastJobRunTime(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, true);
        appUsageHistory.lastJobRunTime = getElapsedTime(elapsedRealtime);
    }

    /**
     * Notes an attempt to put the app in the {@link UsageStatsManager#STANDBY_BUCKET_RESTRICTED}
     * bucket.
     *
     * @param packageName     The package name of the app that is being restricted
     * @param userId          The ID of the user in which the app is being restricted
     * @param elapsedRealtime The time the attempt was made, in the (unadjusted) elapsed realtime
     *                        timebase
     * @param reason          The reason for the restriction attempt
     */
    void noteRestrictionAttempt(String packageName, int userId, long elapsedRealtime, int reason) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, true);
        appUsageHistory.lastRestrictAttemptElapsedTime = getElapsedTime(elapsedRealtime);
        appUsageHistory.lastRestrictReason = reason;
    }

    /**
     * Returns the next estimated launch time of this app. Will return {@link Long#MAX_VALUE} if
     * there's no estimated time.
     */
    @CurrentTimeMillisLong
    public long getEstimatedLaunchTime(String packageName, int userId, long nowElapsed) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, nowElapsed, false);
        // Don't adjust the default, else it'll wrap around to a positive value
        if (appUsageHistory == null
                || appUsageHistory.nextEstimatedLaunchTime < System.currentTimeMillis()) {
            return Long.MAX_VALUE;
        }
        return appUsageHistory.nextEstimatedLaunchTime;
    }

    /**
     * Returns the time since the last job was run for this app. This can be larger than the
     * current elapsedRealtime, in case it happened before boot or a really large value if no jobs
     * were ever run.
     * @param packageName
     * @param userId
     * @param elapsedRealtime
     * @return
     */
    public long getTimeSinceLastJobRun(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, false);
        // Don't adjust the default, else it'll wrap around to a positive value
        if (appUsageHistory == null || appUsageHistory.lastJobRunTime == Long.MIN_VALUE) {
            return Long.MAX_VALUE;
        }
        return getElapsedTime(elapsedRealtime) - appUsageHistory.lastJobRunTime;
    }

    public long getTimeSinceLastUsedByUser(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, false);
        if (appUsageHistory == null || appUsageHistory.lastUsedByUserElapsedTime == Long.MIN_VALUE
                || appUsageHistory.lastUsedByUserElapsedTime <= 0) {
            return Long.MAX_VALUE;
        }
        return getElapsedTime(elapsedRealtime) - appUsageHistory.lastUsedByUserElapsedTime;
    }

    public int getAppStandbyBucket(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, false);
        return appUsageHistory == null ? STANDBY_BUCKET_NEVER : appUsageHistory.currentBucket;
    }

    public ArrayList<AppStandbyInfo> getAppStandbyBuckets(int userId, boolean appIdleEnabled) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        int size = userHistory.size();
        ArrayList<AppStandbyInfo> buckets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            buckets.add(new AppStandbyInfo(userHistory.keyAt(i),
                    appIdleEnabled ? userHistory.valueAt(i).currentBucket : STANDBY_BUCKET_ACTIVE));
        }
        return buckets;
    }

    public int getAppStandbyReason(String packageName, int userId, long elapsedRealtime) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory =
                getPackageHistory(userHistory, packageName, elapsedRealtime, false);
        return appUsageHistory != null ? appUsageHistory.bucketingReason : 0;
    }

    public long getElapsedTime(long elapsedRealtime) {
        return (elapsedRealtime - mElapsedSnapshot + mElapsedDuration);
    }

    /* Returns the new standby bucket the app is assigned to */
    public int setIdle(String packageName, int userId, boolean idle, long elapsedRealtime) {
        final int newBucket;
        final int reason;
        if (idle) {
            newBucket = IDLE_BUCKET_CUTOFF;
            reason = REASON_MAIN_FORCED_BY_USER;
            final AppUsageHistory appHistory = getAppUsageHistory(packageName, userId,
                    elapsedRealtime);
            // Wipe all expiry times that could raise the bucket on reevaluation.
            if (appHistory.bucketExpiryTimesMs != null) {
                for (int i = appHistory.bucketExpiryTimesMs.size() - 1; i >= 0; --i) {
                    if (appHistory.bucketExpiryTimesMs.keyAt(i) < newBucket) {
                        appHistory.bucketExpiryTimesMs.removeAt(i);
                    }
                }
            }
        } else {
            newBucket = STANDBY_BUCKET_ACTIVE;
            // This is to pretend that the app was just used, don't freeze the state anymore.
            reason = REASON_MAIN_USAGE | REASON_SUB_USAGE_USER_INTERACTION;
        }
        setAppStandbyBucket(packageName, userId, elapsedRealtime, newBucket, reason, false);

        return newBucket;
    }

    public void clearUsage(String packageName, int userId) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        userHistory.remove(packageName);
    }

    boolean shouldInformListeners(String packageName, int userId,
            long elapsedRealtime, int bucket) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory = getPackageHistory(userHistory, packageName,
                elapsedRealtime, true);
        if (appUsageHistory.lastInformedBucket != bucket) {
            appUsageHistory.lastInformedBucket = bucket;
            return true;
        }
        return false;
    }

    /**
     * Returns the index in the arrays of screenTimeThresholds and elapsedTimeThresholds
     * that corresponds to how long since the app was used.
     * @param packageName
     * @param userId
     * @param elapsedRealtime current time
     * @param screenTimeThresholds Array of screen times, in ascending order, first one is 0
     * @param elapsedTimeThresholds Array of elapsed time, in ascending order, first one is 0
     * @return The index whose values the app's used time exceeds (in both arrays) or {@code -1} to
     *         indicate that the app has never been used.
     */
    int getThresholdIndex(String packageName, int userId, long elapsedRealtime,
            long[] screenTimeThresholds, long[] elapsedTimeThresholds) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory = getPackageHistory(userHistory, packageName,
                elapsedRealtime, false);
        // If we don't have any state for the app, assume never used
        if (appUsageHistory == null || appUsageHistory.lastUsedElapsedTime < 0
                || appUsageHistory.lastUsedScreenTime < 0) {
            return -1;
        }

        long screenOnDelta = getScreenOnTime(elapsedRealtime) - appUsageHistory.lastUsedScreenTime;
        long elapsedDelta = getElapsedTime(elapsedRealtime) - appUsageHistory.lastUsedElapsedTime;

        if (DEBUG) Slog.d(TAG, packageName
                + " lastUsedScreen=" + appUsageHistory.lastUsedScreenTime
                + " lastUsedElapsed=" + appUsageHistory.lastUsedElapsedTime);
        if (DEBUG) Slog.d(TAG, packageName + " screenOn=" + screenOnDelta
                + ", elapsed=" + elapsedDelta);
        for (int i = screenTimeThresholds.length - 1; i >= 0; i--) {
            if (screenOnDelta >= screenTimeThresholds[i]
                && elapsedDelta >= elapsedTimeThresholds[i]) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Log a standby bucket change to statsd, and also logcat if debug logging is enabled.
     */
    private void logAppStandbyBucketChanged(String packageName, int userId, int bucket,
            int reason) {
        FrameworkStatsLog.write(
                FrameworkStatsLog.APP_STANDBY_BUCKET_CHANGED,
                packageName, userId, bucket,
                (reason & REASON_MAIN_MASK), (reason & REASON_SUB_MASK));
        if (DEBUG) {
            Slog.d(TAG, "Moved " + packageName + " to bucket=" + bucket
                    + ", reason=0x0" + Integer.toHexString(reason));
        }
    }

    @VisibleForTesting
    long getBucketExpiryTimeMs(String packageName, int userId, int bucket, long elapsedRealtimeMs) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory = getPackageHistory(userHistory, packageName,
                elapsedRealtimeMs, false /* create */);
        if (appUsageHistory == null || appUsageHistory.bucketExpiryTimesMs == null) {
            return 0;
        }
        return appUsageHistory.bucketExpiryTimesMs.get(bucket, 0);
    }

    @VisibleForTesting
    File getUserFile(int userId) {
        return new File(new File(new File(mStorageDir, "users"),
                Integer.toString(userId)), APP_IDLE_FILENAME);
    }

    void clearLastUsedTimestamps(String packageName, int userId) {
        ArrayMap<String, AppUsageHistory> userHistory = getUserHistory(userId);
        AppUsageHistory appUsageHistory = getPackageHistory(userHistory, packageName,
                SystemClock.elapsedRealtime(), false /* create */);
        if (appUsageHistory != null) {
            appUsageHistory.lastUsedByUserElapsedTime = Integer.MIN_VALUE;
            appUsageHistory.lastUsedElapsedTime = Integer.MIN_VALUE;
            appUsageHistory.lastUsedScreenTime = Integer.MIN_VALUE;
        }
    }

    /**
     * Check if App Idle File exists on disk
     * @param userId
     * @return true if file exists
     */
    public boolean userFileExists(int userId) {
        return getUserFile(userId).exists();
    }

    private void readAppIdleTimes(int userId, ArrayMap<String, AppUsageHistory> userHistory) {
        FileInputStream fis = null;
        try {
            AtomicFile appIdleFile = new AtomicFile(getUserFile(userId));
            fis = appIdleFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());

            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                // Skip
            }

            if (type != XmlPullParser.START_TAG) {
                Slog.e(TAG, "Unable to read app idle file for user " + userId);
                return;
            }
            if (!parser.getName().equals(TAG_PACKAGES)) {
                return;
            }
            final int version = getIntValue(parser, ATTR_VERSION, XML_VERSION_INITIAL);
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    final String name = parser.getName();
                    if (name.equals(TAG_PACKAGE)) {
                        final String packageName = parser.getAttributeValue(null, ATTR_NAME);
                        AppUsageHistory appUsageHistory = new AppUsageHistory();
                        appUsageHistory.lastUsedElapsedTime =
                                Long.parseLong(parser.getAttributeValue(null, ATTR_ELAPSED_IDLE));
                        appUsageHistory.lastUsedByUserElapsedTime = getLongValue(parser,
                                ATTR_LAST_USED_BY_USER_ELAPSED,
                                appUsageHistory.lastUsedElapsedTime);
                        appUsageHistory.lastUsedScreenTime =
                                Long.parseLong(parser.getAttributeValue(null, ATTR_SCREEN_IDLE));
                        appUsageHistory.lastPredictedTime = getLongValue(parser,
                                ATTR_LAST_PREDICTED_TIME, 0L);
                        String currentBucketString = parser.getAttributeValue(null,
                                ATTR_CURRENT_BUCKET);
                        appUsageHistory.currentBucket = currentBucketString == null
                                ? STANDBY_BUCKET_ACTIVE
                                : Integer.parseInt(currentBucketString);
                        String bucketingReason =
                                parser.getAttributeValue(null, ATTR_BUCKETING_REASON);
                        appUsageHistory.lastJobRunTime = getLongValue(parser,
                                ATTR_LAST_RUN_JOB_TIME, Long.MIN_VALUE);
                        appUsageHistory.bucketingReason = REASON_MAIN_DEFAULT;
                        if (bucketingReason != null) {
                            try {
                                appUsageHistory.bucketingReason =
                                        Integer.parseInt(bucketingReason, 16);
                            } catch (NumberFormatException nfe) {
                                Slog.wtf(TAG, "Unable to read bucketing reason", nfe);
                            }
                        }
                        appUsageHistory.lastRestrictAttemptElapsedTime =
                                getLongValue(parser, ATTR_LAST_RESTRICTION_ATTEMPT_ELAPSED, 0);
                        String lastRestrictReason = parser.getAttributeValue(
                                null, ATTR_LAST_RESTRICTION_ATTEMPT_REASON);
                        if (lastRestrictReason != null) {
                            try {
                                appUsageHistory.lastRestrictReason =
                                        Integer.parseInt(lastRestrictReason, 16);
                            } catch (NumberFormatException nfe) {
                                Slog.wtf(TAG, "Unable to read last restrict reason", nfe);
                            }
                        }
                        appUsageHistory.nextEstimatedLaunchTime = getLongValue(parser,
                                ATTR_NEXT_ESTIMATED_APP_LAUNCH_TIME, 0);
                        if (Flags.avoidIdleCheck()) {
                            // Set lastInformedBucket to the same value with the currentBucket
                            // it should have already been informed.
                            appUsageHistory.lastInformedBucket = appUsageHistory.currentBucket;
                        } else {
                            appUsageHistory.lastInformedBucket = -1;
                        }
                        userHistory.put(packageName, appUsageHistory);

                        if (version >= XML_VERSION_ADD_BUCKET_EXPIRY_TIMES) {
                            final int outerDepth = parser.getDepth();
                            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                                if (TAG_BUCKET_EXPIRY_TIMES.equals(parser.getName())) {
                                    readBucketExpiryTimes(parser, appUsageHistory);
                                }
                            }
                        } else {
                            final long bucketActiveTimeoutTime = getLongValue(parser,
                                    ATTR_BUCKET_ACTIVE_TIMEOUT_TIME, 0L);
                            final long bucketWorkingSetTimeoutTime = getLongValue(parser,
                                    ATTR_BUCKET_WORKING_SET_TIMEOUT_TIME, 0L);
                            if (bucketActiveTimeoutTime != 0 || bucketWorkingSetTimeoutTime != 0) {
                                insertBucketExpiryTime(appUsageHistory,
                                        STANDBY_BUCKET_ACTIVE, bucketActiveTimeoutTime);
                                insertBucketExpiryTime(appUsageHistory,
                                        STANDBY_BUCKET_WORKING_SET, bucketWorkingSetTimeoutTime);
                            }
                        }
                    }
                }
            }
        } catch (FileNotFoundException e) {
            // Expected on first boot
            Slog.d(TAG, "App idle file for user " + userId + " does not exist");
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Unable to read app idle file for user " + userId, e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    private void readBucketExpiryTimes(XmlPullParser parser, AppUsageHistory appUsageHistory)
            throws IOException, XmlPullParserException {
        final int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            if (TAG_ITEM.equals(parser.getName())) {
                final int bucket = getIntValue(parser, ATTR_BUCKET, STANDBY_BUCKET_UNKNOWN);
                if (bucket == STANDBY_BUCKET_UNKNOWN) {
                    Slog.e(TAG, "Error reading the buckets expiry times");
                    continue;
                }
                final long expiryTimeMs = getLongValue(parser, ATTR_EXPIRY_TIME, 0 /* default */);
                insertBucketExpiryTime(appUsageHistory, bucket, expiryTimeMs);
            }
        }
    }

    private void insertBucketExpiryTime(AppUsageHistory appUsageHistory,
            int bucket, long expiryTimeMs) {
        if (expiryTimeMs == 0) {
            return;
        }
        if (appUsageHistory.bucketExpiryTimesMs == null) {
            appUsageHistory.bucketExpiryTimesMs = new SparseLongArray();
        }
        appUsageHistory.bucketExpiryTimesMs.put(bucket, expiryTimeMs);
    }

    private long getLongValue(XmlPullParser parser, String attrName, long defValue) {
        String value = parser.getAttributeValue(null, attrName);
        if (value == null) return defValue;
        return Long.parseLong(value);
    }

    private int getIntValue(XmlPullParser parser, String attrName, int defValue) {
        String value = parser.getAttributeValue(null, attrName);
        if (value == null) return defValue;
        return Integer.parseInt(value);
    }

    public void writeAppIdleTimes(long elapsedRealtimeMs) {
        final int size = mIdleHistory.size();
        for (int i = 0; i < size; i++) {
            writeAppIdleTimes(mIdleHistory.keyAt(i), elapsedRealtimeMs);
        }
    }

    public void writeAppIdleTimes(int userId, long elapsedRealtimeMs) {
        FileOutputStream fos = null;
        AtomicFile appIdleFile = new AtomicFile(getUserFile(userId));
        try {
            fos = appIdleFile.startWrite();
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            FastXmlSerializer xml = new FastXmlSerializer();
            xml.setOutput(bos, StandardCharsets.UTF_8.name());
            xml.startDocument(null, true);
            xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            xml.startTag(null, TAG_PACKAGES);
            xml.attribute(null, ATTR_VERSION, String.valueOf(XML_VERSION_CURRENT));

            final long elapsedTimeMs = getElapsedTime(elapsedRealtimeMs);
            ArrayMap<String,AppUsageHistory> userHistory = getUserHistory(userId);
            final int N = userHistory.size();
            for (int i = 0; i < N; i++) {
                String packageName = userHistory.keyAt(i);
                // Skip any unexpected null package names
                if (packageName == null) {
                    Slog.w(TAG, "Skipping App Idle write for unexpected null package");
                    continue;
                }
                AppUsageHistory history = userHistory.valueAt(i);
                xml.startTag(null, TAG_PACKAGE);
                xml.attribute(null, ATTR_NAME, packageName);
                xml.attribute(null, ATTR_ELAPSED_IDLE,
                        Long.toString(history.lastUsedElapsedTime));
                xml.attribute(null, ATTR_LAST_USED_BY_USER_ELAPSED,
                        Long.toString(history.lastUsedByUserElapsedTime));
                xml.attribute(null, ATTR_SCREEN_IDLE,
                        Long.toString(history.lastUsedScreenTime));
                xml.attribute(null, ATTR_LAST_PREDICTED_TIME,
                        Long.toString(history.lastPredictedTime));
                xml.attribute(null, ATTR_CURRENT_BUCKET,
                        Integer.toString(history.currentBucket));
                xml.attribute(null, ATTR_BUCKETING_REASON,
                        Integer.toHexString(history.bucketingReason));
                if (history.lastJobRunTime != Long.MIN_VALUE) {
                    xml.attribute(null, ATTR_LAST_RUN_JOB_TIME, Long.toString(history
                            .lastJobRunTime));
                }
                if (history.lastRestrictAttemptElapsedTime > 0) {
                    xml.attribute(null, ATTR_LAST_RESTRICTION_ATTEMPT_ELAPSED,
                            Long.toString(history.lastRestrictAttemptElapsedTime));
                }
                xml.attribute(null, ATTR_LAST_RESTRICTION_ATTEMPT_REASON,
                        Integer.toHexString(history.lastRestrictReason));
                if (history.nextEstimatedLaunchTime > 0) {
                    xml.attribute(null, ATTR_NEXT_ESTIMATED_APP_LAUNCH_TIME,
                            Long.toString(history.nextEstimatedLaunchTime));
                }
                if (history.bucketExpiryTimesMs != null) {
                    xml.startTag(null, TAG_BUCKET_EXPIRY_TIMES);
                    final int size = history.bucketExpiryTimesMs.size();
                    for (int j = 0; j < size; ++j) {
                        final long expiryTimeMs = history.bucketExpiryTimesMs.valueAt(j);
                        // Skip writing to disk if the expiry time already elapsed.
                        if (expiryTimeMs < elapsedTimeMs) {
                            continue;
                        }
                        final int bucket = history.bucketExpiryTimesMs.keyAt(j);
                        xml.startTag(null, TAG_ITEM);
                        xml.attribute(null, ATTR_BUCKET, String.valueOf(bucket));
                        xml.attribute(null, ATTR_EXPIRY_TIME, String.valueOf(expiryTimeMs));
                        xml.endTag(null, TAG_ITEM);
                    }
                    xml.endTag(null, TAG_BUCKET_EXPIRY_TIMES);
                }
                xml.endTag(null, TAG_PACKAGE);
            }

            xml.endTag(null, TAG_PACKAGES);
            xml.endDocument();
            appIdleFile.finishWrite(fos);
        } catch (Exception e) {
            appIdleFile.failWrite(fos);
            Slog.e(TAG, "Error writing app idle file for user " + userId, e);
        }
    }

    public void dumpUsers(IndentingPrintWriter idpw, int[] userIds, List<String> pkgs) {
        final int numUsers = userIds.length;
        for (int i = 0; i < numUsers; i++) {
            idpw.println();
            dumpUser(idpw, userIds[i], pkgs);
        }
    }

    private void dumpUser(IndentingPrintWriter idpw, int userId, List<String> pkgs) {
        idpw.print("User ");
        idpw.print(userId);
        idpw.println(" App Standby States:");
        idpw.increaseIndent();
        ArrayMap<String, AppUsageHistory> userHistory = mIdleHistory.get(userId);
        final long now = System.currentTimeMillis();
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        final long totalElapsedTime = getElapsedTime(elapsedRealtime);
        final long screenOnTime = getScreenOnTime(elapsedRealtime);
        if (userHistory == null) return;
        final int P = userHistory.size();
        for (int p = 0; p < P; p++) {
            final String packageName = userHistory.keyAt(p);
            final AppUsageHistory appUsageHistory = userHistory.valueAt(p);
            if (!CollectionUtils.isEmpty(pkgs) && !pkgs.contains(packageName)) {
                continue;
            }
            idpw.print("package=" + packageName);
            idpw.print(" u=" + userId);
            idpw.print(" bucket=" + appUsageHistory.currentBucket
                    + " reason="
                    + UsageStatsManager.reasonToString(appUsageHistory.bucketingReason));
            idpw.print(" used=");
            printLastActionElapsedTime(idpw, totalElapsedTime, appUsageHistory.lastUsedElapsedTime);
            idpw.print(" usedByUser=");
            printLastActionElapsedTime(idpw, totalElapsedTime,
                    appUsageHistory.lastUsedByUserElapsedTime);
            idpw.print(" usedScr=");
            printLastActionElapsedTime(idpw, totalElapsedTime, appUsageHistory.lastUsedScreenTime);
            idpw.print(" lastPred=");
            printLastActionElapsedTime(idpw, totalElapsedTime, appUsageHistory.lastPredictedTime);
            dumpBucketExpiryTimes(idpw, appUsageHistory, totalElapsedTime);
            idpw.print(" lastJob=");
            TimeUtils.formatDuration(totalElapsedTime - appUsageHistory.lastJobRunTime, idpw);
            idpw.print(" lastInformedBucket=" + appUsageHistory.lastInformedBucket);
            if (appUsageHistory.lastRestrictAttemptElapsedTime > 0) {
                idpw.print(" lastRestrictAttempt=");
                TimeUtils.formatDuration(
                        totalElapsedTime - appUsageHistory.lastRestrictAttemptElapsedTime, idpw);
                idpw.print(" lastRestrictReason="
                        + UsageStatsManager.reasonToString(appUsageHistory.lastRestrictReason));
            }
            if (appUsageHistory.nextEstimatedLaunchTime > 0) {
                idpw.print(" nextEstimatedLaunchTime=");
                TimeUtils.formatDuration(appUsageHistory.nextEstimatedLaunchTime - now, idpw);
            }
            idpw.print(" idle=" + (isIdle(packageName, userId, elapsedRealtime) ? "y" : "n"));
            idpw.println();
        }
        idpw.println();
        idpw.print("totalElapsedTime=");
        TimeUtils.formatDuration(getElapsedTime(elapsedRealtime), idpw);
        idpw.println();
        idpw.print("totalScreenOnTime=");
        TimeUtils.formatDuration(getScreenOnTime(elapsedRealtime), idpw);
        idpw.println();
        idpw.decreaseIndent();
    }

    private void printLastActionElapsedTime(IndentingPrintWriter idpw, long totalElapsedTimeMS,
            long lastActionTimeMs) {
        if (lastActionTimeMs < 0) {
            idpw.print("<uninitialized>");
        } else {
            TimeUtils.formatDuration(totalElapsedTimeMS - lastActionTimeMs, idpw);
        }
    }

    private void dumpBucketExpiryTimes(IndentingPrintWriter idpw, AppUsageHistory appUsageHistory,
            long totalElapsedTimeMs) {
        idpw.print(" expiryTimes=");
        if (appUsageHistory.bucketExpiryTimesMs == null
                || appUsageHistory.bucketExpiryTimesMs.size() == 0) {
            idpw.print("<none>");
            return;
        }
        idpw.print("(");
        final int size = appUsageHistory.bucketExpiryTimesMs.size();
        for (int i = 0; i < size; ++i) {
            final int bucket = appUsageHistory.bucketExpiryTimesMs.keyAt(i);
            final long expiryTimeMs = appUsageHistory.bucketExpiryTimesMs.valueAt(i);
            if (i != 0) {
                idpw.print(",");
            }
            idpw.print(bucket + ":");
            TimeUtils.formatDuration(totalElapsedTimeMs - expiryTimeMs, idpw);
        }
        idpw.print(")");
    }
}
