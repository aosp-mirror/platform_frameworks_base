/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.appsearch.stats;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.appsearch.external.localstorage.AppSearchLogger;
import com.android.server.appsearch.external.localstorage.stats.CallStats;
import com.android.server.appsearch.external.localstorage.stats.PutDocumentStats;

import java.util.Map;
import java.util.Random;

/**
 * Logger Implementation using Westworld.
 *
 * <p>This class is thread-safe.
 *
 * @hide
 */
public final class PlatformLogger implements AppSearchLogger {
    private static final String TAG = "AppSearchPlatformLogger";

    // Context of the system service.
    private final Context mContext;

    // User ID of the caller who we're logging for.
    private final int mUserId;

    // Configuration for the logger
    private final Config mConfig;

    private final Random mRng = new Random();
    private final Object mLock = new Object();

    /**
     * SparseArray to track how many stats we skipped due to
     * {@link Config#mMinTimeIntervalBetweenSamplesMillis}.
     *
     * <p> We can have correct extrapolated number by adding those counts back when we log
     * the same type of stats next time. E.g. the true count of an event could be estimated as:
     * SUM(sampling_ratio * (num_skipped_sample + 1)) as est_count
     *
     * <p>The key to the SparseArray is {@link CallStats.CallType}
     */
    @GuardedBy("mLock")
    private final SparseIntArray mSkippedSampleCountLocked =
            new SparseIntArray();

    /**
     * Map to cache the packageUid for each package.
     *
     * <p>It maps packageName to packageUid.
     *
     * <p>The entry will be removed whenever the app gets uninstalled
     */
    @GuardedBy("mLock")
    private final Map<String, Integer> mPackageUidCacheLocked =
            new ArrayMap<>();

    /**
     * Elapsed time for last stats logged from boot in millis
     */
    @GuardedBy("mLock")
    private long mLastPushTimeMillisLocked = 0;

    /**
     * Class to configure the {@link PlatformLogger}
     */
    public static final class Config {
        // Minimum time interval (in millis) since last message logged to Westworld before
        // logging again.
        private final long mMinTimeIntervalBetweenSamplesMillis;

        // Default sampling ratio for all types of stats
        private final int mDefaultSamplingRatio;

        /**
         * Sampling ratios for different types of stats
         *
         * <p>This SparseArray is passed by client and is READ-ONLY. The key to that SparseArray is
         * {@link CallStats.CallType}
         *
         * <p>If sampling ratio is missing for certain stats type,
         * {@link Config#mDefaultSamplingRatio} will be used.
         *
         * <p>E.g. sampling ratio=10 means that one out of every 10 stats was logged. If sampling
         * ratio is 1, we will log each sample and it acts as if the sampling is disabled.
         */
        @NonNull
        private final SparseIntArray mSamplingRatios;

        /**
         * Configuration for {@link PlatformLogger}
         *
         * @param minTimeIntervalBetweenSamplesMillis minimum time interval apart in Milliseconds
         *                                            required for two consecutive stats logged
         * @param defaultSamplingRatio                default sampling ratio
         * @param samplingRatios                    SparseArray to customize sampling ratio for
         *                                            different stat types
         */
        public Config(long minTimeIntervalBetweenSamplesMillis,
                int defaultSamplingRatio,
                @Nullable SparseIntArray samplingRatios) {
            mMinTimeIntervalBetweenSamplesMillis = minTimeIntervalBetweenSamplesMillis;
            mDefaultSamplingRatio = defaultSamplingRatio;
            if (samplingRatios != null) {
                mSamplingRatios = samplingRatios;
            } else {
                mSamplingRatios = new SparseIntArray();
            }
        }
    }

    /**
     * Helper class to hold platform specific stats for Westworld.
     */
    static final class ExtraStats {
        // UID for the calling package of the stats.
        final int mPackageUid;
        // sampling ratio for the call type of the stats.
        final int mSamplingRatio;
        // number of samplings skipped before the current one for the same call type.
        final int mSkippedSampleCount;

        ExtraStats(int packageUid, int samplingRatio, int skippedSampleCount) {
            mPackageUid = packageUid;
            mSamplingRatio = samplingRatio;
            mSkippedSampleCount = skippedSampleCount;
        }
    }

    /**
     * Westworld constructor
     */
    public PlatformLogger(@NonNull Context context, int userId, @NonNull Config config) {
        mContext = Preconditions.checkNotNull(context);
        mConfig = Preconditions.checkNotNull(config);
        mUserId = userId;
    }

    /** Logs {@link CallStats}. */
    @Override
    public void logStats(@NonNull CallStats stats) {
        Preconditions.checkNotNull(stats);
        synchronized (mLock) {
            if (shouldLogForTypeLocked(stats.getCallType())) {
                logToWestworldLocked(stats);
            }
        }
    }

