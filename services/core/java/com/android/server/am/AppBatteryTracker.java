/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.am;

import static android.Manifest.permission.ACCESS_BACKGROUND_LOCATION;
import static android.app.ActivityManager.RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
import static android.app.ActivityManager.RESTRICTION_LEVEL_BACKGROUND_RESTRICTED;
import static android.app.ActivityManager.RESTRICTION_LEVEL_RESTRICTED_BUCKET;
import static android.app.ActivityManager.RESTRICTION_LEVEL_UNKNOWN;
import static android.app.ActivityManager.isLowRamDeviceStatic;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_SYSTEM;
import static android.app.usage.UsageStatsManager.REASON_MAIN_USAGE;
import static android.app.usage.UsageStatsManager.REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_USER_INTERACTION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.BatteryConsumer.POWER_COMPONENT_ANY;
import static android.os.BatteryConsumer.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_CACHED;
import static android.os.BatteryConsumer.PROCESS_STATE_COUNT;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.os.BatteryConsumer.PROCESS_STATE_UNSPECIFIED;
import static android.os.PowerExemptionManager.REASON_DENIED;
import static android.util.TimeUtils.formatTime;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.AppRestrictionController.DEVICE_CONFIG_SUBNAMESPACE_PREFIX;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager.RestrictionLevel;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.AppBackgroundRestrictionsInfo;
import android.os.AppBatteryStatsProto;
import android.os.BatteryConsumer;
import android.os.BatteryConsumer.Dimensions;
import android.os.BatteryStatsInternal;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.PowerExemptionManager;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.SystemClock;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.am.AppBatteryTracker.AppBatteryPolicy;
import com.android.server.am.AppRestrictionController.UidBatteryUsageProvider;
import com.android.server.pm.UserManagerInternal;

import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

/**
 * The battery usage tracker for apps, currently we are focusing on background + FGS battery here.
 */
