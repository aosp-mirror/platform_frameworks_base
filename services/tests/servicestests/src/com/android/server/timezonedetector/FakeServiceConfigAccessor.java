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

import static org.junit.Assert.assertNotNull;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.time.TimeZoneCapabilities;
import android.app.time.TimeZoneConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A partially implemented, fake implementation of ServiceConfigAccessor for tests.
 *
 * <p>This class has rudamentary support for multiple users, but unlike the real thing, it doesn't
 * simulate that some settings are global and shared between users. It also delivers config updates
 * synchronously.
 */
public class FakeServiceConfigAccessor implements ServiceConfigAccessor {

    private final List<StateChangeListener> mConfigurationInternalChangeListeners =
            new ArrayList<>();
    private ConfigurationInternal mCurrentUserConfigurationInternal;
    private ConfigurationInternal mOtherUserConfigurationInternal;

    @Override
    public void addConfigurationInternalChangeListener(StateChangeListener listener) {
        mConfigurationInternalChangeListeners.add(listener);
    }

    @Override
    public void removeConfigurationInternalChangeListener(StateChangeListener listener) {
        mConfigurationInternalChangeListeners.remove(listener);
    }

    @Override
    public ConfigurationInternal getCurrentUserConfigurationInternal() {
        return getConfigurationInternal(mCurrentUserConfigurationInternal.getUserId());
    }

    @Override
    public boolean updateConfiguration(
            @UserIdInt int userId, @NonNull TimeZoneConfiguration requestedChanges,
            boolean bypassUserPolicyChecks) {
        assertNotNull(mCurrentUserConfigurationInternal);
        assertNotNull(requestedChanges);

        ConfigurationInternal toUpdate = getConfigurationInternal(userId);

        // Simulate the real strategy's behavior: the new configuration will be updated to be the
        // old configuration merged with the new if the user has the capability to update the
        // settings. Then, if the configuration changed, the change listener is invoked.
        TimeZoneCapabilities capabilities = toUpdate.asCapabilities(bypassUserPolicyChecks);
        TimeZoneConfiguration configuration = toUpdate.asConfiguration();
        TimeZoneConfiguration newConfiguration =
                capabilities.tryApplyConfigChanges(configuration, requestedChanges);
        if (newConfiguration == null) {
            return false;
        }

        if (!newConfiguration.equals(configuration)) {
            ConfigurationInternal updatedConfiguration = toUpdate.merge(newConfiguration);
            if (updatedConfiguration.getUserId() == mCurrentUserConfigurationInternal.getUserId()) {
                mCurrentUserConfigurationInternal = updatedConfiguration;
            } else if (mOtherUserConfigurationInternal != null
                    && updatedConfiguration.getUserId()
                    == mOtherUserConfigurationInternal.getUserId()) {
                mOtherUserConfigurationInternal = updatedConfiguration;
            }
            // Note: Unlike the real strategy, the listeners are invoked synchronously.
            notifyConfigurationChange();
        }
        return true;
    }

    void initializeCurrentUserConfiguration(ConfigurationInternal configurationInternal) {
        mCurrentUserConfigurationInternal = configurationInternal;
    }

    void initializeOtherUserConfiguration(ConfigurationInternal configurationInternal) {
        mOtherUserConfigurationInternal = configurationInternal;
    }

    void simulateCurrentUserConfigurationInternalChange(
            ConfigurationInternal configurationInternal) {
        mCurrentUserConfigurationInternal = configurationInternal;
        // Note: Unlike the real strategy, the listeners are invoked synchronously.
        notifyConfigurationChange();
    }

    void simulateOtherUserConfigurationInternalChange(ConfigurationInternal configurationInternal) {
        mOtherUserConfigurationInternal = configurationInternal;
        // Note: Unlike the real strategy, the listeners are invoked synchronously.
        notifyConfigurationChange();
    }

    @Override
    public ConfigurationInternal getConfigurationInternal(int userId) {
        if (userId == mCurrentUserConfigurationInternal.getUserId()) {
            return mCurrentUserConfigurationInternal;
        } else if (mOtherUserConfigurationInternal != null
                    && userId == mOtherUserConfigurationInternal.getUserId()) {
            return mOtherUserConfigurationInternal;
        }
        throw new AssertionError("userId not known: " + userId);
    }

    @Override
    public void addLocationTimeZoneManagerConfigListener(StateChangeListener listener) {
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

    private void notifyConfigurationChange() {
        for (StateChangeListener listener : mConfigurationInternalChangeListeners) {
            listener.onChange();
        }
    }

    @SuppressWarnings("UnusedReturnValue")
    private static <T> T failUnimplemented() {
        throw new AssertionError("Unimplemented");
    }
}
