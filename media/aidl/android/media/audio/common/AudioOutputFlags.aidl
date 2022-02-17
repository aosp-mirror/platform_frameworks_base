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
 * Specifies options applicable to audio output. These can be functional
 * requests or performance requests. These flags apply both to audio ports and
 * audio streams. Flags specified for an audio stream are usually used to find
 * the best matching audio port for it.
 *
 * {@hide}
 */
@VintfStability
@Backing(type="int")
enum AudioOutputFlags{
    /**
     * Output must not be altered by the framework, it bypasses software mixers.
     */
    DIRECT = 0,
    /**
     * When used with audio ports, indicates the "main" (primary) port. This
     * port is opened by default and receives routing, audio mode and volume
     * controls related to voice calls.
     */
    PRIMARY = 1,
    /**
     * Output is optimized for decreasing audio latency.
     */
    FAST = 2,
    /**
     * Output is optimized for having the low power consumption.
     */
    DEEP_BUFFER = 3,
    /**
     * Output is compressed audio format, intended for hardware decoding.
     */
    COMPRESS_OFFLOAD = 4,
    /**
     * Write operations must return as fast as possible instead of
     * being blocked until all provided data has been consumed.
     */
    NON_BLOCKING = 5,
    /**
     * Output stream contains AV synchronization markers embedded.
     */
    HW_AV_SYNC = 6,
    /**
     * Used to support ultrasonic communication with beacons.
     * Note: "TTS" here means "Transmitted Through Speaker",
     * not "Text-to-Speech".
     */
    TTS = 7,
    /**
     * Output stream should only have minimal signal processing applied.
     */
    RAW = 8,
    /**
     * Output stream needs to be synchronized with an input stream.
     */
    SYNC = 9,
    /**
     * Output stream is encoded according to IEC958.
     */
    IEC958_NONAUDIO = 10,
    /**
     * Output must not be altered by the framework and hardware.
     */
    DIRECT_PCM = 11,
    /**
     * Output uses MMAP no IRQ mode--direct memory mapping with the hardware.
     */
    MMAP_NOIRQ = 12,
    /**
     * Output is used for transmitting VoIP audio.
     */
    VOIP_RX = 13,
    /**
     * Output is used for music playback during telephony calls.
     */
    INCALL_MUSIC = 14,
    /**
     * The rendered must ignore any empty blocks between compressed audio
     * tracks.
     */
    GAPLESS_OFFLOAD = 15,
    /**
     * Output is used for spatial audio.
     */
    SPATIALIZER = 16,
    /**
     * Output is used for transmitting ultrasound audio.
     */
    ULTRASOUND = 17,
}
