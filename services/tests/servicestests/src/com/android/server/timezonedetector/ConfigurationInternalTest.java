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

import static android.app.time.Capabilities.CAPABILITY_NOT_ALLOWED;
import static android.app.time.Capabilities.CAPABILITY_NOT_APPLICABLE;
import static android.app.time.Capabilities.CAPABILITY_NOT_SUPPORTED;
import static android.app.time.Capabilities.CAPABILITY_POSSESSED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.time.TimeZoneCapabilities;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;

import org.junit.Test;

/**
 * Tests for {@link ConfigurationInternal} and the {@link TimeZoneCapabilitiesAndConfig}.
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
                .setTelephonyDetectionFeatureSupported(true)
                .setGeoDetectionFeatureSupported(true)
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

            TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                    autoOnConfig.createCapabilitiesAndConfig();

            TimeZoneCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_POSSESSED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_NOT_APPLICABLE,
                    capabilities.getSuggestManualTimeZoneCapability());
            assertEquals(CAPABILITY_POSSESSED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }

        {
            ConfigurationInternal autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabled(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOffConfig.getGeoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());
            assertFalse(autoOffConfig.getGeoDetectionEnabledBehavior());

            TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                    autoOffConfig.createCapabilitiesAndConfig();

            TimeZoneCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_POSSESSED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_POSSESSED,
                    capabilities.getSuggestManualTimeZoneCapability());
            assertEquals(CAPABILITY_NOT_APPLICABLE,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertFalse(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }
    }

    /** Tests when {@link ConfigurationInternal#isUserConfigAllowed()} is false */
    @Test
    public void test_restricted() {
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                .setUserConfigAllowed(false)
                .setTelephonyDetectionFeatureSupported(true)
                .setGeoDetectionFeatureSupported(true)
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

            TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                    autoOnConfig.createCapabilitiesAndConfig();

            TimeZoneCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_NOT_ALLOWED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_NOT_ALLOWED,
                    capabilities.getSuggestManualTimeZoneCapability());
            // This has user privacy implications so it is not restricted in the same way as others.
            assertEquals(CAPABILITY_POSSESSED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }

        {
            ConfigurationInternal autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabled(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOffConfig.getGeoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());
            assertFalse(autoOffConfig.getGeoDetectionEnabledBehavior());

            TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                    autoOffConfig.createCapabilitiesAndConfig();

            TimeZoneCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_NOT_ALLOWED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_NOT_ALLOWED,
                    capabilities.getSuggestManualTimeZoneCapability());
            // This has user privacy implications so it is not restricted in the same way as others.
            assertEquals(CAPABILITY_NOT_APPLICABLE,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertFalse(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }
    }

    /** Tests when {@link ConfigurationInternal#isAutoDetectionSupported()} is false. */
    @Test
    public void test_autoDetectNotSupported() {
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                .setUserConfigAllowed(true)
                .setTelephonyDetectionFeatureSupported(false)
                .setGeoDetectionFeatureSupported(false)
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

            TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                    autoOnConfig.createCapabilitiesAndConfig();

            TimeZoneCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_POSSESSED, capabilities.getSuggestManualTimeZoneCapability());
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }
        {
            ConfigurationInternal autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabled(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOffConfig.getGeoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());
            assertFalse(autoOffConfig.getGeoDetectionEnabledBehavior());

            TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                    autoOffConfig.createCapabilitiesAndConfig();

            TimeZoneCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_POSSESSED, capabilities.getSuggestManualTimeZoneCapability());
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertFalse(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }
    }

    /**
     * Tests when {@link ConfigurationInternal#isAutoDetectionSupported()} is true, but
     * {@link ConfigurationInternal#isGeoDetectionSupported()} is false.
     */
    @Test
    public void test_geoDetectNotSupported() {
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                .setUserConfigAllowed(true)
                .setTelephonyDetectionFeatureSupported(true)
                .setGeoDetectionFeatureSupported(false)
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
            assertFalse(autoOnConfig.getGeoDetectionEnabledBehavior());

            TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                    autoOnConfig.createCapabilitiesAndConfig();

            TimeZoneCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_POSSESSED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_NOT_APPLICABLE,
                    capabilities.getSuggestManualTimeZoneCapability());
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }
        {
            ConfigurationInternal autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabled(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOffConfig.getGeoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());
            assertFalse(autoOffConfig.getGeoDetectionEnabledBehavior());

            TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                    autoOffConfig.createCapabilitiesAndConfig();

            TimeZoneCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_POSSESSED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            assertEquals(CAPABILITY_POSSESSED, capabilities.getSuggestManualTimeZoneCapability());
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertFalse(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }
    }
}
