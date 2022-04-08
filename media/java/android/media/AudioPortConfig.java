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

package android.media;

import android.compat.annotation.UnsupportedAppUsage;

/**
 * An AudioPortConfig contains a possible configuration of an audio port chosen
 * among all possible attributes described by an AudioPort.
 * An AudioPortConfig is created by AudioPort.buildConfiguration().
 * AudioPorts are used to specify the sources and sinks of a patch created
 * with AudioManager.connectAudioPatch().
 * Several specialized versions of AudioPortConfig exist to handle different categories of
 * audio ports and their specific attributes:
 * - AudioDevicePortConfig for input (e.g micropohone) and output devices (e.g speaker)
 * - AudioMixPortConfig for input or output streams of the audio framework.
 * @hide
 */

public class AudioPortConfig {
    @UnsupportedAppUsage
    final AudioPort mPort;
    @UnsupportedAppUsage
    private final int mSamplingRate;
    @UnsupportedAppUsage
    private final int mChannelMask;
    @UnsupportedAppUsage
    private final int mFormat;
    @UnsupportedAppUsage
    private final AudioGainConfig mGain;

    // mConfigMask indicates which fields in this configuration should be
    // taken into account. Used with AudioSystem.setAudioPortConfig()
    // framework use only.
    static final int SAMPLE_RATE  = 0x1;
    static final int CHANNEL_MASK = 0x2;
    static final int FORMAT       = 0x4;
    static final int GAIN         = 0x8;
    @UnsupportedAppUsage
    int mConfigMask;

    @UnsupportedAppUsage
    AudioPortConfig(AudioPort port, int samplingRate, int channelMask, int format,
            AudioGainConfig gain) {
        mPort = port;
        mSamplingRate = samplingRate;
        mChannelMask = channelMask;
        mFormat = format;
        mGain = gain;
        mConfigMask = 0;
    }

    /**
     * Returns the audio port this AudioPortConfig is issued from.
     */
    @UnsupportedAppUsage
    public AudioPort port() {
        return mPort;
    }

    /**
     * Sampling rate configured for this AudioPortConfig.
     */
    public int samplingRate() {
        return mSamplingRate;
    }

    /**
     * Channel mask configuration (e.g AudioFormat.CHANNEL_CONFIGURATION_STEREO).
     */
    public int channelMask() {
        return mChannelMask;
    }

    /**
     * Audio format configuration (e.g AudioFormat.ENCODING_PCM_16BIT).
     */
    public int format() {
        return mFormat;
    }

    /**
     * The gain configuration if this port supports gain control, null otherwise
     */
    public AudioGainConfig gain() {
        return mGain;
    }

    @Override
    public String toString() {
        return "{mPort:" + mPort
                + ", mSamplingRate:" + mSamplingRate
                + ", mChannelMask: " + mChannelMask
                + ", mFormat:" + mFormat
                + ", mGain:" + mGain
                + "}";
    }
}
