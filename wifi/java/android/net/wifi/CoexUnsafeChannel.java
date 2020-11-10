/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net.wifi;

import static android.net.wifi.WifiScanner.WIFI_BAND_24_GHZ;
import static android.net.wifi.WifiScanner.WIFI_BAND_5_GHZ;
import static android.net.wifi.WifiScanner.WIFI_BAND_6_GHZ;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Data structure class representing a Wi-Fi channel that would cause interference to/receive
 * interference from the active cellular channels and should be avoided.
 *
 * If {@link #isPowerCapAvailable()} is {@code true}, then a valid power cap value is available
 * through {@link #getPowerCapDbm()} to be used if this channel cannot be avoided. If {@code false},
 * then {@link #getPowerCapDbm()} throws an IllegalStateException and the channel will not need to
 * cap its power.
 *
 * @hide
 */
@SystemApi
public final class CoexUnsafeChannel implements Parcelable {
    private @WifiAnnotations.WifiBandBasic int mBand;
    private int mChannel;
    private boolean mIsPowerCapAvailable = false;
    private int mPowerCapDbm;

    /**
     * Constructor for a CoexUnsafeChannel with no power cap specified.
     * @param band One of {@link WifiAnnotations.WifiBandBasic}
     * @param channel Channel number
     */
    public CoexUnsafeChannel(@WifiAnnotations.WifiBandBasic int band, int channel) {
        mBand = band;
        mChannel = channel;
    }

    /**
     * Constructor for a CoexUnsafeChannel with power cap specified.
     * @param band One of {@link WifiAnnotations.WifiBandBasic}
     * @param channel Channel number
     * @param powerCapDbm Power cap in dBm
     */
    public CoexUnsafeChannel(@WifiAnnotations.WifiBandBasic int band, int channel,
            int powerCapDbm) {
        mBand = band;
        mChannel = channel;
        setPowerCapDbm(powerCapDbm);
    }

    /** Returns the Wi-Fi band of this channel as one of {@link WifiAnnotations.WifiBandBasic} */
    public @WifiAnnotations.WifiBandBasic int getBand() {
        return mBand;
    }

    /** Returns the channel number of this channel. */
    public int getChannel() {
        return mChannel;
    }

    /** Returns {@code true} if {@link #getPowerCapDbm()} is a valid value, else {@code false} */
    public boolean isPowerCapAvailable() {
        return mIsPowerCapAvailable;
    }

    /**
     * Returns the power cap of this channel in dBm. Throws IllegalStateException if
     * {@link #isPowerCapAvailable()} is {@code false}.
     */
    public int getPowerCapDbm() {
        if (!mIsPowerCapAvailable) {
            throw new IllegalStateException("getPowerCapDbm called but power cap is unavailable");
        }
        return mPowerCapDbm;
    }

    /** Set the power cap of this channel. */
    public void setPowerCapDbm(int powerCapDbm) {
        mIsPowerCapAvailable = true;
        mPowerCapDbm = powerCapDbm;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CoexUnsafeChannel that = (CoexUnsafeChannel) o;
        return mBand == that.mBand
                && mChannel == that.mChannel
                && mIsPowerCapAvailable == that.mIsPowerCapAvailable
                && mPowerCapDbm == that.mPowerCapDbm;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mBand, mChannel, mIsPowerCapAvailable, mPowerCapDbm);
    }

    @Override
    public String toString() {
        StringBuilder sj = new StringBuilder("CoexUnsafeChannel{");
        sj.append(mChannel);
        sj.append(", ");
        if (mBand == WIFI_BAND_24_GHZ) {
            sj.append("2.4GHz");
        } else if (mBand == WIFI_BAND_5_GHZ) {
            sj.append("5GHz");
        } else if (mBand == WIFI_BAND_6_GHZ) {
            sj.append("6GHz");
        } else {
            sj.append("UNKNOWN BAND");
        }
        if (mIsPowerCapAvailable) {
            sj.append(", ").append(mPowerCapDbm).append("dBm");
        }
        sj.append('}');
        return sj.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mBand);
        dest.writeInt(mChannel);
        dest.writeBoolean(mIsPowerCapAvailable);
        if (mIsPowerCapAvailable) {
            dest.writeInt(mPowerCapDbm);
        }
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Creator<CoexUnsafeChannel> CREATOR =
            new Creator<CoexUnsafeChannel>() {
                public CoexUnsafeChannel createFromParcel(Parcel in) {
                    final int band = in.readInt();
                    final int channel = in.readInt();
                    final boolean isPowerCapAvailable = in.readBoolean();
                    if (isPowerCapAvailable) {
                        final int powerCapDbm = in.readInt();
                        return new CoexUnsafeChannel(band, channel, powerCapDbm);
                    }
                    return new CoexUnsafeChannel(band, channel);
                }

                public CoexUnsafeChannel[] newArray(int size) {
                    return new CoexUnsafeChannel[size];
                }
            };
}
