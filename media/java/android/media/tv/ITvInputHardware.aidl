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

package android.media.tv;

import android.media.tv.TvStreamConfig;
import android.view.KeyEvent;
import android.view.Surface;

/**
 * TvInputService representing a physical port should connect to HAL through this interface.
 * Framework will take care of communication among system services including TvInputManagerService,
 * HdmiControlService, AudioService, etc.
 *
 * @hide
 */
interface ITvInputHardware {
    /**
     * Make the input render on the surface according to the config. In case of HDMI, this will
     * trigger CEC commands for adjusting active HDMI source. Returns true on success.
     */
    boolean setSurface(in Surface surface, in TvStreamConfig config);

    /**
     * Set volume for this stream via AudioGain.
     */
    void setStreamVolume(float volume);

    /**
     * Override default audio sink from audio policy. When override is on, it is
     * TvInputService's responsibility to adjust to audio configuration change
     * (for example, when the audio sink becomes unavailable or more desirable
     * audio sink is detected).
     *
     * @param audioType one of AudioManager.DEVICE_* values. When it's * DEVICE_NONE, override
     *        becomes off.
     * @param audioAddress audio address of the overriding device.
     * @param samplingRate desired sampling rate. Use default when it's 0.
     * @param channelMask desired channel mask. Use default when it's
     *        AudioFormat.CHANNEL_OUT_DEFAULT.
     * @param format desired format. Use default when it's AudioFormat.ENCODING_DEFAULT.
     */
    void overrideAudioSink(int audioType, String audioAddress, int samplingRate, int channelMask,
            int format);
}
