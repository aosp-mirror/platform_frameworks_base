/**
 * Copyright (c) 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.os.ExternalVibration;

/**
 * The communication channel by which an external system that wants to control the system
 * vibrator can notify the vibrator subsystem.
 *
 * Some vibrators can be driven via multiple paths (e.g. as an audio channel) in addition to
 * the usual interface, but we typically only want one vibration at a time playing because they
 * don't mix well. In order to synchronize the two places where vibration might be controlled,
 * we provide this interface so the vibrator subsystem has a chance to:
 *
 * 1) Decide whether the current vibration should play based on the current system policy.
 * 2) Stop any currently on-going vibrations.
 * {@hide}
 */
interface IExternalVibratorService {
    const int SCALE_MUTE = -100;
    const int SCALE_VERY_LOW = -2;
    const int SCALE_LOW = -1;
    const int SCALE_NONE = 0;
    const int SCALE_HIGH = 1;
    const int SCALE_VERY_HIGH = 2;

    /**
     * A method called by the external system to start a vibration.
     *
     * If this returns {@code SCALE_MUTE}, then the vibration should <em>not</em> play. If this
     * returns any other scale level, then any currently playing vibration controlled by the
     * requesting system must be muted and this vibration can begin playback.
     *
     * Note that the IExternalVibratorService implementation will not call mute on any currently
     * playing external vibrations in order to avoid re-entrancy with the system on the other side.
     *
     * @param vibration An ExternalVibration
     *
     * @return {@code SCALE_MUTE} if the external vibration should not play, and any other scale
     *         level if it should.
     */
    int onExternalVibrationStart(in ExternalVibration vib);

    /**
     * A method called by the external system when a vibration no longer wants to play.
     */
    void onExternalVibrationStop(in ExternalVibration vib);
}
