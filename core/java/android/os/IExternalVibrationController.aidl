/*
 * Copyright 2018, The Android Open Source Project
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

/**
 * {@hide}
 */

interface IExternalVibrationController {
    /**
     * A method to ask a currently playing vibration to mute (i.e. not vibrate).
     *
     * This method is only valid from the time that
     * {@link IExternalVibratorService#onExternalVibrationStart} returns until
     * {@link IExternalVibratorService#onExternalVibrationStop} returns.
     *
     * @return whether the mute operation was successful
     */
    boolean mute();

    /**
     * A method to ask a currently playing vibration to unmute (i.e. start vibrating).
     *
     * This method is only valid from the time that
     * {@link IExternalVibratorService#onExternalVibrationStart} returns until
     * {@link IExternalVibratorService#onExternalVibrationStop} returns.
     *
     * @return whether the unmute operation was successful
     */
    boolean unmute();
}
