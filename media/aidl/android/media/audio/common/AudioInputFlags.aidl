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
 * Specifies options applicable to audio input. These can be functional
 * requests or performance requests. These flags apply both to audio ports and
 * audio streams. Flags specified for an audio stream are usually used to find
 * the best matching audio port for it.
 *
 * {@hide}
 */
@VintfStability
@Backing(type="int")
enum AudioInputFlags {
    /**
     * Input is optimized for decreasing audio latency.
     */
    FAST = 0,
    /**
     * Input is for capturing "hotword" audio commands.
     */
    HW_HOTWORD = 1,
    /**
     * Input stream should only have minimal signal processing applied.
     */
    RAW = 2,
    /**
     * Input stream needs to be synchronized with an output stream.
     */
    SYNC = 3,
    /**
     * Input uses MMAP no IRQ mode--direct memory mapping with the hardware.
     */
    MMAP_NOIRQ = 4,
    /**
     * Input is used for receiving VoIP audio.
     */
    VOIP_TX = 5,
    /**
     * Input stream contains AV synchronization markers embedded.
     */
    HW_AV_SYNC = 6,
    /**
     * Input contains an encoded audio stream.
     */
    DIRECT = 7,
    /**
     * Input is for capturing "ultrasound" audio commands.
     */
    ULTRASOUND = 8,
}
