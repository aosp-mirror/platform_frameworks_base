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

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.os.Handler;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.timezonedetector.ConfigurationInternal;
import com.android.server.timezonedetector.Dumpable;
import com.android.server.timezonedetector.GeolocationTimeZoneSuggestion;
import com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState;

import java.time.Duration;
import java.util.Objects;

/**
 * An base class for the component responsible handling events from {@link
 * LocationTimeZoneProvider}s and synthesizing time zone ID suggestions for sending to the time zone
 * detector. This interface primarily exists to extract testable detection logic, i.e. with
 * a minimal number of threading considerations or dependencies on Android infrastructure.
 *
 * <p>The controller interacts with the following components:
 * <ul>
 *     <li>The surrounding service, which calls {@link #initialize(Environment, Callback)} and
 *     {@link #onConfigChanged()}.</li>
 *     <li>The {@link Environment} through which obtains information it needs.</li>
 *     <li>The {@link Callback} through which it makes time zone suggestions.</li>
 *     <li>Any {@link LocationTimeZoneProvider} instances it owns, which communicate via the
 *     {@link LocationTimeZoneProvider.ProviderListener#onProviderStateChange(ProviderState)}
 *     method.</li>
 * </ul>
 *
 * <p>All incoming calls except for {@link
 * LocationTimeZoneProviderController#dump(android.util.IndentingPrintWriter, String[])} must be
 * made on the {@link Handler} thread of the {@link ThreadingDomain} passed to {@link
 * #LocationTimeZoneProviderController(ThreadingDomain)}.
 *
 * <p>Provider / controller integration notes:
 *
 * <p>Providers distinguish between "unknown unknowns" ("uncertain") and "known unknowns"
 * ("certain"), i.e. a provider can be uncertain and not know what the time zone is, which is
 * different from the certainty that there are no time zone IDs for the current location. A provider
 * can be certain about there being no time zone IDs for a location for good reason, e.g. for
 * disputed areas and oceans. Distinguishing uncertainty allows the controller to try other
 * providers (or give up), where as certainty means it should not.
 *
 * <p>A provider can fail permanently. A permanent failure will stop the provider until next
 * boot.
 */
abstract class LocationTimeZoneProviderController implements Dumpable {

    @NonNull protected final ThreadingDomain mThreadingDomain;
    @NonNull protected final Object mSharedLock;

    LocationTimeZoneProviderController(@NonNull ThreadingDomain threadingDomain) {
        mThreadingDomain = Objects.requireNonNull(threadingDomain);
        mSharedLock = threadingDomain.getLockObject();
    }

    /**
     * Called to initialize the controller during boot. Called once only.
     * {@link LocationTimeZoneProvider#initialize} must be called by this method.
     */
    abstract void initialize(@NonNull Environment environment, @NonNull Callback callback);

    /**
     * Called when any settings or other device state that affect location-based time zone detection
     * have changed. The receiver should call {@link
     * Environment#getCurrentUserConfigurationInternal()} to get the current user's config. This
     * call must be made on the {@link ThreadingDomain} handler thread.
     */
    abstract void onConfigChanged();

    @VisibleForTesting
    abstract boolean isUncertaintyTimeoutSet();

    @VisibleForTesting
    @DurationMillisLong
    abstract long getUncertaintyTimeoutDelayMillis();

    /** Called if the geolocation time zone detection is being reconfigured. */
    abstract void destroy();

    /**
     * Used by {@link LocationTimeZoneProviderController} to obtain information from the surrounding
     * service. It can easily be faked for tests.
     */
    abstract static class Environment {

        @NonNull protected final ThreadingDomain mThreadingDomain;
        @NonNull protected final Object mSharedLock;

        Environment(@NonNull ThreadingDomain threadingDomain) {
            mThreadingDomain = Objects.requireNonNull(threadingDomain);
            mSharedLock = threadingDomain.getLockObject();
        }

        /** Destroys the environment, i.e. deregisters listeners, etc. */
        abstract void destroy();

        /** Returns the {@link ConfigurationInternal} for the current user of the device. */
        abstract ConfigurationInternal getCurrentUserConfigurationInternal();

        /**
         * Returns the value passed to LocationTimeZoneProviders informing them of how long they
         * have to return their first time zone suggestion.
         */
        abstract Duration getProviderInitializationTimeout();

        /**
         * Returns the extra time granted on top of {@link #getProviderInitializationTimeout()} to
         * allow for slop like communication delays.
         */
        abstract Duration getProviderInitializationTimeoutFuzz();

        /**
         * Returns the delay allowed after receiving uncertainty from a provider before it should be
         * passed on.
         */
        abstract Duration getUncertaintyDelay();
    }

    /**
     * Used by {@link LocationTimeZoneProviderController} to interact with the surrounding service.
     * It can easily be faked for tests.
     */
    abstract static class Callback {

        @NonNull protected final ThreadingDomain mThreadingDomain;
        @NonNull protected final Object mSharedLock;

        Callback(@NonNull ThreadingDomain threadingDomain) {
            mThreadingDomain = Objects.requireNonNull(threadingDomain);
            mSharedLock = threadingDomain.getLockObject();
        }

        /**
         * Suggests the latest time zone state for the device.
         */
        abstract void suggest(@NonNull GeolocationTimeZoneSuggestion suggestion);
    }
}
