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

package com.android.internal.location.timezone;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * A request passed to a location time zone provider to configure it.
 *
 * @hide
 */
public final class LocationTimeZoneProviderRequest implements Parcelable {

    public static final LocationTimeZoneProviderRequest EMPTY_REQUEST =
            new LocationTimeZoneProviderRequest(
                    false /* reportLocationTimeZone */,
                    0 /* initializationTimeoutMillis */);

    public static final Creator<LocationTimeZoneProviderRequest> CREATOR =
            new Creator<LocationTimeZoneProviderRequest>() {
                @Override
                public LocationTimeZoneProviderRequest createFromParcel(Parcel in) {
                    return LocationTimeZoneProviderRequest.createFromParcel(in);
                }

                @Override
                public LocationTimeZoneProviderRequest[] newArray(int size) {
                    return new LocationTimeZoneProviderRequest[size];
                }
            };

    private final boolean mReportLocationTimeZone;

    private final long mInitializationTimeoutMillis;

    private LocationTimeZoneProviderRequest(
            boolean reportLocationTimeZone, long initializationTimeoutMillis) {
        mReportLocationTimeZone = reportLocationTimeZone;
        mInitializationTimeoutMillis = initializationTimeoutMillis;
    }

    /**
     * Returns {@code true} if the provider should report events related to the device's current
     * time zone, {@code false} otherwise.
     */
    public boolean getReportLocationTimeZone() {
        return mReportLocationTimeZone;
    }

    // TODO(b/152744911) - once there are a couple of implementations, decide whether this needs to
    //  be passed to the LocationTimeZoneProvider and remove if it is not useful.
    /**
     * Returns the maximum time that the provider is allowed to initialize before it is expected to
     * send an event of any sort. Only valid when {@link #getReportLocationTimeZone()} is {@code
     * true}. Failure to send an event in this time (with some fuzz) may be interpreted as if the
     * provider is uncertain of the time zone, and/or it could lead to the provider being disabled.
     */
    public long getInitializationTimeoutMillis() {
        return mInitializationTimeoutMillis;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeBoolean(mReportLocationTimeZone);
        parcel.writeLong(mInitializationTimeoutMillis);
    }

    static LocationTimeZoneProviderRequest createFromParcel(Parcel in) {
        boolean reportLocationTimeZone = in.readBoolean();
        long initializationTimeoutMillis = in.readLong();
        return new LocationTimeZoneProviderRequest(
                reportLocationTimeZone, initializationTimeoutMillis);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LocationTimeZoneProviderRequest that = (LocationTimeZoneProviderRequest) o;
        return mReportLocationTimeZone == that.mReportLocationTimeZone
            && mInitializationTimeoutMillis == that.mInitializationTimeoutMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mReportLocationTimeZone, mInitializationTimeoutMillis);
    }

    @Override
    public String toString() {
        return "LocationTimeZoneProviderRequest{"
                + "mReportLocationTimeZone=" + mReportLocationTimeZone
                + ", mInitializationTimeoutMillis=" + mInitializationTimeoutMillis
                + "}";
    }

    /** @hide */
    public static final class Builder {

        private boolean mReportLocationTimeZone;
        private long mInitializationTimeoutMillis;

        /**
         * Sets the property that enables / disables the provider. This is set to {@code false} by
         * default.
         */
        public Builder setReportLocationTimeZone(boolean reportLocationTimeZone) {
            mReportLocationTimeZone = reportLocationTimeZone;
            return this;
        }

        /**
         * Sets the initialization timeout. See {@link
         * LocationTimeZoneProviderRequest#getInitializationTimeoutMillis()} for details.
         */
        public Builder setInitializationTimeoutMillis(long timeoutMillis) {
            mInitializationTimeoutMillis = timeoutMillis;
            return this;
        }

        /** Builds the {@link LocationTimeZoneProviderRequest} instance. */
        @NonNull
        public LocationTimeZoneProviderRequest build() {
            return new LocationTimeZoneProviderRequest(
                    mReportLocationTimeZone, mInitializationTimeoutMillis);
        }
    }
}
