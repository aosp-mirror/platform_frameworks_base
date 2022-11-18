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

package com.android.server.timezonedetector.location;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.IndentingPrintWriter;

import java.time.Duration;

/**
 * A {@link LocationTimeZoneProvider} that provides minimal responses needed to operate correctly
 * when there is no "real" provider configured / enabled. This is used when the platform supports
 * more providers than are needed for an Android deployment.
 *
 * <p>That is, the {@link LocationTimeZoneProviderController} supports a primary and a secondary
 * {@link LocationTimeZoneProvider}, but if only a primary is configured, the secondary provider
 * config will marked as "disabled" and the {@link LocationTimeZoneProvider} implementation will use
 * {@link DisabledLocationTimeZoneProvider}. The {@link DisabledLocationTimeZoneProvider} fails
 * initialization and immediately moves to a "permanent failure" state, which ensures the {@link
 * LocationTimeZoneProviderController} correctly categorizes it and won't attempt to use it.
 */
class DisabledLocationTimeZoneProvider extends LocationTimeZoneProvider {

    DisabledLocationTimeZoneProvider(
            @NonNull ProviderMetricsLogger providerMetricsLogger,
            @NonNull ThreadingDomain threadingDomain,
            @NonNull String providerName,
            boolean recordStateChanges) {
        super(providerMetricsLogger, threadingDomain, providerName, x -> x, recordStateChanges);
    }

    @Override
    boolean onInitialize() {
        // Fail initialization, preventing further use.
        return false;
    }

    @Override
    void onDestroy() {
    }

    @Override
    void onStartUpdates(@NonNull Duration initializationTimeout,
            @NonNull Duration eventFilteringAgeThreshold) {
        throw new UnsupportedOperationException("Provider is disabled");
    }

    @Override
    void onStopUpdates() {
        throw new UnsupportedOperationException("Provider is disabled");
    }

    @Override
    public void dump(@NonNull IndentingPrintWriter ipw, @Nullable String[] args) {
        synchronized (mSharedLock) {
            ipw.println("{DisabledLocationTimeZoneProvider}");
            ipw.println("mProviderName=" + mProviderName);
            ipw.println("mCurrentState=" + mCurrentState);
        }
    }

    @Override
    public String toString() {
        synchronized (mSharedLock) {
            return "DisabledLocationTimeZoneProvider{"
                    + "mProviderName=" + mProviderName
                    + ", mCurrentState=" + mCurrentState
                    + '}';
        }
    }
}
