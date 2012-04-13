/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.input;

/** @hide */
interface IInputDevicesChangedListener {
    /* Called when input devices changed, such as a device being added,
     * removed or changing configuration.
     *
     * The parameter is an array of pairs (deviceId, generation) indicating the current
     * device id and generation of all input devices.  The client can determine what
     * has happened by comparing the result to its prior observations.
     */
    oneway void onInputDevicesChanged(in int[] deviceIdAndGeneration);
}
