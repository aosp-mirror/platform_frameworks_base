/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.timezonedetector;

import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_NOT_ALLOWED;
import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_NOT_APPLICABLE;
import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_NOT_SUPPORTED;
import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_POSSESSED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.timezonedetector.TimeZoneCapabilities;

import org.junit.Test;

/**
 * Tests for {@link ConfigurationInternal} and the {@link TimeZoneCapabilities} and
 * {@link android.app.timezonedetector.TimeZoneConfiguration} that can be generated from it.
 */
public class ConfigurationInternalTest {

    private static final int ARBITRARY_USER_ID = 99999;

    /**
     * Tests when {@link ConfigurationInternal#isUserConfigAllowed()} and
     * {@link ConfigurationInternal#isAutoDetectionSupported()} are both true.
     */
    @Test
    public void test_unrestricted() {
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                .setUserConfigAllowed(true)
                .setAutoDetectionSupported(true)
                .setAutoDetectionEnabled(true)
                .setLocationEnabled(true)
                .setGeoDetectionEnabled(true)
                .build();
        {
            ConfigurationInternal autoOnConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabled(true)
                    .build();
            assertTrue(autoOnConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOnConfig.getGeoDetectionEnabledSetting());
            assertTrue(autoOnConfig.getAutoDetectionEnabledBehavior());
            assertTrue(autoOnConfig.getGeoDetectionEnabledBehavior());

            TimeZoneCapabilities capabilities = autoOnConfig.createCapabilities();
            assertEquals(CAPABILITY_POSSESSED, capabilities.getConfigureAutoDetectionEnabled());
            assertEquals(CAPABILITY_POSSESSED, capabilities.getConfigureGeoDetectionEnabled());
            assertEquals(CAPABILITY_NOT_APPLICABLE, capabilities.getSuggestManualTimeZone());
            assertEquals(autoOnConfig.asConfiguration(), capabilities.getConfiguration());
            assertTrue(capabilities.getConfiguration().isAutoDetectionEnabled());
            assertTrue(capabilities.getConfiguration().isGeoDetectionEnabled());
        }

        {
            ConfigurationInternal autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabled(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOffConfig.getGeoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());
            assertFalse(autoOffConfig.getGeoDetectionEnabledBehavior());

            TimeZoneCapabilities capabilities = autoOffConfig.createCapabilities();
            assertEquals(CAPABILITY_POSSESSED, capabilities.getConfigureAutoDetectionEnabled());
            assertEquals(CAPABILITY_POSSESSED, capabilities.getConfigureGeoDetectionEnabled());
            assertEquals(CAPABILITY_POSSESSED, capabilities.getSuggestManualTimeZone());
            assertEquals(autoOffConfig.asConfiguration(), capabilities.getConfiguration());
            assertFalse(capabilities.getConfiguration().isAutoDetectionEnabled());
            assertTrue(capabilities.getConfiguration().isGeoDetectionEnabled());
        }
    }

    /** Tests when {@link ConfigurationInternal#isUserConfigAllowed()} is false */
    @Test
    public void test_restricted() {
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                .setUserConfigAllowed(false)
                .setAutoDetectionSupported(true)
                .setAutoDetectionEnabled(true)
                .setLocationEnabled(true)
                .setGeoDetectionEnabled(true)
                .build();
        {
            ConfigurationInternal autoOnConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabled(true)
                    .build();
            assertTrue(autoOnConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOnConfig.getGeoDetectionEnabledSetting());
            assertTrue(autoOnConfig.getAutoDetectionEnabledBehavior());
            assertTrue(autoOnConfig.getGeoDetectionEnabledBehavior());

            TimeZoneCapabilities capabilities = autoOnConfig.createCapabilities();
            assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getConfigureAutoDetectionEnabled());
            assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getConfigureGeoDetectionEnabled());
            assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSuggestManualTimeZone());
            assertEquals(autoOnConfig.asConfiguration(), capabilities.getConfiguration());
            assertTrue(capabilities.getConfiguration().isAutoDetectionEnabled());
            assertTrue(capabilities.getConfiguration().isGeoDetectionEnabled());
        }

        {
            ConfigurationInternal autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabled(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOffConfig.getGeoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());
            assertFalse(autoOffConfig.getGeoDetectionEnabledBehavior());

            TimeZoneCapabilities capabilities = autoOffConfig.createCapabilities();
            assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getConfigureAutoDetectionEnabled());
            assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getConfigureGeoDetectionEnabled());
            assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSuggestManualTimeZone());
            assertEquals(autoOffConfig.asConfiguration(), capabilities.getConfiguration());
            assertFalse(capabilities.getConfiguration().isAutoDetectionEnabled());
            assertTrue(capabilities.getConfiguration().isGeoDetectionEnabled());
        }
    }

    /** Tests when {@link ConfigurationInternal#isAutoDetectionSupported()} is false. */
    @Test
    public void test_autoDetectNotSupported() {
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                .setUserConfigAllowed(true)
                .setAutoDetectionSupported(false)
                .setAutoDetectionEnabled(true)
                .setLocationEnabled(true)
                .setGeoDetectionEnabled(true)
                .build();
        {
            ConfigurationInternal autoOnConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabled(true)
                    .build();
            assertTrue(autoOnConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOnConfig.getGeoDetectionEnabledSetting());
            assertFalse(autoOnConfig.getAutoDetectionEnabledBehavior());
            assertFalse(autoOnConfig.getGeoDetectionEnabledBehavior());

            TimeZoneCapabilities capabilities = autoOnConfig.createCapabilities();
            assertEquals(CAPABILITY_NOT_SUPPORTED, capabilities.getConfigureAutoDetectionEnabled());
            assertEquals(CAPABILITY_NOT_SUPPORTED, capabilities.getConfigureGeoDetectionEnabled());
            assertEquals(CAPABILITY_POSSESSED, capabilities.getSuggestManualTimeZone());
            assertEquals(autoOnConfig.asConfiguration(), capabilities.getConfiguration());
            assertTrue(capabilities.getConfiguration().isAutoDetectionEnabled());
            assertTrue(capabilities.getConfiguration().isGeoDetectionEnabled());
        }
        {
            ConfigurationInternal autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabled(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOffConfig.getGeoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());
            assertFalse(autoOffConfig.getGeoDetectionEnabledBehavior());

            TimeZoneCapabilities capabilities = autoOffConfig.createCapabilities();
            assertEquals(CAPABILITY_NOT_SUPPORTED, capabilities.getConfigureAutoDetectionEnabled());
            assertEquals(CAPABILITY_NOT_SUPPORTED, capabilities.getConfigureGeoDetectionEnabled());
            assertEquals(CAPABILITY_POSSESSED, capabilities.getSuggestManualTimeZone());
            assertEquals(autoOffConfig.asConfiguration(), capabilities.getConfiguration());
            assertFalse(capabilities.getConfiguration().isAutoDetectionEnabled());
            assertTrue(capabilities.getConfiguration().isGeoDetectionEnabled());
        }
    }
}
