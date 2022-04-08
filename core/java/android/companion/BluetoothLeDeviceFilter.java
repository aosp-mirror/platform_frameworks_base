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
import static com.android.internal.util.Preconditions.checkState;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.provider.OneTimeUseBuilder;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.BitUtils;
import com.android.internal.util.ObjectUtils;

import libcore.util.HexEncoding;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A filter for Bluetooth LE devices
 *
 * @see ScanFilter
 */
public final class BluetoothLeDeviceFilter implements DeviceFilter<ScanResult> {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "BluetoothLeDeviceFilter";

    private static final int RENAME_PREFIX_LENGTH_LIMIT = 10;

    private final Pattern mNamePattern;
    private final ScanFilter mScanFilter;
    private final byte[] mRawDataFilter;
    private final byte[] mRawDataFilterMask;
    private final String mRenamePrefix;
    private final String mRenameSuffix;
    private final int mRenameBytesFrom;
    private final int mRenameBytesLength;
    private final int mRenameNameFrom;
    private final int mRenameNameLength;
    private final boolean mRenameBytesReverseOrder;

    private BluetoothLeDeviceFilter(Pattern namePattern, ScanFilter scanFilter,
            byte[] rawDataFilter, byte[] rawDataFilterMask, String renamePrefix,
            String renameSuffix, int renameBytesFrom, int renameBytesLength,
            int renameNameFrom, int renameNameLength, boolean renameBytesReverseOrder) {
        mNamePattern = namePattern;
        mScanFilter = ObjectUtils.firstNotNull(scanFilter, ScanFilter.EMPTY);
        mRawDataFilter = rawDataFilter;
        mRawDataFilterMask = rawDataFilterMask;
        mRenamePrefix = renamePrefix;
        mRenameSuffix = renameSuffix;
        mRenameBytesFrom = renameBytesFrom;
        mRenameBytesLength = renameBytesLength;
        mRenameNameFrom = renameNameFrom;
        mRenameNameLength = renameNameLength;
        mRenameBytesReverseOrder = renameBytesReverseOrder;
    }

    /** @hide */
    @Nullable
    public Pattern getNamePattern() {
        return mNamePattern;
    }

    /** @hide */
    @NonNull
    @UnsupportedAppUsage
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
    public int getRenameBytesLength() {
        return mRenameBytesLength;
    }

    /** @hide */
    public boolean isRenameBytesReverseOrder() {
        return mRenameBytesReverseOrder;
    }

    /** @hide */
    @Override
    @Nullable
    public String getDeviceDisplayName(ScanResult sr) {
        if (mRenameBytesFrom < 0 && mRenameNameFrom < 0) {
            return getDeviceDisplayNameInternal(sr.getDevice());
        }
        final StringBuilder sb = new StringBuilder(TextUtils.emptyIfNull(mRenamePrefix));
        if (mRenameBytesFrom >= 0) {
            final byte[] bytes = sr.getScanRecord().getBytes();
            int startInclusive = mRenameBytesFrom;
            int endInclusive = mRenameBytesFrom + mRenameBytesLength -1;
            int initial = mRenameBytesReverseOrder ? endInclusive : startInclusive;
            int step = mRenameBytesReverseOrder ? -1 : 1;
            for (int i = initial; startInclusive <= i && i <= endInclusive; i += step) {
                sb.append(HexEncoding.encodeToString(bytes[i], true));
            }
        } else {
            sb.append(
                    getDeviceDisplayNameInternal(sr.getDevice())
                            .substring(mRenameNameFrom, mRenameNameFrom + mRenameNameLength));
        }
        return sb.append(TextUtils.emptyIfNull(mRenameSuffix)).toString();
    }

    /** @hide */
    @Override
    public boolean matches(ScanResult scanResult) {
        BluetoothDevice device = scanResult.getDevice();
        boolean result = getScanFilter().matches(scanResult)
                && BluetoothDeviceFilterUtils.matchesName(getNamePattern(), device)
                && (mRawDataFilter == null
                    || BitUtils.maskedEquals(scanResult.getScanRecord().getBytes(),
                            mRawDataFilter, mRawDataFilterMask));
        if (DEBUG) Log.i(LOG_TAG, "matches(this = " + this + ", device = " + device +
                ") -> " + result);
        return result;
    }

