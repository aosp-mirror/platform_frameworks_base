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

import static android.app.ActivityManager.RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
import static android.app.ActivityManager.RESTRICTION_LEVEL_BACKGROUND_RESTRICTED;
import static android.app.ActivityManager.RESTRICTION_LEVEL_RESTRICTED_BUCKET;
import static android.app.ActivityManager.isLowRamDeviceStatic;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_SYSTEM;
import static android.app.usage.UsageStatsManager.REASON_MAIN_USAGE;
import static android.app.usage.UsageStatsManager.REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_USER_INTERACTION;
import static android.os.BatteryConsumer.POWER_COMPONENT_ANY;
import static android.os.BatteryConsumer.PROCESS_STATE_BACKGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND;
import static android.os.BatteryConsumer.PROCESS_STATE_FOREGROUND_SERVICE;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.AppRestrictionController.DEVICE_CONFIG_SUBNAMESPACE_PREFIX;
import static com.android.server.am.BaseAppStateTracker.ONE_DAY;
import static com.android.server.am.BaseAppStateTracker.ONE_MINUTE;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager.RestrictionLevel;
import android.content.Context;
import android.os.BatteryConsumer;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.SystemClock;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseDoubleArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.server.am.AppBatteryTracker.AppBatteryPolicy;
import com.android.server.am.BaseAppStateTracker.Injector;
import com.android.server.pm.UserManagerInternal;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

/**
 * The battery usage tracker for apps, currently we are focusing on background + FGS battery here.
 */
final class AppBatteryTracker extends BaseAppStateTracker<AppBatteryPolicy> {
    static final String TAG = TAG_WITH_CLASS_NAME ? "AppBatteryTracker" : TAG_AM;

    private static final boolean DEBUG_BACKGROUND_BATTERY_TRACKER = false;

    // As we don't support realtime per-UID battery usage stats yet, we're polling the stats
    // in a regular time basis.
    private final long mBatteryUsageStatsPollingIntervalMs;

    // The timestamp when this system_server was started.
    private long mBootTimestamp;

    static final long BATTERY_USAGE_STATS_POLLING_INTERVAL_MS_LONG = 30 * ONE_MINUTE; // 30 mins
    static final long BATTERY_USAGE_STATS_POLLING_INTERVAL_MS_DEBUG = 2_000L; // 2s

    static final BatteryConsumer.Dimensions BATT_DIMEN_FG =
            new BatteryConsumer.Dimensions(POWER_COMPONENT_ANY, PROCESS_STATE_FOREGROUND);
    static final BatteryConsumer.Dimensions BATT_DIMEN_BG =
            new BatteryConsumer.Dimensions(POWER_COMPONENT_ANY, PROCESS_STATE_BACKGROUND);
    static final BatteryConsumer.Dimensions BATT_DIMEN_FGS =
            new BatteryConsumer.Dimensions(POWER_COMPONENT_ANY, PROCESS_STATE_FOREGROUND_SERVICE);

    private final Runnable mBgBatteryUsageStatsPolling = this::updateBatteryUsageStats;

    /**
     * This tracks the user ids which are or were active during the last polling window,
     * the index is the user id, and the value is if it's still running or not by now.
     */
    @GuardedBy("mLock")
    private final SparseBooleanArray mActiveUserIdStates = new SparseBooleanArray();

    // No lock is needed as it's accessed in the handler thread only.
    private final ArraySet<UserHandle> mTmpUserIds = new ArraySet<>();
    private final SparseDoubleArray mTmpBatteryConsumptions = new SparseDoubleArray();

    private BatteryUsageStatsQuery mBatteryUsageStatsQuery;

    AppBatteryTracker(Context context, AppRestrictionController controller) {
        this(context, controller, null, null);
    }

