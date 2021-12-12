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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represents the codec configuration for a Bluetooth A2DP source device.
 * <p>Contains the source codec type, the codec priority, the codec sample
 * rate, the codec bits per sample, and the codec channel mode.
 * <p>The source codec type values are the same as those supported by the
 * device hardware.
 *
 * {@see BluetoothA2dp}
 */
public final class BluetoothCodecConfig implements Parcelable {
    /** @hide */
    @IntDef(prefix = "SOURCE_CODEC_TYPE_", value = {
            SOURCE_CODEC_TYPE_SBC,
            SOURCE_CODEC_TYPE_AAC,
            SOURCE_CODEC_TYPE_APTX,
            SOURCE_CODEC_TYPE_APTX_HD,
            SOURCE_CODEC_TYPE_LDAC,
            SOURCE_CODEC_TYPE_INVALID
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SourceCodecType {}

    /**
     * Source codec type SBC. This is the mandatory source codec
     * type.
     */
    public static final int SOURCE_CODEC_TYPE_SBC = 0;

    /**
     * Source codec type AAC.
     */
    public static final int SOURCE_CODEC_TYPE_AAC = 1;

    /**
     * Source codec type APTX.
     */
    public static final int SOURCE_CODEC_TYPE_APTX = 2;

    /**
     * Source codec type APTX HD.
     */
    public static final int SOURCE_CODEC_TYPE_APTX_HD = 3;

    /**
     * Source codec type LDAC.
     */
    public static final int SOURCE_CODEC_TYPE_LDAC = 4;

    /**
     * Source codec type invalid. This is the default value used for codec
     * type.
     */
    public static final int SOURCE_CODEC_TYPE_INVALID = 1000 * 1000;

    /**
     * Represents the count of valid source codec types. Can be accessed via
     * {@link #getMaxCodecType}.
     */
    private static final int SOURCE_CODEC_TYPE_MAX = 5;

    /** @hide */
    @IntDef(prefix = "CODEC_PRIORITY_", value = {
            CODEC_PRIORITY_DISABLED,
            CODEC_PRIORITY_DEFAULT,
            CODEC_PRIORITY_HIGHEST
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CodecPriority {}

    /**
     * Codec priority disabled.
     * Used to indicate that this codec is disabled and should not be used.
     */
    public static final int CODEC_PRIORITY_DISABLED = -1;

    /**
     * Codec priority default.
     * Default value used for codec priority.
     */
    public static final int CODEC_PRIORITY_DEFAULT = 0;

    /**
     * Codec priority highest.
     * Used to indicate the highest priority a codec can have.
     */
    public static final int CODEC_PRIORITY_HIGHEST = 1000 * 1000;

    /** @hide */
    @IntDef(prefix = "SAMPLE_RATE_", value = {
            SAMPLE_RATE_NONE,
            SAMPLE_RATE_44100,
            SAMPLE_RATE_48000,
            SAMPLE_RATE_88200,
            SAMPLE_RATE_96000,
            SAMPLE_RATE_176400,
            SAMPLE_RATE_192000
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SampleRate {}

    /**
     * Codec sample rate 0 Hz. Default value used for
     * codec sample rate.
     */
    public static final int SAMPLE_RATE_NONE = 0;

    /**
     * Codec sample rate 44100 Hz.
     */
    public static final int SAMPLE_RATE_44100 = 0x1 << 0;

    /**
     * Codec sample rate 48000 Hz.
     */
    public static final int SAMPLE_RATE_48000 = 0x1 << 1;

    /**
     * Codec sample rate 88200 Hz.
     */
    public static final int SAMPLE_RATE_88200 = 0x1 << 2;

    /**
     * Codec sample rate 96000 Hz.
     */
    public static final int SAMPLE_RATE_96000 = 0x1 << 3;

    /**
     * Codec sample rate 176400 Hz.
     */
    public static final int SAMPLE_RATE_176400 = 0x1 << 4;

    /**
     * Codec sample rate 192000 Hz.
     */
    public static final int SAMPLE_RATE_192000 = 0x1 << 5;

    /** @hide */
    @IntDef(prefix = "BITS_PER_SAMPLE_", value = {
            BITS_PER_SAMPLE_NONE,
            BITS_PER_SAMPLE_16,
            BITS_PER_SAMPLE_24,
            BITS_PER_SAMPLE_32
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BitsPerSample {}

    /**
     * Codec bits per sample 0. Default value of the codec
     * bits per sample.
     */
    public static final int BITS_PER_SAMPLE_NONE = 0;

    /**
     * Codec bits per sample 16.
     */
    public static final int BITS_PER_SAMPLE_16 = 0x1 << 0;

    /**
     * Codec bits per sample 24.
     */
    public static final int BITS_PER_SAMPLE_24 = 0x1 << 1;

    /**
     * Codec bits per sample 32.
     */
    public static final int BITS_PER_SAMPLE_32 = 0x1 << 2;

    /** @hide */
    @IntDef(prefix = "CHANNEL_MODE_", value = {
            CHANNEL_MODE_NONE,
            CHANNEL_MODE_MONO,
            CHANNEL_MODE_STEREO
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ChannelMode {}

    /**
     * Codec channel mode NONE. Default value of the
     * codec channel mode.
     */
    public static final int CHANNEL_MODE_NONE = 0;

    /**
     * Codec channel mode MONO.
     */
    public static final int CHANNEL_MODE_MONO = 0x1 << 0;

    /**
     * Codec channel mode STEREO.
     */
    public static final int CHANNEL_MODE_STEREO = 0x1 << 1;

    private final @SourceCodecType int mCodecType;
    private @CodecPriority int mCodecPriority;
    private final @SampleRate int mSampleRate;
    private final @BitsPerSample int mBitsPerSample;
    private final @ChannelMode int mChannelMode;
    private final long mCodecSpecific1;
    private final long mCodecSpecific2;
    private final long mCodecSpecific3;
    private final long mCodecSpecific4;

    /**
     * Creates a new BluetoothCodecConfig.
     *
     * @param codecType the source codec type
     * @param codecPriority the priority of this codec
     * @param sampleRate the codec sample rate
     * @param bitsPerSample the bits per sample of this codec
     * @param channelMode the channel mode of this codec
     * @param codecSpecific1 the specific value 1
     * @param codecSpecific2 the specific value 2
     * @param codecSpecific3 the specific value 3
     * @param codecSpecific4 the specific value 4
     * values to 0.
     * @hide
     */
    @UnsupportedAppUsage
    public BluetoothCodecConfig(@SourceCodecType int codecType, @CodecPriority int codecPriority,
            @SampleRate int sampleRate, @BitsPerSample int bitsPerSample,
            @ChannelMode int channelMode, long codecSpecific1,
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

    /**
     * Creates a new BluetoothCodecConfig.
     * <p> By default, the codec priority will be set
     * to {@link BluetoothCodecConfig#CODEC_PRIORITY_DEFAULT}, the sample rate to
     * {@link BluetoothCodecConfig#SAMPLE_RATE_NONE}, the bits per sample to
     * {@link BluetoothCodecConfig#BITS_PER_SAMPLE_NONE}, the channel mode to
     * {@link BluetoothCodecConfig#CHANNEL_MODE_NONE}, and all the codec specific
     * values to 0.
     *
     * @param codecType the source codec type
     */
    public BluetoothCodecConfig(@SourceCodecType int codecType) {
        this(codecType, BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT,
                BluetoothCodecConfig.SAMPLE_RATE_NONE,
                BluetoothCodecConfig.BITS_PER_SAMPLE_NONE,
                BluetoothCodecConfig.CHANNEL_MODE_NONE, 0, 0, 0, 0);
    }

    private BluetoothCodecConfig(Parcel in) {
        mCodecType = in.readInt();
        mCodecPriority = in.readInt();
        mSampleRate = in.readInt();
        mBitsPerSample = in.readInt();
        mChannelMode = in.readInt();
        mCodecSpecific1 = in.readLong();
        mCodecSpecific2 = in.readLong();
        mCodecSpecific3 = in.readLong();
        mCodecSpecific4 = in.readLong();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof BluetoothCodecConfig) {
            BluetoothCodecConfig other = (BluetoothCodecConfig) o;
            return (other.mCodecType == mCodecType
                    && other.mCodecPriority == mCodecPriority
                    && other.mSampleRate == mSampleRate
                    && other.mBitsPerSample == mBitsPerSample
                    && other.mChannelMode == mChannelMode
                    && other.mCodecSpecific1 == mCodecSpecific1
                    && other.mCodecSpecific2 == mCodecSpecific2
                    && other.mCodecSpecific3 == mCodecSpecific3
                    && other.mCodecSpecific4 == mCodecSpecific4);
        }
        return false;
    }

    /**
     * Returns a hash representation of this BluetoothCodecConfig
     * based on all the config values.
     */
    @Override
    public int hashCode() {
        return Objects.hash(mCodecType, mCodecPriority, mSampleRate,
                mBitsPerSample, mChannelMode, mCodecSpecific1,
                mCodecSpecific2, mCodecSpecific3, mCodecSpecific4);
    }

    /**
     * Adds capability string to an existing string.
     *
     * @param prevStr the previous string with the capabilities. Can be a {@code null} pointer
     * @param capStr the capability string to append to prevStr argument
     * @return the result string in the form "prevStr|capStr"
     */
    private static String appendCapabilityToString(@Nullable String prevStr,
            @NonNull String capStr) {
        if (prevStr == null) {
            return capStr;
        }
        return prevStr + "|" + capStr;
    }

    /**
     * Returns a {@link String} that describes each BluetoothCodecConfig parameter
     * current value.
     */
    @Override
    public String toString() {
        String sampleRateStr = null;
        if (mSampleRate == SAMPLE_RATE_NONE) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "NONE");
        }
        if ((mSampleRate & SAMPLE_RATE_44100) != 0) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "44100");
        }
        if ((mSampleRate & SAMPLE_RATE_48000) != 0) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "48000");
        }
        if ((mSampleRate & SAMPLE_RATE_88200) != 0) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "88200");
        }
        if ((mSampleRate & SAMPLE_RATE_96000) != 0) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "96000");
        }
        if ((mSampleRate & SAMPLE_RATE_176400) != 0) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "176400");
        }
        if ((mSampleRate & SAMPLE_RATE_192000) != 0) {
            sampleRateStr = appendCapabilityToString(sampleRateStr, "192000");
        }

        String bitsPerSampleStr = null;
        if (mBitsPerSample == BITS_PER_SAMPLE_NONE) {
            bitsPerSampleStr = appendCapabilityToString(bitsPerSampleStr, "NONE");
        }
        if ((mBitsPerSample & BITS_PER_SAMPLE_16) != 0) {
            bitsPerSampleStr = appendCapabilityToString(bitsPerSampleStr, "16");
        }
        if ((mBitsPerSample & BITS_PER_SAMPLE_24) != 0) {
            bitsPerSampleStr = appendCapabilityToString(bitsPerSampleStr, "24");
        }
        if ((mBitsPerSample & BITS_PER_SAMPLE_32) != 0) {
            bitsPerSampleStr = appendCapabilityToString(bitsPerSampleStr, "32");
        }

        String channelModeStr = null;
        if (mChannelMode == CHANNEL_MODE_NONE) {
            channelModeStr = appendCapabilityToString(channelModeStr, "NONE");
        }
        if ((mChannelMode & CHANNEL_MODE_MONO) != 0) {
            channelModeStr = appendCapabilityToString(channelModeStr, "MONO");
        }
        if ((mChannelMode & CHANNEL_MODE_STEREO) != 0) {
            channelModeStr = appendCapabilityToString(channelModeStr, "STEREO");
        }

        return "{codecName:" + getCodecName()
                + ",mCodecType:" + mCodecType
                + ",mCodecPriority:" + mCodecPriority
                + ",mSampleRate:" + String.format("0x%x", mSampleRate)
                + "(" + sampleRateStr + ")"
                + ",mBitsPerSample:" + String.format("0x%x", mBitsPerSample)
                + "(" + bitsPerSampleStr + ")"
                + ",mChannelMode:" + String.format("0x%x", mChannelMode)
                + "(" + channelModeStr + ")"
                + ",mCodecSpecific1:" + mCodecSpecific1
                + ",mCodecSpecific2:" + mCodecSpecific2
                + ",mCodecSpecific3:" + mCodecSpecific3
                + ",mCodecSpecific4:" + mCodecSpecific4 + "}";
    }

    /**
     * @return 0
     * @hide
     */
    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<BluetoothCodecConfig> CREATOR =
            new Parcelable.Creator<BluetoothCodecConfig>() {
                public BluetoothCodecConfig createFromParcel(Parcel in) {
                    return new BluetoothCodecConfig(in);
                }

                public BluetoothCodecConfig[] newArray(int size) {
                    return new BluetoothCodecConfig[size];
                }
            };

    /**
     * Flattens the object to a parcel
     *
     * @param out The Parcel in which the object should be written
     * @param flags Additional flags about how the object should be written
     *
     * @hide
     */
    @Override
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
     * Returns the codec name converted to {@link String}.
     * @hide
     */
    public @NonNull String getCodecName() {
        switch (mCodecType) {
            case SOURCE_CODEC_TYPE_SBC:
                return "SBC";
            case SOURCE_CODEC_TYPE_AAC:
                return "AAC";
            case SOURCE_CODEC_TYPE_APTX:
                return "aptX";
            case SOURCE_CODEC_TYPE_APTX_HD:
                return "aptX HD";
            case SOURCE_CODEC_TYPE_LDAC:
                return "LDAC";
            case SOURCE_CODEC_TYPE_INVALID:
                return "INVALID CODEC";
            default:
                break;
        }
        return "UNKNOWN CODEC(" + mCodecType + ")";
    }

    /**
     * Returns the source codec type of this config.
     */
    public @SourceCodecType int getCodecType() {
        return mCodecType;
    }

    /**
     * Returns the valid codec types count.
     */
    public static int getMaxCodecType() {
        return SOURCE_CODEC_TYPE_MAX;
    }

    /**
     * Checks whether the codec is mandatory.
     * <p> The actual mandatory codec type for Android Bluetooth audio is SBC.
     * See {@link #SOURCE_CODEC_TYPE_SBC}.
     *
     * @return {@code true} if the codec is mandatory, {@code false} otherwise
     * @hide
     */
    public boolean isMandatoryCodec() {
        return mCodecType == SOURCE_CODEC_TYPE_SBC;
    }

    /**
     * Returns the codec selection priority.
     * <p>The codec selection priority is relative to other codecs: larger value
     * means higher priority.
     */
    public @CodecPriority int getCodecPriority() {
        return mCodecPriority;
    }

    /**
     * Sets the codec selection priority.
     * <p>The codec selection priority is relative to other codecs: larger value
     * means higher priority.
     *
     * @param codecPriority the priority this codec should have
     * @hide
     */
    public void setCodecPriority(@CodecPriority int codecPriority) {
        mCodecPriority = codecPriority;
    }

    /**
     * Returns the codec sample rate. The value can be a bitmask with all
     * supported sample rates.
     */
    public @SampleRate int getSampleRate() {
        return mSampleRate;
    }

    /**
     * Returns the codec bits per sample. The value can be a bitmask with all
     * bits per sample supported.
     */
    public @BitsPerSample int getBitsPerSample() {
        return mBitsPerSample;
    }

    /**
     * Returns the codec channel mode. The value can be a bitmask with all
     * supported channel modes.
     */
    public @ChannelMode int getChannelMode() {
        return mChannelMode;
    }

    /**
     * Returns the codec specific value1.
     */
    public long getCodecSpecific1() {
        return mCodecSpecific1;
    }

    /**
     * Returns the codec specific value2.
     */
    public long getCodecSpecific2() {
        return mCodecSpecific2;
    }

    /**
     * Returns the codec specific value3.
     */
    public long getCodecSpecific3() {
        return mCodecSpecific3;
    }

    /**
     * Returns the codec specific value4.
     */
    public long getCodecSpecific4() {
        return mCodecSpecific4;
    }

    /**
     * Checks whether a value set presented by a bitmask has zero or single bit
     *
     * @param valueSet the value set presented by a bitmask
     * @return {@code true} if the valueSet contains zero or single bit, {@code false} otherwise
     * @hide
     */
    private static boolean hasSingleBit(int valueSet) {
        return (valueSet == 0 || (valueSet & (valueSet - 1)) == 0);
    }

    /**
     * Returns whether the object contains none or single sample rate.
     * @hide
     */
    public boolean hasSingleSampleRate() {
        return hasSingleBit(mSampleRate);
    }

    /**
     * Returns whether the object contains none or single bits per sample.
     * @hide
     */
    public boolean hasSingleBitsPerSample() {
        return hasSingleBit(mBitsPerSample);
    }

    /**
     * Returns whether the object contains none or single channel mode.
     * @hide
     */
    public boolean hasSingleChannelMode() {
        return hasSingleBit(mChannelMode);
    }

    /**
     * Checks whether the audio feeding parameters are the same.
     *
     * @param other the codec config to compare against
     * @return {@code true} if the audio feeding parameters are same, {@code false} otherwise
     * @hide
     */
    public boolean sameAudioFeedingParameters(BluetoothCodecConfig other) {
        return (other != null && other.mSampleRate == mSampleRate
                && other.mBitsPerSample == mBitsPerSample
                && other.mChannelMode == mChannelMode);
    }

    /**
     * Checks whether another codec config has the similar feeding parameters.
     * Any parameters with NONE value will be considered to be a wildcard matching.
     *
     * @param other the codec config to compare against
     * @return {@code true} if the audio feeding parameters are similar, {@code false} otherwise
     * @hide
     */
    public boolean similarCodecFeedingParameters(BluetoothCodecConfig other) {
        if (other == null || mCodecType != other.mCodecType) {
            return false;
        }
        int sampleRate = other.mSampleRate;
        if (mSampleRate == SAMPLE_RATE_NONE
                || sampleRate == SAMPLE_RATE_NONE) {
            sampleRate = mSampleRate;
        }
        int bitsPerSample = other.mBitsPerSample;
        if (mBitsPerSample == BITS_PER_SAMPLE_NONE
                || bitsPerSample == BITS_PER_SAMPLE_NONE) {
            bitsPerSample = mBitsPerSample;
        }
        int channelMode = other.mChannelMode;
        if (mChannelMode == CHANNEL_MODE_NONE
                || channelMode == CHANNEL_MODE_NONE) {
            channelMode = mChannelMode;
        }
        return sameAudioFeedingParameters(new BluetoothCodecConfig(
                mCodecType, /* priority */ 0, sampleRate, bitsPerSample, channelMode,
                /* specific1 */ 0, /* specific2 */ 0, /* specific3 */ 0,
                /* specific4 */ 0));
    }

    /**
     * Checks whether the codec specific parameters are the same.
     * <p> Currently, only AAC VBR and LDAC Playback Quality on CodecSpecific1
     * are compared.
     *
     * @param other the codec config to compare against
     * @return {@code true} if the codec specific parameters are the same, {@code false} otherwise
     * @hide
     */
    public boolean sameCodecSpecificParameters(BluetoothCodecConfig other) {
        if (other == null && mCodecType != other.mCodecType) {
            return false;
        }
        switch (mCodecType) {
            case SOURCE_CODEC_TYPE_AAC:
            case SOURCE_CODEC_TYPE_LDAC:
                if (mCodecSpecific1 != other.mCodecSpecific1) {
                    return false;
                }
            default:
                return true;
        }
    }

    /**
     * Builder for {@link BluetoothCodecConfig}.
     * <p> By default, the codec type will be set to
     * {@link BluetoothCodecConfig#SOURCE_CODEC_TYPE_INVALID}, the codec priority
     * to {@link BluetoothCodecConfig#CODEC_PRIORITY_DEFAULT}, the sample rate to
     * {@link BluetoothCodecConfig#SAMPLE_RATE_NONE}, the bits per sample to
     * {@link BluetoothCodecConfig#BITS_PER_SAMPLE_NONE}, the channel mode to
     * {@link BluetoothCodecConfig#CHANNEL_MODE_NONE}, and all the codec specific
     * values to 0.
     */
    public static final class Builder {
        private int mCodecType = BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID;
        private int mCodecPriority = BluetoothCodecConfig.CODEC_PRIORITY_DEFAULT;
        private int mSampleRate = BluetoothCodecConfig.SAMPLE_RATE_NONE;
        private int mBitsPerSample = BluetoothCodecConfig.BITS_PER_SAMPLE_NONE;
        private int mChannelMode = BluetoothCodecConfig.CHANNEL_MODE_NONE;
        private long mCodecSpecific1 = 0;
        private long mCodecSpecific2 = 0;
        private long mCodecSpecific3 = 0;
        private long mCodecSpecific4 = 0;

        /**
         * Set codec type for Bluetooth codec config.
         *
         * @param codecType of this codec
         * @return the same Builder instance
         */
        public @NonNull Builder setCodecType(@SourceCodecType int codecType) {
            mCodecType = codecType;
            return this;
        }

        /**
         * Set codec priority for Bluetooth codec config.
         *
         * @param codecPriority of this codec
         * @return the same Builder instance
         */
        public @NonNull Builder setCodecPriority(@CodecPriority int codecPriority) {
            mCodecPriority = codecPriority;
            return this;
        }

        /**
         * Set sample rate for Bluetooth codec config.
         *
         * @param sampleRate of this codec
         * @return the same Builder instance
         */
        public @NonNull Builder setSampleRate(@SampleRate int sampleRate) {
            mSampleRate = sampleRate;
            return this;
        }

        /**
         * Set the bits per sample for Bluetooth codec config.
         *
         * @param bitsPerSample of this codec
         * @return the same Builder instance
         */
        public @NonNull Builder setBitsPerSample(@BitsPerSample int bitsPerSample) {
            mBitsPerSample = bitsPerSample;
            return this;
        }

        /**
         * Set the channel mode for Bluetooth codec config.
         *
         * @param channelMode of this codec
         * @return the same Builder instance
         */
        public @NonNull Builder setChannelMode(@ChannelMode int channelMode) {
            mChannelMode = channelMode;
            return this;
        }

        /**
         * Set the first codec specific values for Bluetooth codec config.
         *
         * @param codecSpecific1 codec specific value or 0 if default
         * @return the same Builder instance
         */
        public @NonNull Builder setCodecSpecific1(long codecSpecific1) {
            mCodecSpecific1 = codecSpecific1;
            return this;
        }

        /**
         * Set the second codec specific values for Bluetooth codec config.
         *
         * @param codecSpecific2 codec specific value or 0 if default
         * @return the same Builder instance
         */
        public @NonNull Builder setCodecSpecific2(long codecSpecific2) {
            mCodecSpecific2 = codecSpecific2;
            return this;
        }

        /**
         * Set the third codec specific values for Bluetooth codec config.
         *
         * @param codecSpecific3 codec specific value or 0 if default
         * @return the same Builder instance
         */
        public @NonNull Builder setCodecSpecific3(long codecSpecific3) {
            mCodecSpecific3 = codecSpecific3;
            return this;
        }

        /**
         * Set the fourth codec specific values for Bluetooth codec config.
         *
         * @param codecSpecific4 codec specific value or 0 if default
         * @return the same Builder instance
         */
        public @NonNull Builder setCodecSpecific4(long codecSpecific4) {
            mCodecSpecific4 = codecSpecific4;
            return this;
        }

        /**
         * Build {@link BluetoothCodecConfig}.
         * @return new BluetoothCodecConfig built
         */
        public @NonNull BluetoothCodecConfig build() {
            return new BluetoothCodecConfig(mCodecType, mCodecPriority,
                    mSampleRate, mBitsPerSample,
                    mChannelMode, mCodecSpecific1,
                    mCodecSpecific2, mCodecSpecific3,
                    mCodecSpecific4);
        }
    }
}