    /** @hide */
    @Override
    public int getMediumType() {
        return DeviceFilter.MEDIUM_TYPE_BLUETOOTH_LE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BluetoothLeDeviceFilter that = (BluetoothLeDeviceFilter) o;
        return mRenameBytesFrom == that.mRenameBytesFrom &&
                mRenameBytesLength == that.mRenameBytesLength &&
                mRenameNameFrom == that.mRenameNameFrom &&
                mRenameNameLength == that.mRenameNameLength &&
                mRenameBytesReverseOrder == that.mRenameBytesReverseOrder &&
                Objects.equals(mNamePattern, that.mNamePattern) &&
                Objects.equals(mScanFilter, that.mScanFilter) &&
                Arrays.equals(mRawDataFilter, that.mRawDataFilter) &&
                Arrays.equals(mRawDataFilterMask, that.mRawDataFilterMask) &&
                Objects.equals(mRenamePrefix, that.mRenamePrefix) &&
                Objects.equals(mRenameSuffix, that.mRenameSuffix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNamePattern, mScanFilter, mRawDataFilter, mRawDataFilterMask,
                mRenamePrefix, mRenameSuffix, mRenameBytesFrom, mRenameBytesLength,
                mRenameNameFrom, mRenameNameLength, mRenameBytesReverseOrder);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(patternToString(getNamePattern()));
        dest.writeParcelable(mScanFilter, flags);
        dest.writeByteArray(mRawDataFilter);
        dest.writeByteArray(mRawDataFilterMask);
        dest.writeString(mRenamePrefix);
        dest.writeString(mRenameSuffix);
        dest.writeInt(mRenameBytesFrom);
        dest.writeInt(mRenameBytesLength);
        dest.writeInt(mRenameNameFrom);
        dest.writeInt(mRenameNameLength);
        dest.writeBoolean(mRenameBytesReverseOrder);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "BluetoothLEDeviceFilter{" +
                "mNamePattern=" + mNamePattern +
                ", mScanFilter=" + mScanFilter +
                ", mRawDataFilter=" + Arrays.toString(mRawDataFilter) +
                ", mRawDataFilterMask=" + Arrays.toString(mRawDataFilterMask) +
                ", mRenamePrefix='" + mRenamePrefix + '\'' +
                ", mRenameSuffix='" + mRenameSuffix + '\'' +
                ", mRenameBytesFrom=" + mRenameBytesFrom +
                ", mRenameBytesLength=" + mRenameBytesLength +
                ", mRenameNameFrom=" + mRenameNameFrom +
                ", mRenameNameLength=" + mRenameNameLength +
                ", mRenameBytesReverseOrder=" + mRenameBytesReverseOrder +
                '}';
    }

