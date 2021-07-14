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

import java.util.List;

/**
 * The AudioMixPort is a specialized type of AudioPort
 * describing an audio mix or stream at an input or output stream of the audio
 * framework.
 * In addition to base audio port attributes, the mix descriptor contains:
 * - the unique audio I/O handle assigned by AudioFlinger to this mix.
 * @see AudioPort
 * @hide
 */

public class AudioMixPort extends AudioPort {

    private final int mIoHandle;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    AudioMixPort(AudioHandle handle, int ioHandle, int role, String deviceName,
            int[] samplingRates, int[] channelMasks, int[] channelIndexMasks,
            int[] formats, AudioGain[] gains) {
        super(handle, role, deviceName, samplingRates, channelMasks, channelIndexMasks,
                formats, gains);
        mIoHandle = ioHandle;
    }

    AudioMixPort(AudioHandle handle, int ioHandle, int role, String deviceName,
            List<AudioProfile> profiles, AudioGain[] gains) {
        super(handle, role, deviceName, profiles, gains, null);
        mIoHandle = ioHandle;
    }

    /**
     * Build a specific configuration of this audio mix port for use by methods
     * like AudioManager.connectAudioPatch().
     */
    public AudioMixPortConfig buildConfig(int samplingRate, int channelMask, int format,
                                       AudioGainConfig gain) {
        return new AudioMixPortConfig(this, samplingRate, channelMask, format, gain);
    }

    /**
     * Get the device type (e.g AudioManager.DEVICE_OUT_SPEAKER)
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int ioHandle() {
        return mIoHandle;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof AudioMixPort)) {
            return false;
        }
        AudioMixPort other = (AudioMixPort)o;
        if (mIoHandle != other.ioHandle()) {
            return false;
        }

        return super.equals(o);
    }

}
