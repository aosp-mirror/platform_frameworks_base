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

import static android.companion.BluetoothDeviceFilterUtils.patternFromString;
import static android.companion.BluetoothDeviceFilterUtils.patternToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanFilter;
import android.os.Parcel;
import android.provider.OneTimeUseBuilder;

import com.android.internal.util.ObjectUtils;

import java.util.regex.Pattern;

/**
 * A filter for Bluetooth LE devices
 *
 * @see ScanFilter
 */
public final class BluetoothLEDeviceFilter implements DeviceFilter<BluetoothDevice> {

    private static BluetoothLEDeviceFilter NO_OP;

    private final Pattern mNamePattern;
    private final ScanFilter mScanFilter;

    private BluetoothLEDeviceFilter(Pattern namePattern, ScanFilter scanFilter) {
        mNamePattern = namePattern;
        mScanFilter = ObjectUtils.firstNotNull(scanFilter, ScanFilter.EMPTY);
    }

    @SuppressLint("ParcelClassLoader")
    private BluetoothLEDeviceFilter(Parcel in) {
        this(
            patternFromString(in.readString()),
            in.readParcelable(null));
    }

    /** @hide */
    @NonNull
    public static BluetoothLEDeviceFilter nullsafe(@Nullable BluetoothLEDeviceFilter nullable) {
        return nullable != null ? nullable : noOp();
    }

    /** @hide */
    @NonNull
    public static BluetoothLEDeviceFilter noOp() {
        if (NO_OP == null) NO_OP = new Builder().build();
        return NO_OP;
    }

    /** @hide */
    @Nullable
    public Pattern getNamePattern() {
        return mNamePattern;
    }

    /** @hide */
    @NonNull
    public ScanFilter getScanFilter() {
        return mScanFilter;
    }

    /** @hide */
    @Override
    public boolean matches(BluetoothDevice device) {
        return BluetoothDeviceFilterUtils.matches(getScanFilter(), device)
                && BluetoothDeviceFilterUtils.matchesName(getNamePattern(), device);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(patternToString(getNamePattern()));
        dest.writeParcelable(mScanFilter, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<BluetoothLEDeviceFilter> CREATOR
            = new Creator<BluetoothLEDeviceFilter>() {
        @Override
        public BluetoothLEDeviceFilter createFromParcel(Parcel in) {
            return new BluetoothLEDeviceFilter(in);
        }

        @Override
        public BluetoothLEDeviceFilter[] newArray(int size) {
            return new BluetoothLEDeviceFilter[size];
        }
    };

    /**
     * Builder for {@link BluetoothLEDeviceFilter}
     */
    public static final class Builder extends OneTimeUseBuilder<BluetoothLEDeviceFilter> {
        private ScanFilter mScanFilter;
        private Pattern mNamePattern;

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
         * @param scanFilter a {@link ScanFilter} to filter devices by
         *
         * @see ScanFilter for specific details on its various fields
         */
        @NonNull
        public Builder setScanFilter(@Nullable ScanFilter scanFilter) {
            checkNotUsed();
            mScanFilter = scanFilter;
            return this;
        }

        /** @inheritDoc */
        @Override
        @NonNull
        public BluetoothLEDeviceFilter build() {
            markUsed();
            return new BluetoothLEDeviceFilter(mNamePattern, mScanFilter);
        }
    }
}
