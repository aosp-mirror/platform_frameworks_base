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
 * The type of the audio format. Only used as part of 'AudioFormatDescription'
 * structure.
 *
 * {@hide}
 */
@VintfStability
@Backing(type="byte")
enum AudioFormatType {
    /**
     * "Default" type is used when the client does not care about the actual
     * format. All fields of 'AudioFormatDescription' must have default / empty
     * / null values.
     */
    DEFAULT = 0,
    /**
     * When the 'encoding' field of 'AudioFormatDescription' is not empty, it
     * specifies the codec used for bitstream (non-PCM) data. It is also used
     * in the case when the bitstream data is encapsulated into a PCM stream,
     * see the documentation for 'AudioFormatDescription'.
     */
    NON_PCM = DEFAULT,
    /**
     * PCM type. The 'pcm' field of 'AudioFormatDescription' is used to specify
     * the actual sample size and representation.
     */
    PCM = 1,
    /**
     * Value reserved for system use only. HALs must never return this value to
     * the system or accept it from the system.
     */
    SYS_RESERVED_INVALID = -1,
}