final class AppBatteryTracker extends BaseAppStateTracker<AppBatteryPolicy>
        implements UidBatteryUsageProvider {
    static final String TAG = TAG_WITH_CLASS_NAME ? "AppBatteryTracker" : TAG_AM;

    static final boolean DEBUG_BACKGROUND_BATTERY_TRACKER = false;

    // As we don't support realtime per-UID battery usage stats yet, we're polling the stats
    // in a regular time basis.
    private final long mBatteryUsageStatsPollingIntervalMs;

    static final long BATTERY_USAGE_STATS_POLLING_INTERVAL_MS_LONG = 30 * ONE_MINUTE; // 30 mins
    static final long BATTERY_USAGE_STATS_POLLING_INTERVAL_MS_DEBUG = 2_000L; // 2s

    private final long mBatteryUsageStatsPollingMinIntervalMs;

    /**
     * The battery stats query is expensive, so we'd throttle the query.
     */
    static final long BATTERY_USAGE_STATS_POLLING_MIN_INTERVAL_MS_LONG = 5 * ONE_MINUTE; // 5 mins
    static final long BATTERY_USAGE_STATS_POLLING_MIN_INTERVAL_MS_DEBUG = 2_000L; // 2s

    static final ImmutableBatteryUsage BATTERY_USAGE_NONE = new ImmutableBatteryUsage();

    private final Runnable mBgBatteryUsageStatsPolling = this::updateBatteryUsageStatsAndCheck;
    private final Runnable mBgBatteryUsageStatsCheck = this::checkBatteryUsageStats;

    /**
     * This tracks the user ids which are or were active during the last polling window,
     * the index is the user id, and the value is if it's still running or not by now.
     */
    @GuardedBy("mLock")
    private final SparseBooleanArray mActiveUserIdStates = new SparseBooleanArray();

    /**
     * When was the last battery usage sampled.
     */
    @GuardedBy("mLock")
    private long mLastBatteryUsageSamplingTs;

    /**
     * Whether or not there is an ongoing battery stats update.
     */
    @GuardedBy("mLock")
    private boolean mBatteryUsageStatsUpdatePending;

    /**
     * The current known battery usage data for each UID, since the system boots or
     * the last battery stats reset prior to that (whoever is earlier).
     */
    @GuardedBy("mLock")
    private final SparseArray<BatteryUsage> mUidBatteryUsage = new SparseArray<>();

    /**
     * The battery usage for each UID, in the rolling window of the past.
     */
    @GuardedBy("mLock")
    private final SparseArray<ImmutableBatteryUsage> mUidBatteryUsageInWindow = new SparseArray<>();

    /**
     * The uid battery usage stats data from our last query, it consists of the data since
     * last battery stats reset.
     */
    @GuardedBy("mLock")
    private final SparseArray<ImmutableBatteryUsage> mLastUidBatteryUsage = new SparseArray<>();

    // No lock is needed.
    private final SparseArray<BatteryUsage> mTmpUidBatteryUsage = new SparseArray<>();

    // No lock is needed.
    private final SparseArray<ImmutableBatteryUsage> mTmpUidBatteryUsage2 = new SparseArray<>();

    // No lock is needed.
    private final SparseArray<ImmutableBatteryUsage> mTmpUidBatteryUsageInWindow =
            new SparseArray<>();

    // No lock is needed.
    private final ArraySet<UserHandle> mTmpUserIds = new ArraySet<>();

    /**
     * The start timestamp of the battery usage stats result from our last query.
     */
    @GuardedBy("mLock")
    private long mLastUidBatteryUsageStartTs;

    /**
     * elapseRealTime of last time the AppBatteryTracker is reported to statsd.
     */
    @GuardedBy("mLock")
    private long mLastReportTime = 0;

    // For debug only.
    private final SparseArray<BatteryUsage> mDebugUidPercentages = new SparseArray<>();

    AppBatteryTracker(Context context, AppRestrictionController controller) {
        this(context, controller, null, null);
    }

    AppBatteryTracker(Context context, AppRestrictionController controller,
            Constructor<? extends Injector<AppBatteryPolicy>> injector,
            Object outerContext) {
        super(context, controller, injector, outerContext);
        if (injector == null) {
            mBatteryUsageStatsPollingIntervalMs = DEBUG_BACKGROUND_BATTERY_TRACKER
                    ? BATTERY_USAGE_STATS_POLLING_INTERVAL_MS_DEBUG
                    : BATTERY_USAGE_STATS_POLLING_INTERVAL_MS_LONG;
            mBatteryUsageStatsPollingMinIntervalMs = DEBUG_BACKGROUND_BATTERY_TRACKER
                    ? BATTERY_USAGE_STATS_POLLING_MIN_INTERVAL_MS_DEBUG
                    : BATTERY_USAGE_STATS_POLLING_MIN_INTERVAL_MS_LONG;
        } else {
            mBatteryUsageStatsPollingIntervalMs = BATTERY_USAGE_STATS_POLLING_INTERVAL_MS_DEBUG;
            mBatteryUsageStatsPollingMinIntervalMs =
                    BATTERY_USAGE_STATS_POLLING_MIN_INTERVAL_MS_DEBUG;
        }
        mInjector.setPolicy(new AppBatteryPolicy(mInjector, this));
    }

    @Override
    void onSystemReady() {
        super.onSystemReady();
        final UserManagerInternal um = mInjector.getUserManagerInternal();
        final int[] userIds = um.getUserIds();
        for (int userId : userIds) {
            if (um.isUserRunning(userId)) {
                synchronized (mLock) {
                    mActiveUserIdStates.put(userId, true);
                }
            }
        }
        scheduleBatteryUsageStatsUpdateIfNecessary(mBatteryUsageStatsPollingIntervalMs);
    }

    private void scheduleBatteryUsageStatsUpdateIfNecessary(long delay) {
        if (mInjector.getPolicy().isEnabled()) {
            synchronized (mLock) {
                if (!mBgHandler.hasCallbacks(mBgBatteryUsageStatsPolling)) {
                    mBgHandler.postDelayed(mBgBatteryUsageStatsPolling, delay);
                }
            }
            logAppBatteryTrackerIfNeeded();
        }
    }

    /**
     * Log per-uid BatteryTrackerInfo to statsd every 24 hours (as the window specified in
     * {@link AppBatteryPolicy#mBgCurrentDrainWindowMs})
     */
    private void logAppBatteryTrackerIfNeeded() {
        final long now = SystemClock.elapsedRealtime();
        synchronized (mLock) {
            final AppBatteryPolicy bgPolicy = mInjector.getPolicy();
            if (now - mLastReportTime < bgPolicy.mBgCurrentDrainWindowMs) {
                return;
            } else {
                mLastReportTime = now;
            }
        }
        updateBatteryUsageStatsIfNecessary(mInjector.currentTimeMillis(), true);
        synchronized (mLock) {
            for (int i = 0, size = mUidBatteryUsageInWindow.size(); i < size; i++) {
                final int uid = mUidBatteryUsageInWindow.keyAt(i);
                if (!UserHandle.isCore(uid) && !UserHandle.isApp(uid)) {
                    continue;
                }
                if (BATTERY_USAGE_NONE.equals(mUidBatteryUsageInWindow.valueAt(i))) {
                    continue;
                }
                FrameworkStatsLog.write(FrameworkStatsLog.APP_BACKGROUND_RESTRICTIONS_INFO,
                        uid,
                        FrameworkStatsLog
                                .APP_BACKGROUND_RESTRICTIONS_INFO__RESTRICTION_LEVEL__LEVEL_UNKNOWN,
                        FrameworkStatsLog
                                .APP_BACKGROUND_RESTRICTIONS_INFO__THRESHOLD__THRESHOLD_UNKNOWN,
                        FrameworkStatsLog
                                .APP_BACKGROUND_RESTRICTIONS_INFO__TRACKER__UNKNOWN_TRACKER,
                        null /*byte[] fgs_tracker_info*/,
                        getBatteryTrackerInfoProtoLocked(uid) /*byte[] battery_tracker_info*/,
                        null /*byte[] broadcast_events_tracker_info*/,
                        null /*byte[] bind_service_events_tracker_info*/,
                        FrameworkStatsLog
                                .APP_BACKGROUND_RESTRICTIONS_INFO__EXEMPTION_REASON__REASON_UNKNOWN,
                        FrameworkStatsLog
                                .APP_BACKGROUND_RESTRICTIONS_INFO__OPT_LEVEL__UNKNOWN,
                        FrameworkStatsLog
                                .APP_BACKGROUND_RESTRICTIONS_INFO__TARGET_SDK__SDK_UNKNOWN,
                        isLowRamDeviceStatic());
            }
        }
    }

    /**
     * Get the BatteryTrackerInfo proto of a UID.
     * @param uid
     * @return byte array of the proto.
     */
     @NonNull byte[] getBatteryTrackerInfoProtoLocked(int uid) {
        final ImmutableBatteryUsage temp = mUidBatteryUsageInWindow.get(uid);
        if (temp == null) {
            return new byte[0];
        }
        final BatteryUsage bgUsage = temp.calcPercentage(uid, mInjector.getPolicy());
        final double allUsage = bgUsage.mPercentage[BatteryUsage.BATTERY_USAGE_INDEX_UNSPECIFIED]
                + bgUsage.mPercentage[BatteryUsage.BATTERY_USAGE_INDEX_FOREGROUND]
                + bgUsage.mPercentage[BatteryUsage.BATTERY_USAGE_INDEX_BACKGROUND]
                + bgUsage.mPercentage[BatteryUsage.BATTERY_USAGE_INDEX_FOREGROUND_SERVICE]
                + bgUsage.mPercentage[BatteryUsage.BATTERY_USAGE_INDEX_CACHED];
        final double usageBackground =
                bgUsage.mPercentage[BatteryUsage.BATTERY_USAGE_INDEX_BACKGROUND];
        final double usageFgs =
                bgUsage.mPercentage[BatteryUsage.BATTERY_USAGE_INDEX_FOREGROUND_SERVICE];
        Slog.d(TAG, "getBatteryTrackerInfoProtoLocked uid:" + uid
                + " allUsage:" + String.format("%4.2f%%", allUsage)
                + " usageBackground:" + String.format("%4.2f%%", usageBackground)
                + " usageFgs:" + String.format("%4.2f%%", usageFgs));
        final ProtoOutputStream proto = new ProtoOutputStream();
        proto.write(AppBackgroundRestrictionsInfo.BatteryTrackerInfo.BATTERY_24H,
                allUsage * 10000);
        proto.write(AppBackgroundRestrictionsInfo.BatteryTrackerInfo.BATTERY_USAGE_BACKGROUND,
                usageBackground * 10000);
        proto.write(AppBackgroundRestrictionsInfo.BatteryTrackerInfo.BATTERY_USAGE_FGS,
                usageFgs * 10000);
        proto.flush();
        return proto.getBytes();
    }

    @Override
    void onUserStarted(final @UserIdInt int userId) {
        synchronized (mLock) {
            mActiveUserIdStates.put(userId, true);
        }
    }

    @Override
    void onUserStopped(final @UserIdInt int userId) {
        synchronized (mLock) {
            mActiveUserIdStates.put(userId, false);
        }
    }

    @Override
    void onUserRemoved(final @UserIdInt int userId) {
        synchronized (mLock) {
            mActiveUserIdStates.delete(userId);
            for (int i = mUidBatteryUsage.size() - 1; i >= 0; i--) {
                if (UserHandle.getUserId(mUidBatteryUsage.keyAt(i)) == userId) {
                    mUidBatteryUsage.removeAt(i);
                }
            }
            for (int i = mUidBatteryUsageInWindow.size() - 1; i >= 0; i--) {
                if (UserHandle.getUserId(mUidBatteryUsageInWindow.keyAt(i)) == userId) {
                    mUidBatteryUsageInWindow.removeAt(i);
                }
            }
        }
    }

    @Override
    void onUidRemoved(final int uid) {
        synchronized (mLock) {
            mUidBatteryUsage.delete(uid);
            mUidBatteryUsageInWindow.delete(uid);
        }
    }

    @Override
    void onUserInteractionStarted(String packageName, int uid) {
        mInjector.getPolicy().onUserInteractionStarted(packageName, uid);
    }

    @Override
    void onBackgroundRestrictionChanged(int uid, String pkgName, boolean restricted) {
        mInjector.getPolicy().onBackgroundRestrictionChanged(uid, pkgName, restricted);
    }

    /**
     * @return The total battery usage of the given UID since the system boots or last battery
     *         stats reset prior to that (whoever is earlier).
     *
     * <p>
     * Note: as there are throttling in polling the battery usage stats by
     * the {@link #mBatteryUsageStatsPollingMinIntervalMs}, the returned data here
     * could be either from the most recent polling, or the very fresh one - if the most recent
     * polling is outdated, it'll trigger an immediate update.
     * </p>
     */
    @Override
    @NonNull
    public ImmutableBatteryUsage getUidBatteryUsage(int uid) {
        final long now = mInjector.currentTimeMillis();
        final boolean updated = updateBatteryUsageStatsIfNecessary(now, false);
        synchronized (mLock) {
            if (updated) {
                // We just got fresh data, schedule a check right a way.
                mBgHandler.removeCallbacks(mBgBatteryUsageStatsPolling);
                scheduleBgBatteryUsageStatsCheck();
            }
            final BatteryUsage usage = mUidBatteryUsage.get(uid);
            return usage != null ? new ImmutableBatteryUsage(usage) : BATTERY_USAGE_NONE;
        }
    }

    private void scheduleBgBatteryUsageStatsCheck() {
        if (!mBgHandler.hasCallbacks(mBgBatteryUsageStatsCheck)) {
            mBgHandler.post(mBgBatteryUsageStatsCheck);
        }
    }

    private void updateBatteryUsageStatsAndCheck() {
        final long now = mInjector.currentTimeMillis();
        if (updateBatteryUsageStatsIfNecessary(now, false)) {
            checkBatteryUsageStats();
        } else {
            // We didn't do the battery stats update above, schedule a check later.
            synchronized (mLock) {
                scheduleBatteryUsageStatsUpdateIfNecessary(
                        mLastBatteryUsageSamplingTs + mBatteryUsageStatsPollingMinIntervalMs - now);
            }
        }
    }

    private void checkBatteryUsageStats() {
        final long now = SystemClock.elapsedRealtime();
        final AppBatteryPolicy bgPolicy = mInjector.getPolicy();
        try {
            final SparseArray<ImmutableBatteryUsage> uidConsumers = mTmpUidBatteryUsageInWindow;
            synchronized (mLock) {
                copyUidBatteryUsage(mUidBatteryUsageInWindow, uidConsumers);
            }
            final long since = Math.max(0, now - bgPolicy.mBgCurrentDrainWindowMs);
            for (int i = 0, size = uidConsumers.size(); i < size; i++) {
                final int uid = uidConsumers.keyAt(i);
                final ImmutableBatteryUsage actualUsage = uidConsumers.valueAt(i);
                final ImmutableBatteryUsage exemptedUsage = mAppRestrictionController
                        .getUidBatteryExemptedUsageSince(uid, since, now,
                                bgPolicy.mBgCurrentDrainExemptedTypes);
                // It's possible the exemptedUsage could be larger than actualUsage,
                // as the former one is an approximate value.
                final BatteryUsage bgUsage = actualUsage.mutate()
                        .subtract(exemptedUsage)
                        .calcPercentage(uid, bgPolicy);
                if (DEBUG_BACKGROUND_BATTERY_TRACKER) {
                    Slog.i(TAG, String.format(
                            "UID %d: %s (%s) | %s | %s over the past %s",
                            uid,
                            bgUsage.toString(),
                            bgUsage.percentageToString(),
                            exemptedUsage.toString(),
                            actualUsage.toString(),
                            TimeUtils.formatDuration(bgPolicy.mBgCurrentDrainWindowMs)));
                }
                bgPolicy.handleUidBatteryUsage(uid, bgUsage);
            }
            // For debugging only.
            for (int i = 0, size = mDebugUidPercentages.size(); i < size; i++) {
                bgPolicy.handleUidBatteryUsage(mDebugUidPercentages.keyAt(i),
                        mDebugUidPercentages.valueAt(i));
            }
        } finally {
            scheduleBatteryUsageStatsUpdateIfNecessary(mBatteryUsageStatsPollingIntervalMs);
        }
    }

    /**
     * Update the battery usage stats data, if it's allowed to do so.
     *
     * @return {@code true} if the battery stats is up to date.
     */
    private boolean updateBatteryUsageStatsIfNecessary(long now, boolean forceUpdate) {
        boolean needUpdate = false;
        boolean updated = false;
        synchronized (mLock) {
            if (mLastBatteryUsageSamplingTs + mBatteryUsageStatsPollingMinIntervalMs < now
                    || forceUpdate) {
                // The data we have is outdated.
                if (mBatteryUsageStatsUpdatePending) {
                    // An update is ongoing in parallel, just wait for it.
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                    }
                } else {
                    mBatteryUsageStatsUpdatePending = true;
                    needUpdate = true;
                }
                updated = true;
            } else {
                // The data is still fresh, no need to update it.
                return false;
            }
        }
        if (needUpdate) {
            // We don't want to query the battery usage stats with mLock held.
            updateBatteryUsageStatsOnce(now);
            synchronized (mLock) {
                mLastBatteryUsageSamplingTs = now;
                mBatteryUsageStatsUpdatePending = false;
                mLock.notifyAll();
            }
        }
        return updated;
    }

    private void updateBatteryUsageStatsOnce(long now) {
        final AppBatteryPolicy bgPolicy = mInjector.getPolicy();
        final ArraySet<UserHandle> userIds = mTmpUserIds;
        final SparseArray<BatteryUsage> buf = mTmpUidBatteryUsage;
        final BatteryStatsInternal batteryStatsInternal = mInjector.getBatteryStatsInternal();
        final long windowSize = bgPolicy.mBgCurrentDrainWindowMs;

        buf.clear();
        userIds.clear();
        synchronized (mLock) {
            for (int i = mActiveUserIdStates.size() - 1; i >= 0; i--) {
                userIds.add(UserHandle.of(mActiveUserIdStates.keyAt(i)));
                if (!mActiveUserIdStates.valueAt(i)) {
                    mActiveUserIdStates.removeAt(i);
                }
            }
        }

        if (DEBUG_BACKGROUND_BATTERY_TRACKER) {
            Slog.i(TAG, "updateBatteryUsageStatsOnce");
        }

        // Query the current battery usage stats.
        BatteryUsageStatsQuery.Builder builder = new BatteryUsageStatsQuery.Builder()
                .includeProcessStateData()
                .setMaxStatsAgeMs(0);
        final BatteryUsageStats stats = updateBatteryUsageStatsOnceInternal(0,
                buf, builder, userIds, batteryStatsInternal);
        final long curStart = stats != null ? stats.getStatsStartTimestamp() : 0L;
        final long curEnd = stats != null ? stats.getStatsEndTimestamp() : now;
        long curDuration = curEnd - curStart;
        boolean needUpdateUidBatteryUsageInWindow = true;

        if (curDuration >= windowSize) {
            // If we do have long enough data for the window, save it.
            synchronized (mLock) {
                copyUidBatteryUsage(buf, mUidBatteryUsageInWindow, windowSize * 1.0d / curDuration);
            }
            needUpdateUidBatteryUsageInWindow = false;
        }

        // Save the current data, which includes the battery usage since last reset.
        mTmpUidBatteryUsage2.clear();
        copyUidBatteryUsage(buf, mTmpUidBatteryUsage2);

        final long lastUidBatteryUsageStartTs;
        synchronized (mLock) {
            lastUidBatteryUsageStartTs = mLastUidBatteryUsageStartTs;
            mLastUidBatteryUsageStartTs = curStart;
        }
        if (curStart > lastUidBatteryUsageStartTs && lastUidBatteryUsageStartTs > 0) {
            // The battery usage stats committed data since our last query,
            // let's query the snapshots to get the data since last start.
            builder = new BatteryUsageStatsQuery.Builder()
                    .includeProcessStateData()
                    .aggregateSnapshots(lastUidBatteryUsageStartTs, curStart);
            updateBatteryUsageStatsOnceInternal(0, buf, builder, userIds, batteryStatsInternal);
            curDuration += curStart - lastUidBatteryUsageStartTs;
        }
        if (needUpdateUidBatteryUsageInWindow && curDuration >= windowSize) {
            // If we do have long enough data for the window, save it.
            synchronized (mLock) {
                copyUidBatteryUsage(buf, mUidBatteryUsageInWindow, windowSize * 1.0d / curDuration);
            }
            needUpdateUidBatteryUsageInWindow = false;
        }

        // Add the delta into the global records.
        synchronized (mLock) {
            for (int i = 0, size = buf.size(); i < size; i++) {
                final int uid = buf.keyAt(i);
                final int index = mUidBatteryUsage.indexOfKey(uid);
                final BatteryUsage lastUsage = mLastUidBatteryUsage.get(uid, BATTERY_USAGE_NONE);
                final BatteryUsage curUsage = buf.valueAt(i);
                final BatteryUsage before;
                if (index >= 0) {
                    before = mUidBatteryUsage.valueAt(index);
                    before.subtract(lastUsage).add(curUsage);
                } else {
                    before = BATTERY_USAGE_NONE;
                    mUidBatteryUsage.put(uid, curUsage);
                }
                if (DEBUG_BACKGROUND_BATTERY_TRACKER) {
                    final BatteryUsage actualDelta = new BatteryUsage(curUsage).subtract(lastUsage);
                    String msg = "Updating mUidBatteryUsage uid=" + uid + ", before=" + before
                            + ", after=" + mUidBatteryUsage.get(uid, BATTERY_USAGE_NONE)
                            + ", delta=" + actualDelta
                            + ", last=" + lastUsage
                            + ", curStart=" + curStart
                            + ", lastLastStart=" + lastUidBatteryUsageStartTs
                            + ", thisLastStart=" + mLastUidBatteryUsageStartTs;
                    if (!actualDelta.isValid()) {
                        // Something is wrong, the battery usage shouldn't be negative.
                        Slog.e(TAG, msg);
                    } else {
                        Slog.i(TAG, msg);
                    }
                }
            }
            // Now update the mLastUidBatteryUsage with the data we just saved above.
            copyUidBatteryUsage(mTmpUidBatteryUsage2, mLastUidBatteryUsage);
        }
        mTmpUidBatteryUsage2.clear();

        if (needUpdateUidBatteryUsageInWindow) {
            // No sufficient data for the full window still, query snapshots again.
            final long start = now - windowSize;
            final long end = lastUidBatteryUsageStartTs - 1;
            builder = new BatteryUsageStatsQuery.Builder()
                    .includeProcessStateData()
                    .aggregateSnapshots(start, end);
            updateBatteryUsageStatsOnceInternal(end - start,
                    buf, builder, userIds, batteryStatsInternal);
            synchronized (mLock) {
                copyUidBatteryUsage(buf, mUidBatteryUsageInWindow);
            }
        }
        if (DEBUG_BACKGROUND_BATTERY_TRACKER) {
            synchronized (mLock) {
                for (int i = 0, size = mUidBatteryUsageInWindow.size(); i < size; i++) {
                    Slog.i(TAG, "mUidBatteryUsageInWindow uid=" + mUidBatteryUsageInWindow.keyAt(i)
                            + " usage=" + mUidBatteryUsageInWindow.valueAt(i));
                }
            }
        }
    }

    private BatteryUsageStats updateBatteryUsageStatsOnceInternal(long expectedDuration,
            SparseArray<BatteryUsage> buf, BatteryUsageStatsQuery.Builder builder,
            ArraySet<UserHandle> userIds, BatteryStatsInternal batteryStatsInternal) {
        for (int i = 0, size = userIds.size(); i < size; i++) {
            builder.addUser(userIds.valueAt(i));
        }
        final List<BatteryUsageStats> statsList = batteryStatsInternal
                .getBatteryUsageStats(Arrays.asList(builder.build()));
        if (ArrayUtils.isEmpty(statsList)) {
            // Shouldn't happen unless in test.
            return null;
        }
        final BatteryUsageStats stats = statsList.get(0);
        final List<UidBatteryConsumer> uidConsumers = stats.getUidBatteryConsumers();
        if (uidConsumers != null) {
            final long start = stats.getStatsStartTimestamp();
            final long end = stats.getStatsEndTimestamp();
            final double scale = expectedDuration > 0
                    ? (expectedDuration * 1.0d) / (end - start) : 1.0d;
            final AppBatteryPolicy bgPolicy = mInjector.getPolicy();
            for (UidBatteryConsumer uidConsumer : uidConsumers) {
                // TODO: b/200326767 - as we are not supporting per proc state attribution yet,
                // we couldn't distinguish between a real FGS vs. a bound FGS proc state.
                final int uid = uidConsumer.getUid();
                final BatteryUsage bgUsage = new BatteryUsage(uidConsumer, bgPolicy)
                        .scale(scale);
                int index = buf.indexOfKey(uid);
                if (index < 0) {
                    buf.put(uid, bgUsage);
                } else {
                    final BatteryUsage before = buf.valueAt(index);
                    before.add(bgUsage);
                }
                if (DEBUG_BACKGROUND_BATTERY_TRACKER) {
                    Slog.i(TAG, "updateBatteryUsageStatsOnceInternal uid=" + uid
                            + ", bgUsage=" + bgUsage
                            + ", start=" + start
                            + ", end=" + end);
                }
            }
        }
        return stats;
    }

    private static void copyUidBatteryUsage(SparseArray<? extends BatteryUsage> source,
            SparseArray<ImmutableBatteryUsage> dest) {
        dest.clear();
        for (int i = source.size() - 1; i >= 0; i--) {
            dest.put(source.keyAt(i), new ImmutableBatteryUsage(source.valueAt(i)));
        }
    }

    private static void copyUidBatteryUsage(SparseArray<? extends BatteryUsage> source,
            SparseArray<ImmutableBatteryUsage> dest, double scale) {
        dest.clear();
        for (int i = source.size() - 1; i >= 0; i--) {
            dest.put(source.keyAt(i), new ImmutableBatteryUsage(source.valueAt(i), scale));
        }
    }

    private void onCurrentDrainMonitorEnabled(boolean enabled) {
        if (enabled) {
            if (!mBgHandler.hasCallbacks(mBgBatteryUsageStatsPolling)) {
                mBgHandler.postDelayed(mBgBatteryUsageStatsPolling,
                        mBatteryUsageStatsPollingIntervalMs);
            }
        } else {
            mBgHandler.removeCallbacks(mBgBatteryUsageStatsPolling);
            synchronized (mLock) {
                if (mBatteryUsageStatsUpdatePending) {
                    // An update is ongoing in parallel, just wait for it.
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                    }
                }
                mUidBatteryUsage.clear();
                mUidBatteryUsageInWindow.clear();
                mLastUidBatteryUsage.clear();
                mLastUidBatteryUsageStartTs = mLastBatteryUsageSamplingTs = 0L;
            }
        }
    }

    void setDebugUidPercentage(int[] uids, double[][] percentages) {
        mDebugUidPercentages.clear();
        for (int i = 0; i < uids.length; i++) {
            mDebugUidPercentages.put(uids[i], new BatteryUsage().setPercentage(percentages[i]));
        }
        scheduleBgBatteryUsageStatsCheck();
    }

    void clearDebugUidPercentage() {
        mDebugUidPercentages.clear();
        scheduleBgBatteryUsageStatsCheck();
    }

    @VisibleForTesting
    void reset() {
        synchronized (mLock) {
            mUidBatteryUsage.clear();
            mUidBatteryUsageInWindow.clear();
            mLastUidBatteryUsage.clear();
            mLastUidBatteryUsageStartTs = mLastBatteryUsageSamplingTs = 0L;
        }
        mBgHandler.removeCallbacks(mBgBatteryUsageStatsPolling);
        updateBatteryUsageStatsAndCheck();
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.println("APP BATTERY STATE TRACKER:");
        updateBatteryUsageStatsIfNecessary(mInjector.currentTimeMillis(), true);
        synchronized (mLock) {
            final SparseArray<ImmutableBatteryUsage> uidConsumers = mUidBatteryUsageInWindow;
            pw.print("  " + prefix);
            pw.print("  Last battery usage start=");
            TimeUtils.dumpTime(pw, mLastUidBatteryUsageStartTs);
            pw.println();
            pw.print("  " + prefix);
            pw.print("Battery usage over last ");
            final String newPrefix = "    " + prefix;
            final AppBatteryPolicy bgPolicy = mInjector.getPolicy();
            final long now = SystemClock.elapsedRealtime();
            final long since = Math.max(0, now - bgPolicy.mBgCurrentDrainWindowMs);
            pw.println(TimeUtils.formatDuration(now - since));
            if (uidConsumers.size() == 0) {
                pw.print(newPrefix);
                pw.println("(none)");
            } else {
                for (int i = 0, size = uidConsumers.size(); i < size; i++) {
                    final int uid = uidConsumers.keyAt(i);
                    final BatteryUsage bgUsage = uidConsumers.valueAt(i)
                            .calcPercentage(uid, bgPolicy);
                    final BatteryUsage exemptedUsage = mAppRestrictionController
                            .getUidBatteryExemptedUsageSince(uid, since, now,
                                    bgPolicy.mBgCurrentDrainExemptedTypes)
                            .calcPercentage(uid, bgPolicy);
                    final BatteryUsage reportedUsage = new BatteryUsage(bgUsage)
                            .subtract(exemptedUsage)
                            .calcPercentage(uid, bgPolicy);
                    pw.format("%s%s: [%s] %s (%s) | %s (%s) | %s (%s) | %s\n",
                            newPrefix, UserHandle.formatUid(uid),
                            PowerExemptionManager.reasonCodeToString(bgPolicy.shouldExemptUid(uid)),
                            bgUsage.toString(),
                            bgUsage.percentageToString(),
                            exemptedUsage.toString(),
                            exemptedUsage.percentageToString(),
                            reportedUsage.toString(),
                            reportedUsage.percentageToString(),
                            mUidBatteryUsage.get(uid, BATTERY_USAGE_NONE).toString());
                }
            }
        }
        super.dump(pw, prefix);
    }

    @Override
    void dumpAsProto(ProtoOutputStream proto, int uid) {
        synchronized (mLock) {
            final SparseArray<ImmutableBatteryUsage> uidConsumers = mUidBatteryUsageInWindow;
            if (uid != android.os.Process.INVALID_UID) {
                final BatteryUsage usage = uidConsumers.get(uid);
                if (usage != null) {
                    dumpUidStats(proto, uid, usage);
                }
            } else {
                for (int i = 0, size = uidConsumers.size(); i < size; i++) {
                    final int aUid = uidConsumers.keyAt(i);
                    final BatteryUsage usage = uidConsumers.valueAt(i);
                    dumpUidStats(proto, aUid, usage);
                }
            }
        }
    }

    private void dumpUidStats(ProtoOutputStream proto, int uid, BatteryUsage usage) {
        if (usage.mUsage == null) {
            return;
        }

        final double foregroundUsage = usage.getUsagePowerMah(PROCESS_STATE_FOREGROUND);
        final double backgroundUsage = usage.getUsagePowerMah(PROCESS_STATE_BACKGROUND);
        final double fgsUsage = usage.getUsagePowerMah(PROCESS_STATE_FOREGROUND_SERVICE);

        if (foregroundUsage == 0 && backgroundUsage == 0 && fgsUsage == 0) {
            return;
        }

        final long token = proto.start(AppBatteryStatsProto.UID_STATS);
        proto.write(AppBatteryStatsProto.UidStats.UID, uid);
        dumpProcessStateStats(proto,
                AppBatteryStatsProto.UidStats.ProcessStateStats.FOREGROUND,
                foregroundUsage);
        dumpProcessStateStats(proto,
                AppBatteryStatsProto.UidStats.ProcessStateStats.BACKGROUND,
                backgroundUsage);
        dumpProcessStateStats(proto,
                AppBatteryStatsProto.UidStats.ProcessStateStats.FOREGROUND_SERVICE,
                fgsUsage);
        proto.end(token);
    }

    private void dumpProcessStateStats(ProtoOutputStream proto, int processState, double powerMah) {
        if (powerMah == 0) {
            return;
        }

        final long token = proto.start(AppBatteryStatsProto.UidStats.PROCESS_STATE_STATS);
        proto.write(AppBatteryStatsProto.UidStats.ProcessStateStats.PROCESS_STATE, processState);
        proto.write(AppBatteryStatsProto.UidStats.ProcessStateStats.POWER_MAH, powerMah);
        proto.end(token);
    }

    static class BatteryUsage {
        static final int BATTERY_USAGE_INDEX_UNSPECIFIED = PROCESS_STATE_UNSPECIFIED;
        static final int BATTERY_USAGE_INDEX_FOREGROUND = PROCESS_STATE_FOREGROUND;
        static final int BATTERY_USAGE_INDEX_BACKGROUND = PROCESS_STATE_BACKGROUND;
        static final int BATTERY_USAGE_INDEX_FOREGROUND_SERVICE = PROCESS_STATE_FOREGROUND_SERVICE;
        static final int BATTERY_USAGE_INDEX_CACHED = PROCESS_STATE_CACHED;
        static final int BATTERY_USAGE_COUNT = PROCESS_STATE_COUNT;

        static final Dimensions[] BATT_DIMENS = new Dimensions[] {
                new Dimensions(AppBatteryPolicy.DEFAULT_BG_CURRENT_DRAIN_POWER_COMPONENTS,
                        PROCESS_STATE_UNSPECIFIED),
                new Dimensions(AppBatteryPolicy.DEFAULT_BG_CURRENT_DRAIN_POWER_COMPONENTS,
                        PROCESS_STATE_FOREGROUND),
                new Dimensions(AppBatteryPolicy.DEFAULT_BG_CURRENT_DRAIN_POWER_COMPONENTS,
                        PROCESS_STATE_BACKGROUND),
                new Dimensions(AppBatteryPolicy.DEFAULT_BG_CURRENT_DRAIN_POWER_COMPONENTS,
                        PROCESS_STATE_FOREGROUND_SERVICE),
                new Dimensions(AppBatteryPolicy.DEFAULT_BG_CURRENT_DRAIN_POWER_COMPONENTS,
                        PROCESS_STATE_CACHED),
        };

        @NonNull double[] mUsage;
        @Nullable double[] mPercentage;

        BatteryUsage() {
            this(0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
        }

        BatteryUsage(double unspecifiedUsage, double fgUsage, double bgUsage, double fgsUsage,
                double cachedUsage) {
            mUsage = new double[] {unspecifiedUsage, fgUsage, bgUsage, fgsUsage, cachedUsage};
        }

        BatteryUsage(@NonNull double[] usage) {
            mUsage = usage;
        }

        BatteryUsage(@NonNull BatteryUsage other, double scale) {
            this(other);
            scaleInternal(scale);
        }

        BatteryUsage(@NonNull BatteryUsage other) {
            mUsage = new double[other.mUsage.length];
            setToInternal(other);
        }

        BatteryUsage(@NonNull UidBatteryConsumer consumer, @NonNull AppBatteryPolicy policy) {
            final Dimensions[] dims = policy.mBatteryDimensions;
            mUsage = new double[] {
                    getConsumedPowerNoThrow(consumer, dims[BATTERY_USAGE_INDEX_UNSPECIFIED]),
                    getConsumedPowerNoThrow(consumer, dims[BATTERY_USAGE_INDEX_FOREGROUND]),
                    getConsumedPowerNoThrow(consumer, dims[BATTERY_USAGE_INDEX_BACKGROUND]),
                    getConsumedPowerNoThrow(consumer, dims[BATTERY_USAGE_INDEX_FOREGROUND_SERVICE]),
                    getConsumedPowerNoThrow(consumer, dims[BATTERY_USAGE_INDEX_CACHED]),
            };
        }

        BatteryUsage setTo(@NonNull BatteryUsage other) {
            return setToInternal(other);
        }

        private BatteryUsage setToInternal(@NonNull BatteryUsage other) {
            for (int i = 0; i < other.mUsage.length; i++) {
                mUsage[i] = other.mUsage[i];
            }
            return this;
        }

        BatteryUsage add(@NonNull BatteryUsage other) {
            for (int i = 0; i < other.mUsage.length; i++) {
                mUsage[i] += other.mUsage[i];
            }
            return this;
        }

        BatteryUsage subtract(@NonNull BatteryUsage other) {
            for (int i = 0; i < other.mUsage.length; i++) {
                mUsage[i] = Math.max(0.0d, mUsage[i] - other.mUsage[i]);
            }
            return this;
        }

        BatteryUsage scale(double scale) {
            return scaleInternal(scale);
        }

        private BatteryUsage scaleInternal(double scale) {
            for (int i = 0; i < mUsage.length; i++) {
                mUsage[i] *= scale;
            }
            return this;
        }

        ImmutableBatteryUsage unmutate() {
            return new ImmutableBatteryUsage(this);
        }

        BatteryUsage calcPercentage(int uid, @NonNull AppBatteryPolicy policy) {
            if (mPercentage == null || mPercentage.length != mUsage.length) {
                mPercentage = new double[mUsage.length];
            }
            policy.calcPercentage(uid, mUsage, mPercentage);
            return this;
        }

        BatteryUsage setPercentage(@NonNull double[] percentage) {
            mPercentage = percentage;
            return this;
        }

        double[] getPercentage() {
            return mPercentage;
        }

        String percentageToString() {
            return formatBatteryUsagePercentage(mPercentage);
        }

        @Override
        public String toString() {
            return formatBatteryUsage(mUsage);
        }

        double getUsagePowerMah(@BatteryConsumer.ProcessState int processState) {
            switch (processState) {
                case PROCESS_STATE_FOREGROUND: return mUsage[1];
                case PROCESS_STATE_BACKGROUND: return mUsage[2];
                case PROCESS_STATE_FOREGROUND_SERVICE: return mUsage[3];
            }
            return 0;
        }

        boolean isValid() {
            for (int i = 0; i < mUsage.length; i++) {
                if (mUsage[i] < 0.0d) {
                    return false;
                }
            }
            return true;
        }

        boolean isEmpty() {
            for (int i = 0; i < mUsage.length; i++) {
                if (mUsage[i] > 0.0d) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean equals(Object other) {
            if (other == null) {
                return false;
            }
            final BatteryUsage otherUsage = (BatteryUsage) other;
            for (int i = 0; i < mUsage.length; i++) {
                if (Double.compare(mUsage[i], otherUsage.mUsage[i]) != 0) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hashCode = 0;
            for (int i = 0; i < mUsage.length; i++) {
                hashCode = Double.hashCode(mUsage[i]) + hashCode * 31;
            }
            return hashCode;
        }

        private static String formatBatteryUsage(double[] usage) {
            return String.format("%.3f %.3f %.3f %.3f %.3f mAh",
                    usage[BATTERY_USAGE_INDEX_UNSPECIFIED],
                    usage[BATTERY_USAGE_INDEX_FOREGROUND],
                    usage[BATTERY_USAGE_INDEX_BACKGROUND],
                    usage[BATTERY_USAGE_INDEX_FOREGROUND_SERVICE],
                    usage[BATTERY_USAGE_INDEX_CACHED]);
        }

        static String formatBatteryUsagePercentage(double[] percentage) {
            return String.format("%4.2f%% %4.2f%% %4.2f%% %4.2f%% %4.2f%%",
                    percentage[BATTERY_USAGE_INDEX_UNSPECIFIED],
                    percentage[BATTERY_USAGE_INDEX_FOREGROUND],
                    percentage[BATTERY_USAGE_INDEX_BACKGROUND],
                    percentage[BATTERY_USAGE_INDEX_FOREGROUND_SERVICE],
                    percentage[BATTERY_USAGE_INDEX_CACHED]);
        }

        private static double getConsumedPowerNoThrow(final UidBatteryConsumer uidConsumer,
                final Dimensions dimens) {
            try {
                return uidConsumer.getConsumedPower(dimens);
            } catch (IllegalArgumentException e) {
                return 0.0d;
            }
        }
    }

    static final class ImmutableBatteryUsage extends BatteryUsage {
        ImmutableBatteryUsage() {
            super();
        }

        ImmutableBatteryUsage(double unspecifiedUsage, double fgUsage, double bgUsage,
                double fgsUsage, double cachedUsage) {
            super(unspecifiedUsage, fgUsage, bgUsage, fgsUsage, cachedUsage);
        }

        ImmutableBatteryUsage(@NonNull double[] usage) {
            super(usage);
        }

        ImmutableBatteryUsage(@NonNull BatteryUsage other, double scale) {
            super(other, scale);
        }

        ImmutableBatteryUsage(@NonNull BatteryUsage other) {
            super(other);
        }

        ImmutableBatteryUsage(@NonNull UidBatteryConsumer consumer,
                @NonNull AppBatteryPolicy policy) {
            super(consumer, policy);
        }

        @Override
        BatteryUsage setTo(@NonNull BatteryUsage other) {
            throw new RuntimeException("Readonly");
        }

        @Override
        BatteryUsage add(@NonNull BatteryUsage other) {
            throw new RuntimeException("Readonly");
        }

        @Override
        BatteryUsage subtract(@NonNull BatteryUsage other) {
            throw new RuntimeException("Readonly");
        }

        @Override
        BatteryUsage scale(double scale) {
            throw new RuntimeException("Readonly");
        }

        @Override
        BatteryUsage setPercentage(@NonNull double[] percentage) {
            throw new RuntimeException("Readonly");
        }

        BatteryUsage mutate() {
            return new BatteryUsage(this);
        }
    }

    static final class AppBatteryPolicy extends BaseAppStatePolicy<AppBatteryTracker> {
        /**
         * The type of battery usage we could choose to apply the policy on.
         *
         * Must be in sync with android.os.BatteryConsumer.PROCESS_STATE_*.
         */
        static final int BATTERY_USAGE_TYPE_UNSPECIFIED = 1;
        static final int BATTERY_USAGE_TYPE_FOREGROUND = 1 << 1;
        static final int BATTERY_USAGE_TYPE_BACKGROUND = 1 << 2;
        static final int BATTERY_USAGE_TYPE_FOREGROUND_SERVICE = 1 << 3;
        static final int BATTERY_USAGE_TYPE_CACHED = 1 << 4;

        /**
         * Whether or not we should enable the monitoring on background current drains.
         */
        static final String KEY_BG_CURRENT_DRAIN_MONITOR_ENABLED =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "current_drain_monitor_enabled";

        /**
         * The threshold of the background current drain (in percentage) to the restricted
         * standby bucket. In conjunction with the {@link #KEY_BG_CURRENT_DRAIN_WINDOW},
         * the app could be moved to more restricted standby bucket when its background current
         * drain rate is over this limit.
         */
        static final String KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_RESTRICTED_BUCKET =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "current_drain_threshold_to_restricted_bucket";

        /**
         * The threshold of the background current drain (in percentage) to the background
         * restricted level. In conjunction with the {@link #KEY_BG_CURRENT_DRAIN_WINDOW},
         * the app could be moved to more restricted level when its background current
         * drain rate is over this limit.
         */
        static final String KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_BG_RESTRICTED =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "current_drain_threshold_to_bg_restricted";

        /**
         * The background current drain window size. In conjunction with the
         * {@link #KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_RESTRICTED_BUCKET}, the app could be moved to
         * more restrictive bucket when its background current drain rate is over this limit.
         */
        static final String KEY_BG_CURRENT_DRAIN_WINDOW =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "current_drain_window";

        /**
         * Similar to {@link #KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_RESTRICTED_BUCKET}, but a higher
         * value for the legitimate cases with higher background current drain.
         */
        static final String KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_TO_RESTRICTED_BUCKET =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX
                + "current_drain_high_threshold_to_restricted_bucket";

        /**
         * Similar to {@link #KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_BG_RESTRICTED}, but a higher value
         * for the legitimate cases with higher background current drain.
         */
        static final String KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_TO_BG_RESTRICTED =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "current_drain_high_threshold_to_bg_restricted";

        /**
         * The threshold of minimal time of hosting a foreground service with type "mediaPlayback"
         * or a media session, over the given window, so it'd subject towards the higher
         * background current drain threshold as defined in
         * {@link #mBgCurrentDrainBgRestrictedHighThreshold}.
         */
        static final String KEY_BG_CURRENT_DRAIN_MEDIA_PLAYBACK_MIN_DURATION =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "current_drain_media_playback_min_duration";

        /**
         * Similar to {@link #KEY_BG_CURRENT_DRAIN_MEDIA_PLAYBACK_MIN_DURATION} but for foreground
         * service with type "location".
         */
        static final String KEY_BG_CURRENT_DRAIN_LOCATION_MIN_DURATION =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "current_drain_location_min_duration";

        /**
         * Whether or not we should enable the different threshold based on the durations of
         * certain event type.
         */
        static final String KEY_BG_CURRENT_DRAIN_EVENT_DURATION_BASED_THRESHOLD_ENABLED =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX
                + "current_drain_event_duration_based_threshold_enabled";

        /**
         * The types of battery drain we're checking on each app; if the sum of the battery drain
         * exceeds the threshold, it'll be moved to restricted standby bucket; the type here
         * must be one of, or combination of {@link #BATTERY_USAGE_TYPE_BACKGROUND},
         * {@link #BATTERY_USAGE_TYPE_FOREGROUND_SERVICE} and {@link #BATTERY_USAGE_TYPE_CACHED}.
         */
        static final String KEY_BG_CURRENT_DRAIN_TYPES_TO_RESTRICTED_BUCKET =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "current_drain_types_to_restricted_bucket";

        /**
         * The types of battery drain we're checking on each app; if the sum of the battery drain
         * exceeds the threshold, it'll be moved to background restricted level; the type here
         * must be one of, or combination of {@link #BATTERY_USAGE_TYPE_BACKGROUND},
         * {@link #BATTERY_USAGE_TYPE_FOREGROUND_SERVICE} and {@link #BATTERY_USAGE_TYPE_CACHED}.
         */
        static final String KEY_BG_CURRENT_DRAIN_TYPES_TO_BG_RESTRICTED =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "current_drain_types_to_bg_restricted";

        /**
         * The power usage components we're monitoring.
         */
        static final String KEY_BG_CURRENT_DRAIN_POWER_COMPONENTS =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "current_drain_power_components";

        /**
         * The types of state where we'll exempt its battery usage when it's in that state.
         * The state here must be one or a combination of STATE_TYPE_* in BaseAppStateTracker.
         */
        static final String KEY_BG_CURRENT_DRAIN_EXEMPTED_TYPES =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "current_drain_exempted_types";

        /**
         * The behavior when an app has the permission ACCESS_BACKGROUND_LOCATION granted,
         * whether or not the system will use a higher threshold towards its background battery
         * usage because of it.
         */
        static final String KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_BY_BG_LOCATION =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "current_drain_high_threshold_by_bg_location";

        /**
         * Whether or not the battery usage of the offending app should fulfill the 1st threshold
         * before taking actions for the 2nd threshold.
         */
        static final String KEY_BG_CURRENT_DRAIN_DECOUPLE_THRESHOLDS =
                DEVICE_CONFIG_SUBNAMESPACE_PREFIX + "current_drain_decouple_thresholds";

        /**
         * Default value to the {@link #INDEX_REGULAR_CURRENT_DRAIN_THRESHOLD} of
         * the {@link #mBgCurrentDrainRestrictedBucketThreshold}.
         */
        final float mDefaultBgCurrentDrainRestrictedBucket;

        /**
         * Default value to the {@link #INDEX_REGULAR_CURRENT_DRAIN_THRESHOLD} of
         * the {@link #mBgCurrentDrainBgRestrictedThreshold}.
         */
        final float mDefaultBgCurrentDrainBgRestrictedThreshold;

        /**
         * Default value to {@link #mBgCurrentDrainWindowMs}.
         */
        final long mDefaultBgCurrentDrainWindowMs;

        /**
         * Default value to the {@link #INDEX_HIGH_CURRENT_DRAIN_THRESHOLD} of
         * the {@link #mBgCurrentDrainRestrictedBucketThreshold}.
         */
        final float mDefaultBgCurrentDrainRestrictedBucketHighThreshold;

        /**
         * Default value to the {@link #INDEX_HIGH_CURRENT_DRAIN_THRESHOLD} of
         * the {@link #mBgCurrentDrainBgRestrictedThreshold}.
         */
        final float mDefaultBgCurrentDrainBgRestrictedHighThreshold;

        /**
         * Default value to {@link #mBgCurrentDrainMediaPlaybackMinDuration}.
         */
        final long mDefaultBgCurrentDrainMediaPlaybackMinDuration;

        /**
         * Default value to {@link #mBgCurrentDrainLocationMinDuration}.
         */
        final long mDefaultBgCurrentDrainLocationMinDuration;

        /**
         * Default value to {@link #mBgCurrentDrainEventDurationBasedThresholdEnabled}.
         */
        final boolean mDefaultBgCurrentDrainEventDurationBasedThresholdEnabled;

        /**
         * Default value to {@link #mBgCurrentDrainRestrictedBucketTypes}.
         */
        final int mDefaultCurrentDrainTypesToRestrictedBucket;

        /**
         * Default value to {@link #mBgCurrentDrainBgRestrictedTypes}.
         */
        final int mDefaultBgCurrentDrainTypesToBgRestricted;

        /**
         * Default value to {@link #mBgCurrentDrainPowerComponents}.
         **/
        @BatteryConsumer.PowerComponent
        static final int DEFAULT_BG_CURRENT_DRAIN_POWER_COMPONENTS = POWER_COMPONENT_ANY;

        final int mDefaultBgCurrentDrainPowerComponent;

        /**
         * Default value to {@link #mBgCurrentDrainExmptedTypes}.
         **/
        final int mDefaultBgCurrentDrainExemptedTypes;

        /**
         * Default value to {@link #mBgCurrentDrainHighThresholdByBgLocation}.
         */
        final boolean mDefaultBgCurrentDrainHighThresholdByBgLocation;

        /**
         * Default value to {@link #mBgCurrentDrainDecoupleThresholds}.
         */
        static final boolean DEFAULT_BG_CURRENT_DRAIN_DECOUPLE_THRESHOLD = true;

        /**
         * The index to {@link #mBgCurrentDrainRestrictedBucketThreshold}
         * and {@link #mBgCurrentDrainBgRestrictedThreshold}.
         */
        static final int INDEX_REGULAR_CURRENT_DRAIN_THRESHOLD = 0;
        static final int INDEX_HIGH_CURRENT_DRAIN_THRESHOLD = 1;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_RESTRICTED_BUCKET.
         * @see #KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_TO_RESTRICTED_BUCKET.
         */
        volatile float[] mBgCurrentDrainRestrictedBucketThreshold = new float[2];

        /**
         * @see #KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_BG_RESTRICTED.
         * @see #KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_TO_BG_RESTRICTED.
         */
        volatile float[] mBgCurrentDrainBgRestrictedThreshold = new float[2];

        /**
         * @see #KEY_BG_CURRENT_DRAIN_WINDOW.
         */
        volatile long mBgCurrentDrainWindowMs;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_MEDIA_PLAYBACK_MIN_DURATION.
         */
        volatile long mBgCurrentDrainMediaPlaybackMinDuration;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_LOCATION_MIN_DURATION.
         */
        volatile long mBgCurrentDrainLocationMinDuration;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_EVENT_DURATION_BASED_THRESHOLD_ENABLED.
         */
        volatile boolean mBgCurrentDrainEventDurationBasedThresholdEnabled;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_TYPES_TO_RESTRICTED_BUCKET.
         */
        volatile int mBgCurrentDrainRestrictedBucketTypes;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_TYPES_TO_BG_RESTRICTED.
         */
        volatile int mBgCurrentDrainBgRestrictedTypes;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_POWER_COMPONENTS.
         */
        @BatteryConsumer.PowerComponent
        volatile int mBgCurrentDrainPowerComponents;

        volatile Dimensions[] mBatteryDimensions;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_EXEMPTED_TYPES.
         */
        volatile int mBgCurrentDrainExemptedTypes;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_BY_BG_LOCATION.
         */
        volatile boolean mBgCurrentDrainHighThresholdByBgLocation;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_DECOUPLE_THRESHOLDS.
         */
        volatile boolean mBgCurrentDrainDecoupleThresholds;

        /**
         * The capacity of the battery when fully charged in mAh.
         */
        private int mBatteryFullChargeMah;

        /**
         * List of the packages with significant background battery usage, key is the UID of
         * the package and value is an array of the timestamps when the UID is found guilty and
         * should be moved to the next level of restriction.
         */
        @GuardedBy("mLock")
        private final SparseArray<long[]> mHighBgBatteryPackages = new SparseArray<>();

        @NonNull
        private final Object mLock;

        private static final int TIME_STAMP_INDEX_RESTRICTED_BUCKET = 0;
        private static final int TIME_STAMP_INDEX_BG_RESTRICTED = 1;
        private static final int TIME_STAMP_INDEX_LAST = 2;

        AppBatteryPolicy(@NonNull Injector injector, @NonNull AppBatteryTracker tracker) {
            super(injector, tracker, KEY_BG_CURRENT_DRAIN_MONITOR_ENABLED,
                    tracker.mContext.getResources()
                    .getBoolean(R.bool.config_bg_current_drain_monitor_enabled));
            mLock = tracker.mLock;
            final Resources resources = tracker.mContext.getResources();
            float[] val = getFloatArray(resources.obtainTypedArray(
                    R.array.config_bg_current_drain_threshold_to_restricted_bucket));
            mDefaultBgCurrentDrainRestrictedBucket =
                    isLowRamDeviceStatic() ? val[1] : val[0];
            val = getFloatArray(resources.obtainTypedArray(
                    R.array.config_bg_current_drain_threshold_to_bg_restricted));
            mDefaultBgCurrentDrainBgRestrictedThreshold =
                    isLowRamDeviceStatic() ? val[1] : val[0];
            mDefaultBgCurrentDrainWindowMs = resources.getInteger(
                    R.integer.config_bg_current_drain_window) * 1_000;
            val = getFloatArray(resources.obtainTypedArray(
                    R.array.config_bg_current_drain_high_threshold_to_restricted_bucket));
            mDefaultBgCurrentDrainRestrictedBucketHighThreshold =
                    isLowRamDeviceStatic() ? val[1] : val[0];
            val = getFloatArray(resources.obtainTypedArray(
                    R.array.config_bg_current_drain_high_threshold_to_bg_restricted));
            mDefaultBgCurrentDrainBgRestrictedHighThreshold =
                    isLowRamDeviceStatic() ? val[1] : val[0];
            mDefaultBgCurrentDrainMediaPlaybackMinDuration = resources.getInteger(
                    R.integer.config_bg_current_drain_media_playback_min_duration) * 1_000;
            mDefaultBgCurrentDrainLocationMinDuration = resources.getInteger(
                    R.integer.config_bg_current_drain_location_min_duration) * 1_000;
            mDefaultBgCurrentDrainEventDurationBasedThresholdEnabled = resources.getBoolean(
                    R.bool.config_bg_current_drain_event_duration_based_threshold_enabled);
            mDefaultCurrentDrainTypesToRestrictedBucket = resources.getInteger(
                    R.integer.config_bg_current_drain_types_to_restricted_bucket);
            mDefaultBgCurrentDrainTypesToBgRestricted = resources.getInteger(
                    R.integer.config_bg_current_drain_types_to_bg_restricted);
            mDefaultBgCurrentDrainPowerComponent = resources.getInteger(
                    R.integer.config_bg_current_drain_power_components);
            mDefaultBgCurrentDrainExemptedTypes = resources.getInteger(
                    R.integer.config_bg_current_drain_exempted_types);
            mDefaultBgCurrentDrainHighThresholdByBgLocation = resources.getBoolean(
                    R.bool.config_bg_current_drain_high_threshold_by_bg_location);
            mBgCurrentDrainRestrictedBucketThreshold[0] =
                    mDefaultBgCurrentDrainRestrictedBucket;
            mBgCurrentDrainRestrictedBucketThreshold[1] =
                    mDefaultBgCurrentDrainRestrictedBucketHighThreshold;
            mBgCurrentDrainBgRestrictedThreshold[0] =
                    mDefaultBgCurrentDrainBgRestrictedThreshold;
            mBgCurrentDrainBgRestrictedThreshold[1] =
                    mDefaultBgCurrentDrainBgRestrictedHighThreshold;
            mBgCurrentDrainWindowMs = mDefaultBgCurrentDrainWindowMs;
            mBgCurrentDrainMediaPlaybackMinDuration =
                    mDefaultBgCurrentDrainMediaPlaybackMinDuration;
            mBgCurrentDrainLocationMinDuration = mDefaultBgCurrentDrainLocationMinDuration;
        }

        static float[] getFloatArray(TypedArray array) {
            int length = array.length();
            float[] floatArray = new float[length];
            for (int i = 0; i < length; i++) {
                floatArray[i] = array.getFloat(i, Float.NaN);
            }
            array.recycle();
            return floatArray;
        }

        @Override
        public void onPropertiesChanged(String name) {
            switch (name) {
                case KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_RESTRICTED_BUCKET:
                case KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_BG_RESTRICTED:
                case KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_BY_BG_LOCATION:
                case KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_TO_RESTRICTED_BUCKET:
                case KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_TO_BG_RESTRICTED:
                case KEY_BG_CURRENT_DRAIN_TYPES_TO_RESTRICTED_BUCKET:
                case KEY_BG_CURRENT_DRAIN_TYPES_TO_BG_RESTRICTED:
                case KEY_BG_CURRENT_DRAIN_POWER_COMPONENTS:
                    updateCurrentDrainThreshold();
                    break;
                case KEY_BG_CURRENT_DRAIN_WINDOW:
                    updateCurrentDrainWindow();
                    break;
                case KEY_BG_CURRENT_DRAIN_MEDIA_PLAYBACK_MIN_DURATION:
                    updateCurrentDrainMediaPlaybackMinDuration();
                    break;
                case KEY_BG_CURRENT_DRAIN_LOCATION_MIN_DURATION:
                    updateCurrentDrainLocationMinDuration();
                    break;
                case KEY_BG_CURRENT_DRAIN_EVENT_DURATION_BASED_THRESHOLD_ENABLED:
                    updateCurrentDrainEventDurationBasedThresholdEnabled();
                    break;
                case KEY_BG_CURRENT_DRAIN_EXEMPTED_TYPES:
                    updateCurrentDrainExemptedTypes();
                    break;
                case KEY_BG_CURRENT_DRAIN_DECOUPLE_THRESHOLDS:
                    updateCurrentDrainDecoupleThresholds();
                    break;
                default:
                    super.onPropertiesChanged(name);
                    break;
            }
        }

        void updateTrackerEnabled() {
            if (mBatteryFullChargeMah > 0) {
                super.updateTrackerEnabled();
            } else {
                mTrackerEnabled = false;
                onTrackerEnabled(false);
            }
        }

        public void onTrackerEnabled(boolean enabled) {
            mTracker.onCurrentDrainMonitorEnabled(enabled);
        }

        private void updateCurrentDrainThreshold() {
            mBgCurrentDrainRestrictedBucketThreshold[INDEX_REGULAR_CURRENT_DRAIN_THRESHOLD] =
                    DeviceConfig.getFloat(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_RESTRICTED_BUCKET,
                    mDefaultBgCurrentDrainRestrictedBucket);
            mBgCurrentDrainRestrictedBucketThreshold[INDEX_HIGH_CURRENT_DRAIN_THRESHOLD] =
                    DeviceConfig.getFloat(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_TO_RESTRICTED_BUCKET,
                    mDefaultBgCurrentDrainRestrictedBucketHighThreshold);
            mBgCurrentDrainBgRestrictedThreshold[INDEX_REGULAR_CURRENT_DRAIN_THRESHOLD] =
                    DeviceConfig.getFloat(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_BG_RESTRICTED,
                    mDefaultBgCurrentDrainBgRestrictedThreshold);
            mBgCurrentDrainBgRestrictedThreshold[INDEX_HIGH_CURRENT_DRAIN_THRESHOLD] =
                    DeviceConfig.getFloat(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_TO_BG_RESTRICTED,
                    mDefaultBgCurrentDrainBgRestrictedHighThreshold);
            mBgCurrentDrainRestrictedBucketTypes =
                    DeviceConfig.getInt(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_TYPES_TO_RESTRICTED_BUCKET,
                    mDefaultCurrentDrainTypesToRestrictedBucket);
            mBgCurrentDrainBgRestrictedTypes =
                    DeviceConfig.getInt(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_TYPES_TO_BG_RESTRICTED,
                    mDefaultBgCurrentDrainTypesToBgRestricted);
            mBgCurrentDrainPowerComponents =
                    DeviceConfig.getInt(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_POWER_COMPONENTS,
                    mDefaultBgCurrentDrainPowerComponent);
            if (mBgCurrentDrainPowerComponents == DEFAULT_BG_CURRENT_DRAIN_POWER_COMPONENTS) {
                mBatteryDimensions = BatteryUsage.BATT_DIMENS;
            } else {
                mBatteryDimensions = new Dimensions[BatteryUsage.BATTERY_USAGE_COUNT];
                for (int i = 0; i < BatteryUsage.BATTERY_USAGE_COUNT; i++) {
                    mBatteryDimensions[i] = new Dimensions(mBgCurrentDrainPowerComponents, i);
                }
            }
            mBgCurrentDrainHighThresholdByBgLocation =
                    DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_BY_BG_LOCATION,
                    mDefaultBgCurrentDrainHighThresholdByBgLocation);
        }

        private void updateCurrentDrainWindow() {
            mBgCurrentDrainWindowMs = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_WINDOW,
                    mDefaultBgCurrentDrainWindowMs);
        }

        private void updateCurrentDrainMediaPlaybackMinDuration() {
            mBgCurrentDrainMediaPlaybackMinDuration = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_MEDIA_PLAYBACK_MIN_DURATION,
                    mDefaultBgCurrentDrainMediaPlaybackMinDuration);
        }

        private void updateCurrentDrainLocationMinDuration() {
            mBgCurrentDrainLocationMinDuration = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_LOCATION_MIN_DURATION,
                    mDefaultBgCurrentDrainLocationMinDuration);
        }

        private void updateCurrentDrainEventDurationBasedThresholdEnabled() {
            mBgCurrentDrainEventDurationBasedThresholdEnabled = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_EVENT_DURATION_BASED_THRESHOLD_ENABLED,
                    mDefaultBgCurrentDrainEventDurationBasedThresholdEnabled);
        }

        private void updateCurrentDrainExemptedTypes() {
            mBgCurrentDrainExemptedTypes = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_EXEMPTED_TYPES,
                    mDefaultBgCurrentDrainExemptedTypes);
        }

        private void updateCurrentDrainDecoupleThresholds() {
            mBgCurrentDrainDecoupleThresholds = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_DECOUPLE_THRESHOLDS,
                    DEFAULT_BG_CURRENT_DRAIN_DECOUPLE_THRESHOLD);
        }

        @Override
        public void onSystemReady() {
            mBatteryFullChargeMah =
                    mInjector.getBatteryManagerInternal().getBatteryFullCharge() / 1000;
            super.onSystemReady();
            updateCurrentDrainThreshold();
            updateCurrentDrainWindow();
            updateCurrentDrainMediaPlaybackMinDuration();
            updateCurrentDrainLocationMinDuration();
            updateCurrentDrainEventDurationBasedThresholdEnabled();
            updateCurrentDrainExemptedTypes();
            updateCurrentDrainDecoupleThresholds();
        }

        @Override
        @RestrictionLevel
        public int getProposedRestrictionLevel(String packageName, int uid,
                @RestrictionLevel int maxLevel) {
            if (maxLevel <= RESTRICTION_LEVEL_ADAPTIVE_BUCKET) {
                return RESTRICTION_LEVEL_UNKNOWN;
            }
            synchronized (mLock) {
                final long[] ts = mHighBgBatteryPackages.get(uid);
                if (ts != null) {
                    final int restrictedLevel = ts[TIME_STAMP_INDEX_RESTRICTED_BUCKET] > 0
                            ? RESTRICTION_LEVEL_RESTRICTED_BUCKET
                            : RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
                    if (maxLevel > RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                        return ts[TIME_STAMP_INDEX_BG_RESTRICTED] > 0
                                ? RESTRICTION_LEVEL_BACKGROUND_RESTRICTED : restrictedLevel;
                    } else if (maxLevel == RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                        return restrictedLevel;
                    }
                }
                return RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
            }
        }

        double[] calcPercentage(final int uid, final double[] usage, double[] percentage) {
            final BatteryUsage debugUsage = uid > 0 ? mTracker.mDebugUidPercentages.get(uid) : null;
            final double[] forced = debugUsage != null ? debugUsage.getPercentage() : null;
            for (int i = 0; i < usage.length; i++) {
                percentage[i] = forced != null ? forced[i] : usage[i] / mBatteryFullChargeMah * 100;
            }
            return percentage;
        }

        private double sumPercentageOfTypes(double[] percentage, int types) {
            double result = 0.0d;
            for (int type = Integer.highestOneBit(types); type != 0;
                    type = Integer.highestOneBit(types)) {
                final int index = Integer.numberOfTrailingZeros(type);
                result += percentage[index];
                types &= ~type;
            }
            return result;
        }

        private static String batteryUsageTypesToString(int types) {
            final StringBuilder sb = new StringBuilder("[");
            boolean needDelimiter = false;
            for (int type = Integer.highestOneBit(types); type != 0;
                    type = Integer.highestOneBit(types)) {
                if (needDelimiter) {
                    sb.append('|');
                }
                needDelimiter = true;
                switch (type) {
                    case BATTERY_USAGE_TYPE_UNSPECIFIED:
                        sb.append("UNSPECIFIED");
                        break;
                    case BATTERY_USAGE_TYPE_FOREGROUND:
                        sb.append("FOREGROUND");
                        break;
                    case BATTERY_USAGE_TYPE_BACKGROUND:
                        sb.append("BACKGROUND");
                        break;
                    case BATTERY_USAGE_TYPE_FOREGROUND_SERVICE:
                        sb.append("FOREGROUND_SERVICE");
                        break;
                    case BATTERY_USAGE_TYPE_CACHED:
                        sb.append("CACHED");
                        break;
                    default:
                        return "[UNKNOWN(" + Integer.toHexString(types) + ")]";
                }
                types &= ~type;
            }
            sb.append("]");
            return sb.toString();
        }

        void handleUidBatteryUsage(final int uid, final BatteryUsage usage) {
            final @ReasonCode int reason = shouldExemptUid(uid);
            if (reason != REASON_DENIED) {
                if (DEBUG_BACKGROUND_BATTERY_TRACKER) {
                    Slog.i(TAG, "Exempting battery usage in " + UserHandle.formatUid(uid)
                            + " " + PowerExemptionManager.reasonCodeToString(reason));
                }
                return;
            }
            boolean notifyController = false;
            boolean excessive = false;
            final double rbPercentage = sumPercentageOfTypes(usage.getPercentage(),
                    mBgCurrentDrainRestrictedBucketTypes);
            final double brPercentage = sumPercentageOfTypes(usage.getPercentage(),
                    mBgCurrentDrainBgRestrictedTypes);
            synchronized (mLock) {
                final int curLevel = mTracker.mAppRestrictionController.getRestrictionLevel(uid);
                if (curLevel >= RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                    // We're already in the background restricted level, nothing more we could do.
                    return;
                }
                final long now = SystemClock.elapsedRealtime();
                final int thresholdIndex = getCurrentDrainThresholdIndex(uid, now,
                        mBgCurrentDrainWindowMs);
                final int index = mHighBgBatteryPackages.indexOfKey(uid);
                final boolean decoupleThresholds = mBgCurrentDrainDecoupleThresholds;
                final double rbThreshold = mBgCurrentDrainRestrictedBucketThreshold[thresholdIndex];
                final double brThreshold = mBgCurrentDrainBgRestrictedThreshold[thresholdIndex];
                if (index < 0) {
                    long[] ts = null;
                    if (rbPercentage >= rbThreshold) {
                        // New findings to us, track it and let the controller know.
                        ts = new long[TIME_STAMP_INDEX_LAST];
                        ts[TIME_STAMP_INDEX_RESTRICTED_BUCKET] = now;
                        mHighBgBatteryPackages.put(uid, ts);
                        notifyController = excessive = true;
                    }
                    if (decoupleThresholds && brPercentage >= brThreshold) {
                        if (ts == null) {
                            ts = new long[TIME_STAMP_INDEX_LAST];
                            mHighBgBatteryPackages.put(uid, ts);
                        }
                        ts[TIME_STAMP_INDEX_BG_RESTRICTED] = now;
                        notifyController = excessive = true;
                    }
                } else {
                    final long[] ts = mHighBgBatteryPackages.valueAt(index);
                    final long lastRestrictBucketTs = ts[TIME_STAMP_INDEX_RESTRICTED_BUCKET];
                    if (rbPercentage >= rbThreshold) {
                        if (lastRestrictBucketTs == 0) {
                            ts[TIME_STAMP_INDEX_RESTRICTED_BUCKET] = now;
                        }
                        notifyController = excessive = true;
                    } else {
                        // It's actually back to normal, but we don't untrack it until
                        // explicit user interactions, because the restriction could be the cause
                        // of going back to normal.
                    }
                    if (brPercentage >= brThreshold) {
                        // If either
                        // a) It's configured to goto threshold 2 directly without threshold 1;
                        // b) It's already in the restricted standby bucket, but still seeing
                        //    high current drains, and it's been a while since it's restricted;
                        // tell the controller.
                        notifyController = decoupleThresholds
                                || (curLevel == RESTRICTION_LEVEL_RESTRICTED_BUCKET
                                && (now > lastRestrictBucketTs + mBgCurrentDrainWindowMs));
                        if (notifyController) {
                            ts[TIME_STAMP_INDEX_BG_RESTRICTED] = now;
                        }
                        excessive = true;
                    } else {
                        // Reset the track now - if it's already background restricted, it requires
                        // user consent to unrestrict it; or if it's in restricted bucket level,
                        // resetting this won't lift it from that level.
                        ts[TIME_STAMP_INDEX_BG_RESTRICTED] = 0;
                        // Now need to notify the controller.
                    }
                }
            }

            if (excessive) {
                if (DEBUG_BACKGROUND_BATTERY_TRACKER) {
                    Slog.i(TAG, "Excessive background current drain " + uid + " "
                            + usage + " (" + usage.percentageToString() + " ) over "
                            + TimeUtils.formatDuration(mBgCurrentDrainWindowMs));
                }
                if (notifyController) {
                    mTracker.mAppRestrictionController.refreshAppRestrictionLevelForUid(
                            uid, REASON_MAIN_FORCED_BY_SYSTEM,
                            REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE, true);
                }
            } else {
                if (DEBUG_BACKGROUND_BATTERY_TRACKER) {
                    Slog.i(TAG, "Background current drain backs to normal " + uid + " "
                            + usage + " (" + usage.percentageToString() + " ) over "
                            + TimeUtils.formatDuration(mBgCurrentDrainWindowMs));
                }
                // For now, we're not lifting the restrictions if the bg current drain backs to
                // normal util an explicit user interaction.
            }
        }

        private int getCurrentDrainThresholdIndex(int uid, long now, long window) {
            return (hasMediaPlayback(uid, now, window) || hasLocation(uid, now, window))
                    ? INDEX_HIGH_CURRENT_DRAIN_THRESHOLD
                    : INDEX_REGULAR_CURRENT_DRAIN_THRESHOLD;
        }

        private boolean hasMediaPlayback(int uid, long now, long window) {
            return mBgCurrentDrainEventDurationBasedThresholdEnabled
                    && mTracker.mAppRestrictionController.getCompositeMediaPlaybackDurations(
                            uid, now, window) >= mBgCurrentDrainMediaPlaybackMinDuration;
        }

        private boolean hasLocation(int uid, long now, long window) {
            if (!mBgCurrentDrainHighThresholdByBgLocation) {
                return false;
            }
            final AppRestrictionController controller = mTracker.mAppRestrictionController;
            if (mInjector.getPermissionManagerServiceInternal().checkUidPermission(
                    uid, ACCESS_BACKGROUND_LOCATION) == PERMISSION_GRANTED) {
                return true;
            }
            if (!mBgCurrentDrainEventDurationBasedThresholdEnabled) {
                return false;
            }
            final long since = Math.max(0, now - window);
            final long locationDuration = controller.getForegroundServiceTotalDurationsSince(
                    uid, since, now, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            return locationDuration >= mBgCurrentDrainLocationMinDuration;
        }

        void onUserInteractionStarted(String packageName, int uid) {
            boolean changed = false;
            synchronized (mLock) {
                final int curLevel = mTracker.mAppRestrictionController.getRestrictionLevel(
                        uid, packageName);
                if (curLevel == RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                    // It's a sticky state, user interaction won't change it, still track it.
                } else {
                    // Remove the given UID from our tracking list, as user interacted with it.
                    final int index = mHighBgBatteryPackages.indexOfKey(uid);
                    if (index >= 0) {
                        mHighBgBatteryPackages.removeAt(index);
                        changed = true;
                    }
                }
            }
            if (changed) {
                // Request to refresh the app restriction level.
                mTracker.mAppRestrictionController.refreshAppRestrictionLevelForUid(uid,
                        REASON_MAIN_USAGE, REASON_SUB_USAGE_USER_INTERACTION, true);
            }
        }

        void onBackgroundRestrictionChanged(int uid, String pkgName, boolean restricted) {
            if (restricted) {
                return;
            }
            synchronized (mLock) {
                // User has explicitly removed it from background restricted level,
                // clear the timestamp of the background-restricted
                final long[] ts = mHighBgBatteryPackages.get(uid);
                if (ts != null) {
                    ts[TIME_STAMP_INDEX_BG_RESTRICTED] = 0;
                }
            }
        }

        @VisibleForTesting
        void reset() {
            mHighBgBatteryPackages.clear();
            mTracker.reset();
        }

        @Override
        void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.println("APP BATTERY TRACKER POLICY SETTINGS:");
            final String indent = "  ";
            prefix = indent + prefix;
            super.dump(pw, prefix);
            if (isEnabled()) {
                pw.print(prefix);
                pw.print(KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_RESTRICTED_BUCKET);
                pw.print('=');
                pw.println(mBgCurrentDrainRestrictedBucketThreshold[
                        INDEX_REGULAR_CURRENT_DRAIN_THRESHOLD]);
                pw.print(prefix);
                pw.print(KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_TO_RESTRICTED_BUCKET);
                pw.print('=');
                pw.println(mBgCurrentDrainRestrictedBucketThreshold[
                        INDEX_HIGH_CURRENT_DRAIN_THRESHOLD]);
                pw.print(prefix);
                pw.print(KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_BG_RESTRICTED);
                pw.print('=');
                pw.println(mBgCurrentDrainBgRestrictedThreshold[
                        INDEX_REGULAR_CURRENT_DRAIN_THRESHOLD]);
                pw.print(prefix);
                pw.print(KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_TO_BG_RESTRICTED);
                pw.print('=');
                pw.println(mBgCurrentDrainBgRestrictedThreshold[
                        INDEX_HIGH_CURRENT_DRAIN_THRESHOLD]);
                pw.print(prefix);
                pw.print(KEY_BG_CURRENT_DRAIN_WINDOW);
                pw.print('=');
                pw.println(mBgCurrentDrainWindowMs);
                pw.print(prefix);
                pw.print(KEY_BG_CURRENT_DRAIN_MEDIA_PLAYBACK_MIN_DURATION);
                pw.print('=');
                pw.println(mBgCurrentDrainMediaPlaybackMinDuration);
                pw.print(prefix);
                pw.print(KEY_BG_CURRENT_DRAIN_LOCATION_MIN_DURATION);
                pw.print('=');
                pw.println(mBgCurrentDrainLocationMinDuration);
                pw.print(prefix);
                pw.print(KEY_BG_CURRENT_DRAIN_EVENT_DURATION_BASED_THRESHOLD_ENABLED);
                pw.print('=');
                pw.println(mBgCurrentDrainEventDurationBasedThresholdEnabled);
                pw.print(prefix);
                pw.print(KEY_BG_CURRENT_DRAIN_TYPES_TO_RESTRICTED_BUCKET);
                pw.print('=');
                pw.println(batteryUsageTypesToString(mBgCurrentDrainRestrictedBucketTypes));
                pw.print(prefix);
                pw.print(KEY_BG_CURRENT_DRAIN_TYPES_TO_BG_RESTRICTED);
                pw.print('=');
                pw.println(batteryUsageTypesToString(mBgCurrentDrainBgRestrictedTypes));
                pw.print(prefix);
                pw.print(KEY_BG_CURRENT_DRAIN_POWER_COMPONENTS);
                pw.print('=');
                pw.println(mBgCurrentDrainPowerComponents);
                pw.print(prefix);
                pw.print(KEY_BG_CURRENT_DRAIN_EXEMPTED_TYPES);
                pw.print('=');
                pw.println(BaseAppStateTracker.stateTypesToString(mBgCurrentDrainExemptedTypes));
                pw.print(prefix);
                pw.print(KEY_BG_CURRENT_DRAIN_HIGH_THRESHOLD_BY_BG_LOCATION);
                pw.print('=');
                pw.println(mBgCurrentDrainHighThresholdByBgLocation);

                pw.print(prefix);
                pw.println("Excessive current drain detected:");
                synchronized (mLock) {
                    final int size = mHighBgBatteryPackages.size();
                    prefix = indent + prefix;
                    if (size > 0) {
                        final long now = SystemClock.elapsedRealtime();
                        for (int i = 0; i < size; i++) {
                            final int uid = mHighBgBatteryPackages.keyAt(i);
                            final long[] ts = mHighBgBatteryPackages.valueAt(i);
                            final int thresholdIndex = getCurrentDrainThresholdIndex(uid, now,
                                    mBgCurrentDrainWindowMs);
                            pw.format("%s%s: (threshold=%4.2f%%/%4.2f%%) %s/%s\n",
                                    prefix,
                                    UserHandle.formatUid(uid),
                                    mBgCurrentDrainRestrictedBucketThreshold[thresholdIndex],
                                    mBgCurrentDrainBgRestrictedThreshold[thresholdIndex],
                                    ts[TIME_STAMP_INDEX_RESTRICTED_BUCKET] == 0 ? "0"
                                        : formatTime(ts[TIME_STAMP_INDEX_RESTRICTED_BUCKET], now),
                                    ts[TIME_STAMP_INDEX_BG_RESTRICTED] == 0 ? "0"
                                        : formatTime(ts[TIME_STAMP_INDEX_BG_RESTRICTED], now));
                        }
                    } else {
                        pw.print(prefix);
                        pw.println("(none)");
                    }
                }
            }
        }
    }
}
