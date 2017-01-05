/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.bluetooth;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Represents the codec configuration for a Bluetooth A2DP source device.
 *
 * {@see BluetoothA2dp}
 *
 * {@hide}
 */
public final class BluetoothCodecConfig implements Parcelable {

    /**
     * Extra for the codec configuration intents of the individual profiles.
     *
     * This extra represents the current codec configuration of the A2DP
     * profile.
     */
    public static final String EXTRA_CODEC_CONFIG = "android.bluetooth.codec.extra.CODEC_CONFIG";

    /**
     * Extra for the codec configuration intents of the individual profiles.
     *
     * This extra represents the previous codec configuration of the A2DP
     * profile.
     */
    public static final String EXTRA_PREVIOUS_CODEC_CONFIG =
        "android.bluetooth.codec.extra.PREVIOUS_CODEC_CONFIG";

    // Add an entry for each source codec here.
    // NOTE: The values should be same as those listed in the following file:
    //   hardware/libhardware/include/hardware/bt_av.h
    public static final int SOURCE_CODEC_TYPE_SBC     = 0;
    public static final int SOURCE_CODEC_TYPE_APTX    = 1;
    public static final int SOURCE_CODEC_TYPE_APTX_HD = 2;
    public static final int SOURCE_CODEC_TYPE_LDAC    = 3;

    public static final int SOURCE_CODEC_TYPE_INVALID = 1000 * 1000;

    public static final int CODEC_PRIORITY_DEFAULT = 0;
    public static final int CODEC_PRIORITY_HIGHEST = 1000 * 1000;

    public static final int SAMPLE_RATE_NONE   = 0;
    public static final int SAMPLE_RATE_44100  = 0x1 << 0;
    public static final int SAMPLE_RATE_48000  = 0x1 << 1;
    public static final int SAMPLE_RATE_88200  = 0x1 << 2;
    public static final int SAMPLE_RATE_96000  = 0x1 << 3;
    public static final int SAMPLE_RATE_176400 = 0x1 << 4;
    public static final int SAMPLE_RATE_192000 = 0x1 << 5;

    public static final int BITS_PER_SAMPLE_NONE = 0;
    public static final int BITS_PER_SAMPLE_16   = 0x1 << 0;
    public static final int BITS_PER_SAMPLE_24   = 0x1 << 1;
    public static final int BITS_PER_SAMPLE_32   = 0x1 << 2;

    public static final int CHANNEL_MODE_NONE   = 0;
    public static final int CHANNEL_MODE_MONO   = 0x1 << 0;
    public static final int CHANNEL_MODE_STEREO = 0x1 << 1;

    private final int mCodecType;
    private final int mCodecPriority;
    private final int mSampleRate;
    private final int mBitsPerSample;
    private final int mChannelMode;
    private final long mCodecSpecific1;
    private final long mCodecSpecific2;
    private final long mCodecSpecific3;
    private final long mCodecSpecific4;

