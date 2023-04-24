/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.UserIdInt;
import android.app.time.ExternalTimeSuggestion;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.app.time.TimeState;
import android.app.time.UnixEpochTime;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.util.IndentingPrintWriter;

import com.android.server.timezonedetector.StateChangeListener;

/**
 * A fake implementation of {@link com.android.server.timedetector.TimeDetectorStrategy} for use
 * in tests.
 */
public class FakeTimeDetectorStrategy implements TimeDetectorStrategy {
    private final FakeServiceConfigAccessor mFakeServiceConfigAccessor;

    // State
    private TimeState mTimeState;
    private NetworkTimeSuggestion mLatestNetworkTimeSuggestion;

    FakeTimeDetectorStrategy() {
        mFakeServiceConfigAccessor = new FakeServiceConfigAccessor();
    }

    void initializeConfiguration(ConfigurationInternal configuration) {
        mFakeServiceConfigAccessor.initializeCurrentUserConfiguration(configuration);
    }

    @Override
    public TimeState getTimeState() {
        return mTimeState;
    }

    @Override
    public void setTimeState(TimeState timeState) {
        mTimeState = timeState;
    }

    @Override
    public boolean confirmTime(UnixEpochTime confirmationTime) {
        return false;
    }

    @Override
    public void addChangeListener(StateChangeListener listener) {
        mFakeServiceConfigAccessor.addConfigurationInternalChangeListener(listener);
    }

    @Override
    public TimeCapabilitiesAndConfig getCapabilitiesAndConfig(int userId,
            boolean bypassUserPolicyChecks) {
        ConfigurationInternal configurationInternal =
                mFakeServiceConfigAccessor.getConfigurationInternal(userId);
        return configurationInternal.createCapabilitiesAndConfig(bypassUserPolicyChecks);
    }

    @Override
    public boolean updateConfiguration(int userId, TimeConfiguration configuration,
            boolean bypassUserPolicyChecks) {
        return mFakeServiceConfigAccessor.updateConfiguration(
                userId, configuration, bypassUserPolicyChecks);
    }

    @Override
    public void suggestTelephonyTime(TelephonyTimeSuggestion suggestion) {
    }

    @Override
    public boolean suggestManualTime(@UserIdInt int userId, ManualTimeSuggestion suggestion,
            boolean bypassUserPolicyChecks) {
        return true;
    }

    @Override
    public void suggestNetworkTime(NetworkTimeSuggestion suggestion) {
    }

    @Override
    public void addNetworkTimeUpdateListener(StateChangeListener networkSuggestionUpdateListener) {
    }

    @Override
    public NetworkTimeSuggestion getLatestNetworkSuggestion() {
        return mLatestNetworkTimeSuggestion;
    }

    @Override
    public void clearLatestNetworkSuggestion() {
    }

    @Override
    public void suggestGnssTime(GnssTimeSuggestion suggestion) {
    }

    @Override
    public void suggestExternalTime(ExternalTimeSuggestion suggestion) {
    }

    @Override
    public void dump(IndentingPrintWriter pw, String[] args) {
    }

    void setLatestNetworkTime(NetworkTimeSuggestion networkTimeSuggestion) {
        mLatestNetworkTimeSuggestion = networkTimeSuggestion;
    }
}
