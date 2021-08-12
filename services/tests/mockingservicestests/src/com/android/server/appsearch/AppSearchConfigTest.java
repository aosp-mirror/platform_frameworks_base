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

package com.android.server.appsearch;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import static com.google.common.truth.Truth.assertThat;

import android.provider.DeviceConfig;

import com.android.server.testables.TestableDeviceConfig;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for {@link AppSearchConfig}.
 *
 * <p>Build/Install/Run: atest FrameworksMockingServicesTests:AppSearchConfigTest
 */
public class AppSearchConfigTest {
    @Rule
    public final TestableDeviceConfig.TestableDeviceConfigRule
            mDeviceConfigRule = new TestableDeviceConfig.TestableDeviceConfigRule();

    @Test
    public void testDefaultValues_allCachedValue() {
        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        assertThat(appSearchConfig.getCachedMinTimeIntervalBetweenSamplesMillis()).isEqualTo(
                AppSearchConfig.DEFAULT_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS);
        assertThat(appSearchConfig.getCachedSamplingIntervalDefault()).isEqualTo(
                AppSearchConfig.DEFAULT_SAMPLING_INTERVAL);
        assertThat(appSearchConfig.getCachedSamplingIntervalForBatchCallStats()).isEqualTo(
                AppSearchConfig.DEFAULT_SAMPLING_INTERVAL);
        assertThat(appSearchConfig.getCachedSamplingIntervalForPutDocumentStats()).isEqualTo(
                AppSearchConfig.DEFAULT_SAMPLING_INTERVAL);
        assertThat(appSearchConfig.getCachedSamplingIntervalForInitializeStats()).isEqualTo(
                AppSearchConfig.DEFAULT_SAMPLING_INTERVAL);
        assertThat(appSearchConfig.getCachedSamplingIntervalForSearchStats()).isEqualTo(
                AppSearchConfig.DEFAULT_SAMPLING_INTERVAL);
        assertThat(appSearchConfig.getCachedSamplingIntervalForGlobalSearchStats()).isEqualTo(
                AppSearchConfig.DEFAULT_SAMPLING_INTERVAL);
        assertThat(appSearchConfig.getCachedSamplingIntervalForOptimizeStats()).isEqualTo(
                AppSearchConfig.DEFAULT_SAMPLING_INTERVAL);
        assertThat(appSearchConfig.getCachedLimitConfigMaxDocumentSizeBytes()).isEqualTo(
                AppSearchConfig.DEFAULT_LIMIT_CONFIG_MAX_DOCUMENT_SIZE_BYTES);
        assertThat(appSearchConfig.getCachedLimitConfigMaxDocumentCount()).isEqualTo(
                AppSearchConfig.DEFAULT_LIMIT_CONFIG_MAX_DOCUMENT_COUNT);
        assertThat(appSearchConfig.getCachedBytesOptimizeThreshold()).isEqualTo(
                AppSearchConfig.DEFAULT_BYTES_OPTIMIZE_THRESHOLD);
        assertThat(appSearchConfig.getCachedTimeOptimizeThresholdMs()).isEqualTo(
                AppSearchConfig.DEFAULT_TIME_OPTIMIZE_THRESHOLD_MILLIS);
        assertThat(appSearchConfig.getCachedDocCountOptimizeThreshold()).isEqualTo(
                AppSearchConfig.DEFAULT_DOC_COUNT_OPTIMIZE_THRESHOLD);
    }

