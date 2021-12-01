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

package com.android.server.timezonedetector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.time.TimeZoneCapabilities;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A partially implemented, fake implementation of ServiceConfigAccessor for tests. */
class FakeServiceConfigAccessor implements ServiceConfigAccessor {

    private final List<ConfigurationChangeListener> mConfigurationInternalChangeListeners =
            new ArrayList<>();
    private ConfigurationInternal mConfigurationInternal;

    @Override
    public void addConfigurationInternalChangeListener(ConfigurationChangeListener listener) {
        mConfigurationInternalChangeListeners.add(listener);
    }

    @Override
    public void removeConfigurationInternalChangeListener(ConfigurationChangeListener listener) {
        mConfigurationInternalChangeListeners.remove(listener);
    }

    @Override
    public ConfigurationInternal getCurrentUserConfigurationInternal() {
        return mConfigurationInternal;
    }

    @Override
    public boolean updateConfiguration(
            @UserIdInt int userID, @NonNull TimeZoneConfiguration requestedChanges) {
        assertNotNull(mConfigurationInternal);
        assertNotNull(requestedChanges);

        // Simulate the real strategy's behavior: the new configuration will be updated to be the
        // old configuration merged with the new if the user has the capability to up the settings.
        // Then, if the configuration changed, the change listener is invoked.
        TimeZoneCapabilitiesAndConfig capabilitiesAndConfig =
                mConfigurationInternal.createCapabilitiesAndConfig();
        TimeZoneCapabilities capabilities = capabilitiesAndConfig.getCapabilities();
        TimeZoneConfiguration configuration = capabilitiesAndConfig.getConfiguration();
        TimeZoneConfiguration newConfiguration =
                capabilities.tryApplyConfigChanges(configuration, requestedChanges);
        if (newConfiguration == null) {
            return false;
        }

        if (!newConfiguration.equals(capabilitiesAndConfig.getConfiguration())) {
            mConfigurationInternal = mConfigurationInternal.merge(newConfiguration);

            // Note: Unlike the real strategy, the listeners are invoked synchronously.
            simulateConfigurationChangeForTests();
        }
        return true;
    }

    void initializeConfiguration(ConfigurationInternal configurationInternal) {
        mConfigurationInternal = configurationInternal;
    }

    void simulateConfigurationChangeForTests() {
        for (ConfigurationChangeListener listener : mConfigurationInternalChangeListeners) {
            listener.onChange();
        }
    }

    @Override
    public ConfigurationInternal getConfigurationInternal(int userId) {
        assertEquals("Multi-user testing not supported currently",
                userId, mConfigurationInternal.getUserId());
        return mConfigurationInternal;
    }

    @Override
    public void addLocationTimeZoneManagerConfigListener(ConfigurationChangeListener listener) {
        failUnimplemented();
    }

    @Override
    public boolean isTelephonyTimeZoneDetectionFeatureSupported() {
        return failUnimplemented();
    }

    @Override
    public boolean isGeoTimeZoneDetectionFeatureSupportedInConfig() {
        return failUnimplemented();
    }

    @Override
    public boolean isGeoTimeZoneDetectionFeatureSupported() {
        return failUnimplemented();
    }

    @Override
    public String getPrimaryLocationTimeZoneProviderPackageName() {
        return failUnimplemented();
    }

    @Override
    public void setTestPrimaryLocationTimeZoneProviderPackageName(
            String testPrimaryLocationTimeZoneProviderPackageName) {
        failUnimplemented();
    }

    @Override
    public boolean isTestPrimaryLocationTimeZoneProvider() {
        return failUnimplemented();
    }

    @Override
    public String getSecondaryLocationTimeZoneProviderPackageName() {
        return failUnimplemented();
    }

    @Override
    public void setTestSecondaryLocationTimeZoneProviderPackageName(
            String testSecondaryLocationTimeZoneProviderPackageName) {
        failUnimplemented();
    }

    @Override
    public boolean isTestSecondaryLocationTimeZoneProvider() {
        return failUnimplemented();
    }

    @Override
    public void setRecordStateChangesForTests(boolean enabled) {
        failUnimplemented();
    }

    @Override
    public boolean getRecordStateChangesForTests() {
        return failUnimplemented();
    }

    @Override
    public @ProviderMode String getPrimaryLocationTimeZoneProviderMode() {
        return failUnimplemented();
    }

    @Override
    public @ProviderMode String getSecondaryLocationTimeZoneProviderMode() {
        return failUnimplemented();
    }

    @Override
    public boolean isGeoDetectionEnabledForUsersByDefault() {
        return failUnimplemented();
    }

    @Override
    public Optional<Boolean> getGeoDetectionSettingEnabledOverride() {
        return failUnimplemented();
    }

    @Override
    public Duration getLocationTimeZoneProviderInitializationTimeout() {
        return failUnimplemented();
    }

    @Override
    public Duration getLocationTimeZoneProviderInitializationTimeoutFuzz() {
        return failUnimplemented();
    }

    @Override
    public Duration getLocationTimeZoneUncertaintyDelay() {
        return failUnimplemented();
    }

    @Override
    public Duration getLocationTimeZoneProviderEventFilteringAgeThreshold() {
        return failUnimplemented();
    }

    @Override
    public void resetVolatileTestConfig() {
        failUnimplemented();
    }

    @SuppressWarnings("UnusedReturnValue")
    private static <T> T failUnimplemented() {
        fail("Unimplemented");
        return null;
    }
}
