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

import static com.android.server.timezonedetector.location.LocationTimeZoneManagerService.debugLog;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_DESTROYED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_CERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_UNCERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STOPPED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.service.timezone.TimeZoneProviderEvent;
import android.util.IndentingPrintWriter;

import java.time.Duration;
import java.util.Objects;

/**
 * The real, system-server side implementation of a binder call backed {@link
 * LocationTimeZoneProvider}. It handles keeping track of current state, timeouts and ensuring
 * events are passed to the {@link LocationTimeZoneProviderController} on the required thread.
 */
class BinderLocationTimeZoneProvider extends LocationTimeZoneProvider {

    @NonNull private final LocationTimeZoneProviderProxy mProxy;

    BinderLocationTimeZoneProvider(
            @NonNull ProviderMetricsLogger providerMetricsLogger,
            @NonNull ThreadingDomain threadingDomain,
            @NonNull String providerName,
            @NonNull LocationTimeZoneProviderProxy proxy,
            boolean recordStateChanges) {
        super(providerMetricsLogger, threadingDomain, providerName,
                new ZoneInfoDbTimeZoneProviderEventPreProcessor(), recordStateChanges);
        mProxy = Objects.requireNonNull(proxy);
    }

    @Override
    void onInitialize() {
        mProxy.initialize(new LocationTimeZoneProviderProxy.Listener() {
            @Override
            public void onReportTimeZoneProviderEvent(
                    @NonNull TimeZoneProviderEvent timeZoneProviderEvent) {
                handleTimeZoneProviderEvent(timeZoneProviderEvent);
            }

            @Override
            public void onProviderBound() {
                handleOnProviderBound();
            }

            @Override
            public void onProviderUnbound() {
                handleTemporaryFailure("onProviderUnbound()");
            }
        });
    }

    @Override
    void onDestroy() {
        mProxy.destroy();
    }

    private void handleOnProviderBound() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            ProviderState currentState = mCurrentState.get();
            switch (currentState.stateEnum) {
                case PROVIDER_STATE_STARTED_INITIALIZING:
                case PROVIDER_STATE_STARTED_CERTAIN:
                case PROVIDER_STATE_STARTED_UNCERTAIN: {
                    debugLog("handleOnProviderBound mProviderName=" + mProviderName
                            + ", currentState=" + currentState + ": Provider is started.");
                    break;
                }
                case PROVIDER_STATE_STOPPED: {
                    debugLog("handleOnProviderBound mProviderName=" + mProviderName
                            + ", currentState=" + currentState + ": Provider is stopped.");
                    break;
                }
                case PROVIDER_STATE_PERM_FAILED:
                case PROVIDER_STATE_DESTROYED: {
                    debugLog("handleOnProviderBound"
                            + ", mProviderName=" + mProviderName
                            + ", currentState=" + currentState
                            + ": No state change required, provider is terminated.");
                    break;
                }
                default: {
                    throw new IllegalStateException("Unknown currentState=" + currentState);
                }
            }
        }
    }

    @Override
    void onStartUpdates(@NonNull Duration initializationTimeout,
            @NonNull Duration eventFilteringAgeThreshold) {
        // Set a request on the proxy - it will be sent immediately if the service is bound,
        // or will be sent as soon as the service becomes bound.
        TimeZoneProviderRequest request = TimeZoneProviderRequest.createStartUpdatesRequest(
                initializationTimeout, eventFilteringAgeThreshold);
        mProxy.setRequest(request);
    }

    @Override
    void onStopUpdates() {
        TimeZoneProviderRequest request = TimeZoneProviderRequest.createStopUpdatesRequest();
        mProxy.setRequest(request);
    }

    @Override
    public void dump(@NonNull IndentingPrintWriter ipw, @Nullable String[] args) {
        synchronized (mSharedLock) {
            ipw.println("{BinderLocationTimeZoneProvider}");
            ipw.println("mProviderName=" + mProviderName);
            ipw.println("mCurrentState=" + mCurrentState);
            ipw.println("mProxy=" + mProxy);

            ipw.println("State history:");
            ipw.increaseIndent();
            mCurrentState.dump(ipw);
            ipw.decreaseIndent();

            ipw.println("Proxy details:");
            ipw.increaseIndent();
            mProxy.dump(ipw, args);
            ipw.decreaseIndent();
        }
    }

    @Override
    public String toString() {
        synchronized (mSharedLock) {
            return "BinderLocationTimeZoneProvider{"
                    + "mProviderName=" + mProviderName
                    + ", mCurrentState=" + mCurrentState
                    + ", mProxy=" + mProxy
                    + '}';
        }
    }
}
