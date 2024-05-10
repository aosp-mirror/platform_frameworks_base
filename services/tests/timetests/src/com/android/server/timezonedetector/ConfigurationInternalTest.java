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

import static com.android.server.timezonedetector.ConfigurationInternal.DETECTION_MODE_GEO;
import static com.android.server.timezonedetector.ConfigurationInternal.DETECTION_MODE_MANUAL;
import static com.android.server.timezonedetector.ConfigurationInternal.DETECTION_MODE_TELEPHONY;
import static com.android.server.timezonedetector.ConfigurationInternal.DETECTION_MODE_UNKNOWN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeZoneCapabilities;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link ConfigurationInternal} and associated {@link TimeZoneCapabilitiesAndConfig}
 * behavior.
 */
@RunWith(JUnitParamsRunner.class)
public class ConfigurationInternalTest {

    private static final int ARBITRARY_USER_ID = 99999;

    /**
     * Tests {@link TimeCapabilitiesAndConfig} behavior in different scenarios when auto detection
     * is supported (both telephony and geo detection are supported)
     */
    @Test
    @Parameters({ "true,true", "true,false", "false,true", "false,false" })
    public void test_telephonyAndGeoSupported_capabilitiesAndConfiguration(
            boolean userConfigAllowed, boolean bypassUserPolicyChecks) {
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder()
                .setUserId(ARBITRARY_USER_ID)
                .setUserConfigAllowed(userConfigAllowed)
                .setTelephonyDetectionFeatureSupported(true)
                .setGeoDetectionFeatureSupported(true)
                .setGeoDetectionRunInBackgroundEnabled(false)
                .setTelephonyFallbackSupported(false)
                .setEnhancedMetricsCollectionEnabled(false)
                .setAutoDetectionEnabledSetting(true)
                .setLocationEnabledSetting(true)
                .setGeoDetectionEnabledSetting(true)
                .build();

        boolean userRestrictionsExpected = !(userConfigAllowed || bypassUserPolicyChecks);

        // Auto-detection enabled, location enabled.
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(true)
                    .build();
            assertTrue(config.getAutoDetectionEnabledSetting());
            assertTrue(config.getLocationEnabledSetting());
            assertTrue(config.getGeoDetectionEnabledSetting());
            assertTrue(config.getAutoDetectionEnabledBehavior());
            assertTrue(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_GEO, config.getDetectionMode());

            TimeZoneCapabilities capabilities = config.asCapabilities(bypassUserPolicyChecks);
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getSetManualTimeZoneCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_APPLICABLE,
                        capabilities.getSetManualTimeZoneCapability());
            }
            // This has user privacy implications so it is not restricted in the same way as others.
            assertEquals(CAPABILITY_POSSESSED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = config.asConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }

        // Auto-detection enabled, location disabled.
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(false)
                    .build();
            assertTrue(config.getAutoDetectionEnabledSetting());
            assertFalse(config.getLocationEnabledSetting());
            assertTrue(config.getGeoDetectionEnabledSetting());
            assertTrue(config.getAutoDetectionEnabledBehavior());
            assertFalse(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_TELEPHONY, config.getDetectionMode());

            TimeZoneCapabilities capabilities = config.asCapabilities(bypassUserPolicyChecks);
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getSetManualTimeZoneCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_APPLICABLE,
                        capabilities.getSetManualTimeZoneCapability());
            }
            // This has user privacy implications so it is not restricted in the same way as others.
            assertEquals(CAPABILITY_NOT_APPLICABLE,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = config.asConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }

        // Auto-detection disabled.
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(false)
                    .build();
            assertFalse(config.getAutoDetectionEnabledSetting());
            assertTrue(config.getLocationEnabledSetting());
            assertTrue(config.getGeoDetectionEnabledSetting());
            assertFalse(config.getAutoDetectionEnabledBehavior());
            assertFalse(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_MANUAL, config.getDetectionMode());

            TimeZoneCapabilities capabilities = config.asCapabilities(bypassUserPolicyChecks);
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getSetManualTimeZoneCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_POSSESSED,
                        capabilities.getSetManualTimeZoneCapability());
            }
            // This has user privacy implications so it is not restricted in the same way as others.
            assertEquals(CAPABILITY_NOT_APPLICABLE,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = config.asConfiguration();
            assertFalse(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }
    }

    /**
     * Tests {@link TimeCapabilitiesAndConfig} behavior in different scenarios when auto detection
     * is not supported.
     */
    @Test
    @Parameters({ "true,true", "true,false", "false,true", "false,false" })
    public void test_autoDetectNotSupported_capabilitiesAndConfiguration(
            boolean userConfigAllowed, boolean bypassUserPolicyChecks) {
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder()
                .setUserId(ARBITRARY_USER_ID)
                .setUserConfigAllowed(userConfigAllowed)
                .setTelephonyDetectionFeatureSupported(false)
                .setGeoDetectionFeatureSupported(false)
                .setGeoDetectionRunInBackgroundEnabled(false)
                .setTelephonyFallbackSupported(false)
                .setEnhancedMetricsCollectionEnabled(false)
                .setAutoDetectionEnabledSetting(true)
                .setLocationEnabledSetting(true)
                .setGeoDetectionEnabledSetting(true)
                .build();

        boolean userRestrictionsExpected = !(userConfigAllowed || bypassUserPolicyChecks);

        // Auto-detection enabled, location enabled.
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(true)
                    .build();
            assertTrue(config.getAutoDetectionEnabledSetting());
            assertTrue(config.getLocationEnabledSetting());
            assertTrue(config.getGeoDetectionEnabledSetting());
            assertFalse(config.getAutoDetectionEnabledBehavior());
            assertFalse(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_MANUAL, config.getDetectionMode());

            TimeZoneCapabilities capabilities = config.asCapabilities(bypassUserPolicyChecks);
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSetManualTimeZoneCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED, capabilities.getSetManualTimeZoneCapability());
            }
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = config.asConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }

        // Auto-detection enabled, location disabled.
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(false)
                    .build();
            assertTrue(config.getAutoDetectionEnabledSetting());
            assertFalse(config.getLocationEnabledSetting());
            assertTrue(config.getGeoDetectionEnabledSetting());
            assertFalse(config.getAutoDetectionEnabledBehavior());
            assertFalse(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_MANUAL, config.getDetectionMode());

            TimeZoneCapabilities capabilities = config.asCapabilities(bypassUserPolicyChecks);
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSetManualTimeZoneCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED, capabilities.getSetManualTimeZoneCapability());
            }
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = config.asConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }

        // Auto-detection disabled.
        {
            ConfigurationInternal autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOffConfig.getGeoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());
            assertFalse(autoOffConfig.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_MANUAL, autoOffConfig.getDetectionMode());

            TimeZoneCapabilities capabilities =
                    autoOffConfig.asCapabilities(bypassUserPolicyChecks);
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSetManualTimeZoneCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED, capabilities.getSetManualTimeZoneCapability());
            }
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = autoOffConfig.asConfiguration();
            assertFalse(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }
    }

    /**
     * Tests {@link TimeCapabilitiesAndConfig} behavior in different scenarios when auto detection
     * is supported (telephony only).
     */
    @Test
    @Parameters({ "true,true", "true,false", "false,true", "false,false" })
    public void test_onlyTelephonySupported_capabilitiesAndConfiguration(
            boolean userConfigAllowed, boolean bypassUserPolicyChecks) {
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder()
                .setUserId(ARBITRARY_USER_ID)
                .setUserConfigAllowed(userConfigAllowed)
                .setTelephonyDetectionFeatureSupported(true)
                .setGeoDetectionFeatureSupported(false)
                .setGeoDetectionRunInBackgroundEnabled(false)
                .setTelephonyFallbackSupported(false)
                .setEnhancedMetricsCollectionEnabled(false)
                .setAutoDetectionEnabledSetting(true)
                .setLocationEnabledSetting(true)
                .setGeoDetectionEnabledSetting(true)
                .build();

        boolean userRestrictionsExpected = !(userConfigAllowed || bypassUserPolicyChecks);

        // Auto-detection enabled.
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(true)
                    .build();
            assertTrue(config.getAutoDetectionEnabledSetting());
            assertTrue(config.getGeoDetectionEnabledSetting());
            assertTrue(config.getAutoDetectionEnabledBehavior());
            assertFalse(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_TELEPHONY, config.getDetectionMode());

            TimeZoneCapabilities capabilities = config.asCapabilities(bypassUserPolicyChecks);
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getSetManualTimeZoneCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_APPLICABLE,
                        capabilities.getSetManualTimeZoneCapability());
            }
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = config.asConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }

        // Auto-detection disabled.
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(false)
                    .build();
            assertFalse(config.getAutoDetectionEnabledSetting());
            assertTrue(config.getGeoDetectionEnabledSetting());
            assertFalse(config.getAutoDetectionEnabledBehavior());
            assertFalse(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_MANUAL, config.getDetectionMode());

            TimeZoneCapabilities capabilities = config.asCapabilities(bypassUserPolicyChecks);
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSetManualTimeZoneCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_POSSESSED, capabilities.getSetManualTimeZoneCapability());
            }
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = config.asConfiguration();
            assertFalse(configuration.isAutoDetectionEnabled());
            assertTrue(configuration.isGeoDetectionEnabled());
        }
    }

    /**
     * Tests {@link TimeCapabilitiesAndConfig} behavior in different scenarios when auto detection
     * is supported (only geo detection)
     */
    @Test
    @Parameters({ "true,true", "true,false", "false,true", "false,false" })
    public void test_onlyGeoSupported_capabilitiesAndConfiguration(
            boolean userConfigAllowed, boolean bypassUserPolicyChecks) {
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder()
                .setUserId(ARBITRARY_USER_ID)
                .setUserConfigAllowed(userConfigAllowed)
                .setTelephonyDetectionFeatureSupported(false)
                .setGeoDetectionFeatureSupported(true)
                .setGeoDetectionRunInBackgroundEnabled(false)
                .setTelephonyFallbackSupported(false)
                .setEnhancedMetricsCollectionEnabled(false)
                .setAutoDetectionEnabledSetting(true)
                .setLocationEnabledSetting(true)
                .setGeoDetectionEnabledSetting(false)
                .build();

        boolean userRestrictionsExpected = !(userConfigAllowed || bypassUserPolicyChecks);

        // Auto-detection enabled, location enabled.
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(true)
                    .build();
            assertTrue(config.getAutoDetectionEnabledSetting());
            assertTrue(config.getLocationEnabledSetting());
            assertFalse(config.getGeoDetectionEnabledSetting());
            assertTrue(config.getAutoDetectionEnabledBehavior());
            assertTrue(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_GEO, config.getDetectionMode());

            TimeZoneCapabilities capabilities = config.asCapabilities(bypassUserPolicyChecks);
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getSetManualTimeZoneCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_APPLICABLE,
                        capabilities.getSetManualTimeZoneCapability());
            }
            // This capability is always "not supported" if geo detection is the only mechanism.
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = config.asConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
            assertFalse(configuration.isGeoDetectionEnabled());
        }

        // Auto-detection enabled, location disabled.
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(false)
                    .build();
            assertTrue(config.getAutoDetectionEnabledSetting());
            assertFalse(config.getLocationEnabledSetting());
            assertFalse(config.getGeoDetectionEnabledSetting());
            assertTrue(config.getAutoDetectionEnabledBehavior());
            assertFalse(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_UNKNOWN, config.getDetectionMode());

            TimeZoneCapabilities capabilities = config.asCapabilities(bypassUserPolicyChecks);
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getSetManualTimeZoneCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_APPLICABLE,
                        capabilities.getSetManualTimeZoneCapability());
            }
            // This capability is always "not supported" if geo detection is the only mechanism.
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = config.asConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
            assertFalse(configuration.isGeoDetectionEnabled());
        }

        // Auto-detection disabled.
        {
            ConfigurationInternal autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getGeoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());
            assertFalse(autoOffConfig.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_MANUAL, autoOffConfig.getDetectionMode());

            TimeZoneCapabilities capabilities =
                    autoOffConfig.asCapabilities(bypassUserPolicyChecks);
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getSetManualTimeZoneCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_POSSESSED,
                        capabilities.getSetManualTimeZoneCapability());
            }
            // This capability is always "not supported" if geo detection is the only mechanism.
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureGeoDetectionEnabledCapability());

            TimeZoneConfiguration configuration = autoOffConfig.asConfiguration();
            assertFalse(configuration.isAutoDetectionEnabled());
            assertFalse(configuration.isGeoDetectionEnabled());
        }
    }

    @Test
    public void test_telephonyFallbackSupported() {
        ConfigurationInternal config = new ConfigurationInternal.Builder()
                .setUserId(ARBITRARY_USER_ID)
                .setUserConfigAllowed(true)
                .setTelephonyDetectionFeatureSupported(true)
                .setGeoDetectionFeatureSupported(false)
                .setTelephonyFallbackSupported(true)
                .setAutoDetectionEnabledSetting(true)
                .setLocationEnabledSetting(true)
                .setGeoDetectionEnabledSetting(true)
                .build();
        assertTrue(config.isTelephonyFallbackSupported());
    }

    /**
     * Tests when {@link ConfigurationInternal#getGeoDetectionRunInBackgroundEnabledSetting()}
     * is true.
     */
    @Test
    public void test_geoDetectionRunInBackgroundEnabled() {
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder()
                .setUserId(ARBITRARY_USER_ID)
                .setUserConfigAllowed(true)
                .setTelephonyDetectionFeatureSupported(true)
                .setGeoDetectionFeatureSupported(true)
                .setGeoDetectionRunInBackgroundEnabled(true)
                .setEnhancedMetricsCollectionEnabled(false)
                .setAutoDetectionEnabledSetting(true)
                .setLocationEnabledSetting(true)
                .setGeoDetectionEnabledSetting(true)
                .build();
        {
            ConfigurationInternal config = baseConfig;
            assertTrue(config.getAutoDetectionEnabledBehavior());
            assertTrue(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_GEO, config.getDetectionMode());
        }
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setGeoDetectionFeatureSupported(false)
                    .build();
            assertTrue(config.getAutoDetectionEnabledBehavior());
            assertFalse(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_TELEPHONY, config.getDetectionMode());
        }
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setTelephonyDetectionFeatureSupported(false)
                    .setGeoDetectionFeatureSupported(false)
                    .build();
            assertFalse(config.getAutoDetectionEnabledBehavior());
            assertFalse(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_MANUAL, config.getDetectionMode());
        }
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setGeoDetectionEnabledSetting(false)
                    .build();
            assertTrue(config.getAutoDetectionEnabledBehavior());
            assertTrue(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_TELEPHONY, config.getDetectionMode());
        }
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setLocationEnabledSetting(false)
                    .build();
            assertTrue(config.getAutoDetectionEnabledBehavior());
            assertFalse(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_TELEPHONY, config.getDetectionMode());
        }
        {
            ConfigurationInternal config = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(false)
                    .build();
            assertFalse(config.getAutoDetectionEnabledBehavior());
            assertFalse(config.isGeoDetectionExecutionEnabled());
            assertEquals(DETECTION_MODE_MANUAL, config.getDetectionMode());
        }
    }
}
