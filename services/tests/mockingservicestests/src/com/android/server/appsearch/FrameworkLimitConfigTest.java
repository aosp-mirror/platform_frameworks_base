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

import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for {@link FrameworkLimitConfig}.
 *
 * <p>Build/Install/Run: atest FrameworksMockingServicesTests:AppSearchConfigTest
 */
public class FrameworkLimitConfigTest {
    @Rule
    public final TestableDeviceConfig.TestableDeviceConfigRule
            mDeviceConfigRule = new TestableDeviceConfig.TestableDeviceConfigRule();

    @Test
    public void testDefaultValues() {
        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);
        FrameworkLimitConfig config = new FrameworkLimitConfig(appSearchConfig);
        assertThat(config.getMaxDocumentSizeBytes()).isEqualTo(
                AppSearchConfig.DEFAULT_LIMIT_CONFIG_MAX_DOCUMENT_SIZE_BYTES);
        assertThat(appSearchConfig.getCachedLimitConfigMaxDocumentCount()).isEqualTo(
                AppSearchConfig.DEFAULT_LIMIT_CONFIG_MAX_DOCUMENT_COUNT);
    }

    @Test
    public void testCustomizedValues() {
        AppSearchConfig appSearchConfig = AppSearchConfig.create(DIRECT_EXECUTOR);
        FrameworkLimitConfig config = new FrameworkLimitConfig(appSearchConfig);
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_LIMIT_CONFIG_MAX_DOCUMENT_SIZE_BYTES,
                "2001",
                /*makeDefault=*/ false);
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_APPSEARCH,
                AppSearchConfig.KEY_LIMIT_CONFIG_MAX_DOCUMENT_COUNT,
                "2002",
                /*makeDefault=*/ false);

        assertThat(config.getMaxDocumentSizeBytes()).isEqualTo(2001);
        assertThat(appSearchConfig.getCachedLimitConfigMaxDocumentCount()).isEqualTo(2002);
    }
}
