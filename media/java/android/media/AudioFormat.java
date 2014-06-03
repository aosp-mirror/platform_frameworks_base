/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.media;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The AudioFormat class is used to access a number of audio format and
 * channel configuration constants. They are for instance used
 * in {@link AudioTrack} and {@link AudioRecord}.
 * 
 */
public class AudioFormat {
    
    //---------------------------------------------------------
    // Constants
    //--------------------
    /** Invalid audio data format */
    public static final int ENCODING_INVALID = 0;
    /** Default audio data format */
    public static final int ENCODING_DEFAULT = 1;

    // These values must be kept in sync with core/jni/android_media_AudioFormat.h
    /** Audio data format: PCM 16 bit per sample. Guaranteed to be supported by devices. */
    public static final int ENCODING_PCM_16BIT = 2;
    /** Audio data format: PCM 8 bit per sample. Not guaranteed to be supported by devices. */
    public static final int ENCODING_PCM_8BIT = 3;
    /** Audio data format: single-precision floating-point per sample */
    public static final int ENCODING_PCM_FLOAT = 4;

    /** Invalid audio channel configuration */
    /** @deprecated use CHANNEL_INVALID instead  */
    @Deprecated    public static final int CHANNEL_CONFIGURATION_INVALID   = 0;
    /** Default audio channel configuration */
    /** @deprecated use CHANNEL_OUT_DEFAULT or CHANNEL_IN_DEFAULT instead  */
    @Deprecated    public static final int CHANNEL_CONFIGURATION_DEFAULT   = 1;
    /** Mono audio configuration */
    /** @deprecated use CHANNEL_OUT_MONO or CHANNEL_IN_MONO instead  */
    @Deprecated    public static final int CHANNEL_CONFIGURATION_MONO      = 2;
    /** Stereo (2 channel) audio configuration */
    /** @deprecated use CHANNEL_OUT_STEREO or CHANNEL_IN_STEREO instead  */
    @Deprecated    public static final int CHANNEL_CONFIGURATION_STEREO    = 3;

    /** Invalid audio channel mask */
    public static final int CHANNEL_INVALID = 0;
    /** Default audio channel mask */
    public static final int CHANNEL_OUT_DEFAULT = 1;

    // Output channel mask definitions below are translated to the native values defined in
    //  in /system/core/include/system/audio.h in the JNI code of AudioTrack
    public static final int CHANNEL_OUT_FRONT_LEFT = 0x4;
    public static final int CHANNEL_OUT_FRONT_RIGHT = 0x8;
    public static final int CHANNEL_OUT_FRONT_CENTER = 0x10;
    public static final int CHANNEL_OUT_LOW_FREQUENCY = 0x20;
    public static final int CHANNEL_OUT_BACK_LEFT = 0x40;
    public static final int CHANNEL_OUT_BACK_RIGHT = 0x80;
    public static final int CHANNEL_OUT_FRONT_LEFT_OF_CENTER = 0x100;
    public static final int CHANNEL_OUT_FRONT_RIGHT_OF_CENTER = 0x200;
    public static final int CHANNEL_OUT_BACK_CENTER = 0x400;
    /** @hide */
    public static final int CHANNEL_OUT_SIDE_LEFT =         0x800;
    /** @hide */
    public static final int CHANNEL_OUT_SIDE_RIGHT =       0x1000;
    /** @hide */
    public static final int CHANNEL_OUT_TOP_CENTER =       0x2000;
    /** @hide */
    public static final int CHANNEL_OUT_TOP_FRONT_LEFT =   0x4000;
    /** @hide */
    public static final int CHANNEL_OUT_TOP_FRONT_CENTER = 0x8000;
    /** @hide */
    public static final int CHANNEL_OUT_TOP_FRONT_RIGHT = 0x10000;
    /** @hide */
    public static final int CHANNEL_OUT_TOP_BACK_LEFT =   0x20000;
    /** @hide */
    public static final int CHANNEL_OUT_TOP_BACK_CENTER = 0x40000;
    /** @hide */
    public static final int CHANNEL_OUT_TOP_BACK_RIGHT =  0x80000;