    @Test
    public void testCustomizedValue_minTimeIntervalBetweenSamplesMillis() {
        final long minTimeIntervalBetweenSamplesMillis = -1;
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS,
                Long.toString(minTimeIntervalBetweenSamplesMillis),
                false);

        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        assertThat(appSearchConfig.getCachedMinTimeIntervalBetweenSamplesMillis()).isEqualTo(
                minTimeIntervalBetweenSamplesMillis);
    }

    @Test
    public void testCustomizedValueOverride_minTimeIntervalBetweenSamplesMillis() {
        long minTimeIntervalBetweenSamplesMillis = -1;
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS,
                Long.toString(minTimeIntervalBetweenSamplesMillis),
                false);
        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        minTimeIntervalBetweenSamplesMillis = -2;
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_MIN_TIME_INTERVAL_BETWEEN_SAMPLES_MILLIS,
                Long.toString(minTimeIntervalBetweenSamplesMillis),
                false);

        assertThat(appSearchConfig.getCachedMinTimeIntervalBetweenSamplesMillis()).isEqualTo(
                minTimeIntervalBetweenSamplesMillis);
    }

    @Test
    public void testCustomizedValue_allSamplingIntervals() {
        final int samplingIntervalDefault = -1;
        final int samplingIntervalPutDocumentStats = -2;
        final int samplingIntervalBatchCallStats = -3;
        final int samplingIntervalInitializeStats = -4;
        final int samplingIntervalSearchStats = -5;
        final int samplingIntervalGlobalSearchStats = -6;
        final int samplingIntervalOptimizeStats = -7;

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_DEFAULT,
                Integer.toString(samplingIntervalDefault),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_PUT_DOCUMENT_STATS,
                Integer.toString(samplingIntervalPutDocumentStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_BATCH_CALL_STATS,
                Integer.toString(samplingIntervalBatchCallStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_INITIALIZE_STATS,
                Integer.toString(samplingIntervalInitializeStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_SEARCH_STATS,
                Integer.toString(samplingIntervalSearchStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_GLOBAL_SEARCH_STATS,
                Integer.toString(samplingIntervalGlobalSearchStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_OPTIMIZE_STATS,
                Integer.toString(samplingIntervalOptimizeStats),
                false);

        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        assertThat(appSearchConfig.getCachedSamplingIntervalDefault()).isEqualTo(
                samplingIntervalDefault);
        assertThat(appSearchConfig.getCachedSamplingIntervalForPutDocumentStats()).isEqualTo(
                samplingIntervalPutDocumentStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForBatchCallStats()).isEqualTo(
                samplingIntervalBatchCallStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForInitializeStats()).isEqualTo(
                samplingIntervalInitializeStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForSearchStats()).isEqualTo(
                samplingIntervalSearchStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForGlobalSearchStats()).isEqualTo(
                samplingIntervalGlobalSearchStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForOptimizeStats()).isEqualTo(
                samplingIntervalOptimizeStats);
    }

    @Test
    public void testCustomizedValueOverride_allSamplingIntervals() {
        int samplingIntervalDefault = -1;
        int samplingIntervalPutDocumentStats = -2;
        int samplingIntervalBatchCallStats = -3;
        int samplingIntervalInitializeStats = -4;
        int samplingIntervalSearchStats = -5;
        int samplingIntervalGlobalSearchStats = -6;
        int samplingIntervalOptimizeStats = -7;
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_DEFAULT,
                Integer.toString(samplingIntervalDefault),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_PUT_DOCUMENT_STATS,
                Integer.toString(samplingIntervalPutDocumentStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_BATCH_CALL_STATS,
                Integer.toString(samplingIntervalBatchCallStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_INITIALIZE_STATS,
                Integer.toString(samplingIntervalInitializeStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_SEARCH_STATS,
                Integer.toString(samplingIntervalSearchStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_GLOBAL_SEARCH_STATS,
                Integer.toString(samplingIntervalGlobalSearchStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_OPTIMIZE_STATS,
                Integer.toString(samplingIntervalOptimizeStats),
                false);
        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        // Overrides
        samplingIntervalDefault = -4;
        samplingIntervalPutDocumentStats = -5;
        samplingIntervalBatchCallStats = -6;
        samplingIntervalInitializeStats = -7;
        samplingIntervalSearchStats = -8;
        samplingIntervalGlobalSearchStats = -9;
        samplingIntervalOptimizeStats = -10;
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_DEFAULT,
                Integer.toString(samplingIntervalDefault),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_PUT_DOCUMENT_STATS,
                Integer.toString(samplingIntervalPutDocumentStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_BATCH_CALL_STATS,
                Integer.toString(samplingIntervalBatchCallStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_INITIALIZE_STATS,
                Integer.toString(samplingIntervalInitializeStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_SEARCH_STATS,
                Integer.toString(samplingIntervalSearchStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_GLOBAL_SEARCH_STATS,
                Integer.toString(samplingIntervalGlobalSearchStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_OPTIMIZE_STATS,
                Integer.toString(samplingIntervalOptimizeStats),
                false);

        assertThat(appSearchConfig.getCachedSamplingIntervalDefault()).isEqualTo(
                samplingIntervalDefault);
        assertThat(appSearchConfig.getCachedSamplingIntervalForPutDocumentStats()).isEqualTo(
                samplingIntervalPutDocumentStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForBatchCallStats()).isEqualTo(
                samplingIntervalBatchCallStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForInitializeStats()).isEqualTo(
                samplingIntervalInitializeStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForSearchStats()).isEqualTo(
                samplingIntervalSearchStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForGlobalSearchStats()).isEqualTo(
                samplingIntervalGlobalSearchStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForOptimizeStats()).isEqualTo(
                samplingIntervalOptimizeStats);
    }

    /**
     * Tests if we fall back to {@link AppSearchConfig#DEFAULT_SAMPLING_INTERVAL} if both default
     * sampling interval and custom value are not set in DeviceConfig, and there is some other
     * sampling interval set.
     */
    @Test
    public void testFallbackToDefaultSamplingValue_useHardCodedDefault() {
        final int samplingIntervalPutDocumentStats = -1;
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_PUT_DOCUMENT_STATS,
                Integer.toString(samplingIntervalPutDocumentStats),
                false);

        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        assertThat(appSearchConfig.getCachedSamplingIntervalForPutDocumentStats()).isEqualTo(
                samplingIntervalPutDocumentStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForBatchCallStats()).isEqualTo(
                AppSearchConfig.DEFAULT_SAMPLING_INTERVAL);
    }

    // Tests if we fall back to configured default sampling interval if custom value is not set in
    // DeviceConfig.
    @Test
    public void testFallbackDefaultSamplingValue_useConfiguredDefault() {
        final int samplingIntervalPutDocumentStats = -1;
        final int samplingIntervalDefault = -2;
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_PUT_DOCUMENT_STATS,
                Integer.toString(samplingIntervalPutDocumentStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_DEFAULT,
                Integer.toString(samplingIntervalDefault),
                false);

        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        assertThat(appSearchConfig.getCachedSamplingIntervalForPutDocumentStats()).isEqualTo(
                samplingIntervalPutDocumentStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForBatchCallStats()).isEqualTo(
                samplingIntervalDefault);
    }

    // Tests that cached values should reflect latest values in DeviceConfig.
    @Test
    public void testFallbackDefaultSamplingValue_defaultValueChanged() {
        int samplingIntervalPutDocumentStats = -1;
        int samplingIntervalDefault = -2;
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_PUT_DOCUMENT_STATS,
                Integer.toString(samplingIntervalPutDocumentStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_DEFAULT,
                Integer.toString(samplingIntervalDefault),
                false);

        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        // Sampling values changed.
        samplingIntervalPutDocumentStats = -3;
        samplingIntervalDefault = -4;
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_PUT_DOCUMENT_STATS,
                Integer.toString(samplingIntervalPutDocumentStats),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_DEFAULT,
                Integer.toString(samplingIntervalDefault),
                false);

        assertThat(appSearchConfig.getCachedSamplingIntervalForPutDocumentStats()).isEqualTo(
                samplingIntervalPutDocumentStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForBatchCallStats()).isEqualTo(
                samplingIntervalDefault);
    }

    // Tests default sampling interval won't affect custom sampling intervals if they are set.
    @Test
    public void testShouldNotFallBack_ifValueConfigured() {
        int samplingIntervalDefault = -1;
        int samplingIntervalBatchCallStats = -2;
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_DEFAULT,
                Integer.toString(samplingIntervalDefault),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_FOR_BATCH_CALL_STATS,
                Integer.toString(samplingIntervalBatchCallStats),
                false);

        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        // Default sampling interval changed.
        samplingIntervalDefault = -3;
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_SAMPLING_INTERVAL_DEFAULT,
                Integer.toString(samplingIntervalDefault),
                false);

        assertThat(appSearchConfig.getCachedSamplingIntervalForBatchCallStats()).isEqualTo(
                samplingIntervalBatchCallStats);
    }

    @Test
    public void testCustomizedValue_maxDocument() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_LIMIT_CONFIG_MAX_DOCUMENT_SIZE_BYTES,
                Integer.toString(2001),
                /*makeDefault=*/ false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_LIMIT_CONFIG_MAX_DOCUMENT_COUNT,
                Integer.toString(2002),
                /*makeDefault=*/ false);

        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);
        assertThat(appSearchConfig.getCachedLimitConfigMaxDocumentSizeBytes()).isEqualTo(2001);
        assertThat(appSearchConfig.getCachedLimitConfigMaxDocumentCount()).isEqualTo(2002);
    }

    @Test
    public void testCustomizedValue_optimizeThreshold() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_BYTES_OPTIMIZE_THRESHOLD,
                Integer.toString(147147),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_TIME_OPTIMIZE_THRESHOLD_MILLIS,
                Integer.toString(258258),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_DOC_COUNT_OPTIMIZE_THRESHOLD,
                Integer.toString(369369),
                false);

        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        assertThat(appSearchConfig.getCachedBytesOptimizeThreshold()).isEqualTo(147147);
        assertThat(appSearchConfig.getCachedTimeOptimizeThresholdMs()).isEqualTo(258258);
        assertThat(appSearchConfig.getCachedDocCountOptimizeThreshold()).isEqualTo(369369);
    }

    @Test
    public void testCustomizedValueOverride_optimizeThreshold() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_BYTES_OPTIMIZE_THRESHOLD,
                Integer.toString(147147),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_TIME_OPTIMIZE_THRESHOLD_MILLIS,
                Integer.toString(258258),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_DOC_COUNT_OPTIMIZE_THRESHOLD,
                Integer.toString(369369),
                false);

        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        // Override
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_BYTES_OPTIMIZE_THRESHOLD,
                Integer.toString(741741),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_TIME_OPTIMIZE_THRESHOLD_MILLIS,
                Integer.toString(852852),
                false);
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_DOC_COUNT_OPTIMIZE_THRESHOLD,
                Integer.toString(963963),
                false);

        assertThat(appSearchConfig.getCachedBytesOptimizeThreshold()).isEqualTo(741741);
        assertThat(appSearchConfig.getCachedTimeOptimizeThresholdMs()).isEqualTo(852852);
        assertThat(appSearchConfig.getCachedDocCountOptimizeThreshold()).isEqualTo(963963);
    }

    @Test
    public void testNotUsable_afterClose() {
        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        appSearchConfig.close();

        Assert.assertThrows("Trying to use a closed AppSearchConfig instance.",
                IllegalStateException.class,
                () -> appSearchConfig.getCachedMinTimeIntervalBetweenSamplesMillis());
        Assert.assertThrows("Trying to use a closed AppSearchConfig instance.",
                IllegalStateException.class,
                () -> appSearchConfig.getCachedSamplingIntervalDefault());
        Assert.assertThrows("Trying to use a closed AppSearchConfig instance.",
                IllegalStateException.class,
                () -> appSearchConfig.getCachedSamplingIntervalForBatchCallStats());
        Assert.assertThrows("Trying to use a closed AppSearchConfig instance.",
                IllegalStateException.class,
                () -> appSearchConfig.getCachedSamplingIntervalForPutDocumentStats());
        Assert.assertThrows("Trying to use a closed AppSearchConfig instance.",
                IllegalStateException.class,
                () -> appSearchConfig.getCachedSamplingIntervalForInitializeStats());
        Assert.assertThrows("Trying to use a closed AppSearchConfig instance.",
                IllegalStateException.class,
                () -> appSearchConfig.getCachedSamplingIntervalForSearchStats());
        Assert.assertThrows("Trying to use a closed AppSearchConfig instance.",
                IllegalStateException.class,
                () -> appSearchConfig.getCachedSamplingIntervalForGlobalSearchStats());
        Assert.assertThrows("Trying to use a closed AppSearchConfig instance.",
                IllegalStateException.class,
                () -> appSearchConfig.getCachedSamplingIntervalForOptimizeStats());
        Assert.assertThrows("Trying to use a closed AppSearchConfig instance.",
                IllegalStateException.class,
                () -> appSearchConfig.getCachedBytesOptimizeThreshold());
        Assert.assertThrows("Trying to use a closed AppSearchConfig instance.",
                IllegalStateException.class,
                () -> appSearchConfig.getCachedTimeOptimizeThresholdMs());
        Assert.assertThrows("Trying to use a closed AppSearchConfig instance.",
                IllegalStateException.class,
                () -> appSearchConfig.getCachedDocCountOptimizeThreshold());
    }
}
