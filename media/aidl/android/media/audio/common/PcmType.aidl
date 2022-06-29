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
 * The type of the encoding used for representing PCM samples. Only used as
 * part of 'AudioFormatDescription' structure.
 *
 * {@hide}
 */
@VintfStability
@Backing(type="byte")
enum PcmType {
    /**
     * "Default" value used when the type 'AudioFormatDescription' is "default".
     */
    DEFAULT = 0,
    /**
     * Unsigned 8-bit integer.
     */
    UINT_8_BIT = DEFAULT,
    /**
     * Signed 16-bit integer.
     */
    INT_16_BIT = 1,
    /**
     * Signed 32-bit integer.
     */
    INT_32_BIT = 2,
    /**
     * Q8.24 fixed point format.
     */
    FIXED_Q_8_24 = 3,
    /**
     * IEEE 754 32-bit floating point format.
     */
    FLOAT_32_BIT = 4,
    /**
     * Signed 24-bit integer.
     */
    INT_24_BIT = 5,
}
