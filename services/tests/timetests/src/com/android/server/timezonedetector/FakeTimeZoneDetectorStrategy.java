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

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.app.time.TimeZoneDetectorStatus;
import android.app.time.TimeZoneState;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.util.IndentingPrintWriter;

import java.util.ArrayList;
import java.util.Objects;

public class FakeTimeZoneDetectorStrategy implements TimeZoneDetectorStrategy {

    private final FakeServiceConfigAccessor mFakeServiceConfigAccessor =
            new FakeServiceConfigAccessor();
    private final ArrayList<StateChangeListener> mListeners = new ArrayList<>();
    private TimeZoneState mTimeZoneState;
    private TimeZoneDetectorStatus mStatus;

    public FakeTimeZoneDetectorStrategy() {
        mFakeServiceConfigAccessor.addConfigurationInternalChangeListener(
                this::notifyChangeListeners);
    }

    void initializeConfigurationAndStatus(
            ConfigurationInternal configuration, TimeZoneDetectorStatus status) {
        mFakeServiceConfigAccessor.initializeCurrentUserConfiguration(configuration);
        mStatus = Objects.requireNonNull(status);
    }

    @Override
    public boolean confirmTimeZone(String timeZoneId) {
        return false;
    }

    @Override
    public TimeZoneCapabilitiesAndConfig getCapabilitiesAndConfig(int userId,
            boolean bypassUserPolicyChecks) {
        ConfigurationInternal configurationInternal =
                mFakeServiceConfigAccessor.getCurrentUserConfigurationInternal();
        assertEquals("Multi-user testing not supported",
                configurationInternal.getUserId(), userId);
        return new TimeZoneCapabilitiesAndConfig(
                mStatus,
                configurationInternal.asCapabilities(bypassUserPolicyChecks),
                configurationInternal.asConfiguration());
    }

    @Override
    public boolean updateConfiguration(int userId, TimeZoneConfiguration requestedChanges,
            boolean bypassUserPolicyChecks) {
        return mFakeServiceConfigAccessor.updateConfiguration(
                userId, requestedChanges, bypassUserPolicyChecks);
    }

    @Override
    public void addChangeListener(StateChangeListener listener) {
        mListeners.add(listener);
    }

    private void notifyChangeListeners() {
        for (StateChangeListener listener : mListeners) {
            listener.onChange();
        }
    }

    @Override
    public TimeZoneState getTimeZoneState() {
        return mTimeZoneState;
    }

    @Override
    public void setTimeZoneState(TimeZoneState timeZoneState) {
        mTimeZoneState = timeZoneState;
    }

    @Override
    public void handleLocationAlgorithmEvent(LocationAlgorithmEvent locationAlgorithmEvent) {
    }

    @Override
    public boolean suggestManualTimeZone(
            @UserIdInt int userId, @NonNull ManualTimeZoneSuggestion timeZoneSuggestion,
            boolean bypassUserPolicyChecks) {
        return true;
    }

    @Override
    public void suggestTelephonyTimeZone(@NonNull TelephonyTimeZoneSuggestion timeZoneSuggestion) {
    }

    @Override
    public void enableTelephonyTimeZoneFallback(String reason) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MetricsTimeZoneDetectorState generateMetricsState() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isTelephonyTimeZoneDetectionSupported() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isGeoTimeZoneDetectionSupported() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dump(IndentingPrintWriter pw, String[] args) {
    }
}
