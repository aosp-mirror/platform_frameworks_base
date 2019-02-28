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
import static android.companion.BluetoothDeviceFilterUtils.patternFromString;
import static android.companion.BluetoothDeviceFilterUtils.patternToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanFilter;
import android.net.wifi.ScanResult;
import android.os.Parcel;
import android.provider.OneTimeUseBuilder;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A filter for Wifi devices
 *
 * @see ScanFilter
 */
public final class WifiDeviceFilter implements DeviceFilter<ScanResult> {

    private final Pattern mNamePattern;

    private WifiDeviceFilter(Pattern namePattern) {
        mNamePattern = namePattern;
    }

    @SuppressLint("ParcelClassLoader")
    private WifiDeviceFilter(Parcel in) {
        this(patternFromString(in.readString()));
    }

    /** @hide */
    @Nullable
    public Pattern getNamePattern() {
        return mNamePattern;
    }


    /** @hide */
    @Override
    public boolean matches(ScanResult device) {
        return BluetoothDeviceFilterUtils.matchesName(getNamePattern(), device);
    }

    /** @hide */
    @Override
    public String getDeviceDisplayName(ScanResult device) {
        return getDeviceDisplayNameInternal(device);
    }

    /** @hide */
    @Override
    public int getMediumType() {
        return MEDIUM_TYPE_WIFI;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WifiDeviceFilter that = (WifiDeviceFilter) o;
        return Objects.equals(mNamePattern, that.mNamePattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNamePattern);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(patternToString(getNamePattern()));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Creator<WifiDeviceFilter> CREATOR
            = new Creator<WifiDeviceFilter>() {
        @Override
        public WifiDeviceFilter createFromParcel(Parcel in) {
            return new WifiDeviceFilter(in);
        }

        @Override
        public WifiDeviceFilter[] newArray(int size) {
            return new WifiDeviceFilter[size];
        }
    };

    /**
     * Builder for {@link WifiDeviceFilter}
     */
    public static final class Builder extends OneTimeUseBuilder<WifiDeviceFilter> {
        private Pattern mNamePattern;

        /**
         * @param regex if set, only devices with {@link BluetoothDevice#getName name} matching the
         *              given regular expression will be shown
         * @return self for chaining
         */
        public Builder setNamePattern(@Nullable Pattern regex) {
            checkNotUsed();
            mNamePattern = regex;
            return this;
        }

        /** @inheritDoc */
        @Override
        @NonNull
        public WifiDeviceFilter build() {
            markUsed();
            return new WifiDeviceFilter(mNamePattern);
        }
    }
}
