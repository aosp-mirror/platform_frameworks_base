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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanFilter;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Parcelling;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A filter for Wifi devices
 *
 * @see ScanFilter
 */
public final class WifiDeviceFilter implements DeviceFilter<ScanResult> {

    /**
     * If set, only devices with {@link BluetoothDevice#getName name} matching the given regular
     * expression will be shown
     */
    @Nullable
    private final Pattern mNamePattern;

    /**
     * If set, only devices with BSSID matching the given one will be shown
     */
    @Nullable
    private final MacAddress mBssid;

    /**
     * If set, only bits at positions set in this mask, will be compared to the given
     * {@link Builder#setBssid BSSID} filter.
     */
    @NonNull
    private final MacAddress mBssidMask;

    /** @hide */
    @Override
    public boolean matches(ScanResult device) {
        return BluetoothDeviceFilterUtils.matchesName(getNamePattern(), device)
                && (mBssid == null
                        || MacAddress.fromString(device.BSSID).matches(mBssid, mBssidMask));
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

    /* package-private */ WifiDeviceFilter(
            @Nullable Pattern namePattern,
            @Nullable MacAddress bssid,
            @NonNull MacAddress bssidMask) {
        this.mNamePattern = namePattern;
        this.mBssid = bssid;
        this.mBssidMask = bssidMask;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mBssidMask);
    }

    /**
     * If set, only devices with {@link BluetoothDevice#getName name} matching the given regular
     * expression will be shown
     *
     * @hide
     */
    @Nullable
    public Pattern getNamePattern() {
        return mNamePattern;
    }

    /**
     * If set, only devices with BSSID matching the given one will be shown
     *
     * @hide
     */
    @Nullable
    public MacAddress getBssid() {
        return mBssid;
    }

    /**
     * If set, only bits at positions set in this mask, will be compared to the given
     * {@link Builder#setBssid BSSID} filter.
     *
     * @hide
     */
    @NonNull
    public MacAddress getBssidMask() {
        return mBssidMask;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WifiDeviceFilter that = (WifiDeviceFilter) o;
        return Objects.equals(mNamePattern, that.mNamePattern)
                && Objects.equals(mBssid, that.mBssid)
                && Objects.equals(mBssidMask, that.mBssidMask);
    }

    @Override
    public int hashCode() {
        int _hash = 1;
        _hash = 31 * _hash + Objects.hashCode(mNamePattern);
        _hash = 31 * _hash + Objects.hashCode(mBssid);
        _hash = 31 * _hash + Objects.hashCode(mBssidMask);
        return _hash;
    }

    static Parcelling<Pattern> sParcellingForNamePattern =
            Parcelling.Cache.get(Parcelling.BuiltIn.ForPattern.class);
    static {
        if (sParcellingForNamePattern == null) {
            sParcellingForNamePattern = Parcelling.Cache.put(
                    new Parcelling.BuiltIn.ForPattern());
        }
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        byte flg = 0;
        if (mNamePattern != null) flg |= 0x1;
        if (mBssid != null) flg |= 0x2;
        dest.writeByte(flg);
        sParcellingForNamePattern.parcel(mNamePattern, dest, flags);
        if (mBssid != null) dest.writeTypedObject(mBssid, flags);
        dest.writeTypedObject(mBssidMask, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    /* package-private */ WifiDeviceFilter(@NonNull Parcel in) {
        byte flg = in.readByte();
        Pattern namePattern = sParcellingForNamePattern.unparcel(in);
        MacAddress bssid = (flg & 0x2) == 0 ? null : in.readTypedObject(MacAddress.CREATOR);
        MacAddress bssidMask = in.readTypedObject(MacAddress.CREATOR);

        this.mNamePattern = namePattern;
        this.mBssid = bssid;
        this.mBssidMask = bssidMask;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mBssidMask);
    }

    @NonNull
    public static final Parcelable.Creator<WifiDeviceFilter> CREATOR =
            new Parcelable.Creator<WifiDeviceFilter>() {
        @Override
        public WifiDeviceFilter[] newArray(int size) {
            return new WifiDeviceFilter[size];
        }

        @Override
        public WifiDeviceFilter createFromParcel(@NonNull Parcel in) {
            return new WifiDeviceFilter(in);
        }
    };

    /**
     * A builder for {@link WifiDeviceFilter}
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {

        @Nullable private Pattern mNamePattern;
        @Nullable private MacAddress mBssid;
        @NonNull private MacAddress mBssidMask;

        private long mBuilderFieldsSet = 0L;

        public Builder() {
        }

        /**
         * If set, only devices with {@link BluetoothDevice#getName name} matching the given regular
         * expression will be shown
         */
        @NonNull
        public Builder setNamePattern(@Nullable Pattern value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mNamePattern = value;
            return this;
        }

        /**
         * If set, only devices with BSSID matching the given one will be shown
         */
        @NonNull
        public Builder setBssid(@NonNull MacAddress value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mBssid = value;
            return this;
        }

        /**
         * If set, only bits at positions set in this mask, will be compared to the given
         * {@link Builder#setBssid BSSID} filter.
         */
        @NonNull
        public Builder setBssidMask(@NonNull MacAddress value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mBssidMask = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        @NonNull
        public WifiDeviceFilter build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                mNamePattern = null;
            }
            if ((mBuilderFieldsSet & 0x2) == 0) {
                mBssid = null;
            }
            if ((mBuilderFieldsSet & 0x4) == 0) {
                mBssidMask = MacAddress.BROADCAST_ADDRESS;
            }
            return new WifiDeviceFilter(
                    mNamePattern,
                    mBssid,
                    mBssidMask);
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x8) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
