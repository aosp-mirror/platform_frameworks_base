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

package android.net.lowpan;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Objects;

/**
 * Provides detailed information about a given channel.
 *
 * @hide
 */
// @SystemApi
public class LowpanChannelInfo implements Parcelable {

    public static final int UNKNOWN_POWER = Integer.MAX_VALUE;
    public static final float UNKNOWN_FREQUENCY = 0.0f;
    public static final float UNKNOWN_BANDWIDTH = 0.0f;

    private int mIndex = 0;
    private String mName = null;
    private float mSpectrumCenterFrequency = UNKNOWN_FREQUENCY;
    private float mSpectrumBandwidth = UNKNOWN_BANDWIDTH;
    private int mMaxTransmitPower = UNKNOWN_POWER;
    private boolean mIsMaskedByRegulatoryDomain = false;

    /** @hide */
    public static LowpanChannelInfo getChannelInfoForIeee802154Page0(int index) {
        LowpanChannelInfo info = new LowpanChannelInfo();

        if (index < 0) {
            info = null;

        } else if (index == 0) {
            info.mSpectrumCenterFrequency = 868300000.0f;
            info.mSpectrumBandwidth = 600000.0f;

        } else if (index < 11) {
            info.mSpectrumCenterFrequency = 906000000.0f - (2000000.0f * 1) + 2000000.0f * (index);
            info.mSpectrumBandwidth = 0; // Unknown

        } else if (index < 26) {
            info.mSpectrumCenterFrequency =
                    2405000000.0f - (5000000.0f * 11) + 5000000.0f * (index);
            info.mSpectrumBandwidth = 2000000.0f;

        } else {
            info = null;
        }

        info.mName = Integer.toString(index);

        return info;
    }

    private LowpanChannelInfo() {}

    private LowpanChannelInfo(int index, String name, float cf, float bw) {
        mIndex = index;
        mName = name;
        mSpectrumCenterFrequency = cf;
        mSpectrumBandwidth = bw;
    }

    public String getName() {
        return mName;
    }

    public int getIndex() {
        return mIndex;
    }

    public int getMaxTransmitPower() {
        return mMaxTransmitPower;
    }

    public boolean isMaskedByRegulatoryDomain() {
        return mIsMaskedByRegulatoryDomain;
    }

    public float getSpectrumCenterFrequency() {
        return mSpectrumCenterFrequency;
    }

    public float getSpectrumBandwidth() {
        return mSpectrumBandwidth;
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("Channel ").append(mIndex);

        if (mName != null && !mName.equals(Integer.toString(mIndex))) {
            sb.append(" (").append(mName).append(")");
        }

        if (mSpectrumCenterFrequency > 0.0f) {
            if (mSpectrumCenterFrequency > 1000000000.0f) {
                sb.append(", SpectrumCenterFrequency: ")
                        .append(mSpectrumCenterFrequency / 1000000000.0f)
                        .append("GHz");
            } else if (mSpectrumCenterFrequency > 1000000.0f) {
                sb.append(", SpectrumCenterFrequency: ")
                        .append(mSpectrumCenterFrequency / 1000000.0f)
                        .append("MHz");
            } else {
                sb.append(", SpectrumCenterFrequency: ")
                        .append(mSpectrumCenterFrequency / 1000.0f)
                        .append("kHz");
            }
        }

        if (mSpectrumBandwidth > 0.0f) {
            if (mSpectrumBandwidth > 1000000000.0f) {
                sb.append(", SpectrumBandwidth: ")
                        .append(mSpectrumBandwidth / 1000000000.0f)
                        .append("GHz");
            } else if (mSpectrumBandwidth > 1000000.0f) {
                sb.append(", SpectrumBandwidth: ")
                        .append(mSpectrumBandwidth / 1000000.0f)
                        .append("MHz");
            } else {
                sb.append(", SpectrumBandwidth: ")
                        .append(mSpectrumBandwidth / 1000.0f)
                        .append("kHz");
            }
        }

        if (mMaxTransmitPower != UNKNOWN_POWER) {
            sb.append(", MaxTransmitPower: ").append(mMaxTransmitPower).append("dBm");
        }

        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof LowpanChannelInfo)) {
            return false;
        }
        LowpanChannelInfo rhs = (LowpanChannelInfo) obj;
        return Objects.equals(mName, rhs.mName)
                && mIndex == rhs.mIndex
                && mIsMaskedByRegulatoryDomain == rhs.mIsMaskedByRegulatoryDomain
                && mSpectrumCenterFrequency == rhs.mSpectrumCenterFrequency
                && mSpectrumBandwidth == rhs.mSpectrumBandwidth
                && mMaxTransmitPower == rhs.mMaxTransmitPower;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mName,
                mIndex,
                mIsMaskedByRegulatoryDomain,
                mSpectrumCenterFrequency,
                mSpectrumBandwidth,
                mMaxTransmitPower);
    }

    /** Implement the Parcelable interface. */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mIndex);
        dest.writeString(mName);
        dest.writeFloat(mSpectrumCenterFrequency);
        dest.writeFloat(mSpectrumBandwidth);
        dest.writeInt(mMaxTransmitPower);
        dest.writeBoolean(mIsMaskedByRegulatoryDomain);
    }

    /** Implement the Parcelable interface. */
    public static final @android.annotation.NonNull Creator<LowpanChannelInfo> CREATOR =
            new Creator<LowpanChannelInfo>() {

                public LowpanChannelInfo createFromParcel(Parcel in) {
                    LowpanChannelInfo info = new LowpanChannelInfo();

                    info.mIndex = in.readInt();
                    info.mName = in.readString();
                    info.mSpectrumCenterFrequency = in.readFloat();
                    info.mSpectrumBandwidth = in.readFloat();
                    info.mMaxTransmitPower = in.readInt();
                    info.mIsMaskedByRegulatoryDomain = in.readBoolean();

                    return info;
                }

                public LowpanChannelInfo[] newArray(int size) {
                    return new LowpanChannelInfo[size];
                }
            };
}
