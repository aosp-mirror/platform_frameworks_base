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
 * An AudioDevicePortConfig describes a possible configuration of an output or input device
 * (speaker, headphone, microphone ...).
 * It is used to specify a sink or source when creating a connection with
 * AudioManager.connectAudioPatch().
 * An AudioDevicePortConfig is obtained from AudioDevicePort.buildConfig().
 * @hide
 */

public class AudioDevicePortConfig extends AudioPortConfig {
    @UnsupportedAppUsage
    AudioDevicePortConfig(AudioDevicePort devicePort, int samplingRate, int channelMask,
            int format, AudioGainConfig gain) {
        super((AudioPort)devicePort, samplingRate, channelMask, format, gain);
    }

    AudioDevicePortConfig(AudioDevicePortConfig config) {
        this(config.port(), config.samplingRate(), config.channelMask(), config.format(),
                config.gain());
    }

    /**
     * Returns the audio device port this AudioDevicePortConfig is issued from.
     */
    public AudioDevicePort port() {
        return (AudioDevicePort)mPort;
    }
}

