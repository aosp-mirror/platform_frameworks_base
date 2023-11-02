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

import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_NETWORK;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.time.TimeCapabilities;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;

import com.android.server.timedetector.TimeDetectorStrategy.Origin;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;

/**
 * Tests for {@link ConfigurationInternal} and associated {@link TimeCapabilitiesAndConfig}
 * behavior.
 */
@RunWith(JUnitParamsRunner.class)
public class ConfigurationInternalTest {

    private static final int ARBITRARY_USER_ID = 99999;
    private static final int ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS = 1234;
    private static final Instant ARBITRARY_SUGGESTION_LOWER_BOUND = Instant.ofEpochMilli(0);
    private static final Instant ARBITRARY_SUGGESTION_UPPER_BOUND =
            Instant.ofEpochMilli(Long.MAX_VALUE);
    private static final @Origin int[] ARBITRARY_ORIGIN_PRIORITIES = { ORIGIN_NETWORK };

    /**
     * Tests {@link TimeCapabilitiesAndConfig} behavior in different scenarios when auto detection
     * is supported.
     */
    @Test
    @Parameters({ "true,true", "true,false", "false,true", "false,false" })
    public void test_autoDetectionSupported_capabilitiesAndConfiguration(
            boolean userConfigAllowed, boolean bypassUserPolicyChecks) {
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                .setUserConfigAllowed(userConfigAllowed)
                .setAutoDetectionSupported(true)
                .setSystemClockUpdateThresholdMillis(ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS)
                .setAutoSuggestionLowerBound(ARBITRARY_SUGGESTION_LOWER_BOUND)
                .setManualSuggestionLowerBound(ARBITRARY_SUGGESTION_LOWER_BOUND)
                .setSuggestionUpperBound(ARBITRARY_SUGGESTION_UPPER_BOUND)
                .setOriginPriorities(ARBITRARY_ORIGIN_PRIORITIES)
                .setAutoDetectionEnabledSetting(true)
                .build();

        boolean userRestrictionsExpected = !(userConfigAllowed || bypassUserPolicyChecks);
        {
            ConfigurationInternal autoOnConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(true)
                    .build();
            assertTrue(autoOnConfig.getAutoDetectionEnabledSetting());
            assertTrue(autoOnConfig.getAutoDetectionEnabledBehavior());

            TimeCapabilitiesAndConfig capabilitiesAndConfig =
                    autoOnConfig.createCapabilitiesAndConfig(bypassUserPolicyChecks);

            TimeCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSetManualTimeCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_APPLICABLE, capabilities.getSetManualTimeCapability());
            }

            TimeConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
        }

        {
            ConfigurationInternal autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());

            TimeCapabilitiesAndConfig capabilitiesAndConfig =
                    autoOffConfig.createCapabilitiesAndConfig(bypassUserPolicyChecks);

            TimeCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSetManualTimeCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED,
                        capabilities.getConfigureAutoDetectionEnabledCapability());
                assertEquals(CAPABILITY_POSSESSED,
                        capabilities.getSetManualTimeCapability());
            }
            TimeConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertFalse(configuration.isAutoDetectionEnabled());
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
        ConfigurationInternal baseConfig = new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                .setUserConfigAllowed(userConfigAllowed)
                .setAutoDetectionSupported(false)
                .setSystemClockUpdateThresholdMillis(ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS)
                .setAutoSuggestionLowerBound(ARBITRARY_SUGGESTION_LOWER_BOUND)
                .setManualSuggestionLowerBound(ARBITRARY_SUGGESTION_LOWER_BOUND)
                .setSuggestionUpperBound(ARBITRARY_SUGGESTION_UPPER_BOUND)
                .setOriginPriorities(ARBITRARY_ORIGIN_PRIORITIES)
                .setAutoDetectionEnabledSetting(true)
                .build();
        boolean userRestrictionsExpected = !(userConfigAllowed || bypassUserPolicyChecks);

        // Auto-detection enabled.
        {
            ConfigurationInternal autoOnConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(true)
                    .build();
            assertTrue(autoOnConfig.getAutoDetectionEnabledSetting());
            assertFalse(autoOnConfig.getAutoDetectionEnabledBehavior());

            TimeCapabilitiesAndConfig capabilitiesAndConfig =
                    autoOnConfig.createCapabilitiesAndConfig(bypassUserPolicyChecks);

            TimeCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSetManualTimeCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED, capabilities.getSetManualTimeCapability());
            }

            TimeConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertTrue(configuration.isAutoDetectionEnabled());
        }

        // Auto-detection disabled.
        {
            ConfigurationInternal autoOffConfig = new ConfigurationInternal.Builder(baseConfig)
                    .setAutoDetectionEnabledSetting(false)
                    .build();
            assertFalse(autoOffConfig.getAutoDetectionEnabledSetting());
            assertFalse(autoOffConfig.getAutoDetectionEnabledBehavior());

            TimeCapabilitiesAndConfig capabilitiesAndConfig =
                    autoOffConfig.createCapabilitiesAndConfig(bypassUserPolicyChecks);

            TimeCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
            assertEquals(CAPABILITY_NOT_SUPPORTED,
                    capabilities.getConfigureAutoDetectionEnabledCapability());
            if (userRestrictionsExpected) {
                assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSetManualTimeCapability());
            } else {
                assertEquals(CAPABILITY_POSSESSED, capabilities.getSetManualTimeCapability());
            }

            TimeConfiguration configuration = capabilitiesAndConfig.getConfiguration();
            assertFalse(configuration.isAutoDetectionEnabled());
        }
    }
}
