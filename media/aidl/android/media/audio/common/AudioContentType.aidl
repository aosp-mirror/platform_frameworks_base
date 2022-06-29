/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * Content type specifies "what" is playing. The content type expresses the
 * general category of the content: speech, music, movie audio, etc.
 * This enum corresponds to AudioAttributes.CONTENT_TYPE_* constants in the SDK.
 *
 * {@hide}
 */
@Backing(type="int")
@VintfStability
enum AudioContentType {
    /**
     * Content type value to use when the content type is unknown, or other than
     * the ones defined.
     */
    UNKNOWN = 0,
    /**
     * Content type value to use when the content type is speech.
     */
    SPEECH = 1,
    /**
     * Content type value to use when the content type is music.
     */
    MUSIC = 2,
    /**
     * Content type value to use when the content type is a soundtrack,
     * typically accompanying a movie or TV program.
     */
    MOVIE = 3,
    /**
     * Content type value to use when the content type is a sound used to
     * accompany a user action, such as a beep or sound effect expressing a key
     * click, or event, such as the type of a sound for a bonus being received
     * in a game. These sounds are mostly synthesized or short Foley sounds.
     */
    SONIFICATION = 4,
    /**
     * Content type value to use when the content type is ultrasound.
     */
    ULTRASOUND = 1997,
}
