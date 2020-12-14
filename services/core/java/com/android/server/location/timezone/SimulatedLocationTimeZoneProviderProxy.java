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

import static com.android.server.location.timezone.SimulatedBinderProviderEvent.INJECTED_EVENT_TYPE_LOCATION_TIME_ZONE_EVENT;
import static com.android.server.location.timezone.SimulatedBinderProviderEvent.INJECTED_EVENT_TYPE_ON_BIND;
import static com.android.server.location.timezone.SimulatedBinderProviderEvent.INJECTED_EVENT_TYPE_ON_UNBIND;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.server.timezonedetector.ReferenceWithHistory;

import java.util.Objects;

/**
 * A replacement for a real binder proxy for use during integration testing
 * that can be used to inject simulated {@link LocationTimeZoneProviderProxy} behavior.
 */
class SimulatedLocationTimeZoneProviderProxy extends LocationTimeZoneProviderProxy {

    @GuardedBy("mSharedLock")
    @NonNull private TimeZoneProviderRequest mRequest;

    @GuardedBy("mSharedLock")
    @NonNull private final ReferenceWithHistory<String> mLastEvent = new ReferenceWithHistory<>(50);

    SimulatedLocationTimeZoneProviderProxy(
            @NonNull Context context, @NonNull ThreadingDomain threadingDomain) {
        super(context, threadingDomain);
        mRequest = TimeZoneProviderRequest.createStopUpdatesRequest();
    }

    @Override
    void onInitialize() {
        // No-op - nothing to do for the simulated provider.
    }

    void simulate(@NonNull SimulatedBinderProviderEvent event) {
        mThreadingDomain.assertCurrentThread();

        Objects.requireNonNull(event);

        synchronized (mSharedLock) {
            switch (event.getType()) {
                case INJECTED_EVENT_TYPE_ON_BIND: {
                    mLastEvent.set("Simulating onProviderBound(), event=" + event);
                    mThreadingDomain.post(this::onBindOnHandlerThread);
                    break;
                }
                case INJECTED_EVENT_TYPE_ON_UNBIND: {
                    mLastEvent.set("Simulating onProviderUnbound(), event=" + event);
                    mThreadingDomain.post(this::onUnbindOnHandlerThread);
                    break;
                }
                case INJECTED_EVENT_TYPE_LOCATION_TIME_ZONE_EVENT: {
                    if (!mRequest.sendUpdates()) {
                        mLastEvent.set("Test event=" + event + " is testing an invalid case:"
                                + " reporting is off. mRequest=" + mRequest);
                    }
                    mLastEvent.set("Simulating TimeZoneProviderResult, event=" + event);
                    handleTimeZoneProviderEvent(event.getTimeZoneProviderEvent());
                    break;
                }
                default: {
                    mLastEvent.set("Unknown simulated event type. event=" + event);
                    throw new IllegalArgumentException(
                            "Unknown simulated event type. event=" + event);
                }
            }
        }
    }

    private void onBindOnHandlerThread() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            mListener.onProviderBound();
        }
    }

    private void onUnbindOnHandlerThread() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            mListener.onProviderUnbound();
        }
    }

    @Override
    final void setRequest(@NonNull TimeZoneProviderRequest request) {
        mThreadingDomain.assertCurrentThread();

        Objects.requireNonNull(request);
        synchronized (mSharedLock) {
            mLastEvent.set("Request received: " + request);
            mRequest = request;
        }
    }

    @Override
    public void dump(@NonNull IndentingPrintWriter ipw, @Nullable String[] args) {
        synchronized (mSharedLock) {
            ipw.println("mRequest=" + mRequest);
            ipw.println("mLastEvent=" + mLastEvent);

            ipw.increaseIndent();
            ipw.println("Last event history:");
            mLastEvent.dump(ipw);
            ipw.decreaseIndent();
        }
    }
}
