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

package com.android.server.location.timezone;

import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import java.time.Duration;

/**
 * A {@link LocationTimeZoneProvider} that provides minimal responses needed for the {@link
 * LocationTimeZoneProviderController} to operate correctly when there is no "real" provider
 * configured. This can be used during development / testing, or in a production build when the
 * platform supports more providers than are needed for an Android deployment.
 *
 * <p>For example, if the {@link LocationTimeZoneProviderController} supports a primary
 * and a secondary {@link LocationTimeZoneProvider}, but only a primary is configured, the secondary
 * config will be left null and the {@link LocationTimeZoneProvider} implementation will be
 * defaulted to a {@link NullLocationTimeZoneProvider}. The {@link NullLocationTimeZoneProvider}
 * enters a {@link ProviderState#PROVIDER_STATE_PERM_FAILED} state immediately after being started
 * for the first time and sends the appropriate event, which ensures the {@link
 * LocationTimeZoneProviderController} won't expect any further {@link
 * TimeZoneProviderEvent}s to come from it, and won't attempt to use it
 * again.
 */
class NullLocationTimeZoneProvider extends LocationTimeZoneProvider {

    private static final String TAG = "NullLocationTimeZoneProvider";

    /** Creates the instance. */
    NullLocationTimeZoneProvider(@NonNull ThreadingDomain threadingDomain,
            @NonNull String providerName) {
        super(threadingDomain, providerName);
    }

    @Override
    void onInitialize() {
        // No-op
    }

    @Override
    void onStartUpdates(@NonNull Duration initializationTimeout) {
        // Report a failure (asynchronously using the mThreadingDomain thread to avoid recursion).
        mThreadingDomain.post(()-> {
            // Enter the perm-failed state.
            ProviderState currentState = mCurrentState.get();
            ProviderState failedState = currentState.newState(
                    PROVIDER_STATE_PERM_FAILED, null, null, "Stubbed provider");
            setCurrentState(failedState, true);
        });
    }

    @Override
    void onStopUpdates() {
        // Ignored - this implementation is always permanently failed.
    }

    @Override
    void logWarn(String msg) {
        Slog.w(TAG, msg);
    }

    @Override
    public void dump(@NonNull IndentingPrintWriter ipw, @Nullable String[] args) {
        synchronized (mSharedLock) {
            ipw.println("{Stubbed LocationTimeZoneProvider}");
            ipw.println("mProviderName=" + mProviderName);
            ipw.println("mCurrentState=" + mCurrentState);
        }
    }

    @Override
    public String toString() {
        synchronized (mSharedLock) {
            return "NullLocationTimeZoneProvider{"
                    + "mProviderName='" + mProviderName + '\''
                    + ", mCurrentState='" + mCurrentState + '\''
                    + '}';
        }
    }
}
