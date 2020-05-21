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
            new LocationTimeZoneProviderRequest(false);

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

    /** Location time zone reporting is requested (true) */
    private final boolean mReportLocationTimeZone;

    private LocationTimeZoneProviderRequest(boolean reportLocationTimeZone) {
        mReportLocationTimeZone = reportLocationTimeZone;
    }

    public boolean getReportLocationTimeZone() {
        return mReportLocationTimeZone;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mReportLocationTimeZone ? 1 : 0);
    }

    static LocationTimeZoneProviderRequest createFromParcel(Parcel in) {
        ClassLoader classLoader = LocationTimeZoneProviderRequest.class.getClassLoader();
        return new Builder()
                .setReportLocationTimeZone(in.readInt() == 1)
                .build();
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
        return mReportLocationTimeZone == that.mReportLocationTimeZone;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mReportLocationTimeZone);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("TimeZoneProviderRequest[");
        if (mReportLocationTimeZone) {
            s.append("ON");
        } else {
            s.append("OFF");
        }
        s.append(']');
        return s.toString();
    }

    /** @hide */
    public static final class Builder {

        private boolean mReportLocationTimeZone;

        /**
         * Sets the property that enables / disables the provider. This is set to {@code false} by
         * default.
         */
        public Builder setReportLocationTimeZone(boolean reportLocationTimeZone) {
            mReportLocationTimeZone = reportLocationTimeZone;
            return this;
        }

        /** Builds the {@link LocationTimeZoneProviderRequest} instance. */
        @NonNull
        public LocationTimeZoneProviderRequest build() {
            return new LocationTimeZoneProviderRequest(this.mReportLocationTimeZone);
        }
    }
}
