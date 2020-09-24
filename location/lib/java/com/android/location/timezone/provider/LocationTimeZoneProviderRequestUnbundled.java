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

package com.android.location.timezone.provider;

import android.annotation.IntRange;
import android.annotation.NonNull;

import com.android.internal.location.timezone.LocationTimeZoneProviderRequest;

import java.util.Objects;

/**
 * This class is an interface to LocationTimeZoneProviderRequest for provider implementations.
 *
 * <p>IMPORTANT: This class is effectively a public API for unbundled code, and must remain API
 * stable.
 */
public final class LocationTimeZoneProviderRequestUnbundled {

    private final LocationTimeZoneProviderRequest mRequest;

    /** @hide */
    public LocationTimeZoneProviderRequestUnbundled(
            @NonNull LocationTimeZoneProviderRequest request) {
        mRequest = Objects.requireNonNull(request);
    }

    /**
     * Returns {@code true} if the provider should report events related to the device's current
     * time zone, {@code false} otherwise.
     */
    public boolean getReportLocationTimeZone() {
        return mRequest.getReportLocationTimeZone();
    }

    /**
     * Returns the maximum time that the provider is allowed to initialize before it is expected to
     * send an event of any sort. Only valid when {@link #getReportLocationTimeZone()} is {@code
     * true}. Failure to send an event in this time (with some fuzz) may be interpreted as if the
     * provider is uncertain of the time zone, and/or it could lead to the provider being disabled.
     */
    @IntRange(from = 0)
    public long getInitializationTimeoutMillis() {
        return mRequest.getInitializationTimeoutMillis();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LocationTimeZoneProviderRequestUnbundled that =
                (LocationTimeZoneProviderRequestUnbundled) o;
        return mRequest.equals(that.mRequest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRequest);
    }

    @Override
    public String toString() {
        return mRequest.toString();
    }
}
