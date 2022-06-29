/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.audio.common;

/**
 * Audio encapsulation type is used to describe if the audio data should be sent
 * with a particular encapsulation type or not. This enum corresponds to
 * AudioProfile.AUDIO_ENCAPSULATION_* constants in the SDK.
 *
 * {@hide}
 */
@Backing(type="int")
@VintfStability
enum AudioEncapsulationType {
    /** No encapsulation type is specified. */
    NONE = 0,
    /** Encapsulation used the format defined in the standard IEC 61937. */
    IEC61937 = 1,
}
