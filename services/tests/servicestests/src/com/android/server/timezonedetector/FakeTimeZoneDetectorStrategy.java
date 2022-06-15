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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.time.TimeZoneCapabilities;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.util.IndentingPrintWriter;

import java.util.ArrayList;
import java.util.List;

class FakeTimeZoneDetectorStrategy implements TimeZoneDetectorStrategy {

    private ConfigurationChangeListener mConfigurationChangeListener;

    // Fake state
    private ConfigurationInternal mConfigurationInternal;

    // Call tracking.
    private GeolocationTimeZoneSuggestion mLastGeolocationSuggestion;
    private ManualTimeZoneSuggestion mLastManualSuggestion;
    private TelephonyTimeZoneSuggestion mLastTelephonySuggestion;
    private boolean mDumpCalled;
    private final List<Dumpable> mDumpables = new ArrayList<>();

    @Override
    public void addConfigChangeListener(@NonNull ConfigurationChangeListener listener) {
        if (mConfigurationChangeListener != null) {
            fail("Fake only supports one listener");
        }
        mConfigurationChangeListener = listener;
    }

    @Override
    public ConfigurationInternal getConfigurationInternal(int userId) {
        if (mConfigurationInternal.getUserId() != userId) {
            fail("Fake only supports one user");
        }
        return mConfigurationInternal;
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

            // Note: Unlike the real strategy, the listeners is invoked synchronously.
            mConfigurationChangeListener.onChange();
        }
        return true;
    }

    public void simulateConfigurationChangeForTests() {
        mConfigurationChangeListener.onChange();
    }

    @Override
    public void suggestGeolocationTimeZone(GeolocationTimeZoneSuggestion timeZoneSuggestion) {
        mLastGeolocationSuggestion = timeZoneSuggestion;
    }

    @Override
    public boolean suggestManualTimeZone(
            @UserIdInt int userId, @NonNull ManualTimeZoneSuggestion timeZoneSuggestion) {
        mLastManualSuggestion = timeZoneSuggestion;
        return true;
    }

    @Override
    public void suggestTelephonyTimeZone(
            @NonNull TelephonyTimeZoneSuggestion timeZoneSuggestion) {
        mLastTelephonySuggestion = timeZoneSuggestion;
    }

    @Override
    public MetricsTimeZoneDetectorState generateMetricsState() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addDumpable(Dumpable dumpable) {
        mDumpables.add(dumpable);
    }

    @Override
    public void dump(IndentingPrintWriter pw, String[] args) {
        mDumpCalled = true;
    }

    void initializeConfiguration(ConfigurationInternal configurationInternal) {
        mConfigurationInternal = configurationInternal;
    }

    void resetCallTracking() {
        mLastGeolocationSuggestion = null;
        mLastManualSuggestion = null;
        mLastTelephonySuggestion = null;
        mDumpCalled = false;
    }

    void verifySuggestGeolocationTimeZoneCalled(
            GeolocationTimeZoneSuggestion expectedSuggestion) {
        assertEquals(expectedSuggestion, mLastGeolocationSuggestion);
    }

    void verifySuggestManualTimeZoneCalled(ManualTimeZoneSuggestion expectedSuggestion) {
        assertEquals(expectedSuggestion, mLastManualSuggestion);
    }

    void verifySuggestTelephonyTimeZoneCalled(TelephonyTimeZoneSuggestion expectedSuggestion) {
        assertEquals(expectedSuggestion, mLastTelephonySuggestion);
    }

    void verifyDumpCalled() {
        assertTrue(mDumpCalled);
    }

    void verifyHasDumpable(Dumpable expected) {
        assertTrue(mDumpables.contains(expected));
    }
}
