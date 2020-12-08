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

package android.location;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * An encapsulation of various parameters for requesting last location via {@link LocationManager}.
 *
 * @hide
 */
@SystemApi
public final class LastLocationRequest implements Parcelable {

    private final boolean mHiddenFromAppOps;
    private final boolean mLocationSettingsIgnored;

    private LastLocationRequest(
            boolean hiddenFromAppOps,
            boolean locationSettingsIgnored) {
        mHiddenFromAppOps = hiddenFromAppOps;
        mLocationSettingsIgnored = locationSettingsIgnored;
    }

    /**
     * Returns true if this last location request should be ignored while updating app ops with
     * location usage. This implies that someone else (usually the creator of the last location
     * request) is responsible for updating app ops.
     *
     * @return true if this request should be ignored while updating app ops with location usage
     *
     */
    public boolean isHiddenFromAppOps() {
        return mHiddenFromAppOps;
    }

    /**
     * Returns true if location settings, throttling, background location limits, and any other
     * possible limiting factors will be ignored in order to satisfy this last location request.
     *
     * @return true if all limiting factors will be ignored to satisfy this request
     */
    public boolean isLocationSettingsIgnored() {
        return mLocationSettingsIgnored;
    }

    public static final @NonNull Parcelable.Creator<LastLocationRequest> CREATOR =
            new Parcelable.Creator<LastLocationRequest>() {
                @Override
                public LastLocationRequest createFromParcel(Parcel in) {
                    return new LastLocationRequest(
                            /* hiddenFromAppOps= */ in.readBoolean(),
                            /* locationSettingsIgnored= */ in.readBoolean());
                }
                @Override
                public LastLocationRequest[] newArray(int size) {
                    return new LastLocationRequest[size];
                }
            };
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeBoolean(mHiddenFromAppOps);
        parcel.writeBoolean(mLocationSettingsIgnored);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LastLocationRequest that = (LastLocationRequest) o;
        return mHiddenFromAppOps == that.mHiddenFromAppOps
                && mLocationSettingsIgnored == that.mLocationSettingsIgnored;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mHiddenFromAppOps, mLocationSettingsIgnored);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("LastLocationRequest[");
        if (mHiddenFromAppOps) {
            s.append("hiddenFromAppOps, ");
        }
        if (mLocationSettingsIgnored) {
            s.append("locationSettingsIgnored, ");
        }
        if (s.length() > "LastLocationRequest[".length()) {
            s.setLength(s.length() - 2);
        }
        s.append(']');
        return s.toString();
    }

    /**
     * A builder class for {@link LastLocationRequest}.
     */
    public static final class Builder {

        private boolean mHiddenFromAppOps;
        private boolean mLocationSettingsIgnored;

        /**
         * Creates a new Builder.
         */
        public Builder() {
            mHiddenFromAppOps = false;
            mLocationSettingsIgnored = false;
        }

        /**
         * Creates a new Builder with all parameters copied from the given last location request.
         */
        public Builder(@NonNull LastLocationRequest lastLocationRequest) {
            mHiddenFromAppOps = lastLocationRequest.mHiddenFromAppOps;
            mLocationSettingsIgnored = lastLocationRequest.mLocationSettingsIgnored;
        }

        /**
         * If set to true, indicates that app ops should not be updated with location usage due to
         * this request. This implies that someone else (usually the creator of the last location
         * request) is responsible for updating app ops as appropriate. Defaults to false.
         *
         * <p>Permissions enforcement occurs when resulting last location request is actually used,
         * not when this method is invoked.
         */
        @RequiresPermission(Manifest.permission.UPDATE_APP_OPS_STATS)
        public @NonNull Builder setHiddenFromAppOps(boolean hiddenFromAppOps) {
            mHiddenFromAppOps = hiddenFromAppOps;
            return this;
        }

        /**
         * If set to true, indicates that location settings, throttling, background location limits,
         * and any other possible limiting factors should be ignored in order to satisfy this
         * last location request. This is only intended for use in user initiated emergency
         * situations, and should be used extremely cautiously. Defaults to false.
         *
         * <p>Permissions enforcement occurs when resulting last location request is actually used,
         * not when this method is invoked.
         */
        @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
        public @NonNull Builder setLocationSettingsIgnored(boolean locationSettingsIgnored) {
            mLocationSettingsIgnored = locationSettingsIgnored;
            return this;
        }

        /**
         * Builds a last location request from this builder.
         *
         * @return a new last location request
         */
        public @NonNull LastLocationRequest build() {
            return new LastLocationRequest(
                    mHiddenFromAppOps,
                    mLocationSettingsIgnored);
        }
    }
}
