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

import android.annotation.SystemApi;
import android.bluetooth.BluetoothDevice;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Bluetooth LE scan settings are passed to {@link BluetoothLeScanner#startScan} to define the
 * parameters for the scan.
 */
public final class ScanSettings implements Parcelable {

    /**
     * A special Bluetooth LE scan mode. Applications using this scan mode will passively listen for
     * other scan results without starting BLE scans themselves.
     */
    public static final int SCAN_MODE_OPPORTUNISTIC = -1;

    /**
     * Perform Bluetooth LE scan in low power mode. This is the default scan mode as it consumes the
     * least power. This mode is enforced if the scanning application is not in foreground.
     */
    public static final int SCAN_MODE_LOW_POWER = 0;

    /**
     * Perform Bluetooth LE scan in balanced power mode. Scan results are returned at a rate that
     * provides a good trade-off between scan frequency and power consumption.
     */
    public static final int SCAN_MODE_BALANCED = 1;

    /**
     * Scan using highest duty cycle. It's recommended to only use this mode when the application is
     * running in the foreground.
     */
    public static final int SCAN_MODE_LOW_LATENCY = 2;

    /**
     * Perform Bluetooth LE scan in ambient discovery mode. This mode has lower duty cycle and more
     * aggressive scan interval than balanced mode that provides a good trade-off between scan
     * latency and power consumption.
     *
     * @hide
     */
    @SystemApi
    public static final int SCAN_MODE_AMBIENT_DISCOVERY = 3;

    /**
     * Trigger a callback for every Bluetooth advertisement found that matches the filter criteria.
     * If no filter is active, all advertisement packets are reported.
     */
    public static final int CALLBACK_TYPE_ALL_MATCHES = 1;

    /**
     * A result callback is only triggered for the first advertisement packet received that matches
     * the filter criteria.
     */
    public static final int CALLBACK_TYPE_FIRST_MATCH = 2;

    /**
     * Receive a callback when advertisements are no longer received from a device that has been
     * previously reported by a first match callback.
     */
    public static final int CALLBACK_TYPE_MATCH_LOST = 4;


    /**
     * Determines how many advertisements to match per filter, as this is scarce hw resource
     */
    /**
     * Match one advertisement per filter
     */
    public static final int MATCH_NUM_ONE_ADVERTISEMENT = 1;

    /**
     * Match few advertisement per filter, depends on current capability and availibility of
     * the resources in hw
     */
    public static final int MATCH_NUM_FEW_ADVERTISEMENT = 2;

    /**
     * Match as many advertisement per filter as hw could allow, depends on current
     * capability and availibility of the resources in hw
     */
    public static final int MATCH_NUM_MAX_ADVERTISEMENT = 3;


    /**
     * In Aggressive mode, hw will determine a match sooner even with feeble signal strength
     * and few number of sightings/match in a duration.
     */
    public static final int MATCH_MODE_AGGRESSIVE = 1;

    /**
     * For sticky mode, higher threshold of signal strength and sightings is required
     * before reporting by hw
     */
    public static final int MATCH_MODE_STICKY = 2;

    /**
     * Request full scan results which contain the device, rssi, advertising data, scan response
     * as well as the scan timestamp.
     *
     * @hide
     */
    @SystemApi
    public static final int SCAN_RESULT_TYPE_FULL = 0;

    /**
     * Request abbreviated scan results which contain the device, rssi and scan timestamp.
     * <p>
     * <b>Note:</b> It is possible for an application to get more scan results than it asked for, if
     * there are multiple apps using this type.
     *
     * @hide
     */
    @SystemApi
    public static final int SCAN_RESULT_TYPE_ABBREVIATED = 1;

    /**
     * Use all supported PHYs for scanning.
     * This will check the controller capabilities, and start
     * the scan on 1Mbit and LE Coded PHYs if supported, or on
     * the 1Mbit PHY only.
     */
    public static final int PHY_LE_ALL_SUPPORTED = 255;

    // Bluetooth LE scan mode.
    private int mScanMode;

    // Bluetooth LE scan callback type
    private int mCallbackType;

    // Bluetooth LE scan result type
    private int mScanResultType;

    // Time of delay for reporting the scan result
    private long mReportDelayMillis;

    private int mMatchMode;

    private int mNumOfMatchesPerFilter;

    // Include only legacy advertising results
    private boolean mLegacy;

    private int mPhy;

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
     * @hide
     */
    public int getMatchMode() {
        return mMatchMode;
    }

    /**
     * @hide
     */
    public int getNumOfMatches() {
        return mNumOfMatchesPerFilter;
    }

    /**
     * Returns whether only legacy advertisements will be returned.
     * Legacy advertisements include advertisements as specified
     * by the Bluetooth core specification 4.2 and below.
     */
    public boolean getLegacy() {
        return mLegacy;
    }

    /**
     * Returns the physical layer used during a scan.
     */
    public int getPhy() {
        return mPhy;
    }

    /**
     * Returns report delay timestamp based on the device clock.
     */
    public long getReportDelayMillis() {
        return mReportDelayMillis;
    }

    private ScanSettings(int scanMode, int callbackType, int scanResultType,
            long reportDelayMillis, int matchMode,
            int numOfMatchesPerFilter, boolean legacy, int phy) {
        mScanMode = scanMode;
        mCallbackType = callbackType;
        mScanResultType = scanResultType;
        mReportDelayMillis = reportDelayMillis;
        mNumOfMatchesPerFilter = numOfMatchesPerFilter;
        mMatchMode = matchMode;
        mLegacy = legacy;
        mPhy = phy;
    }

    private ScanSettings(Parcel in) {
        mScanMode = in.readInt();
        mCallbackType = in.readInt();
        mScanResultType = in.readInt();
        mReportDelayMillis = in.readLong();
        mMatchMode = in.readInt();
        mNumOfMatchesPerFilter = in.readInt();
        mLegacy = in.readInt() != 0;
        mPhy = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mScanMode);
        dest.writeInt(mCallbackType);
        dest.writeInt(mScanResultType);
        dest.writeLong(mReportDelayMillis);
        dest.writeInt(mMatchMode);
        dest.writeInt(mNumOfMatchesPerFilter);
        dest.writeInt(mLegacy ? 1 : 0);
        dest.writeInt(mPhy);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ScanSettings> CREATOR =
            new Creator<ScanSettings>() {
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
        private int mCallbackType = CALLBACK_TYPE_ALL_MATCHES;
        private int mScanResultType = SCAN_RESULT_TYPE_FULL;
        private long mReportDelayMillis = 0;
        private int mMatchMode = MATCH_MODE_AGGRESSIVE;
        private int mNumOfMatchesPerFilter = MATCH_NUM_MAX_ADVERTISEMENT;
        private boolean mLegacy = true;
        private int mPhy = PHY_LE_ALL_SUPPORTED;

        /**
         * Set scan mode for Bluetooth LE scan.
         *
         * @param scanMode The scan mode can be one of {@link ScanSettings#SCAN_MODE_LOW_POWER},
         * {@link ScanSettings#SCAN_MODE_BALANCED} or {@link ScanSettings#SCAN_MODE_LOW_LATENCY}.
         * @throws IllegalArgumentException If the {@code scanMode} is invalid.
         */
        public Builder setScanMode(int scanMode) {
            switch (scanMode) {
                case SCAN_MODE_OPPORTUNISTIC:
                case SCAN_MODE_LOW_POWER:
                case SCAN_MODE_BALANCED:
                case SCAN_MODE_LOW_LATENCY:
                case SCAN_MODE_AMBIENT_DISCOVERY:
                    mScanMode = scanMode;
                    break;
                default:
                    throw new IllegalArgumentException("invalid scan mode " + scanMode);
            }
            return this;
        }

        /**
         * Set callback type for Bluetooth LE scan.
         *
         * @param callbackType The callback type flags for the scan.
         * @throws IllegalArgumentException If the {@code callbackType} is invalid.
         */
        public Builder setCallbackType(int callbackType) {

            if (!isValidCallbackType(callbackType)) {
                throw new IllegalArgumentException("invalid callback type - " + callbackType);
            }
            mCallbackType = callbackType;
            return this;
        }

        // Returns true if the callbackType is valid.
        private boolean isValidCallbackType(int callbackType) {
            if (callbackType == CALLBACK_TYPE_ALL_MATCHES
                    || callbackType == CALLBACK_TYPE_FIRST_MATCH
                    || callbackType == CALLBACK_TYPE_MATCH_LOST) {
                return true;
            }
            return callbackType == (CALLBACK_TYPE_FIRST_MATCH | CALLBACK_TYPE_MATCH_LOST);
        }

        /**
         * Set scan result type for Bluetooth LE scan.
         *
         * @param scanResultType Type for scan result, could be either {@link
         * ScanSettings#SCAN_RESULT_TYPE_FULL} or {@link ScanSettings#SCAN_RESULT_TYPE_ABBREVIATED}.
         * @throws IllegalArgumentException If the {@code scanResultType} is invalid.
         * @hide
         */
        @SystemApi
        public Builder setScanResultType(int scanResultType) {
            if (scanResultType < SCAN_RESULT_TYPE_FULL
                    || scanResultType > SCAN_RESULT_TYPE_ABBREVIATED) {
                throw new IllegalArgumentException(
                        "invalid scanResultType - " + scanResultType);
            }
            mScanResultType = scanResultType;
            return this;
        }

        /**
         * Set report delay timestamp for Bluetooth LE scan.
         *
         * @param reportDelayMillis Delay of report in milliseconds. Set to 0 to be notified of
         * results immediately. Values &gt; 0 causes the scan results to be queued up and delivered
         * after the requested delay or when the internal buffers fill up.
         * @throws IllegalArgumentException If {@code reportDelayMillis} &lt; 0.
         */
        public Builder setReportDelay(long reportDelayMillis) {
            if (reportDelayMillis < 0) {
                throw new IllegalArgumentException("reportDelay must be > 0");
            }
            mReportDelayMillis = reportDelayMillis;
            return this;
        }

        /**
         * Set the number of matches for Bluetooth LE scan filters hardware match
         *
         * @param numOfMatches The num of matches can be one of
         * {@link ScanSettings#MATCH_NUM_ONE_ADVERTISEMENT}
         * or {@link ScanSettings#MATCH_NUM_FEW_ADVERTISEMENT} or {@link
         * ScanSettings#MATCH_NUM_MAX_ADVERTISEMENT}
         * @throws IllegalArgumentException If the {@code matchMode} is invalid.
         */
        public Builder setNumOfMatches(int numOfMatches) {
            if (numOfMatches < MATCH_NUM_ONE_ADVERTISEMENT
                    || numOfMatches > MATCH_NUM_MAX_ADVERTISEMENT) {
                throw new IllegalArgumentException("invalid numOfMatches " + numOfMatches);
            }
            mNumOfMatchesPerFilter = numOfMatches;
            return this;
        }

        /**
         * Set match mode for Bluetooth LE scan filters hardware match
         *
         * @param matchMode The match mode can be one of {@link ScanSettings#MATCH_MODE_AGGRESSIVE}
         * or {@link ScanSettings#MATCH_MODE_STICKY}
         * @throws IllegalArgumentException If the {@code matchMode} is invalid.
         */
        public Builder setMatchMode(int matchMode) {
            if (matchMode < MATCH_MODE_AGGRESSIVE
                    || matchMode > MATCH_MODE_STICKY) {
                throw new IllegalArgumentException("invalid matchMode " + matchMode);
            }
            mMatchMode = matchMode;
            return this;
        }

        /**
         * Set whether only legacy advertisments should be returned in scan results.
         * Legacy advertisements include advertisements as specified by the
         * Bluetooth core specification 4.2 and below. This is true by default
         * for compatibility with older apps.
         *
         * @param legacy true if only legacy advertisements will be returned
         */
        public Builder setLegacy(boolean legacy) {
            mLegacy = legacy;
            return this;
        }

        /**
         * Set the Physical Layer to use during this scan.
         * This is used only if {@link ScanSettings.Builder#setLegacy}
         * is set to false.
         * {@link android.bluetooth.BluetoothAdapter#isLeCodedPhySupported}
         * may be used to check whether LE Coded phy is supported by calling
         * {@link android.bluetooth.BluetoothAdapter#isLeCodedPhySupported}.
         * Selecting an unsupported phy will result in failure to start scan.
         *
         * @param phy Can be one of {@link BluetoothDevice#PHY_LE_1M}, {@link
         * BluetoothDevice#PHY_LE_CODED} or {@link ScanSettings#PHY_LE_ALL_SUPPORTED}
         */
        public Builder setPhy(int phy) {
            mPhy = phy;
            return this;
        }

        /**
         * Build {@link ScanSettings}.
         */
        public ScanSettings build() {
            return new ScanSettings(mScanMode, mCallbackType, mScanResultType,
                    mReportDelayMillis, mMatchMode,
                    mNumOfMatchesPerFilter, mLegacy, mPhy);
        }
    }
}
