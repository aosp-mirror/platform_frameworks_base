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

/**
 * The AudioGainConfig is used by APIs setting or getting values on a given gain
 * controller. It contains a valid configuration (value, channels...) for a gain controller
 * exposed by an audio port.
 * @see AudioGain
 * @see AudioPort
 * @hide
 */
public class AudioGainConfig {
    AudioGain mGain;
    private final int mIndex;
    private final int mMode;
    private final int mChannelMask;
    private final int mValues[];
    private final int mRampDurationMs;

    AudioGainConfig(int index, AudioGain gain, int mode, int channelMask,
            int[] values, int rampDurationMs) {
        mIndex = index;
        mGain = gain;
        mMode = mode;
        mChannelMask = channelMask;
        mValues = values;
        mRampDurationMs = rampDurationMs;
    }

    /**
     * get the index of the parent gain.
     * frameworks use only.
     */
    int index() {
        return mIndex;
    }

    /**
     * Bit field indicating requested modes of operation. See {@link AudioGain#MODE_JOINT},
     * {@link AudioGain#MODE_CHANNELS}, {@link AudioGain#MODE_RAMP}
     */
    public int mode() {
        return mMode;
    }

    /**
     * Indicates for which channels the gain is set.
     * See {@link AudioFormat#CHANNEL_OUT_STEREO}, {@link AudioFormat#CHANNEL_OUT_MONO} ...
     */
    public int channelMask() {
        return mChannelMask;
    }

    /**
     * Gain values for each channel in the order of bits set in
     * channelMask() from LSB to MSB
     */
    public int[] values() {
        return mValues;
    }

    /**
     * Ramp duration in milliseconds. N/A if mode() does not
     * specify MODE_RAMP.
     */
    public int rampDurationMs() {
        return mRampDurationMs;
    }
}
