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
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.appsearch.external.localstorage.AppSearchLogger;
import com.android.server.appsearch.external.localstorage.stats.CallStats;
import com.android.server.appsearch.external.localstorage.stats.InitializeStats;
import com.android.server.appsearch.external.localstorage.stats.PutDocumentStats;
import com.android.server.appsearch.external.localstorage.stats.RemoveStats;
import com.android.server.appsearch.external.localstorage.stats.SearchStats;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
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

    // User we're logging for.
    private final UserHandle mUserHandle;

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
         * @param samplingRatios                      SparseArray to customize sampling ratio for
         *                                            different stat types
         */
        public Config(long minTimeIntervalBetweenSamplesMillis,
                int defaultSamplingRatio,
                @NonNull SparseIntArray samplingRatios) {
            // TODO(b/173532925) Probably we can get rid of those three after we have p/h flags
            // for them.
            // e.g. we can just call DeviceConfig.get(SAMPLING_RATIO_FOR_PUT_DOCUMENTS).
            mMinTimeIntervalBetweenSamplesMillis = minTimeIntervalBetweenSamplesMillis;
            mDefaultSamplingRatio = defaultSamplingRatio;
            mSamplingRatios = samplingRatios;
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
    public PlatformLogger(
            @NonNull Context context, @NonNull UserHandle userHandle, @NonNull Config config) {
        mContext = Objects.requireNonNull(context);
        mUserHandle = Objects.requireNonNull(userHandle);
        mConfig = Objects.requireNonNull(config);
    }

    /** Logs {@link CallStats}. */
    @Override
    public void logStats(@NonNull CallStats stats) {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            if (shouldLogForTypeLocked(stats.getCallType())) {
                logStatsImplLocked(stats);
            }
        }
    }

    /** Logs {@link PutDocumentStats}. */
    @Override
    public void logStats(@NonNull PutDocumentStats stats) {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            if (shouldLogForTypeLocked(CallStats.CALL_TYPE_PUT_DOCUMENT)) {
                logStatsImplLocked(stats);
            }
        }
    }

    @Override
    public void logStats(@NonNull InitializeStats stats) throws AppSearchException {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            if (shouldLogForTypeLocked(CallStats.CALL_TYPE_INITIALIZE)) {
                logStatsImplLocked(stats);
            }
        }
    }

    @Override
    public void logStats(@NonNull SearchStats stats) throws AppSearchException {
        Objects.requireNonNull(stats);
        synchronized (mLock) {
            if (shouldLogForTypeLocked(CallStats.CALL_TYPE_SEARCH)) {
                logStatsImplLocked(stats);
            }
        }
    }

    @Override
    public void logStats(@androidx.annotation.NonNull RemoveStats stats) throws AppSearchException {
        // TODO(b/173532925): Log stats
    }

    /**
     * Removes cached UID for package.
     *
     * @return removed UID for the package, or {@code INVALID_UID} if package was not previously
     * cached.
    */
    public int removeCachedUidForPackage(@NonNull String packageName) {
        // TODO(b/173532925) This needs to be called when we get PACKAGE_REMOVED intent
        Objects.requireNonNull(packageName);
        synchronized (mLock) {
            Integer uid = mPackageUidCacheLocked.remove(packageName);
            return uid != null ? uid : Process.INVALID_UID;
        }
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull CallStats stats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats = createExtraStatsLocked(stats.getGeneralStats().getPackageName(),
                stats.getCallType());
        String database = stats.getGeneralStats().getDatabase();
        try {
            int hashCodeForDatabase = calculateHashCodeMd5(database);
            AppSearchStatsLog.write(AppSearchStatsLog.APP_SEARCH_CALL_STATS_REPORTED,
                    extraStats.mSamplingRatio,
                    extraStats.mSkippedSampleCount,
                    extraStats.mPackageUid,
                    hashCodeForDatabase,
                    stats.getGeneralStats().getStatusCode(),
                    stats.getGeneralStats().getTotalLatencyMillis(),
                    stats.getCallType(),
                    stats.getEstimatedBinderLatencyMillis(),
                    stats.getNumOperationsSucceeded(),
                    stats.getNumOperationsFailed());
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            // TODO(b/184204720) report hashing error to Westworld
            //  We need to set a special value(e.g. 0xFFFFFFFF) for the hashing of the database,
            //  so in the dashboard we know there is some error for hashing.
            //
            // Something is wrong while calculating the hash code for database
            // this shouldn't happen since we always use "MD5" and "UTF-8"
            if (database != null) {
                Log.e(TAG, "Error calculating hash code for database " + database, e);
            }
        }
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull PutDocumentStats stats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats = createExtraStatsLocked(stats.getGeneralStats().getPackageName(),
                CallStats.CALL_TYPE_PUT_DOCUMENT);
        String database = stats.getGeneralStats().getDatabase();
        try {
            int hashCodeForDatabase = calculateHashCodeMd5(database);
            AppSearchStatsLog.write(AppSearchStatsLog.APP_SEARCH_PUT_DOCUMENT_STATS_REPORTED,
                    extraStats.mSamplingRatio,
                    extraStats.mSkippedSampleCount,
                    extraStats.mPackageUid,
                    hashCodeForDatabase,
                    stats.getGeneralStats().getStatusCode(),
                    stats.getGeneralStats().getTotalLatencyMillis(),
                    stats.getGenerateDocumentProtoLatencyMillis(),
                    stats.getRewriteDocumentTypesLatencyMillis(),
                    stats.getNativeLatencyMillis(),
                    stats.getNativeDocumentStoreLatencyMillis(),
                    stats.getNativeIndexLatencyMillis(),
                    stats.getNativeIndexMergeLatencyMillis(),
                    stats.getNativeDocumentSizeBytes(),
                    stats.getNativeNumTokensIndexed(),
                    stats.getNativeExceededMaxNumTokens());
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            // TODO(b/184204720) report hashing error to Westworld
            //  We need to set a special value(e.g. 0xFFFFFFFF) for the hashing of the database,
            //  so in the dashboard we know there is some error for hashing.
            //
            // Something is wrong while calculating the hash code for database
            // this shouldn't happen since we always use "MD5" and "UTF-8"
            if (database != null) {
                Log.e(TAG, "Error calculating hash code for database " + database, e);
            }
        }
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull SearchStats stats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats = createExtraStatsLocked(stats.getPackageName(),
                CallStats.CALL_TYPE_SEARCH);
        String database = stats.getDatabase();
        try {
            int hashCodeForDatabase = calculateHashCodeMd5(database);
            AppSearchStatsLog.write(AppSearchStatsLog.APP_SEARCH_QUERY_STATS_REPORTED,
                    extraStats.mSamplingRatio,
                    extraStats.mSkippedSampleCount,
                    extraStats.mPackageUid,
                    hashCodeForDatabase,
                    stats.getStatusCode(),
                    stats.getTotalLatencyMillis(),
                    stats.getRewriteSearchSpecLatencyMillis(),
                    stats.getRewriteSearchResultLatencyMillis(),
                    stats.getVisibilityScope(),
                    stats.getNativeLatencyMillis(),
                    stats.getTermCount(),
                    stats.getQueryLength(),
                    stats.getFilteredNamespaceCount(),
                    stats.getFilteredSchemaTypeCount(),
                    stats.getRequestedPageSize(),
                    stats.getCurrentPageReturnedResultCount(),
                    stats.isFirstPage(),
                    stats.getParseQueryLatencyMillis(),
                    stats.getRankingStrategy(),
                    stats.getScoredDocumentCount(),
                    stats.getScoringLatencyMillis(),
                    stats.getRankingLatencyMillis(),
                    stats.getDocumentRetrievingLatencyMillis(),
                    stats.getResultWithSnippetsCount());
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            // TODO(b/184204720) report hashing error to Westworld
            //  We need to set a special value(e.g. 0xFFFFFFFF) for the hashing of the database,
            //  so in the dashboard we know there is some error for hashing.
            //
            // Something is wrong while calculating the hash code for database
            // this shouldn't happen since we always use "MD5" and "UTF-8"
            if (database != null) {
                Log.e(TAG, "Error calculating hash code for database " + database, e);
            }
        }
    }

    @GuardedBy("mLock")
    private void logStatsImplLocked(@NonNull InitializeStats stats) {
        mLastPushTimeMillisLocked = SystemClock.elapsedRealtime();
        ExtraStats extraStats = createExtraStatsLocked(/*packageName=*/ null,
                CallStats.CALL_TYPE_INITIALIZE);
        AppSearchStatsLog.write(AppSearchStatsLog.APP_SEARCH_INITIALIZE_STATS_REPORTED,
                extraStats.mSamplingRatio,
                extraStats.mSkippedSampleCount,
                extraStats.mPackageUid,
                stats.getStatusCode(),
                stats.getTotalLatencyMillis(),
                stats.hasDeSync(),
                stats.getPrepareSchemaAndNamespacesLatencyMillis(),
                stats.getPrepareVisibilityStoreLatencyMillis(),
                stats.getNativeLatencyMillis(),
                stats.getDocumentStoreRecoveryCause(),
                stats.getIndexRestorationCause(),
                stats.getSchemaStoreRecoveryCause(),
                stats.getDocumentStoreRecoveryLatencyMillis(),
                stats.getIndexRestorationLatencyMillis(),
                stats.getSchemaStoreRecoveryLatencyMillis(),
                stats.getDocumentStoreDataStatus(),
                stats.getDocumentCount(),
                stats.getSchemaTypeCount());
    }

    /**
     * Calculate the hash code as an integer by returning the last four bytes of its MD5.
     *
     * @param str a string
     * @return hash code as an integer. returns -1 if str is null.
     * @throws AppSearchException if either algorithm or encoding does not exist.
     */
    @VisibleForTesting
    @NonNull
    static int calculateHashCodeMd5(@Nullable String str) throws
            NoSuchAlgorithmException, UnsupportedEncodingException {
        if (str == null) {
            // Just return -1 if caller doesn't have database name
            // For some stats like globalQuery, databaseName can be null.
            // Since in atom it is an integer, we have to return something here.
            return -1;
        }

        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(str.getBytes(/*charsetName=*/ "UTF-8"));
        byte[] digest = md.digest();

        // Since MD5 generates 16 bytes digest, we don't need to check the length here to see
        // if it is smaller than sizeof(int)(4).
        //
        // We generate the same value as BigInteger(digest).intValue().
        // BigInteger takes bytes[] and treat it as big endian. And its intValue() would get the
        // lower 4 bytes. So here we take the last 4 bytes and treat them as big endian.
        return (digest[12] & 0xFF) << 24
                | (digest[13] & 0xFF) << 16
                | (digest[14] & 0xFF) << 8
                | (digest[15] & 0xFF);
    }

    /**
     * Creates {@link ExtraStats} to hold additional information generated for logging.
     *
     * <p>This method is called by most of logToWestworldLocked functions to reduce code
     * duplication.
     */
    // TODO(b/173532925) Once we add CTS test for logging atoms and can inspect the result, we can
    // remove this @VisibleForTesting and directly use PlatformLogger.logStats to test sampling and
    // rate limiting.
    @VisibleForTesting
    @GuardedBy("mLock")
    @NonNull
    ExtraStats createExtraStatsLocked(@Nullable String packageName,
            @CallStats.CallType int callType) {
        int packageUid = Process.INVALID_UID;
        if (packageName != null) {
            packageUid = getPackageUidAsUserLocked(packageName);
        }

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
    // TODO(b/173532925) Once we add CTS test for logging atoms and can inspect the result, we can
    // remove this @VisibleForTesting and directly use PlatformLogger.logStats to test sampling and
    // rate limiting.
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
            packageUid = mContext.getPackageManager().getPackageUidAsUser(
                    packageName, mUserHandle.getIdentifier());
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
