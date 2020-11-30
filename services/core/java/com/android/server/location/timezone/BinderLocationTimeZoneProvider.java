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

import static com.android.server.location.timezone.LocationTimeZoneManagerService.debugLog;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_DISABLED;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_CERTAIN;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_INITIALIZING;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_UNCERTAIN;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.location.timezone.LocationTimeZoneEvent;
import com.android.internal.location.timezone.LocationTimeZoneProviderRequest;

import java.time.Duration;
import java.util.Objects;

/**
 * The real, system-server side implementation of a binder call backed {@link
 * LocationTimeZoneProvider}. It handles keeping track of current state, timeouts and ensuring
 * events are passed to the {@link LocationTimeZoneProviderController} on the required thread.
 */
class BinderLocationTimeZoneProvider extends LocationTimeZoneProvider {

    private static final String TAG = LocationTimeZoneManagerService.TAG;

    @NonNull private final LocationTimeZoneProviderProxy mProxy;

    BinderLocationTimeZoneProvider(
            @NonNull ThreadingDomain threadingDomain,
            @NonNull String providerName,
            @NonNull LocationTimeZoneProviderProxy proxy) {
        super(threadingDomain, providerName);
        mProxy = Objects.requireNonNull(proxy);
    }

    @Override
    void onInitialize() {
        mProxy.initialize(new LocationTimeZoneProviderProxy.Listener() {
            @Override
            public void onReportLocationTimeZoneEvent(
                    @NonNull LocationTimeZoneEvent locationTimeZoneEvent) {
                handleLocationTimeZoneEvent(locationTimeZoneEvent);
            }

            @Override
            public void onProviderBound() {
                handleOnProviderBound();
            }

            @Override
            public void onProviderUnbound() {
                handleProviderLost("onProviderUnbound()");
            }
        });
    }

    private void handleProviderLost(String reason) {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            ProviderState currentState = mCurrentState.get();
            switch (currentState.stateEnum) {
                case PROVIDER_STATE_ENABLED_INITIALIZING:
                case PROVIDER_STATE_ENABLED_UNCERTAIN:
                case PROVIDER_STATE_ENABLED_CERTAIN: {
                    // Losing a remote provider is treated as becoming uncertain.
                    String msg = "handleProviderLost reason=" + reason
                            + ", mProviderName=" + mProviderName
                            + ", currentState=" + currentState;
                    debugLog(msg);
                    // This is an unusual PROVIDER_STATE_ENABLED_UNCERTAIN state because
                    // event == null
                    ProviderState newState = currentState.newState(
                            PROVIDER_STATE_ENABLED_UNCERTAIN, null,
                            currentState.currentUserConfiguration, msg);
                    setCurrentState(newState, true);
                    break;
                }
                case PROVIDER_STATE_DISABLED: {
                    debugLog("handleProviderLost reason=" + reason
                            + ", mProviderName=" + mProviderName
                            + ", currentState=" + currentState
                            + ": No state change required, provider is disabled.");
                    break;
                }
                case PROVIDER_STATE_PERM_FAILED: {
                    debugLog("handleProviderLost reason=" + reason
                            + ", mProviderName=" + mProviderName
                            + ", currentState=" + currentState
                            + ": No state change required, provider is perm failed.");
                    break;
                }
                default: {
                    throw new IllegalStateException("Unknown currentState=" + currentState);
                }
            }
        }
    }

    private void handleOnProviderBound() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            ProviderState currentState = mCurrentState.get();
            switch (currentState.stateEnum) {
                case PROVIDER_STATE_ENABLED_INITIALIZING:
                case PROVIDER_STATE_ENABLED_CERTAIN:
                case PROVIDER_STATE_ENABLED_UNCERTAIN: {
                    debugLog("handleOnProviderBound mProviderName=" + mProviderName
                            + ", currentState=" + currentState + ": Provider is enabled.");
                    break;
                }
                case PROVIDER_STATE_DISABLED: {
                    debugLog("handleOnProviderBound mProviderName=" + mProviderName
                            + ", currentState=" + currentState + ": Provider is disabled.");
                    break;
                }
                case PROVIDER_STATE_PERM_FAILED: {
                    debugLog("handleOnProviderBound"
                            + ", mProviderName=" + mProviderName
                            + ", currentState=" + currentState
                            + ": No state change required, provider is perm failed.");
                    break;
                }
                default: {
                    throw new IllegalStateException("Unknown currentState=" + currentState);
                }
            }
        }
    }

    @Override
    void onEnable(@NonNull Duration initializationTimeout) {
        // Set a request on the proxy - it will be sent immediately if the service is bound,
        // or will be sent as soon as the service becomes bound.
        LocationTimeZoneProviderRequest request =
                new LocationTimeZoneProviderRequest.Builder()
                        .setReportLocationTimeZone(true)
                        .setInitializationTimeoutMillis(initializationTimeout.toMillis())
                        .build();
        mProxy.setRequest(request);
    }

    @Override
    void onDisable() {
        LocationTimeZoneProviderRequest request =
                new LocationTimeZoneProviderRequest.Builder()
                        .setReportLocationTimeZone(false)
                        .build();
        mProxy.setRequest(request);
    }

    @Override
    void logWarn(String msg) {
        Slog.w(TAG, msg);
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

    /**
     * Passes the supplied simulation / testing event to the current proxy iff the proxy is a
     * {@link SimulatedLocationTimeZoneProviderProxy}. If not, the event is logged but discarded.
     */
    void simulateBinderProviderEvent(SimulatedBinderProviderEvent event) {
        mThreadingDomain.assertCurrentThread();

        if (!(mProxy instanceof SimulatedLocationTimeZoneProviderProxy)) {
            Slog.w(TAG, mProxy + " is not a " + SimulatedLocationTimeZoneProviderProxy.class
                    + ", event=" + event);
            return;
        }
        ((SimulatedLocationTimeZoneProviderProxy) mProxy).simulate(event);
    }
}
