/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.companion.BluetoothDeviceFilterUtils.getDeviceDisplayNameInternal;
import static android.companion.BluetoothDeviceFilterUtils.matchesAddress;
import static android.companion.BluetoothDeviceFilterUtils.matchesName;
import static android.companion.BluetoothDeviceFilterUtils.matchesServiceUuids;
import static android.companion.BluetoothDeviceFilterUtils.patternFromString;
import static android.companion.BluetoothDeviceFilterUtils.patternToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothDevice;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.provider.OneTimeUseBuilder;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A filter for Bluetooth(non-LE) devices
 */
public final class BluetoothDeviceFilter implements DeviceFilter<BluetoothDevice> {

    private final Pattern mNamePattern;
    private final String mAddress;
    private final List<ParcelUuid> mServiceUuids;
    private final List<ParcelUuid> mServiceUuidMasks;

    private BluetoothDeviceFilter(
            Pattern namePattern,
            String address,
            List<ParcelUuid> serviceUuids,
            List<ParcelUuid> serviceUuidMasks) {
        mNamePattern = namePattern;
        mAddress = address;
        mServiceUuids = CollectionUtils.emptyIfNull(serviceUuids);
        mServiceUuidMasks = CollectionUtils.emptyIfNull(serviceUuidMasks);
    }

    private BluetoothDeviceFilter(Parcel in) {
        this(
            patternFromString(in.readString()),
            in.readString(),
            readUuids(in),
            readUuids(in));
    }

    private static List<ParcelUuid> readUuids(Parcel in) {
        return in.readParcelableList(new ArrayList<>(), ParcelUuid.class.getClassLoader());
    }

    /** @hide */
    @Override
    public boolean matches(BluetoothDevice device) {
        return matchesAddress(mAddress, device)
                && matchesServiceUuids(mServiceUuids, mServiceUuidMasks, device)
                && matchesName(getNamePattern(), device);
    }

    /** @hide */
    @Override
    public String getDeviceDisplayName(BluetoothDevice device) {
        return getDeviceDisplayNameInternal(device);
    }

    /** @hide */
    @Override
    public int getMediumType() {
        return DeviceFilter.MEDIUM_TYPE_BLUETOOTH;
    }

    /** @hide */
    @Nullable
    public Pattern getNamePattern() {
        return mNamePattern;
    }

    /** @hide */
    @Nullable
    @UnsupportedAppUsage
    public String getAddress() {
        return mAddress;
    }

    /** @hide */
    @NonNull
    public List<ParcelUuid> getServiceUuids() {
        return mServiceUuids;
    }

    /** @hide */
    @NonNull
    public List<ParcelUuid> getServiceUuidMasks() {
        return mServiceUuidMasks;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(patternToString(getNamePattern()));
        dest.writeString(mAddress);
        dest.writeParcelableList(mServiceUuids, flags);
        dest.writeParcelableList(mServiceUuidMasks, flags);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BluetoothDeviceFilter that = (BluetoothDeviceFilter) o;
        return Objects.equals(mNamePattern, that.mNamePattern) &&
                Objects.equals(mAddress, that.mAddress) &&
                Objects.equals(mServiceUuids, that.mServiceUuids) &&
                Objects.equals(mServiceUuidMasks, that.mServiceUuidMasks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNamePattern, mAddress, mServiceUuids, mServiceUuidMasks);
    }

    @Override
    public String toString() {
        return "BluetoothDeviceFilter{"
                + "mNamePattern=" + mNamePattern
                + ", mAddress='" + mAddress + '\''
                + ", mServiceUuids=" + mServiceUuids
                + ", mServiceUuidMasks=" + mServiceUuidMasks
                + '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Creator<BluetoothDeviceFilter> CREATOR
            = new Creator<BluetoothDeviceFilter>() {
        @Override
        public BluetoothDeviceFilter createFromParcel(Parcel in) {
            return new BluetoothDeviceFilter(in);
        }

        @Override
        public BluetoothDeviceFilter[] newArray(int size) {
            return new BluetoothDeviceFilter[size];
        }
    };

    /**
     * A builder for {@link BluetoothDeviceFilter}
     */
    public static final class Builder extends OneTimeUseBuilder<BluetoothDeviceFilter> {
        private Pattern mNamePattern;
        private String mAddress;
        private ArrayList<ParcelUuid> mServiceUuid;
        private ArrayList<ParcelUuid> mServiceUuidMask;

        /**
         * @param regex if set, only devices with {@link BluetoothDevice#getName name} matching the
         *              given regular expression will be shown
         */
        public Builder setNamePattern(@Nullable Pattern regex) {
            checkNotUsed();
            mNamePattern = regex;
            return this;
        }

        /**
         * @param address if set, only devices with MAC address exactly matching the given one will
         *                pass the filter
         */
        @NonNull
        public Builder setAddress(@Nullable String address) {
            checkNotUsed();
            mAddress = address;
            return this;
        }

        /**
         * Add filtering by certain bits of {@link BluetoothDevice#getUuids()}
         *
         * A device with any uuid matching the given bits is considered passing
         *
         * @param serviceUuid the values for the bits to match
         * @param serviceUuidMask if provided, only those bits would have to match.
         */
        @NonNull
        public Builder addServiceUuid(
                @Nullable ParcelUuid serviceUuid, @Nullable ParcelUuid serviceUuidMask) {
            checkNotUsed();
            mServiceUuid = ArrayUtils.add(mServiceUuid, serviceUuid);
            mServiceUuidMask = ArrayUtils.add(mServiceUuidMask, serviceUuidMask);
            return this;
        }

        /** @inheritDoc */
        @Override
        @NonNull
        public BluetoothDeviceFilter build() {
            markUsed();
            return new BluetoothDeviceFilter(
                    mNamePattern, mAddress, mServiceUuid, mServiceUuidMask);
        }
    }
}
