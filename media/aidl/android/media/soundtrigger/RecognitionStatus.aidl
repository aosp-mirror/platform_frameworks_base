/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.media.soundtrigger;

/**
 * A status for indicating the type of a recognition event.
 * {@hide}
 */
@VintfStability
@Backing(type="int")
enum RecognitionStatus {
    /**
     * Used as default value in parcelables to indicate that a value was not set.
     * Should never be considered a valid setting, except for backward compatibility scenarios.
     */
    INVALID = -1,
    /** Recognition success. */
    SUCCESS = 0,
    /** Recognition aborted (e.g. capture preempted by another use-case. */
    ABORTED = 1,
    /** Recognition failure. */
    FAILURE = 2,
    /**
    * Recognition event was triggered by a forceRecognitionEvent request, not by the DSP.
    * Note that forced detections *do not* stop the active recognition, unlike the other types.
    */
    FORCED = 3
}