    /** Logs {@link PutDocumentStats}. */
    @Override
    public void logStats(@NonNull PutDocumentStats stats) {
        Preconditions.checkNotNull(stats);
        synchronized (mLock) {
            if (shouldLogForTypeLocked(CallStats.CALL_TYPE_PUT_DOCUMENT)) {
                logToWestworldLocked(stats);
            }
        }
    }

    /**
     * Removes cached UID for package.
     *
     * @return removed UID for the package, or {@code INVALID_UID} if package was not previously
     * cached.
    */
    public int removeCachedUidForPackage(@NonNull String packageName) {
        // TODO(b/173532925) This needs to be called when we get PACKAGE_REMOVED intent
        Preconditions.checkNotNull(packageName);
        synchronized (mLock) {
            Integer uid = mPackageUidCacheLocked.remove(packageName);
            return uid != null ? uid : Process.INVALID_UID;
        }
    }

    @GuardedBy("mLock")
    private void logToWestworldLocked(@NonNull CallStats stats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats = createExtraStatsLocked(stats.getGeneralStats().getPackageName(),
                stats.getCallType());
        /* TODO(b/173532925) Log the CallStats to Westworld
        stats.log(..., samplingRatio, skippedSampleCount, ...)
         */
    }

    @GuardedBy("mLock")
    private void logToWestworldLocked(@NonNull PutDocumentStats stats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats = createExtraStatsLocked(stats.getGeneralStats().getPackageName(),
                CallStats.CALL_TYPE_PUT_DOCUMENT);
        /* TODO(b/173532925) Log the PutDocumentStats to Westworld
        stats.log(..., samplingRatio, skippedSampleCount, ...)
         */
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    @NonNull
    ExtraStats createExtraStatsLocked(@NonNull String packageName,
            @CallStats.CallType int callType) {
        int packageUid = getPackageUidAsUserLocked(packageName);
        int samplingRatio = mConfig.mSamplingRatios.get(callType,
                mConfig.mDefaultSamplingRatio);

        int skippedSampleCount = mSkippedSampleCountLocked.get(callType,
                /*valueOfKeyIfNotFound=*/ 0);
        mSkippedSampleCountLocked.put(callType, 0);

        return new ExtraStats(packageUid, samplingRatio, skippedSampleCount);
    }

    /**
     * Checks if this stats should be logged.
     *
     * <p>It won't be logged if it is "sampled" out, or it is too close to the previous logged
     * stats.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    boolean shouldLogForTypeLocked(@CallStats.CallType int callType) {
        int samplingRatio = mConfig.mSamplingRatios.get(callType,
                mConfig.mDefaultSamplingRatio);

        // Sampling
        if (!shouldSample(samplingRatio)) {
            return false;
        }

        // Rate limiting
        // Check the timestamp to see if it is too close to last logged sample
        long currentTimeMillis = SystemClock.elapsedRealtime();
        if (mLastPushTimeMillisLocked
                > currentTimeMillis - mConfig.mMinTimeIntervalBetweenSamplesMillis) {
            int count = mSkippedSampleCountLocked.get(callType, /*valueOfKeyIfNotFound=*/ 0);
            ++count;
            mSkippedSampleCountLocked.put(callType, count);
            return false;
        }

        return true;
    }

    /**
     * Checks if the stats should be "sampled"
     *
     * @param samplingRatio sampling ratio
     * @return if the stats should be sampled
     */
    private boolean shouldSample(int samplingRatio) {
        if (samplingRatio <= 0) {
            return false;
        }

        return mRng.nextInt((int) samplingRatio) == 0;
    }

    /**
     * Finds the UID of the {@code packageName}. Returns {@link Process#INVALID_UID} if unable to
     * find the UID.
     */
    @GuardedBy("mLock")
    private int getPackageUidAsUserLocked(@NonNull String packageName) {
        Integer packageUid = mPackageUidCacheLocked.get(packageName);
        if (packageUid != null) {
            return packageUid;
        }

        // TODO(b/173532925) since VisibilityStore has the same method, we can make this a
        //  utility function
        try {
            packageUid = mContext.getPackageManager().getPackageUidAsUser(packageName, mUserId);
            mPackageUidCacheLocked.put(packageName, packageUid);
            return packageUid;
        } catch (PackageManager.NameNotFoundException e) {
            // Package doesn't exist, continue
        }
        return Process.INVALID_UID;
    }

    //
    // Functions below are used for tests only
    //
    @VisibleForTesting
    @GuardedBy("mLock")
    void setLastPushTimeMillisLocked(long lastPushElapsedTimeMillis) {
        mLastPushTimeMillisLocked = lastPushElapsedTimeMillis;
    }
}
