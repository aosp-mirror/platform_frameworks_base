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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.SparseIntArray;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.external.localstorage.MockPackageManager;
import com.android.server.appsearch.external.localstorage.stats.CallStats;

import org.junit.Before;
import org.junit.Test;

public class PlatformLoggerTest {
    private static final int TEST_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS = 100;
    private static final int TEST_DEFAULT_SAMPLING_RATIO = 10;
    private static final String TEST_PACKAGE_NAME = "packageName";
    private MockPackageManager mMockPackageManager = new MockPackageManager();
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        mContext =
                new ContextWrapper(context) {
                    @Override
                    public PackageManager getPackageManager() {
                        return mMockPackageManager.getMockPackageManager();
                    }
                };
    }

    @Test
    public void testcreateExtraStatsLocked_nullSamplingRatioMap_returnsDefaultSamplingRatio() {
        PlatformLogger logger = new PlatformLogger(
                ApplicationProvider.getApplicationContext(),
                UserHandle.USER_NULL,
                new PlatformLogger.Config(
                        TEST_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS,
                        TEST_DEFAULT_SAMPLING_RATIO,
                        /*samplingRatioMap=*/ null));

        // Make sure default sampling ratio is used if samplingMap is not provided.
        assertThat(logger.createExtraStatsLocked(TEST_PACKAGE_NAME,
                CallStats.CALL_TYPE_UNKNOWN).mSamplingRatio).isEqualTo(
                TEST_DEFAULT_SAMPLING_RATIO);
        assertThat(logger.createExtraStatsLocked(TEST_PACKAGE_NAME,
                CallStats.CALL_TYPE_INITIALIZE).mSamplingRatio).isEqualTo(
                TEST_DEFAULT_SAMPLING_RATIO);
        assertThat(logger.createExtraStatsLocked(TEST_PACKAGE_NAME,
                CallStats.CALL_TYPE_QUERY).mSamplingRatio).isEqualTo(
                TEST_DEFAULT_SAMPLING_RATIO);
        assertThat(logger.createExtraStatsLocked(TEST_PACKAGE_NAME,
                CallStats.CALL_TYPE_FLUSH).mSamplingRatio).isEqualTo(
                TEST_DEFAULT_SAMPLING_RATIO);
    }


    @Test
    public void testcreateExtraStatsLocked_with_samplingRatioMap_returnsConfiguredSamplingRatio() {
        int putDocumentSamplingRatio = 1;
        int querySamplingRatio = 2;
        final SparseIntArray samplingRatios = new SparseIntArray();
        samplingRatios.put(CallStats.CALL_TYPE_PUT_DOCUMENT, putDocumentSamplingRatio);
        samplingRatios.put(CallStats.CALL_TYPE_QUERY, querySamplingRatio);
        PlatformLogger logger = new PlatformLogger(
                ApplicationProvider.getApplicationContext(),
                UserHandle.USER_NULL,
                new PlatformLogger.Config(
                        TEST_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS,
                        TEST_DEFAULT_SAMPLING_RATIO,
                        samplingRatios));

         // The default sampling ratio should be used if no sampling ratio is
         // provided for certain call type.
        assertThat(logger.createExtraStatsLocked(TEST_PACKAGE_NAME,
                CallStats.CALL_TYPE_INITIALIZE).mSamplingRatio).isEqualTo(
                TEST_DEFAULT_SAMPLING_RATIO);
        assertThat(logger.createExtraStatsLocked(TEST_PACKAGE_NAME,
                CallStats.CALL_TYPE_FLUSH).mSamplingRatio).isEqualTo(
                TEST_DEFAULT_SAMPLING_RATIO);

        // The configured sampling ratio is used if sampling ratio is available
        // for certain call type.
        assertThat(logger.createExtraStatsLocked(TEST_PACKAGE_NAME,
                CallStats.CALL_TYPE_PUT_DOCUMENT).mSamplingRatio).isEqualTo(
                putDocumentSamplingRatio);
        assertThat(logger.createExtraStatsLocked(TEST_PACKAGE_NAME,
                CallStats.CALL_TYPE_QUERY).mSamplingRatio).isEqualTo(
                querySamplingRatio);
    }

    @Test
    public void testShouldLogForTypeLocked_trueWhenSampleRatioIsOne() {
        final int samplingRatio = 1;
        final String testPackageName = "packageName";
        PlatformLogger logger = new PlatformLogger(
                ApplicationProvider.getApplicationContext(),
                UserHandle.USER_NULL,
                new PlatformLogger.Config(
                        TEST_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS,
                        samplingRatio,
                        /* samplingMap=*/ null));

        // Sample should always be logged for the first time if sampling is disabled(value is one).
        assertThat(logger.shouldLogForTypeLocked(CallStats.CALL_TYPE_PUT_DOCUMENT)).isTrue();
        assertThat(logger.createExtraStatsLocked(testPackageName,
                CallStats.CALL_TYPE_PUT_DOCUMENT).mSkippedSampleCount).isEqualTo(0);
    }

    @Test
    public void testShouldLogForTypeLocked_falseWhenSampleRatioIsNegative() {
        final int samplingRatio = -1;
        final String testPackageName = "packageName";
        PlatformLogger logger = new PlatformLogger(
                ApplicationProvider.getApplicationContext(),
                UserHandle.USER_NULL,
                new PlatformLogger.Config(
                        TEST_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS,
                        samplingRatio,
                        /* samplingMap=*/ null));

        // Makes sure sample will be excluded due to sampling if sample ratio is negative.
        assertThat(logger.shouldLogForTypeLocked(CallStats.CALL_TYPE_PUT_DOCUMENT)).isFalse();
        // Skipped count should be 0 since it doesn't pass the sampling.
        assertThat(logger.createExtraStatsLocked(testPackageName,
                CallStats.CALL_TYPE_PUT_DOCUMENT).mSkippedSampleCount).isEqualTo(0);
    }

    @Test
    public void testShouldLogForTypeLocked_falseWhenWithinCoolOffInterval() {
        // Next sample won't be excluded due to sampling.
        final int samplingRatio = 1;
        // Next sample would guaranteed to be too close.
        final int minTimeIntervalBetweenSamplesMillis = Integer.MAX_VALUE;
        final String testPackageName = "packageName";
        PlatformLogger logger = new PlatformLogger(
                ApplicationProvider.getApplicationContext(),
                UserHandle.USER_NULL,
                new PlatformLogger.Config(
                        minTimeIntervalBetweenSamplesMillis,
                        samplingRatio,
                        /* samplingMap=*/ null));
        logger.setLastPushTimeMillisLocked(SystemClock.elapsedRealtime());

        // Makes sure sample will be excluded due to rate limiting if samples are too close.
        assertThat(logger.shouldLogForTypeLocked(CallStats.CALL_TYPE_PUT_DOCUMENT)).isFalse();
        assertThat(logger.createExtraStatsLocked(testPackageName,
                CallStats.CALL_TYPE_PUT_DOCUMENT).mSkippedSampleCount).isEqualTo(1);
    }

    @Test
    public void testShouldLogForTypeLocked_trueWhenOutsideOfCoolOffInterval() {
        // Next sample won't be excluded due to sampling.
        final int samplingRatio = 1;
        // Next sample would guaranteed to be included.
        final int minTimeIntervalBetweenSamplesMillis = 0;
        final String testPackageName = "packageName";
        PlatformLogger logger = new PlatformLogger(
                ApplicationProvider.getApplicationContext(),
                UserHandle.USER_NULL,
                new PlatformLogger.Config(
                        minTimeIntervalBetweenSamplesMillis,
                        samplingRatio,
                        /* samplingMap=*/ null));
        logger.setLastPushTimeMillisLocked(SystemClock.elapsedRealtime());

        // Makes sure sample will be logged if it is not too close to previous sample.
        assertThat(logger.shouldLogForTypeLocked(CallStats.CALL_TYPE_PUT_DOCUMENT)).isTrue();
        assertThat(logger.createExtraStatsLocked(testPackageName,
                CallStats.CALL_TYPE_PUT_DOCUMENT).mSkippedSampleCount).isEqualTo(0);
    }

    /** Makes sure the caching works while getting the UID for calling package. */
    @Test
    public void testGetPackageUidAsUser() throws Exception {
        final String testPackageName = "packageName";
        final int testUid = 1234;
        PlatformLogger logger = new PlatformLogger(
                mContext,
                mContext.getUserId(),
                new PlatformLogger.Config(
                        TEST_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS,
                        TEST_DEFAULT_SAMPLING_RATIO,
                        /* samplingMap=*/ null));
        mMockPackageManager.mockGetPackageUidAsUser(testPackageName, mContext.getUserId(), testUid);

        //
        // First time, no cache
        //
        PlatformLogger.ExtraStats extraStats = logger.createExtraStatsLocked(testPackageName,
                CallStats.CALL_TYPE_PUT_DOCUMENT);

        verify(mMockPackageManager.getMockPackageManager(), times(1)).getPackageUidAsUser(
                eq(testPackageName), /*userId=*/ anyInt());
        assertThat(extraStats.mPackageUid).isEqualTo(testUid);

        //
        // Second time, we have cache
        //
        extraStats = logger.createExtraStatsLocked(testPackageName,
                CallStats.CALL_TYPE_PUT_DOCUMENT);

        // Count is still one since we will use the cache
        verify(mMockPackageManager.getMockPackageManager(), times(1)).getPackageUidAsUser(
                eq(testPackageName), /*userId=*/ anyInt());
        assertThat(extraStats.mPackageUid).isEqualTo(testUid);

        //
        // Remove the cache and try again
        //
        assertThat(logger.removeCachedUidForPackage(testPackageName)).isEqualTo(testUid);
        extraStats = logger.createExtraStatsLocked(testPackageName,
                CallStats.CALL_TYPE_PUT_DOCUMENT);

        // count increased by 1 since cache is cleared
        verify(mMockPackageManager.getMockPackageManager(), times(2)).getPackageUidAsUser(
                eq(testPackageName), /*userId=*/ anyInt());
        assertThat(extraStats.mPackageUid).isEqualTo(testUid);
    }
}
