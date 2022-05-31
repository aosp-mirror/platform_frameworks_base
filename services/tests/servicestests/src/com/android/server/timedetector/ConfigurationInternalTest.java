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

package com.android.server.timedetector;

import static android.app.time.Capabilities.CAPABILITY_NOT_ALLOWED;
import static android.app.time.Capabilities.CAPABILITY_NOT_APPLICABLE;
import static android.app.time.Capabilities.CAPABILITY_NOT_SUPPORTED;
import static android.app.time.Capabilities.CAPABILITY_POSSESSED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.time.TimeCapabilities;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ConfigurationInternalTest {

    private static final int ARBITRARY_USER_ID = 99999;

    /**
     * Tests when {@link ConfigurationInternal#isUserConfigAllowed()} and
     * {@link ConfigurationInternal#isAutoDetectionSupported()} are both true.
     */
    @Test
    public void test_unrestricted() {
        ConfigurationInternal
                baseConfig = new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                .setUserConfigAllowed(true)
                .setAutoDetectionSupported(true)
                .setAutoDetectionEnabledSetting(true)
                .build();
        {
            ConfigurationInternal autoOnConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(true)
                    .build();
            assertTrue(autoOnConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOnConfig.getAutoDetectionEnabledBehavior());

            TimeCapabilitiesAndConfig capabilitiesAndConfig = autoOnConfig.capabilitiesAndConfig();

            TimeCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_POSSESSED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_NOT_APPLICABLE, capabilities.getSuggestManualTimeCapability());

            TimeConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
        }

        {
            ConfigurationInternal autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());

            TimeCapabilitiesAndConfig capabilitiesAndConfig = autoOffConfig.capabilitiesAndConfig();

            TimeCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_POSSESSED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_POSSESSED,
                    capabilities.getSuggestManualTimeCapability());

            TimeConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertFalse(configuration.isAutoDetectionEnabled());
        }
    }

    /** Tests when {@link ConfigurationInternal#isUserConfigAllowed()} is false */
    @Test
    public void test_restricted() {
        ConfigurationInternal
                baseConfig = new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                .setUserConfigAllowed(false)
                .setAutoDetectionSupported(true)
                .setAutoDetectionEnabledSetting(true)
                .build();
        {
            ConfigurationInternal autoOnConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(true)
                    .build();
            assertTrue(autoOnConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOnConfig.getAutoDetectionEnabledBehavior());

            TimeCapabilitiesAndConfig capabilitiesAndConfig = autoOnConfig.capabilitiesAndConfig();

            TimeCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_NOT_ALLOWED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSuggestManualTimeCapability());

            TimeConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
        }

        {
            ConfigurationInternal autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());

            TimeCapabilitiesAndConfig capabilitiesAndConfig = autoOffConfig.capabilitiesAndConfig();

            TimeCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_NOT_ALLOWED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSuggestManualTimeCapability());

            TimeConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertFalse(configuration.isAutoDetectionEnabled());
        }
    }

    /** Tests when {@link ConfigurationInternal#isAutoDetectionSupported()} is false. */
    @Test
    public void test_autoDetectNotSupported() {
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                .setUserConfigAllowed(true)
                .setAutoDetectionSupported(false)
                .setAutoDetectionEnabledSetting(true)
                .build();
        {
            ConfigurationInternal autoOnConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(true)
                    .build();
            assertTrue(autoOnConfig.getAutoDetectionEnabledSetting());
            assertFalse(autoOnConfig.getAutoDetectionEnabledBehavior());

            TimeCapabilitiesAndConfig capabilitiesAndConfig = autoOnConfig.capabilitiesAndConfig();

            TimeCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_POSSESSED, capabilities.getSuggestManualTimeCapability());

            TimeConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
        }
        {
            ConfigurationInternal
                    autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());

            TimeCapabilitiesAndConfig capabilitiesAndConfig = autoOffConfig.capabilitiesAndConfig();

            TimeCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_POSSESSED, capabilities.getSuggestManualTimeCapability());

            TimeConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertFalse(configuration.isAutoDetectionEnabled());
        }
    }
}
