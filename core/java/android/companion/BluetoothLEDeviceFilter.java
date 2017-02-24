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

import static com.android.internal.util.Preconditions.checkArgument;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.Parcel;
import android.provider.OneTimeUseBuilder;
import android.text.TextUtils;

import com.android.internal.util.BitUtils;
import com.android.internal.util.ObjectUtils;
import com.android.internal.util.Preconditions;

import java.util.regex.Pattern;

/**
 * A filter for Bluetooth LE devices
 *
 * @see ScanFilter
 */
public final class BluetoothLEDeviceFilter implements DeviceFilter<ScanResult> {

    private static final int RENAME_PREFIX_LENGTH_LIMIT = 10;

    private final Pattern mNamePattern;
    private final ScanFilter mScanFilter;
    private final byte[] mRawDataFilter;
    private final byte[] mRawDataFilterMask;
    private final String mRenamePrefix;
    private final String mRenameSuffix;
    private final int mRenameBytesFrom;
    private final int mRenameBytesTo;
    private final boolean mRenameBytesReverseOrder;

    private BluetoothLEDeviceFilter(Pattern namePattern, ScanFilter scanFilter,
            byte[] rawDataFilter, byte[] rawDataFilterMask, String renamePrefix,
            String renameSuffix, int renameBytesFrom, int renameBytesTo,
            boolean renameBytesReverseOrder) {
        mNamePattern = namePattern;
        mScanFilter = ObjectUtils.firstNotNull(scanFilter, ScanFilter.EMPTY);
        mRawDataFilter = rawDataFilter;
        mRawDataFilterMask = rawDataFilterMask;
        mRenamePrefix = renamePrefix;
        mRenameSuffix = renameSuffix;
        mRenameBytesFrom = renameBytesFrom;
        mRenameBytesTo = renameBytesTo;
        mRenameBytesReverseOrder = renameBytesReverseOrder;
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
    @Nullable
    public byte[] getRawDataFilter() {
        return mRawDataFilter;
    }

    /** @hide */
    @Nullable
    public byte[] getRawDataFilterMask() {
        return mRawDataFilterMask;
    }

    /** @hide */
    @Nullable
    public String getRenamePrefix() {
        return mRenamePrefix;
    }

    /** @hide */
    @Nullable
    public String getRenameSuffix() {
        return mRenameSuffix;
    }

    /** @hide */
    public int getRenameBytesFrom() {
        return mRenameBytesFrom;
    }

    /** @hide */
    public int getRenameBytesTo() {
        return mRenameBytesTo;
    }

    /** @hide */
    public boolean isRenameBytesReverseOrder() {
        return mRenameBytesReverseOrder;
    }

    /** @hide */
    @Override
    @Nullable
    public String getDeviceDisplayName(ScanResult sr) {
        if (mRenameBytesFrom < 0) return getDeviceDisplayNameInternal(sr.getDevice());
        final byte[] bytes = sr.getScanRecord().getBytes();
        final StringBuilder sb = new StringBuilder(TextUtils.emptyIfNull(mRenamePrefix));
        int startInclusive = mRenameBytesFrom;
        int endInclusive = mRenameBytesTo - 1;
        int initial = mRenameBytesReverseOrder ? endInclusive : startInclusive;
        int step = mRenameBytesReverseOrder ? -1 : 1;
        for (int i = initial; startInclusive <= i && i <= endInclusive; i+=step) {
            sb.append(Byte.toHexString(bytes[i], true));
        }
        return sb.append(TextUtils.emptyIfNull(mRenameSuffix)).toString();
    }

    /** @hide */
    @Override
    public boolean matches(ScanResult device) {
        return matches(device.getDevice())
                && BitUtils.maskedEquals(device.getScanRecord().getBytes(),
                        mRawDataFilter, mRawDataFilterMask);
    }

    private boolean matches(BluetoothDevice device) {
        return BluetoothDeviceFilterUtils.matches(getScanFilter(), device)
                && BluetoothDeviceFilterUtils.matchesName(getNamePattern(), device);
    }

    /** @hide */
    @Override
    public int getMediumType() {
        return DeviceFilter.MEDIUM_TYPE_BLUETOOTH_LE;
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
            return new BluetoothLEDeviceFilter.Builder()
                    .setNamePattern(patternFromString(in.readString()))
                    .setScanFilter(in.readParcelable(null))
                    .setRawDataFilter(in.readBlob(), in.readBlob())
                    .setRename(in.readString(), in.readString(),
                            in.readInt(), in.readInt(), in.readBoolean())
                    .build();
        }

        @Override
        public BluetoothLEDeviceFilter[] newArray(int size) {
            return new BluetoothLEDeviceFilter[size];
        }
    };