    public BluetoothCodecConfig(int codecType, int codecPriority,
                                int sampleRate, int bitsPerSample,
                                int channelMode,long codecSpecific1,
                                long codecSpecific2, long codecSpecific3,
                                long codecSpecific4) {
        mCodecType = codecType;
        mCodecPriority = codecPriority;
        mSampleRate = sampleRate;
        mBitsPerSample = bitsPerSample;
        mChannelMode = channelMode;
        mCodecSpecific1 = codecSpecific1;
        mCodecSpecific2 = codecSpecific2;
        mCodecSpecific3 = codecSpecific3;
        mCodecSpecific4 = codecSpecific4;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof BluetoothCodecConfig) {
            BluetoothCodecConfig other = (BluetoothCodecConfig)o;
            return (other.mCodecType == mCodecType &&
                    other.mCodecPriority == mCodecPriority &&
                    other.mSampleRate == mSampleRate &&
                    other.mBitsPerSample == mBitsPerSample &&
                    other.mChannelMode == mChannelMode &&
                    other.mCodecSpecific1 == mCodecSpecific1 &&
                    other.mCodecSpecific2 == mCodecSpecific2 &&
                    other.mCodecSpecific3 == mCodecSpecific3 &&
                    other.mCodecSpecific4 == mCodecSpecific4);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCodecType, mCodecPriority, mSampleRate,
                            mBitsPerSample, mChannelMode, mCodecSpecific1,
                            mCodecSpecific2, mCodecSpecific3, mCodecSpecific4);
    }

    @Override
    public String toString() {
        return "{mCodecType:" + mCodecType +
            ",mCodecPriority:" + mCodecPriority +
            ",mSampleRate:" + String.format("0x%x", mSampleRate) +
            ",mBitsPerSample:" + String.format("0x%x", mBitsPerSample) +
            ",mChannelMode:" + String.format("0x%x", mChannelMode) +
            ",mCodecSpecific1:" + mCodecSpecific1 +
            ",mCodecSpecific2:" + mCodecSpecific2 +
            ",mCodecSpecific3:" + mCodecSpecific3 +
            ",mCodecSpecific4:" + mCodecSpecific4 + "}";
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<BluetoothCodecConfig> CREATOR =
            new Parcelable.Creator<BluetoothCodecConfig>() {
        public BluetoothCodecConfig createFromParcel(Parcel in) {
            final int codecType = in.readInt();
            final int codecPriority = in.readInt();
            final int sampleRate = in.readInt();
            final int bitsPerSample = in.readInt();
            final int channelMode = in.readInt();
            final long codecSpecific1 = in.readLong();
            final long codecSpecific2 = in.readLong();
            final long codecSpecific3 = in.readLong();
            final long codecSpecific4 = in.readLong();
            return new BluetoothCodecConfig(codecType, codecPriority,
                                            sampleRate, bitsPerSample,
                                            channelMode, codecSpecific1,
                                            codecSpecific2, codecSpecific3,
                                            codecSpecific4);
        }
        public BluetoothCodecConfig[] newArray(int size) {
            return new BluetoothCodecConfig[size];
        }
    };

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mCodecType);
        out.writeInt(mCodecPriority);
        out.writeInt(mSampleRate);
        out.writeInt(mBitsPerSample);
        out.writeInt(mChannelMode);
        out.writeLong(mCodecSpecific1);
        out.writeLong(mCodecSpecific2);
        out.writeLong(mCodecSpecific3);
        out.writeLong(mCodecSpecific4);
    }

    /**
     * Returns the codec type.
     * See {@link android.bluetooth.BluetoothCodecConfig#SOURCE_CODEC_TYPE_SBC}.
     *
     * @return the codec type
     */
    public int getCodecType() {
        return mCodecType;
    }

    /**
     * Returns the codec selection priority.
     * The codec selection priority is relative to other codecs: larger value
     * means higher priority. If 0, reset to default.
     *
     * @return the codec priority
     */
    public int getCodecPriority() {
        return mCodecPriority;
    }

    /**
     * Returns the codec sample rate. The value can be a bitmask with all
     * supported sample rates:
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_NONE} or
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_44100} or
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_48000} or
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_88200} or
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_96000} or
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_176400} or
     * {@link android.bluetooth.BluetoothCodecConfig#SAMPLE_RATE_192000}
     *
     * @return the codec sample rate
     */
    public int getSampleRate() {
        return mSampleRate;
    }

    /**
     * Returns the codec bits per sample. The value can be a bitmask with all
     * bits per sample supported:
     * {@link android.bluetooth.BluetoothCodecConfig#BITS_PER_SAMPLE_NONE} or
     * {@link android.bluetooth.BluetoothCodecConfig#BITS_PER_SAMPLE_16} or
     * {@link android.bluetooth.BluetoothCodecConfig#BITS_PER_SAMPLE_24} or
     * {@link android.bluetooth.BluetoothCodecConfig#BITS_PER_SAMPLE_32}
     *
     * @return the codec bits per sample
     */
    public int getBitsPerSample() {
        return mBitsPerSample;
    }

    /**
     * Returns the codec channel mode. The value can be a bitmask with all
     * supported channel modes:
     * {@link android.bluetooth.BluetoothCodecConfig#CHANNEL_MODE_NONE} or
     * {@link android.bluetooth.BluetoothCodecConfig#CHANNEL_MODE_MONO} or
     * {@link android.bluetooth.BluetoothCodecConfig#CHANNEL_MODE_STEREO}
     *
     * @return the codec channel mode
     */
    public int getChannelMode() {
        return mChannelMode;
    }

    /**
     * Returns a codec specific value1.
     *
     * @return a codec specific value1.
     */
    public long getCodecSpecific1() {
        return mCodecSpecific1;
    }

    /**
     * Returns a codec specific value2.
     *
     * @return a codec specific value2
     */
    public long getCodecSpecific2() {
        return mCodecSpecific2;
    }

    /**
     * Returns a codec specific value3.
     *
     * @return a codec specific value3
     */
    public long getCodecSpecific3() {
        return mCodecSpecific3;
    }

    /**
     * Returns a codec specific value4.
     *
     * @return a codec specific value4
     */
    public long getCodecSpecific4() {
        return mCodecSpecific4;
    }

    /**
     * Checks whether the audio feeding parameters are same.
     *
     * @param other the codec config to compare against
     * @return true if the audio feeding parameters are same, otherwise false
     */
    public boolean sameAudioFeedingParameters(BluetoothCodecConfig other) {
        return (other != null && other.mSampleRate == mSampleRate &&
                other.mBitsPerSample == mBitsPerSample &&
                other.mChannelMode == mChannelMode);
    }
}