    AppBatteryTracker(Context context, AppRestrictionController controller,
            Constructor<? extends Injector<AppBatteryPolicy>> injector,
            Object outerContext) {
        super(context, controller, injector, outerContext);
        if (injector == null) {
            mBatteryUsageStatsPollingIntervalMs = BATTERY_USAGE_STATS_POLLING_INTERVAL_MS_LONG;
        } else {
            mBatteryUsageStatsPollingIntervalMs = BATTERY_USAGE_STATS_POLLING_INTERVAL_MS_DEBUG;
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
        mBootTimestamp = mInjector.currentTimeMillis();
        scheduleBatteryUsageStatsUpdateIfNecessary();
    }

    private void scheduleBatteryUsageStatsUpdateIfNecessary() {
        if (mInjector.getPolicy().isEnabled()) {
            synchronized (mLock) {
                if (!mBgHandler.hasCallbacks(mBgBatteryUsageStatsPolling)) {
                    mBgHandler.postDelayed(mBgBatteryUsageStatsPolling,
                            mBatteryUsageStatsPollingIntervalMs);
                }
            }
        }
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
    void onUserInteractionStarted(String packageName, int uid) {
        mInjector.getPolicy().onUserInteractionStarted(packageName, uid);
    }

    @Override
    void onBackgroundRestrictionChanged(int uid, String pkgName, boolean restricted) {
        mInjector.getPolicy().onBackgroundRestrictionChanged(uid, pkgName, restricted);
    }

    private void updateBatteryUsageStats() {
        final AppBatteryPolicy bgPolicy = mInjector.getPolicy();
        try {
            updateBatteryUsageStatsOnce(mTmpBatteryConsumptions, mTmpUserIds);
            final SparseDoubleArray uidConsumers = mTmpBatteryConsumptions;
            for (int i = 0, size = uidConsumers.size(); i < size; i++) {
                final int uid = uidConsumers.keyAt(i);
                final double bgConsumption = uidConsumers.valueAt(i);
                final double percentage = bgPolicy.getPercentage(bgConsumption);
                if (DEBUG_BACKGROUND_BATTERY_TRACKER) {
                    Slog.i(TAG, String.format("UID consumed %6.3f mAh (or %4.2f%%) in the past %s",
                            bgConsumption, percentage,
                            TimeUtils.formatDuration(bgPolicy.mBgCurrentDrainWindowMs)));
                }
                bgPolicy.handleUidBatteryConsumption(uid, percentage);
            }
        } finally {
            scheduleBatteryUsageStatsUpdateIfNecessary();
        }
    }

    /**
     * Query the battery usage stats and fill the UID battery usage into the given buffer.
     */
    private void updateBatteryUsageStatsOnce(@NonNull SparseDoubleArray buf,
            @NonNull ArraySet<UserHandle> userIdsHolder) {
        final AppBatteryPolicy bgPolicy = mInjector.getPolicy();
        final long now = mInjector.currentTimeMillis();
        buf.clear();
        userIdsHolder.clear();
        synchronized (mLock) {
            for (int i = mActiveUserIdStates.size() - 1; i >= 0; i--) {
                userIdsHolder.add(UserHandle.of(mActiveUserIdStates.keyAt(i)));
                if (!mActiveUserIdStates.valueAt(i)) {
                    mActiveUserIdStates.removeAt(i);
                }
            }
        }
        if (DEBUG_BACKGROUND_BATTERY_TRACKER) {
            Slog.i(TAG, "updateBatteryUsageStats");
        }
        BatteryUsageStatsQuery.Builder builder = new BatteryUsageStatsQuery.Builder()
                .includeProcessStateData()
                .setMaxStatsAgeMs(0);
        final BatteryUsageStats stats = updateBatteryUsageStatsOnceInternal(
                buf, builder, bgPolicy, userIdsHolder);
        final long curStart = stats != null ? stats.getStatsStartTimestamp() : 0L;
        final long curDuration = now - curStart;
        final long windowSize = calcWindowSize(now, bgPolicy);
        if (curDuration < windowSize) {
            // No sufficient data, query snapshots instead.
            builder = new BatteryUsageStatsQuery.Builder()
                    .includeProcessStateData()
                    .aggregateSnapshots(now - windowSize, curStart);
            updateBatteryUsageStatsOnceInternal(buf, builder, bgPolicy, userIdsHolder);
        } else if (curDuration > windowSize) {
            final double scale = windowSize * 1.0d / curDuration;
            for (int i = 0, size = buf.size(); i < size; i++) {
                buf.setValueAt(i, buf.valueAt(i) * scale);
            }
        }
    }

    private long calcWindowSize(long now, AppBatteryPolicy bgPolicy) {
        return Math.min(now - mBootTimestamp, bgPolicy.mBgCurrentDrainWindowMs);
    }

    private BatteryUsageStats updateBatteryUsageStatsOnceInternal(SparseDoubleArray buf,
            BatteryUsageStatsQuery.Builder builder, AppBatteryPolicy bgPolicy,
            ArraySet<UserHandle> userIds) {
        for (int i = 0, size = userIds.size(); i < size; i++) {
            builder.addUser(userIds.valueAt(i));
        }
        final List<BatteryUsageStats> statsList = mInjector.getBatteryStatsInternal()
                .getBatteryUsageStats(Arrays.asList(builder.build()));
        if (ArrayUtils.isEmpty(statsList)) {
            // Shouldn't happen unless in test.
            return null;
        }
        final BatteryUsageStats stats = statsList.get(0);
        final List<UidBatteryConsumer> uidConsumers = stats.getUidBatteryConsumers();
        for (UidBatteryConsumer uidConsumer : uidConsumers) {
            // TODO: b/200326767 - as we are not supporting per proc state attribution yet,
            // we couldn't distinguish between a real FGS vs. a bound FGS proc state.
            final int uid = uidConsumer.getUid();
            final double bgConsumption = bgPolicy.getBgConsumption(uidConsumer);
            int index = buf.indexOfKey(uid);
            if (index < 0) {
                buf.put(uid, bgConsumption);
            } else {
                buf.setValueAt(index, buf.valueAt(index) + bgConsumption);
            }
        }
        return stats;
    }

    private void onCurrentDrainMonitorEnabled(boolean enabled) {
        if (enabled) {
            if (!mBgHandler.hasCallbacks(mBgBatteryUsageStatsPolling)) {
                mBgHandler.postDelayed(mBgBatteryUsageStatsPolling,
                        mBatteryUsageStatsPollingIntervalMs);
            }
        } else {
            mBgHandler.removeCallbacks(mBgBatteryUsageStatsPolling);
        }
    }

    static final class AppBatteryPolicy extends BaseAppStatePolicy<AppBatteryTracker> {
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
         * Default value to {@link #mBgCurrentDrainMonitorEnabled}.
         */
        static final boolean DEFAULT_BG_CURRENT_DRAIN_MONITOR_ENABLED = true;

        /**
         * Default value to {@link #mBgCurrentDrainRestrictedBucketThreshold}.
         */
        static final float DEFAULT_BG_CURRENT_DRAIN_RESTRICTED_BUCKET_THRESHOLD =
                isLowRamDeviceStatic() ? 4.0f : 2.0f;

        /**
         * Default value to {@link #mBgCurrentDrainBgRestrictedThreshold}.
         */
        static final float DEFAULT_BG_CURRENT_DRAIN_BG_RESTRICTED_THRESHOLD =
                isLowRamDeviceStatic() ? 8.0f : 4.0f;

        /**
         * Default value to {@link #mBgCurrentDrainWindowMs}.
         */
        static final long DEFAULT_BG_CURRENT_DRAIN_WINDOW_MS = ONE_DAY;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_MONITOR_ENABLED.
         */
        volatile boolean mBgCurrentDrainMonitorEnabled = DEFAULT_BG_CURRENT_DRAIN_MONITOR_ENABLED;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_RESTRICTED_BUCKET.
         */
        volatile float mBgCurrentDrainRestrictedBucketThreshold =
                DEFAULT_BG_CURRENT_DRAIN_RESTRICTED_BUCKET_THRESHOLD;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_BG_RESTRICTED.
         */
        volatile float mBgCurrentDrainBgRestrictedThreshold =
                DEFAULT_BG_CURRENT_DRAIN_BG_RESTRICTED_THRESHOLD;

        /**
         * @see #KEY_BG_CURRENT_DRAIN_WINDOW.
         */
        volatile long mBgCurrentDrainWindowMs = DEFAULT_BG_CURRENT_DRAIN_WINDOW_MS;

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
            super(injector, tracker);
            mLock = tracker.mLock;
        }

        @Override
        public void onPropertiesChanged(String name) {
            switch (name) {
                case KEY_BG_CURRENT_DRAIN_MONITOR_ENABLED:
                    updateCurrentDrainMonitorEnabled();
                    break;
                case KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_RESTRICTED_BUCKET:
                case KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_BG_RESTRICTED:
                    updateCurrentDrainThreshold();
                    break;
                case KEY_BG_CURRENT_DRAIN_WINDOW:
                    updateCurrentDrainWindow();
                    break;
            }
        }

        private void updateCurrentDrainMonitorEnabled() {
            final boolean enabled = mBatteryFullChargeMah > 0
                    && DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_MONITOR_ENABLED,
                    DEFAULT_BG_CURRENT_DRAIN_MONITOR_ENABLED);
            if (enabled != mBgCurrentDrainMonitorEnabled) {
                mBgCurrentDrainMonitorEnabled = enabled;
                mTracker.onCurrentDrainMonitorEnabled(enabled);
            }
        }

        private void updateCurrentDrainThreshold() {
            mBgCurrentDrainRestrictedBucketThreshold = DeviceConfig.getFloat(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_RESTRICTED_BUCKET,
                    DEFAULT_BG_CURRENT_DRAIN_RESTRICTED_BUCKET_THRESHOLD);
            mBgCurrentDrainBgRestrictedThreshold = DeviceConfig.getFloat(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_THRESHOLD_TO_BG_RESTRICTED,
                    DEFAULT_BG_CURRENT_DRAIN_BG_RESTRICTED_THRESHOLD);
        }

        private void updateCurrentDrainWindow() {
            mBgCurrentDrainWindowMs = DeviceConfig.getLong(
                    DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_BG_CURRENT_DRAIN_WINDOW,
                    DEFAULT_BG_CURRENT_DRAIN_WINDOW_MS);
        }

        @Override
        public void onSystemReady() {
            mBatteryFullChargeMah =
                    mInjector.getBatteryManagerInternal().getBatteryFullCharge() / 1000;
            updateCurrentDrainMonitorEnabled();
            updateCurrentDrainThreshold();
            updateCurrentDrainWindow();
        }

        @Override
        public @RestrictionLevel int getProposedRestrictionLevel(String packageName, int uid) {
            synchronized (mLock) {
                final int index = mHighBgBatteryPackages.indexOfKey(uid);
                if (index < 0) {
                    // Not found, return adaptive as the default one.
                    return RESTRICTION_LEVEL_ADAPTIVE_BUCKET;
                }
                final long[] ts = mHighBgBatteryPackages.valueAt(index);
                return ts[TIME_STAMP_INDEX_BG_RESTRICTED] > 0
                        ? RESTRICTION_LEVEL_BACKGROUND_RESTRICTED
                        : RESTRICTION_LEVEL_RESTRICTED_BUCKET;
            }
        }

        @Override
        public boolean isEnabled() {
            return mBgCurrentDrainMonitorEnabled;
        }

        double getBgConsumption(final UidBatteryConsumer uidConsumer) {
            return getConsumedPowerNoThrow(uidConsumer, BATT_DIMEN_BG)
                    + getConsumedPowerNoThrow(uidConsumer, BATT_DIMEN_FGS);
        }

        double getPercentage(final double consumption) {
            return consumption / mBatteryFullChargeMah * 100;
        }

        void handleUidBatteryConsumption(final int uid, final double percentage) {
            if (shouldExemptUid(uid)) {
                return;
            }
            boolean notifyController = false;
            boolean excessive = false;
            synchronized (mLock) {
                final int curLevel = mTracker.mAppRestrictionController.getRestrictionLevel(uid);
                if (curLevel >= RESTRICTION_LEVEL_BACKGROUND_RESTRICTED) {
                    // We're already in the background restricted level, nothing more we could do.
                    return;
                }
                final int index = mHighBgBatteryPackages.indexOfKey(uid);
                if (index < 0) {
                    if (percentage >= mBgCurrentDrainRestrictedBucketThreshold) {
                        // New findings to us, track it and let the controller know.
                        final long[] ts = new long[TIME_STAMP_INDEX_LAST];
                        ts[TIME_STAMP_INDEX_RESTRICTED_BUCKET] = SystemClock.elapsedRealtime();
                        mHighBgBatteryPackages.put(uid, ts);
                        notifyController = excessive = true;
                    }
                } else {
                    final long[] ts = mHighBgBatteryPackages.valueAt(index);
                    if (percentage < mBgCurrentDrainRestrictedBucketThreshold) {
                        // it's actually back to normal, but we don't untrack it until
                        // explicit user interactions.
                        notifyController = true;
                    } else if (percentage >= mBgCurrentDrainBgRestrictedThreshold) {
                        // If we're in the restricted standby bucket but still seeing high
                        // current drains, tell the controller again.
                        if (curLevel == RESTRICTION_LEVEL_RESTRICTED_BUCKET
                                && ts[TIME_STAMP_INDEX_BG_RESTRICTED] == 0) {
                            final long now = SystemClock.elapsedRealtime();
                            if (now > ts[TIME_STAMP_INDEX_RESTRICTED_BUCKET]
                                    + mBgCurrentDrainWindowMs) {
                                ts[TIME_STAMP_INDEX_BG_RESTRICTED] = now;
                                notifyController = excessive = true;
                            }
                        }
                    }
                }
            }

            if (excessive) {
                if (DEBUG_BACKGROUND_BATTERY_TRACKER) {
                    Slog.i(TAG, "Excessive background current drain " + uid
                            + String.format(" %.2f%%", percentage) + " over "
                            + TimeUtils.formatDuration(mBgCurrentDrainWindowMs));
                }
                if (notifyController) {
                    mTracker.mAppRestrictionController.refreshAppRestrictionLevelForUid(
                            uid, REASON_MAIN_FORCED_BY_SYSTEM,
                            REASON_SUB_FORCED_SYSTEM_FLAG_ABUSE, true);
                }
            } else {
                if (DEBUG_BACKGROUND_BATTERY_TRACKER) {
                    Slog.i(TAG, "Background current drain backs to normal " + uid
                            + String.format(" %.2f%%", percentage) + " over "
                            + TimeUtils.formatDuration(mBgCurrentDrainWindowMs));
                }
                // For now, we're not lifting the restrictions if the bg current drain backs to
                // normal util an explicit user interaction.
            }
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

        private double getConsumedPowerNoThrow(final UidBatteryConsumer uidConsumer,
                final BatteryConsumer.Dimensions dimens) {
            try {
                return uidConsumer.getConsumedPower(dimens);
            } catch (IllegalArgumentException e) {
                return 0.0d;
            }
        }

        /**
         * Note: The {@link com.android.server.usage.AppStandbyController} has a complete exemption
         * list and will place exempted packages in exempted bucket eventually.
         */
        @Override
        public boolean shouldExemptUid(int uid) {
            if (!super.shouldExemptUid(uid)) {
                // TODO: b/200326767 - Exempt the location/music/pinned apps.
            }
            return false;
        }
    }
}