    public static int getRenamePrefixLengthLimit() {
        return RENAME_PREFIX_LENGTH_LIMIT;
    }

    /**
     * Builder for {@link BluetoothLEDeviceFilter}
     */
    public static final class Builder extends OneTimeUseBuilder<BluetoothLEDeviceFilter> {
        private ScanFilter mScanFilter;
        private Pattern mNamePattern;
        private byte[] mRawDataFilter;
        private byte[] mRawDataFilterMask;
        private String mRenamePrefix;
        private String mRenameSuffix;
        private int mRenameBytesFrom = -1;
        private int mRenameBytesTo;
        private boolean mRenameBytesReverseOrder = false;

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

        /**
         * @param scanFilter a {@link ScanFilter} to filter devices by
         *
         * @return self for chaining
         * @see ScanFilter for specific details on its various fields
         */
        @NonNull
        public Builder setScanFilter(@Nullable ScanFilter scanFilter) {
            checkNotUsed();
            mScanFilter = scanFilter;
            return this;
        }

        /**
         * Filter devices by raw advertisement data, as obtained by {@link ScanRecord#getBytes}
         *
         * @param rawDataFilter bit values that have to match against advertized data
         * @param rawDataFilterMask bits that have to be matched
         * @return self for chaining
         */
        @NonNull
        public Builder setRawDataFilter(@NonNull byte[] rawDataFilter,
                @NonNull byte[] rawDataFilterMask) {
            checkNotUsed();
            checkArgument(rawDataFilter.length == rawDataFilterMask.length,
                    "Mask and filter should be the same length");
            mRawDataFilter = Preconditions.checkNotNull(rawDataFilter);
            mRawDataFilterMask = Preconditions.checkNotNull(rawDataFilterMask);
            return this;
        }

        /**
         * Rename the devices shown in the list, using specific bytes from the raw advertisement
         * data ({@link ScanRecord#getBytes}) in hexadecimal format, as well as a custom
         * prefix/suffix around them
         *
         * Note that the prefix length is limited to {@link #getRenamePrefixLengthLimit} characters
         * to ensure that there's enough space to display the byte data
         *
         * The range of bytes to be displayed cannot be empty
         *
         * @param prefix to be displayed before the byte data
         * @param suffix to be displayed after the byte data
         * @param bytesFrom the start byte index to be displayed (inclusive)
         * @param bytesTo the end byte index to be displayed (exclusive)
         * @param bytesReverseOrder if true, the byte order of the provided range will be flipped
         *                          when displaying
         * @return self for chaining
         */
        @NonNull
        public Builder setRename(@NonNull String prefix, @NonNull String suffix,
                int bytesFrom, int bytesTo, boolean bytesReverseOrder) {
            checkNotUsed();
            checkArgument(TextUtils.length(prefix) >= getRenamePrefixLengthLimit(),
                    "Prefix is too short");
            mRenamePrefix = prefix;
            mRenameSuffix = suffix;
            checkArgument(bytesFrom < bytesTo, "Byte range must be non-empty");
            mRenameBytesFrom = bytesFrom;
            mRenameBytesTo = bytesTo;
            mRenameBytesReverseOrder = bytesReverseOrder;
            return this;
        }

        /** @inheritDoc */
        @Override
        @NonNull
        public BluetoothLEDeviceFilter build() {
            markUsed();
            return new BluetoothLEDeviceFilter(mNamePattern, mScanFilter,
                    mRawDataFilter, mRawDataFilterMask,
                    mRenamePrefix, mRenameSuffix,
                    mRenameBytesFrom, mRenameBytesTo, mRenameBytesReverseOrder);
        }
    }
}
