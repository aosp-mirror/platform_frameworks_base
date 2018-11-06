/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * @hide
 */
public interface MicrophoneDirection {
    /**
     * @hide
     */
    int MIC_DIRECTION_UNSPECIFIED = 0;

    /**
     * @hide
     */
    int MIC_DIRECTION_FRONT = 1;

    /**
     * @hide
     */
    int MIC_DIRECTION_BACK = 2;

    /**
     * @hide
     */
    int MIC_DIRECTION_EXTERNAL = 3;

    /**
     * Specifies the logical microphone (for processing).
     *
     * @param direction Direction constant (MicrophoneDirection.MIC_DIRECTION_*)
     * @return retval OK if the call is successful, an error code otherwise.
     * @hide
     */
    int setMicrophoneDirection(int direction);

    /**
     * Specifies the zoom factor (i.e. the field dimension) for the selected microphone
     * (for processing). The selected microphone is determined by the use-case for the stream.
     *
     * @param zoom the desired field dimension of microphone capture. Range is from -1 (wide angle),
     * though 0 (no zoom) to 1 (maximum zoom).
     * @return retval OK if the call is successful, an error code otherwise.
     * @hide
     */
    int setMicrophoneFieldDimension(float zoom);
}
