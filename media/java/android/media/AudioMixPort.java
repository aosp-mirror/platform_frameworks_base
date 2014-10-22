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
 * The AudioMixPort is a specialized type of AudioPort
 * describing an audio mix or stream at an input or output stream of the audio
 * framework.
 * @see AudioPort
 * @hide
 */

public class AudioMixPort extends AudioPort {

    AudioMixPort(AudioHandle handle, int role, int[] samplingRates, int[] channelMasks,
            int[] formats, AudioGain[] gains) {
        super(handle, role, samplingRates, channelMasks, formats, gains);
    }

    /**
     * Build a specific configuration of this audio mix port for use by methods
     * like AudioManager.connectAudioPatch().
     */
    public AudioMixPortConfig buildConfig(int samplingRate, int channelMask, int format,
                                       AudioGainConfig gain) {
        return new AudioMixPortConfig(this, samplingRate, channelMask, format, gain);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof AudioMixPort)) {
            return false;
        }
        return super.equals(o);
    }

}
