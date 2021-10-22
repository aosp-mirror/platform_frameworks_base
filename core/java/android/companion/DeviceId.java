/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * The class represents free-form ID of a companion device.
 *
 * Since companion devices may have multiple IDs of different type at the same time
 * (eg. a MAC address and a Serial Number), this class not only stores the ID itself, it also stores
 * the type of the ID.
 * Both the type of the ID and its actual value are represented as {@link String}-s.
 *
 * Examples of device IDs:
 *  - "mac_address: f0:18:98:b3:fd:2e"
 *  - "ip_address: 128.121.35.200"
 *  - "imei: 352932100034923 / 44"
 *  - "serial_number: 96141FFAZ000B7"
 *  - "meid_hex: 35293210003492"
 *  - "meid_dic: 08918 92240 0001 3548"
 *
 * @hide
 * TODO(b/1979395): un-hide when implementing public APIs that use this class.
 */
public final class DeviceId implements Parcelable {
    public static final String TYPE_MAC_ADDRESS = "mac_address";

    private final @NonNull String mType;
    private final @NonNull String mValue;

    /**
     * @param type type of the ID. Non-empty. Max length - 16 characters.
     * @param value the ID. Non-empty. Max length - 48 characters.
     * @throws IllegalArgumentException if either {@param type} or {@param value} is empty or
     *         exceeds its max allowed length.
     */
    public DeviceId(@NonNull String type, @NonNull String value) {
        if (type.isEmpty() || value.isEmpty()) {
            throw new IllegalArgumentException("'type' and 'value' should not be empty");
        }
        this.mType = type;
        this.mValue = value;
    }

    /**
     * @return the type of the ID.
     */
    public @NonNull String getType() {
        return mType;
    }

    /**
     * @return the ID.
     */
    public @NonNull String getValue() {
        return mValue;
    }

    @Override
    public String toString() {
        return "DeviceId{"
                + "type='" + mType + '\''
                + ", value='" + mValue + '\''
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeviceId)) return false;
        DeviceId deviceId = (DeviceId) o;
        return Objects.equals(mType, deviceId.mType) && Objects.equals(mValue,
                deviceId.mValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mValue);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mType);
        dest.writeString(mValue);
    }

    private DeviceId(@NonNull Parcel in) {
        mType = in.readString();
        mValue = in.readString();
    }

    public static final @NonNull Creator<DeviceId> CREATOR = new Creator<DeviceId>() {
        @Override
        public DeviceId createFromParcel(@NonNull Parcel in) {
            return new DeviceId(in);
        }

        @Override
        public DeviceId[] newArray(int size) {
            return new DeviceId[size];
        }
    };
}
