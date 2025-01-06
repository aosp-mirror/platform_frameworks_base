/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.companion;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.MacAddress;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Locale;
import java.util.Objects;

/**
 *  A device id represents a device identifier managed by the companion app.
 */
@FlaggedApi(Flags.FLAG_ASSOCIATION_TAG)
public final class DeviceId implements Parcelable {
    /**
     * The length limit of custom id.
     */
    private static final int CUSTOM_ID_LENGTH_LIMIT = 1024;

    private final String mCustomId;
    private final MacAddress mMacAddress;

    /**
     * @hide
     */
    public DeviceId(@Nullable String customId, @Nullable MacAddress macAddress) {
        mCustomId = customId;
        mMacAddress = macAddress;
    }

    /**
     * Returns true if two Device ids are represent the same device. False otherwise.
     * @hide
     */
    public boolean isSameDevice(@Nullable DeviceId other) {
        if (other == null) {
            return false;
        }

        if (this.mCustomId != null && other.mCustomId != null) {
            return this.mCustomId.equals(other.mCustomId);
        }
        if (this.mMacAddress != null && other.mMacAddress != null) {
            return this.mMacAddress.equals(other.mMacAddress);
        }

        return false;
    }

    /** @hide */
    @Nullable
    public String getMacAddressAsString() {
        return mMacAddress != null ? mMacAddress.toString().toUpperCase(Locale.US) : null;
    }

    /**
     * @return the custom id that managed by the companion app.
     */
    @Nullable
    public String getCustomId() {
        return mCustomId;
    }

    /**
     * @return the mac address that managed by the companion app.
     */
    @Nullable
    public MacAddress getMacAddress() {
        return mMacAddress;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        if (mCustomId != null) {
            dest.writeInt(1);
            dest.writeString8(mCustomId);
        } else {
            dest.writeInt(0);
        }
        dest.writeTypedObject(mMacAddress, 0);

    }

    private DeviceId(@NonNull Parcel in) {
        int flg = in.readInt();
        if (flg == 1) {
            mCustomId = in.readString8();
        } else {
            mCustomId = null;
        }
        mMacAddress = in.readTypedObject(MacAddress.CREATOR);
    }

    @NonNull
    public static final Parcelable.Creator<DeviceId> CREATOR =
            new Parcelable.Creator<DeviceId>() {
                @Override
                public DeviceId[] newArray(int size) {
                    return new DeviceId[size];
                }

                @Override
                public DeviceId createFromParcel(@android.annotation.NonNull Parcel in) {
                    return new DeviceId(in);
                }
            };

    @Override
    public int hashCode() {
        return Objects.hash(mCustomId, mMacAddress);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceId that)) return false;

        return Objects.equals(mCustomId, that.mCustomId)
                && Objects.equals(mMacAddress, that.mMacAddress);
    }

    @Override
    public String toString() {
        return "DeviceId{"
                + "," + "mCustomId= " + mCustomId
                + "," + "mMacAddress= " + mMacAddress
                + "}";
    }

    /**
     * A builder for {@link DeviceId}
     *
     * <p>Calling apps must provide at least one of the following to identify
     * the device: a custom ID using {@link #setCustomId(String)}, or a MAC address using
     * {@link #setMacAddress(MacAddress)}.</p>
     */
    public static final class Builder {
        private String mCustomId;
        private MacAddress mMacAddress;

        public Builder() {}

        /**
         * Sets the custom device id. This id is used by the Companion app to
         * identify a specific device.
         *
         * @param customId the custom device id
         * @throws IllegalArgumentException length of the custom id must more than 1024
         * characters to save disk space.
         */
        @NonNull
        public Builder setCustomId(@Nullable String customId) {
            if (customId != null
                    && customId.length() > CUSTOM_ID_LENGTH_LIMIT) {
                throw new IllegalArgumentException("Length of the custom id must be at most "
                        + CUSTOM_ID_LENGTH_LIMIT + " characters");
            }
            this.mCustomId = customId;
            return this;
        }

        /**
         * Sets the mac address. This mac address is used by the Companion app to
         * identify a specific device.
         *
         * @param macAddress the remote device mac address
         * @throws IllegalArgumentException length of the custom id must more than 1024
         * characters to save disk space.
         */
        @NonNull
        public Builder setMacAddress(@Nullable MacAddress macAddress) {
            mMacAddress = macAddress;
            return this;
        }

        @NonNull
        public DeviceId build() {
            if (mCustomId == null && mMacAddress == null) {
                throw new IllegalArgumentException("At least one device id property must be"
                        + "non-null to build a DeviceId.");
            }
            return new DeviceId(mCustomId, mMacAddress);
        }
    }
}
