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
        assertThat(appSearchConfig.getCachedLimitConfigMaxDocumentSizeBytes()).isEqualTo(
                AppSearchConfig.DEFAULT_LIMIT_CONFIG_MAX_DOCUMENT_SIZE_BYTES);
        assertThat(appSearchConfig.getCachedLimitConfigMaxDocumentCount()).isEqualTo(
                AppSearchConfig.DEFAULT_LIMIT_CONFIG_MAX_DOCUMENT_COUNT);
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

        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        assertThat(appSearchConfig.getCachedSamplingIntervalDefault()).isEqualTo(
                samplingIntervalDefault);
        assertThat(appSearchConfig.getCachedSamplingIntervalForPutDocumentStats()).isEqualTo(
                samplingIntervalPutDocumentStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForBatchCallStats()).isEqualTo(
                samplingIntervalBatchCallStats);
    }

    @Test
    public void testCustomizedValueOverride_allSamplingIntervals() {
        int samplingIntervalDefault = -1;
        int samplingIntervalPutDocumentStats = -2;
        int samplingIntervalBatchCallStats = -3;
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
        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);

        // Overrides
        samplingIntervalDefault = -4;
        samplingIntervalPutDocumentStats = -5;
        samplingIntervalBatchCallStats = -6;
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

        assertThat(appSearchConfig.getCachedSamplingIntervalDefault()).isEqualTo(
                samplingIntervalDefault);
        assertThat(appSearchConfig.getCachedSamplingIntervalForPutDocumentStats()).isEqualTo(
                samplingIntervalPutDocumentStats);
        assertThat(appSearchConfig.getCachedSamplingIntervalForBatchCallStats()).isEqualTo(
                samplingIntervalBatchCallStats);
    }

    /**
     * Tests if we fall back to {@link AppSearchConfig#DEFAULT_SAMPLING_INTERVAL} if both default
     * sampling
     * interval and custom value are not set in DeviceConfig, and there is some other sampling
     * interval
     * set.
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
    public void testCustomizedValue() {
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
    }
}