    public static final int CHANNEL_OUT_MONO = CHANNEL_OUT_FRONT_LEFT;
    public static final int CHANNEL_OUT_STEREO = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT);
    // aka QUAD_BACK
    public static final int CHANNEL_OUT_QUAD = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_BACK_LEFT | CHANNEL_OUT_BACK_RIGHT);
    /** @hide */
    public static final int CHANNEL_OUT_QUAD_SIDE = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_SIDE_LEFT | CHANNEL_OUT_SIDE_RIGHT);
    public static final int CHANNEL_OUT_SURROUND = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_BACK_CENTER);
    // aka 5POINT1_BACK
    public static final int CHANNEL_OUT_5POINT1 = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_LOW_FREQUENCY | CHANNEL_OUT_BACK_LEFT | CHANNEL_OUT_BACK_RIGHT);
    /** @hide */
    public static final int CHANNEL_OUT_5POINT1_SIDE = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_LOW_FREQUENCY |
            CHANNEL_OUT_SIDE_LEFT | CHANNEL_OUT_SIDE_RIGHT);
    // TODO does this need an @deprecated ?
    // different from AUDIO_CHANNEL_OUT_7POINT1
    public static final int CHANNEL_OUT_7POINT1 = (CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_LOW_FREQUENCY | CHANNEL_OUT_BACK_LEFT | CHANNEL_OUT_BACK_RIGHT |
            CHANNEL_OUT_FRONT_LEFT_OF_CENTER | CHANNEL_OUT_FRONT_RIGHT_OF_CENTER);
    /** @hide */
    // matches AUDIO_CHANNEL_OUT_7POINT1
    public static final int CHANNEL_OUT_7POINT1_SURROUND = (
            CHANNEL_OUT_FRONT_LEFT | CHANNEL_OUT_FRONT_CENTER | CHANNEL_OUT_FRONT_RIGHT |
            CHANNEL_OUT_SIDE_LEFT | CHANNEL_OUT_SIDE_RIGHT |
            CHANNEL_OUT_BACK_LEFT | CHANNEL_OUT_BACK_RIGHT |
            CHANNEL_OUT_LOW_FREQUENCY);
    // CHANNEL_OUT_ALL is not yet defined; if added then it should match AUDIO_CHANNEL_OUT_ALL

    public static final int CHANNEL_IN_DEFAULT = 1;
    // These directly match native
    public static final int CHANNEL_IN_LEFT = 0x4;
    public static final int CHANNEL_IN_RIGHT = 0x8;
    public static final int CHANNEL_IN_FRONT = 0x10;
    public static final int CHANNEL_IN_BACK = 0x20;
    public static final int CHANNEL_IN_LEFT_PROCESSED = 0x40;
    public static final int CHANNEL_IN_RIGHT_PROCESSED = 0x80;
    public static final int CHANNEL_IN_FRONT_PROCESSED = 0x100;
    public static final int CHANNEL_IN_BACK_PROCESSED = 0x200;
    public static final int CHANNEL_IN_PRESSURE = 0x400;
    public static final int CHANNEL_IN_X_AXIS = 0x800;
    public static final int CHANNEL_IN_Y_AXIS = 0x1000;
    public static final int CHANNEL_IN_Z_AXIS = 0x2000;
    public static final int CHANNEL_IN_VOICE_UPLINK = 0x4000;
    public static final int CHANNEL_IN_VOICE_DNLINK = 0x8000;
    public static final int CHANNEL_IN_MONO = CHANNEL_IN_FRONT;
    public static final int CHANNEL_IN_STEREO = (CHANNEL_IN_LEFT | CHANNEL_IN_RIGHT);
    /** @hide */
    public static final int CHANNEL_IN_FRONT_BACK = CHANNEL_IN_FRONT | CHANNEL_IN_BACK;
    // CHANNEL_IN_ALL is not yet defined; if added then it should match AUDIO_CHANNEL_IN_ALL

    /** @hide */
    public static int getBytesPerSample(int audioFormat)
    {
        switch (audioFormat) {
        case ENCODING_PCM_8BIT:
            return 1;
        case ENCODING_PCM_16BIT:
        case ENCODING_DEFAULT:
            return 2;
        case ENCODING_INVALID:
        default:
            throw new IllegalArgumentException("Bad audio format " + audioFormat);
        }
    }

    /** @removed */
    public AudioFormat()
    {
        throw new UnsupportedOperationException("There is no valid usage of this constructor");
    }

    /**
     * Private constructor with an ignored argument to differentiate from the removed default ctor
     * @param ignoredArgument
     */
    private AudioFormat(int ignoredArgument) {
    }

    /** @hide */
    public final static int AUDIO_FORMAT_HAS_PROPERTY_NONE = 0x0;
    /** @hide */
    public final static int AUDIO_FORMAT_HAS_PROPERTY_ENCODING = 0x1 << 0;
    /** @hide */
    public final static int AUDIO_FORMAT_HAS_PROPERTY_SAMPLE_RATE = 0x1 << 1;
    /** @hide */
    public final static int AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK = 0x1 << 2;

    private int mEncoding;
    private int mSampleRate;
    private int mChannelMask;
    private int mPropertySetMask;

    /**
     * @hide CANDIDATE FOR PUBLIC API
     * Builder class for {@link AudioFormat} objects.
     */
    public static class Builder {
        private int mEncoding = ENCODING_DEFAULT;
        private int mSampleRate = 0;
        private int mChannelMask = CHANNEL_INVALID;
        private int mPropertySetMask = AUDIO_FORMAT_HAS_PROPERTY_NONE;

        /**
         * Constructs a new Builder with the defaults.
         */
        public Builder() {
        }

        /**
         * Constructs a new Builder from a given {@link AudioFormat}.
         * @param af the {@link AudioFormat} object whose data will be reused in the new Builder.
         */
        public Builder(AudioFormat af) {
            mEncoding = af.mEncoding;
            mSampleRate = af.mSampleRate;
            mChannelMask = af.mChannelMask;
            mPropertySetMask = af.mPropertySetMask;
        }

        /**
         * Combines all of the format characteristics that have been set and return a new
         * {@link AudioFormat} object.
         * @return a new {@link AudioFormat} object
         */
        public AudioFormat build() {
            AudioFormat af = new AudioFormat(1980/*ignored*/);
            af.mEncoding = mEncoding;
            af.mSampleRate = mSampleRate;
            af.mChannelMask = mChannelMask;
            af.mPropertySetMask = mPropertySetMask;
            return af;
        }

        /**
         * Sets the data encoding format.
         * @param encoding one of {@link AudioFormat#ENCODING_DEFAULT},
         *     {@link AudioFormat#ENCODING_PCM_8BIT},
         *     {@link AudioFormat#ENCODING_PCM_16BIT},
         *     {@link AudioFormat#ENCODING_PCM_FLOAT}.
         * @return the same Builder instance.
         * @throws java.lang.IllegalArgumentException
         */
        public Builder setEncoding(@Encoding int encoding) throws IllegalArgumentException {
            switch (encoding) {
                case ENCODING_DEFAULT:
                    mEncoding = ENCODING_PCM_16BIT;
                    break;
                case ENCODING_PCM_8BIT:
                case ENCODING_PCM_16BIT:
                case ENCODING_PCM_FLOAT:
                    mEncoding = encoding;
                    break;
                case ENCODING_INVALID:
                default:
                    throw new IllegalArgumentException("Invalid encoding " + encoding);
            }
            mPropertySetMask |= AUDIO_FORMAT_HAS_PROPERTY_ENCODING;
            return this;
        }

        /**
         * Sets the channel mask.
         * @param channelMask describes the configuration of the audio channels.
         *    <p>For output, the mask should be a combination of
         *    {@link AudioFormat#CHANNEL_OUT_FRONT_LEFT},
         *    {@link AudioFormat#CHANNEL_OUT_FRONT_CENTER},
         *    {@link AudioFormat#CHANNEL_OUT_FRONT_RIGHT},
         *    {@link AudioFormat#CHANNEL_OUT_SIDE_LEFT},
         *    {@link AudioFormat#CHANNEL_OUT_SIDE_RIGHT},
         *    {@link AudioFormat#CHANNEL_OUT_BACK_LEFT},
         *    {@link AudioFormat#CHANNEL_OUT_BACK_RIGHT}.
         *    <p>for input, the mask should be {@link AudioFormat#CHANNEL_IN_MONO} or
         *    {@link AudioFormat#CHANNEL_IN_STEREO}.  {@link AudioFormat#CHANNEL_IN_MONO} is
         *    guaranteed to work on all devices.
         * @return the same Builder instance.
         */
        public Builder setChannelMask(int channelMask) {
            // only validated when used, with input or output context
            mChannelMask = channelMask;
            mPropertySetMask |= AUDIO_FORMAT_HAS_PROPERTY_CHANNEL_MASK;
            return this;
        }

        /**
         * Sets the sample rate.
         * @param sampleRate the sample rate expressed in Hz
         * @return the same Builder instance.
         * @throws java.lang.IllegalArgumentException
         */
        public Builder setSampleRate(int sampleRate) throws IllegalArgumentException {
            if ((sampleRate <= 0) || (sampleRate > 192000)) {
                throw new IllegalArgumentException("Invalid sample rate " + sampleRate);
            }
            mSampleRate = sampleRate;
            mPropertySetMask |= AUDIO_FORMAT_HAS_PROPERTY_SAMPLE_RATE;
            return this;
        }
    }

    @Override
    public String toString () {
        return new String("AudioFormat:"
                + " props=" + mPropertySetMask
                + " enc=" + mEncoding
                + " chan=0x" + Integer.toHexString(mChannelMask)
                + " rate=" + mSampleRate);
    }

    /** @hide */
    @IntDef({
        ENCODING_DEFAULT,
        ENCODING_PCM_8BIT,
        ENCODING_PCM_16BIT,
        ENCODING_PCM_FLOAT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Encoding {}

}
