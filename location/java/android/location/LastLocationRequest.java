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
    private final boolean mAdasGnssBypass;
    private final boolean mLocationSettingsIgnored;

    private LastLocationRequest(
            boolean hiddenFromAppOps,
            boolean adasGnssBypass,
            boolean locationSettingsIgnored) {
        mHiddenFromAppOps = hiddenFromAppOps;
        mAdasGnssBypass = adasGnssBypass;
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
     * Returns true if this request may access GNSS even if location settings would normally deny
     * this, in order to enable automotive safety features. This field is only respected on
     * automotive devices, and only if the client is recognized as a legitimate ADAS (Advanced
     * Driving Assistance Systems) application.
     *
     * @return true if all limiting factors will be ignored to satisfy GNSS request
     * @hide
     */
    // TODO: make this system api
    public boolean isAdasGnssBypass() {
        return mAdasGnssBypass;
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

    /**
     * Returns true if any bypass flag is set on this request. For internal use only.
     *
     * @hide
     */
    public boolean isBypass() {
        return mAdasGnssBypass || mLocationSettingsIgnored;
    }

    public static final @NonNull Parcelable.Creator<LastLocationRequest> CREATOR =
            new Parcelable.Creator<LastLocationRequest>() {
                @Override
                public LastLocationRequest createFromParcel(Parcel in) {
                    return new LastLocationRequest(
                            /* hiddenFromAppOps= */ in.readBoolean(),
                            /* adasGnssBypass= */ in.readBoolean(),
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
        parcel.writeBoolean(mAdasGnssBypass);
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
                && mAdasGnssBypass == that.mAdasGnssBypass
                && mLocationSettingsIgnored == that.mLocationSettingsIgnored;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mHiddenFromAppOps, mAdasGnssBypass, mLocationSettingsIgnored);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("LastLocationRequest[");
        if (mHiddenFromAppOps) {
            s.append("hiddenFromAppOps, ");
        }
        if (mAdasGnssBypass) {
            s.append("adasGnssBypass, ");
        }
        if (mLocationSettingsIgnored) {
            s.append("settingsBypass, ");
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
        private boolean mAdasGnssBypass;
        private boolean mLocationSettingsIgnored;

        /**
         * Creates a new Builder.
         */
        public Builder() {
            mHiddenFromAppOps = false;
            mAdasGnssBypass = false;
            mLocationSettingsIgnored = false;
        }

        /**
         * Creates a new Builder with all parameters copied from the given last location request.
         */
        public Builder(@NonNull LastLocationRequest lastLocationRequest) {
            mHiddenFromAppOps = lastLocationRequest.mHiddenFromAppOps;
            mAdasGnssBypass = lastLocationRequest.mAdasGnssBypass;
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
         * If set to true, indicates that the client is an ADAS (Advanced Driving Assistance
         * Systems) client, which requires access to GNSS even if location settings would normally
         * deny this, in order to enable auto safety features. This field is only respected on
         * automotive devices, and only if the client is recognized as a legitimate ADAS
         * application. Defaults to false.
         *
         * <p>Permissions enforcement occurs when resulting location request is actually used, not
         * when this method is invoked.
         *
         * @hide
         */
        // TODO: make this system api
        @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
        public @NonNull LastLocationRequest.Builder setAdasGnssBypass(boolean adasGnssBypass) {
            mAdasGnssBypass = adasGnssBypass;
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
                    mAdasGnssBypass,
                    mLocationSettingsIgnored);
        }
    }
}
