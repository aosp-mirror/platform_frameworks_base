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
 * The {@link AdvertiseSettings} provide a way to adjust advertising preferences for each
 * Bluetooth LE advertisement instance. Use {@link AdvertiseSettings.Builder} to create an
 * instance of this class.
 */
public final class AdvertiseSettings implements Parcelable {
    /**
     * Perform Bluetooth LE advertising in low power mode. This is the default and preferred
     * advertising mode as it consumes the least power.
     */
    public static final int ADVERTISE_MODE_LOW_POWER = 0;

    /**
     * Perform Bluetooth LE advertising in balanced power mode. This is balanced between advertising
     * frequency and power consumption.
     */
    public static final int ADVERTISE_MODE_BALANCED = 1;

    /**
     * Perform Bluetooth LE advertising in low latency, high power mode. This has the highest power
     * consumption and should not be used for continuous background advertising.
     */
    public static final int ADVERTISE_MODE_LOW_LATENCY = 2;

    /**
     * Advertise using the lowest transmission (TX) power level. Low transmission power can be used
     * to restrict the visibility range of advertising packets.
     */
    public static final int ADVERTISE_TX_POWER_ULTRA_LOW = 0;

    /**
     * Advertise using low TX power level.
     */
    public static final int ADVERTISE_TX_POWER_LOW = 1;

    /**
     * Advertise using medium TX power level.
     */
    public static final int ADVERTISE_TX_POWER_MEDIUM = 2;

    /**
     * Advertise using high TX power level. This corresponds to largest visibility range of the
     * advertising packet.
     */
    public static final int ADVERTISE_TX_POWER_HIGH = 3;

    /**
     * The maximum limited advertisement duration as specified by the Bluetooth SIG
     */
    private static final int LIMITED_ADVERTISING_MAX_MILLIS = 180 * 1000;

    private final int mAdvertiseMode;
    private final int mAdvertiseTxPowerLevel;
    private final int mAdvertiseTimeoutMillis;
    private final boolean mAdvertiseConnectable;

    private AdvertiseSettings(int advertiseMode, int advertiseTxPowerLevel,
            boolean advertiseConnectable, int advertiseTimeout) {
        mAdvertiseMode = advertiseMode;
        mAdvertiseTxPowerLevel = advertiseTxPowerLevel;
        mAdvertiseConnectable = advertiseConnectable;
        mAdvertiseTimeoutMillis = advertiseTimeout;
    }

    private AdvertiseSettings(Parcel in) {
        mAdvertiseMode = in.readInt();
        mAdvertiseTxPowerLevel = in.readInt();
        mAdvertiseConnectable = in.readInt() != 0 ? true : false;
        mAdvertiseTimeoutMillis = in.readInt();
    }

    /**
     * Returns the advertise mode.
     */
    public int getMode() {
        return mAdvertiseMode;
    }

    /**
     * Returns the TX power level for advertising.
     */
    public int getTxPowerLevel() {
        return mAdvertiseTxPowerLevel;
    }

    /**
     * Returns whether the advertisement will indicate connectable.
     */
    public boolean isConnectable() {
        return mAdvertiseConnectable;
    }

    /**
     * Returns the advertising time limit in milliseconds.
     */
    public int getTimeout() {
        return mAdvertiseTimeoutMillis;
    }

