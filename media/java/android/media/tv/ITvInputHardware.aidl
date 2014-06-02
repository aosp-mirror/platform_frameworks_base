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
     * Set volume for this stream via AudioGain. (TBD)
     */
    void setVolume(float volume);

    /**
     * Dispatch key event to HDMI service. The events would be automatically converted to
     * HDMI CEC commands. If the hardware is not representing an HDMI port, this method will fail.
     */
    boolean dispatchKeyEventToHdmi(in KeyEvent event);
}
