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

package com.android.server.timezonedetector.location;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.server.timezonedetector.GeolocationTimeZoneSuggestion;
import com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A snapshot of the location time zone manager service's state for tests. */
final class LocationTimeZoneManagerServiceState {

    @Nullable private final GeolocationTimeZoneSuggestion mLastSuggestion;
    @NonNull private final List<ProviderState> mPrimaryProviderStates;
    @NonNull private final List<ProviderState> mSecondaryProviderStates;

    LocationTimeZoneManagerServiceState(@NonNull Builder builder) {
        mLastSuggestion = builder.mLastSuggestion;
        mPrimaryProviderStates = Objects.requireNonNull(builder.mPrimaryProviderStates);
        mSecondaryProviderStates = Objects.requireNonNull(builder.mSecondaryProviderStates);
    }

    @Nullable
    public GeolocationTimeZoneSuggestion getLastSuggestion() {
        return mLastSuggestion;
    }

    @NonNull
    public List<ProviderState> getPrimaryProviderStates() {
        return Collections.unmodifiableList(mPrimaryProviderStates);
    }

    @NonNull
    public List<ProviderState> getSecondaryProviderStates() {
        return Collections.unmodifiableList(mSecondaryProviderStates);
    }

    @Override
    public String toString() {
        return "LocationTimeZoneManagerServiceState{"
                + "mLastSuggestion=" + mLastSuggestion
                + ", mPrimaryProviderStates=" + mPrimaryProviderStates
                + ", mSecondaryProviderStates=" + mSecondaryProviderStates
                + '}';
    }

    static final class Builder {

        private GeolocationTimeZoneSuggestion mLastSuggestion;
        private List<ProviderState> mPrimaryProviderStates;
        private List<ProviderState> mSecondaryProviderStates;

        @NonNull
        Builder setLastSuggestion(@NonNull GeolocationTimeZoneSuggestion lastSuggestion) {
            mLastSuggestion = Objects.requireNonNull(lastSuggestion);
            return this;
        }

        @NonNull
        Builder setPrimaryProviderStateChanges(@NonNull List<ProviderState> primaryProviderStates) {
            mPrimaryProviderStates = new ArrayList<>(primaryProviderStates);
            return this;
        }

        @NonNull
        Builder setSecondaryProviderStateChanges(
                @NonNull List<ProviderState> secondaryProviderStates) {
            mSecondaryProviderStates = new ArrayList<>(secondaryProviderStates);
            return this;
        }

        @NonNull
        LocationTimeZoneManagerServiceState build() {
            return new LocationTimeZoneManagerServiceState(this);
        }
    }
}