    @Override
    public String toString() {
        return "Settings [mAdvertiseMode=" + mAdvertiseMode
             + ", mAdvertiseTxPowerLevel=" + mAdvertiseTxPowerLevel
             + ", mAdvertiseConnectable=" + mAdvertiseConnectable
             + ", mAdvertiseTimeoutMillis=" + mAdvertiseTimeoutMillis + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mAdvertiseMode);
        dest.writeInt(mAdvertiseTxPowerLevel);
        dest.writeInt(mAdvertiseConnectable ? 1 : 0);
        dest.writeInt(mAdvertiseTimeoutMillis);
    }

    public static final Parcelable.Creator<AdvertiseSettings> CREATOR =
            new Creator<AdvertiseSettings>() {
            @Override
                public AdvertiseSettings[] newArray(int size) {
                    return new AdvertiseSettings[size];
                }

            @Override
                public AdvertiseSettings createFromParcel(Parcel in) {
                    return new AdvertiseSettings(in);
                }
            };

    /**
     * Builder class for {@link AdvertiseSettings}.
     */
    public static final class Builder {
        private int mMode = ADVERTISE_MODE_LOW_POWER;
        private int mTxPowerLevel = ADVERTISE_TX_POWER_MEDIUM;
        private int mTimeoutMillis = 0;
        private boolean mConnectable = true;

        /**
         * Set advertise mode to control the advertising power and latency.
         *
         * @param advertiseMode Bluetooth LE Advertising mode, can only be one of
         *            {@link AdvertiseSettings#ADVERTISE_MODE_LOW_POWER},
         *            {@link AdvertiseSettings#ADVERTISE_MODE_BALANCED}, or
         *            {@link AdvertiseSettings#ADVERTISE_MODE_LOW_LATENCY}.
         * @throws IllegalArgumentException If the advertiseMode is invalid.
         */
        public Builder setAdvertiseMode(int advertiseMode) {
            if (advertiseMode < ADVERTISE_MODE_LOW_POWER
                    || advertiseMode > ADVERTISE_MODE_LOW_LATENCY) {
                throw new IllegalArgumentException("unknown mode " + advertiseMode);
            }
            mMode = advertiseMode;
            return this;
        }

        /**
         * Set advertise TX power level to control the transmission power level for the advertising.
         *
         * @param txPowerLevel Transmission power of Bluetooth LE Advertising, can only be one of
         *            {@link AdvertiseSettings#ADVERTISE_TX_POWER_ULTRA_LOW},
         *            {@link AdvertiseSettings#ADVERTISE_TX_POWER_LOW},
         *            {@link AdvertiseSettings#ADVERTISE_TX_POWER_MEDIUM} or
         *            {@link AdvertiseSettings#ADVERTISE_TX_POWER_HIGH}.
         * @throws IllegalArgumentException If the {@code txPowerLevel} is invalid.
         */
        public Builder setTxPowerLevel(int txPowerLevel) {
            if (txPowerLevel < ADVERTISE_TX_POWER_ULTRA_LOW
                    || txPowerLevel > ADVERTISE_TX_POWER_HIGH) {
                throw new IllegalArgumentException("unknown tx power level " + txPowerLevel);
            }
            mTxPowerLevel = txPowerLevel;
            return this;
        }

        /**
         * Set whether the advertisement type should be connectable or non-connectable.
         *
         * @param connectable Controls whether the advertisment type will be connectable (true)
         *                    or non-connectable (false).
         */
        public Builder setConnectable(boolean connectable) {
            mConnectable = connectable;
            return this;
        }

        /**
         * Limit advertising to a given amount of time.
         * @param timeoutMillis Advertising time limit. May not exceed 180000 milliseconds.
         *                       A value of 0 will disable the time limit.
         * @throws IllegalArgumentException If the provided timeout is over 180000 ms.
         */
        public Builder setTimeout(int timeoutMillis) {
            if (timeoutMillis < 0 || timeoutMillis > LIMITED_ADVERTISING_MAX_MILLIS) {
                throw new IllegalArgumentException("timeoutMillis invalid (must be 0-"
                                    + LIMITED_ADVERTISING_MAX_MILLIS + " milliseconds)");
            }
            mTimeoutMillis = timeoutMillis;
            return this;
        }

        /**
         * Build the {@link AdvertiseSettings} object.
         */
        public AdvertiseSettings build() {
            return new AdvertiseSettings(mMode, mTxPowerLevel, mConnectable, mTimeoutMillis);
        }
    }
}
