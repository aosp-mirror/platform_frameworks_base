/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony.ims;

import android.annotation.NonNull;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * The container of RCS application related configs.
 *
 * @hide
 */
@SystemApi
public final class RcsClientConfiguration implements Parcelable {

    /**@hide*/
    @StringDef(prefix = "RCS_PROFILE_",
            value = {RCS_PROFILE_1_0, RCS_PROFILE_2_3})
    public @interface StringRcsProfile {}

    /**
     * RCS profile UP 1.0
     */
    public static final String RCS_PROFILE_1_0 = "UP_1.0";
    /**
     * RCS profile UP 2.3
     */
    public static final String RCS_PROFILE_2_3 = "UP_2.3";

    private String mRcsVersion;
    private String mRcsProfile;
    private String mClientVendor;
    private String mClientVersion;

    /**
     * Create a RcsClientConfiguration object.
     * Default messaging application must pass a valid configuration object
     * @param rcsVersion The parameter identifies the RCS version supported
     * by the client. Refer to GSMA RCC.07 "rcs_version" parameter.
     * @param rcsProfile Identifies a fixed set of RCS services that are
     * supported by the client. See {@link #RCS_PROFILE_1_0 } or
     * {@link #RCS_PROFILE_2_3 }
     * @param clientVendor Identifies the vendor providing the RCS client.
     * @param clientVersion Identifies the RCS client version. Refer to GSMA
     * RCC.07 "client_version" parameter.
     * Example:client_version=RCSAndrd-1.0
     */
    public RcsClientConfiguration(@NonNull String rcsVersion,
            @NonNull @StringRcsProfile String rcsProfile,
            @NonNull String clientVendor, @NonNull String clientVersion) {
        mRcsVersion = rcsVersion;
        mRcsProfile = rcsProfile;
        mClientVendor = clientVendor;
        mClientVersion = clientVersion;
    }

    /**
     * Returns RCS version supported.
     */
    public @NonNull String getRcsVersion() {
        return mRcsVersion;
    }

    /**
     * Returns RCS profile supported.
     */
    public @NonNull @StringRcsProfile String getRcsProfile() {
        return mRcsProfile;
    }

    /**
     * Returns the name of the vendor providing the RCS client.
     */
    public @NonNull String getClientVendor() {
        return mClientVendor;
    }

    /**
     * Returns the RCS client version.
     */
    public @NonNull String getClientVersion() {
        return mClientVersion;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeString(mRcsVersion);
        out.writeString(mRcsProfile);
        out.writeString(mClientVendor);
        out.writeString(mClientVersion);
    }

    /**
     * {@link Parcelable.Creator}
     *
     */
    public static final @android.annotation.NonNull Parcelable.Creator<
            RcsClientConfiguration> CREATOR = new Creator<RcsClientConfiguration>() {
                @Override
                public RcsClientConfiguration createFromParcel(Parcel in) {
                    String rcsVersion = in.readString();
                    String rcsProfile = in.readString();
                    String clientVendor = in.readString();
                    String clientVersion = in.readString();
                    return new RcsClientConfiguration(rcsVersion, rcsProfile,
                            clientVendor, clientVersion);
                }

                @Override
                public RcsClientConfiguration[] newArray(int size) {
                    return new RcsClientConfiguration[size];
                }
            };

    /**
     * {@link Parcelable#describeContents}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof RcsClientConfiguration)) {
            return false;
        }

        RcsClientConfiguration other = (RcsClientConfiguration) obj;

        return mRcsVersion.equals(other.mRcsVersion) && mRcsProfile.equals(other.mRcsProfile)
                && mClientVendor.equals(other.mClientVendor)
                && mClientVersion.equals(other.mClientVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRcsVersion, mRcsProfile, mClientVendor, mClientVersion);
    }
}
