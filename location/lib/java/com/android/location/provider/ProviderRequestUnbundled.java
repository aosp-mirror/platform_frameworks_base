/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.location.provider;

import android.annotation.NonNull;
import android.location.LocationRequest;
import android.location.provider.ProviderRequest;
import android.os.Build;
import android.os.WorkSource;

import androidx.annotation.RequiresApi;

import java.util.Collections;
import java.util.List;

/**
 * Represents a provider request for unbundled applications.
 *
 * <p>IMPORTANT: This class is effectively a public API for unbundled applications, and must remain
 * API stable.
 */
public final class ProviderRequestUnbundled {

    /**
     * Represents a disabled request.
     */
    public static final long INTERVAL_DISABLED = ProviderRequest.INTERVAL_DISABLED;

    private final ProviderRequest mRequest;

    /** @hide */
    public ProviderRequestUnbundled(ProviderRequest request) {
        mRequest = request;
    }

    /**
     * True if this is an active request with a valid location reporting interval, false if this
     * request is inactive and does not require any locations to be reported.
     */
    public boolean getReportLocation() {
        return mRequest.isActive();
    }

    /**
     * The interval at which a provider should report location. Will return
     * {@link #INTERVAL_DISABLED} for an inactive request.
     */
    public long getInterval() {
        return mRequest.getIntervalMillis();
    }

    /**
     * The quality hint for this location request. The quality hint informs the provider how it
     * should attempt to manage any accuracy vs power tradeoffs while attempting to satisfy this
     * provider request.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public @LocationRequest.Quality int getQuality() {
        return mRequest.getQuality();
    }

    /**
     * The maximum time any location update may be delayed, and thus grouped with following updates
     * to enable location batching. If the maximum update delay is equal to or greater than
     * twice the interval, then the provider may provide batched results if possible. The maximum
     * batch size a provider is allowed to return is the maximum update delay divided by the
     * interval.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public long getMaxUpdateDelayMillis() {
        return mRequest.getMaxUpdateDelayMillis();
    }

    /**
     * Whether any applicable hardware low power modes should be used to satisfy this request.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public boolean isLowPower() {
        return mRequest.isLowPower();
    }

    /**
     * Whether the provider should ignore all location settings, user consents, power restrictions
     * or any other restricting factors and always satisfy this request to the best of their
     * ability. This should only be used in case of a user initiated emergency.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    public boolean isLocationSettingsIgnored() {
        return mRequest.isLocationSettingsIgnored();
    }

    /**
     * The full list of location requests contributing to this provider request.
     *
     * @deprecated Do not use.
     */
    @Deprecated
    public @NonNull List<LocationRequestUnbundled> getLocationRequests() {
        if (!mRequest.isActive()) {
            return Collections.emptyList();
        }

        return Collections.singletonList(new LocationRequestUnbundled(
                new LocationRequest.Builder(mRequest.getIntervalMillis())
                        .setQuality(mRequest.getQuality())
                        .setLowPower(mRequest.isLowPower())
                        .setLocationSettingsIgnored(mRequest.isLocationSettingsIgnored())
                        .setWorkSource(mRequest.getWorkSource())
                        .build()));
    }

    /**
     * The power blame for this provider request.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public @NonNull WorkSource getWorkSource() {
        return mRequest.getWorkSource();
    }

    @Override
    public String toString() {
        return mRequest.toString();
    }
}
