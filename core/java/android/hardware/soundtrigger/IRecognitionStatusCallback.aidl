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

package android.hardware.soundtrigger;

/**
 * @hide
 */
oneway interface IRecognitionStatusCallback {
    /**
     * Called when the keyphrase is spoken.
     *
     * @param data Optional trigger audio data, if it was requested and is available.
     *        TODO: See if the data being passed in works well, if not use shared memory.
     *        This *MUST* not exceed 100K.
     */
    void onDetected(in byte[] data);
    /**
     * Called when the detection for the associated keyphrase starts.
     */
    void onDetectionStarted();
    /**
     * Called when the detection for the associated keyphrase stops.
     */
    void onDetectionStopped();
}