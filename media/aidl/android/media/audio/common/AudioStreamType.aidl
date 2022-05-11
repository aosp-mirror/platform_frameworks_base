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

package android.media.audio.common;

/**
 * Audio stream type describing the intended use case of a stream. Streams
 * must be used in the context of volume management only. For playback type
 * identification purposes, AudioContentType and AudioUsage must be used,
 * similar to how it's done in the SDK.
 *
 * {@hide}
 */
@VintfStability
@Backing(type="int")
enum AudioStreamType {
    /**
     * Used as default value in parcelables to indicate that a value was not
     * set. Should never be considered a valid setting, except for backward
     * compatibility scenarios.
     */
    INVALID = -2,
    /**
     * Indicates that the operation is applied to the "default" stream
     * in this context, e.g. MUSIC in normal device state, or RING if the
     * phone is ringing.
     *
     * Value reserved for system use only. HALs must never return this value to
     * the system or accept it from the system.
     */
    SYS_RESERVED_DEFAULT = -1,
    /** Used to identify the volume of audio streams for phone calls. */
    VOICE_CALL = 0,
    /** Used to identify the volume of audio streams for system sounds. */
    SYSTEM = 1,
    /**
     * Used to identify the volume of audio streams for the phone ring and
     * message alerts.
     */
    RING = 2,
    /** Used to identify the volume of audio streams for music playback. */
    MUSIC = 3,
    /** Used to identify the volume of audio streams for alarms. */
    ALARM = 4,
    /** Used to identify the volume of audio streams for notifications. */
    NOTIFICATION = 5,
    /**
     * Used to identify the volume of audio streams for phone calls when
     * connected via Bluetooth.
     */
    BLUETOOTH_SCO = 6,
    /**
     * Used to identify the volume of audio streams for enforced system sounds
     * in certain countries (e.g camera in Japan).
     */
    ENFORCED_AUDIBLE = 7,
    /** Used to identify the volume of audio streams for DTMF tones. */
    DTMF = 8,
    /**
     * Used to identify the volume of audio streams exclusively transmitted
     * through the speaker (TTS) of the device.
     */
    TTS = 9,
    /**
     * Used to identify the volume of audio streams for accessibility prompts.
     */
    ACCESSIBILITY = 10,
    /**
     * Used to identify the volume of audio streams for virtual assistant.
     */
    ASSISTANT = 11,
    /**
     * Used for dynamic policy output mixes. Only used by the audio policy.
     *
     * Value reserved for system use only. HALs must never return this value to
     * the system or accept it from the system.
     */
    SYS_RESERVED_REROUTING = 12,
    /**
     * Used for audio flinger tracks volume. Only used by the audioflinger.
     *
     * Value reserved for system use only. HALs must never return this value to
     * the system or accept it from the system.
     */
    SYS_RESERVED_PATCH = 13,
    /** Used for the stream corresponding to the call assistant usage. */
    CALL_ASSISTANT = 14,
}
