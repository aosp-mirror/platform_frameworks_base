/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth.le;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Settings for Bluetooth LE scan.
 */
public final class ScanSettings implements Parcelable {
    /**
     * Perform Bluetooth LE scan in low power mode. This is the default scan mode as it consumes the
     * least power.
     */
    public static final int SCAN_MODE_LOW_POWER = 0;
    /**
     * Perform Bluetooth LE scan in balanced power mode.
     */
    public static final int SCAN_MODE_BALANCED = 1;
    /**
     * Scan using highest duty cycle. It's recommended only using this mode when the application is
     * running in foreground.
     */
    public static final int SCAN_MODE_LOW_LATENCY = 2;

    /**
     * Callback each time when a bluetooth advertisement is found.
     */
    public static final int CALLBACK_TYPE_ON_UPDATE = 0;
    /**
     * Callback when a bluetooth advertisement is found for the first time.
     *
     * @hide
     */
    public static final int CALLBACK_TYPE_ON_FOUND = 1;
    /**
     * Callback when a bluetooth advertisement is found for the first time, then lost.
     *
     * @hide
     */
    public static final int CALLBACK_TYPE_ON_LOST = 2;

    /**
     * Full scan result which contains device mac address, rssi, advertising and scan response and
     * scan timestamp.
     */
    public static final int SCAN_RESULT_TYPE_FULL = 0;
    /**
     * Truncated scan result which contains device mac address, rssi and scan timestamp. Note it's
     * possible for an app to get more scan results that it asks if there are multiple apps using
     * this type. TODO: decide whether we could unhide this setting.
     *
     * @hide
     */
    public static final int SCAN_RESULT_TYPE_TRUNCATED = 1;

    // Bluetooth LE scan mode.
    private int mScanMode;

    // Bluetooth LE scan callback type
    private int mCallbackType;

    // Bluetooth LE scan result type
    private int mScanResultType;

    // Time of delay for reporting the scan result
    private long mReportDelayNanos;

    public int getScanMode() {
        return mScanMode;
    }

    public int getCallbackType() {
        return mCallbackType;
    }

    public int getScanResultType() {
        return mScanResultType;
    }

    /**
     * Returns report delay timestamp based on the device clock.
     */
    public long getReportDelayNanos() {
        return mReportDelayNanos;
    }

    private ScanSettings(int scanMode, int callbackType, int scanResultType,
            long reportDelayNanos) {
        mScanMode = scanMode;
        mCallbackType = callbackType;
        mScanResultType = scanResultType;
        mReportDelayNanos = reportDelayNanos;
    }

    private ScanSettings(Parcel in) {
        mScanMode = in.readInt();
        mCallbackType = in.readInt();
        mScanResultType = in.readInt();
        mReportDelayNanos = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mScanMode);
        dest.writeInt(mCallbackType);
        dest.writeInt(mScanResultType);
        dest.writeLong(mReportDelayNanos);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<ScanSettings>
            CREATOR = new Creator<ScanSettings>() {
                    @Override
                public ScanSettings[] newArray(int size) {
                    return new ScanSettings[size];
                }

                    @Override
                public ScanSettings createFromParcel(Parcel in) {
                    return new ScanSettings(in);
                }
            };

    /**
     * Builder for {@link ScanSettings}.
     */
    public static final class Builder {
        private int mScanMode = SCAN_MODE_LOW_POWER;
        private int mCallbackType = CALLBACK_TYPE_ON_UPDATE;
        private int mScanResultType = SCAN_RESULT_TYPE_FULL;
        private long mReportDelayNanos = 0;

        /**
         * Set scan mode for Bluetooth LE scan.
         *
         * @param scanMode The scan mode can be one of
         *            {@link ScanSettings#SCAN_MODE_LOW_POWER},
         *            {@link ScanSettings#SCAN_MODE_BALANCED} or
         *            {@link ScanSettings#SCAN_MODE_LOW_LATENCY}.
         * @throws IllegalArgumentException If the {@code scanMode} is invalid.
         */
        public Builder setScanMode(int scanMode) {
            if (scanMode < SCAN_MODE_LOW_POWER || scanMode > SCAN_MODE_LOW_LATENCY) {
                throw new IllegalArgumentException("invalid scan mode " + scanMode);
            }
            mScanMode = scanMode;
            return this;
        }

        /**
         * Set callback type for Bluetooth LE scan.
         *
         * @param callbackType The callback type for the scan. Can only be
         *            {@link ScanSettings#CALLBACK_TYPE_ON_UPDATE}.
         * @throws IllegalArgumentException If the {@code callbackType} is invalid.
         */
        public Builder setCallbackType(int callbackType) {
            if (callbackType < CALLBACK_TYPE_ON_UPDATE
                    || callbackType > CALLBACK_TYPE_ON_LOST) {
                throw new IllegalArgumentException("invalid callback type - " + callbackType);
            }
            mCallbackType = callbackType;
            return this;
        }

        /**
         * Set scan result type for Bluetooth LE scan.
         *
         * @param scanResultType Type for scan result, could be either
         *            {@link ScanSettings#SCAN_RESULT_TYPE_FULL} or
         *            {@link ScanSettings#SCAN_RESULT_TYPE_TRUNCATED}.
         * @throws IllegalArgumentException If the {@code scanResultType} is invalid.
         * @hide
         */
        public Builder setScanResultType(int scanResultType) {
            if (scanResultType < SCAN_RESULT_TYPE_FULL
                    || scanResultType > SCAN_RESULT_TYPE_TRUNCATED) {
                throw new IllegalArgumentException(
                        "invalid scanResultType - " + scanResultType);
            }
            mScanResultType = scanResultType;
            return this;
        }

        /**
         * Set report delay timestamp for Bluetooth LE scan.
         */
        public Builder setReportDelayNanos(long reportDelayNanos) {
            mReportDelayNanos = reportDelayNanos;
            return this;
        }

        /**
         * Build {@link ScanSettings}.
         */
        public ScanSettings build() {
            return new ScanSettings(mScanMode, mCallbackType, mScanResultType,
                    mReportDelayNanos);
        }
    }
}