    public static final @android.annotation.NonNull Creator<BluetoothLeDeviceFilter> CREATOR
            = new Creator<BluetoothLeDeviceFilter>() {
        @Override
        public BluetoothLeDeviceFilter createFromParcel(Parcel in) {
            Builder builder = new Builder()
                    .setNamePattern(patternFromString(in.readString()))
                    .setScanFilter(in.readParcelable(null));
            byte[] rawDataFilter = in.createByteArray();
            byte[] rawDataFilterMask = in.createByteArray();
            if (rawDataFilter != null) {
                builder.setRawDataFilter(rawDataFilter, rawDataFilterMask);
            }
            String renamePrefix = in.readString();
            String suffix = in.readString();
            int bytesFrom = in.readInt();
            int bytesTo = in.readInt();
            int nameFrom = in.readInt();
            int nameTo = in.readInt();
            boolean bytesReverseOrder = in.readBoolean();
            if (renamePrefix != null) {
                if (bytesFrom >= 0) {
                    builder.setRenameFromBytes(renamePrefix, suffix, bytesFrom, bytesTo,
                            bytesReverseOrder ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                } else {
                    builder.setRenameFromName(renamePrefix, suffix, nameFrom, nameTo);
                }
            }
            return builder.build();
        }

        @Override
        public BluetoothLeDeviceFilter[] newArray(int size) {
            return new BluetoothLeDeviceFilter[size];
        }
    };

    public static int getRenamePrefixLengthLimit() {
        return RENAME_PREFIX_LENGTH_LIMIT;
    }

    /**
     * Builder for {@link BluetoothLeDeviceFilter}
     */
    public static final class Builder extends OneTimeUseBuilder<BluetoothLeDeviceFilter> {
        private ScanFilter mScanFilter;
        private Pattern mNamePattern;
        private byte[] mRawDataFilter;
        private byte[] mRawDataFilterMask;
        private String mRenamePrefix;
        private String mRenameSuffix;
        private int mRenameBytesFrom = -1;
        private int mRenameBytesLength;
        private int mRenameNameFrom = -1;
        private int mRenameNameLength;
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
                @Nullable byte[] rawDataFilterMask) {
            checkNotUsed();
            Objects.requireNonNull(rawDataFilter);
            checkArgument(rawDataFilterMask == null ||
                    rawDataFilter.length == rawDataFilterMask.length,
                    "Mask and filter should be the same length");
            mRawDataFilter = rawDataFilter;
            mRawDataFilterMask = rawDataFilterMask;
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
         * @param bytesLength the number of bytes to be displayed from the given index
         * @param byteOrder whether the given range of bytes is big endian (will be displayed
         *                   in same order) or little endian (will be flipped before displaying)
         * @return self for chaining
         */
        @NonNull
        public Builder setRenameFromBytes(@NonNull String prefix, @NonNull String suffix,
                int bytesFrom, int bytesLength, ByteOrder byteOrder) {
            checkRenameNotSet();
            checkRangeNotEmpty(bytesLength);
            mRenameBytesFrom = bytesFrom;
            mRenameBytesLength = bytesLength;
            mRenameBytesReverseOrder = byteOrder == ByteOrder.LITTLE_ENDIAN;
            return setRename(prefix, suffix);
        }

        /**
         * Rename the devices shown in the list, using specific characters from the advertised name,
         * as well as a custom prefix/suffix around them
         *
         * Note that the prefix length is limited to {@link #getRenamePrefixLengthLimit} characters
         * to ensure that there's enough space to display the byte data
         *
         * The range of name characters to be displayed cannot be empty
         *
         * @param prefix to be displayed before the byte data
         * @param suffix to be displayed after the byte data
         * @param nameFrom the start name character index to be displayed (inclusive)
         * @param nameLength the number of characters to be displayed from the given index
         * @return self for chaining
         */
        @NonNull
        public Builder setRenameFromName(@NonNull String prefix, @NonNull String suffix,
                int nameFrom, int nameLength) {
            checkRenameNotSet();
            checkRangeNotEmpty(nameLength);
            mRenameNameFrom = nameFrom;
            mRenameNameLength = nameLength;
            mRenameBytesReverseOrder = false;
            return setRename(prefix, suffix);
        }

        private void checkRenameNotSet() {
            checkState(mRenamePrefix == null, "Renaming rule can only be set once");
        }

        private void checkRangeNotEmpty(int length) {
            checkArgument(length > 0, "Range must be non-empty");
        }

        @NonNull
        private Builder setRename(@NonNull String prefix, @NonNull String suffix) {
            checkNotUsed();
            checkArgument(TextUtils.length(prefix) <= getRenamePrefixLengthLimit(),
                    "Prefix is too long");
            mRenamePrefix = prefix;
            mRenameSuffix = suffix;
            return this;
        }

        /** @inheritDoc */
        @Override
        @NonNull
        public BluetoothLeDeviceFilter build() {
            markUsed();
            return new BluetoothLeDeviceFilter(mNamePattern, mScanFilter,
                    mRawDataFilter, mRawDataFilterMask,
                    mRenamePrefix, mRenameSuffix,
                    mRenameBytesFrom, mRenameBytesLength,
                    mRenameNameFrom, mRenameNameLength,
                    mRenameBytesReverseOrder);
        }
    }
}
