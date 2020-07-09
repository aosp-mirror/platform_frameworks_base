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

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.app.timezonedetector.TimeZoneCapabilities;
import android.app.timezonedetector.TimeZoneConfiguration;
import android.util.IndentingPrintWriter;

class FakeTimeZoneDetectorStrategy implements TimeZoneDetectorStrategy {

    private StrategyListener mListener;

    // Fake state
    private TimeZoneCapabilities mCapabilities;
    private TimeZoneConfiguration mConfiguration;

    // Call tracking.
    private GeolocationTimeZoneSuggestion mLastGeolocationSuggestion;
    private ManualTimeZoneSuggestion mLastManualSuggestion;
    private TelephonyTimeZoneSuggestion mLastTelephonySuggestion;
    private boolean mHandleAutoTimeZoneConfigChangedCalled;
    private boolean mDumpCalled;

    @Override
    public void setStrategyListener(@NonNull StrategyListener listener) {
        mListener = listener;
    }

    @Override
    public TimeZoneCapabilities getCapabilities(@UserIdInt int userId) {
        return mCapabilities;
    }

    @Override
    public boolean updateConfiguration(
            @UserIdInt int userId, @NonNull TimeZoneConfiguration configuration) {
        assertNotNull(mConfiguration);
        assertNotNull(configuration);

        // Simulate the strategy's behavior: the new configuration will be the old configuration
        // merged with the new.
        TimeZoneConfiguration oldConfiguration = mConfiguration;
        TimeZoneConfiguration newConfiguration =
                new TimeZoneConfiguration.Builder(mConfiguration)
                        .mergeProperties(configuration)
                        .build();

        if (newConfiguration.equals(oldConfiguration)) {
            return false;
        }
        mConfiguration = newConfiguration;
        mListener.onConfigurationChanged();
        return true;
    }

    @Override
    @NonNull
    public TimeZoneConfiguration getConfiguration(@UserIdInt int userId) {
        return mConfiguration;
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
    public void handleAutoTimeZoneConfigChanged() {
        mHandleAutoTimeZoneConfigChangedCalled = true;
    }

    @Override
    public void addDumpable(Dumpable dumpable) {
        // Stubbed
    }

    @Override
    public void dump(IndentingPrintWriter pw, String[] args) {
        mDumpCalled = true;
    }

    void initializeConfiguration(TimeZoneConfiguration configuration) {
        mConfiguration = configuration;
    }

    void initializeCapabilities(TimeZoneCapabilities capabilities) {
        mCapabilities = capabilities;
    }

    void resetCallTracking() {
        mLastGeolocationSuggestion = null;
        mLastManualSuggestion = null;
        mLastTelephonySuggestion = null;
        mHandleAutoTimeZoneConfigChangedCalled = false;
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

    void verifyHandleAutoTimeZoneConfigChangedCalled() {
        assertTrue(mHandleAutoTimeZoneConfigChangedCalled);
    }

    void verifyDumpCalled() {
        assertTrue(mDumpCalled);
    }
}
