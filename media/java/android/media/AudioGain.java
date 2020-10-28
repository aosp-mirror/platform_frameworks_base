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
import android.os.Build;

/**
 * The AudioGain describes a gain controller. Gain controllers are exposed by
 * audio ports when the gain is configurable at this port's input or output.
 * Gain values are expressed in millibels.
 * A gain controller has the following attributes:
 * - mode: defines modes of operation or features
 *    MODE_JOINT: all channel gains are controlled simultaneously
 *    MODE_CHANNELS: each channel gain is controlled individually
 *    MODE_RAMP: ramps can be applied when gain changes
 * - channel mask: indicates for which channels the gain can be controlled
 * - min value: minimum gain value in millibel
 * - max value: maximum gain value in millibel
 * - default value: gain value after reset in millibel
 * - step value: granularity of gain control in millibel
 * - min ramp duration: minimum ramp duration in milliseconds
 * - max ramp duration: maximum ramp duration in milliseconds
 *
 * This object is always created by the framework and read only by applications.
 * Applications get a list of AudioGainDescriptors from AudioPortDescriptor.gains() and can build a
 * valid gain configuration from AudioGain.buildConfig()
 * @hide
 */
public class AudioGain {

    /**
     * Bit of AudioGain.mode() field indicating that
     * all channel gains are controlled simultaneously
     */
    public static final int MODE_JOINT = 1;
    /**
     * Bit of AudioGain.mode() field indicating that
     * each channel gain is controlled individually
     */
    public static final int MODE_CHANNELS = 2;
    /**
     * Bit of AudioGain.mode() field indicating that
     * ramps can be applied when gain changes. The type of ramp (linear, log etc...) is
     * implementation specific.
     */
    public static final int MODE_RAMP = 4;

    private final int mIndex;
    private final int mMode;
    private final int mChannelMask;
    private final int mMinValue;
    private final int mMaxValue;
    private final int mDefaultValue;
    private final int mStepValue;
    private final int mRampDurationMinMs;
    private final int mRampDurationMaxMs;

    // The channel mask passed to the constructor is as specified in AudioFormat
    // (e.g. AudioFormat.CHANNEL_OUT_STEREO)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    AudioGain(int index, int mode, int channelMask,
                        int minValue, int maxValue, int defaultValue, int stepValue,
                        int rampDurationMinMs, int rampDurationMaxMs) {
        mIndex = index;
        mMode = mode;
        mChannelMask = channelMask;
        mMinValue = minValue;
        mMaxValue = maxValue;
        mDefaultValue = defaultValue;
        mStepValue = stepValue;
        mRampDurationMinMs = rampDurationMinMs;
        mRampDurationMaxMs = rampDurationMaxMs;
    }

    /**
     * Bit field indicating supported modes of operation
     */
    public int mode() {
        return mMode;
    }

    /**
     * Indicates for which channels the gain can be controlled
     * (e.g. AudioFormat.CHANNEL_OUT_STEREO)
     */
    public int channelMask() {
        return mChannelMask;
    }

    /**
     * Minimum gain value in millibel
     */
    public int minValue() {
        return mMinValue;
    }

    /**
     * Maximum gain value in millibel
     */
    public int maxValue() {
        return mMaxValue;
    }

    /**
     * Default gain value in millibel
     */
    public int defaultValue() {
        return mDefaultValue;
    }

    /**
     * Granularity of gain control in millibel
     */
    public int stepValue() {
        return mStepValue;
    }

    /**
     * Minimum ramp duration in milliseconds
     * 0 if MODE_RAMP not set
     */
    public int rampDurationMinMs() {
        return mRampDurationMinMs;
    }

    /**
     * Maximum ramp duration in milliseconds
     * 0 if MODE_RAMP not set
     */
    public int rampDurationMaxMs() {
        return mRampDurationMaxMs;
    }

    /**
     * Build a valid gain configuration for this gain controller for use by
     * AudioPortDescriptor.setGain()
     * @param mode: desired mode of operation
     * @param channelMask: channels of which the gain should be modified.
     * @param values: gain values for each channels.
     * @param rampDurationMs: ramp duration if mode MODE_RAMP is set.
     * ignored if MODE_JOINT.
     */
    public AudioGainConfig buildConfig(int mode, int channelMask,
                                       int[] values, int rampDurationMs) {
        //TODO: check params here
        return new AudioGainConfig(mIndex, this, mode, channelMask, values, rampDurationMs);
    }
}
