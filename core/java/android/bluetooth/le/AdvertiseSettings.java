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
 * individual advertisement. Use {@link AdvertiseSettings.Builder} to create an instance.
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
     * consumption and should not be used for background continuous advertising.
     */
    public static final int ADVERTISE_MODE_LOW_LATENCY = 2;

    /**
     * Advertise using the lowest transmission(tx) power level. An app can use low transmission
     * power to restrict the visibility range of its advertising packet.
     */
    public static final int ADVERTISE_TX_POWER_ULTRA_LOW = 0;
    /**
     * Advertise using low tx power level.
     */
    public static final int ADVERTISE_TX_POWER_LOW = 1;
    /**
     * Advertise using medium tx power level.
     */
    public static final int ADVERTISE_TX_POWER_MEDIUM = 2;
    /**
     * Advertise using high tx power level. This is corresponding to largest visibility range of the
     * advertising packet.
     */
    public static final int ADVERTISE_TX_POWER_HIGH = 3;

    /**
     * Non-connectable undirected advertising event, as defined in Bluetooth Specification V4.1
     * vol6, part B, section 4.4.2 - Advertising state.
     */
    public static final int ADVERTISE_TYPE_NON_CONNECTABLE = 0;
    /**
     * Scannable undirected advertise type, as defined in same spec mentioned above. This event type
     * allows a scanner to send a scan request asking additional information about the advertiser.
     */
    public static final int ADVERTISE_TYPE_SCANNABLE = 1;
    /**
     * Connectable undirected advertising type, as defined in same spec mentioned above. This event
     * type allows a scanner to send scan request asking additional information about the
     * advertiser. It also allows an initiator to send a connect request for connection.
     */
    public static final int ADVERTISE_TYPE_CONNECTABLE = 2;

    private final int mAdvertiseMode;
    private final int mAdvertiseTxPowerLevel;
    private final int mAdvertiseEventType;

    private AdvertiseSettings(int advertiseMode, int advertiseTxPowerLevel,
            int advertiseEventType) {
        mAdvertiseMode = advertiseMode;
        mAdvertiseTxPowerLevel = advertiseTxPowerLevel;
        mAdvertiseEventType = advertiseEventType;
    }

    private AdvertiseSettings(Parcel in) {
        mAdvertiseMode = in.readInt();
        mAdvertiseTxPowerLevel = in.readInt();
        mAdvertiseEventType = in.readInt();
    }

    /**
     * Returns the advertise mode.
     */
    public int getMode() {
        return mAdvertiseMode;
    }

    /**
     * Returns the tx power level for advertising.
     */
    public int getTxPowerLevel() {
        return mAdvertiseTxPowerLevel;
    }

    /**
     * Returns the advertise event type.
     */
    public int getType() {
        return mAdvertiseEventType;
    }

    @Override
    public String toString() {
        return "Settings [mAdvertiseMode=" + mAdvertiseMode + ", mAdvertiseTxPowerLevel="
                + mAdvertiseTxPowerLevel + ", mAdvertiseEventType=" + mAdvertiseEventType + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mAdvertiseMode);
        dest.writeInt(mAdvertiseTxPowerLevel);
        dest.writeInt(mAdvertiseEventType);
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
        private int mType = ADVERTISE_TYPE_NON_CONNECTABLE;

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
         * Set advertise tx power level to control the transmission power level for the advertising.
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
         * Set advertise type to control the event type of advertising.
         *
         * @param type Bluetooth LE Advertising type, can be either
         *            {@link AdvertiseSettings#ADVERTISE_TYPE_NON_CONNECTABLE},
         *            {@link AdvertiseSettings#ADVERTISE_TYPE_SCANNABLE} or
         *            {@link AdvertiseSettings#ADVERTISE_TYPE_CONNECTABLE}.
         * @throws IllegalArgumentException If the {@code type} is invalid.
         */
        public Builder setType(int type) {
            if (type < ADVERTISE_TYPE_NON_CONNECTABLE
                    || type > ADVERTISE_TYPE_CONNECTABLE) {
                throw new IllegalArgumentException("unknown advertise type " + type);
            }
            mType = type;
            return this;
        }

        /**
         * Build the {@link AdvertiseSettings} object.
         */
        public AdvertiseSettings build() {
            return new AdvertiseSettings(mMode, mTxPowerLevel, mType);
        }
    }
}
