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
 * {@hide}
 */
@VintfStability
@Backing(type="int")
enum AudioUsage {
    /**
     * Used as default value in parcelables to indicate that a value was not
     * set. Should never be considered a valid setting, except for backward
     * compatibility scenarios.
     */
    INVALID = -1,
    /**
     * Usage value to use when the usage is unknown.
     */
    UNKNOWN = 0,
    /**
     * Usage value to use when the usage is media, such as music, or movie
     * soundtracks.
     */
    MEDIA = 1,
    /**
     * Usage value to use when the usage is voice communications, such as
     * telephony or VoIP.
     */
    VOICE_COMMUNICATION = 2,
    /**
     * Usage value to use when the usage is in-call signalling, such as with
     * a "busy" beep, or DTMF tones.
     */
    VOICE_COMMUNICATION_SIGNALLING = 3,
    /**
     * Usage value to use when the usage is an alarm (e.g. wake-up alarm).
     */
    ALARM = 4,
    /**
     * Usage value to use when the usage is notification. See other notification
     * usages for more specialized uses.
     */
    NOTIFICATION = 5,
    /**
     * Usage value to use when the usage is telephony ringtone.
     */
    NOTIFICATION_TELEPHONY_RINGTONE = 6,
    /**
     * Usage value to use when the usage is a request to enter/end a
     * communication, such as a VoIP communication or video-conference.
     *
     * Value reserved for system use only. HALs must never return this value to
     * the system or accept it from the system.
     */
    SYS_RESERVED_NOTIFICATION_COMMUNICATION_REQUEST = 7,
    /**
     * Usage value to use when the usage is notification for an "instant"
     * communication such as a chat, or SMS.
     *
     * Value reserved for system use only. HALs must never return this value to
     * the system or accept it from the system.
     */
    SYS_RESERVED_NOTIFICATION_COMMUNICATION_INSTANT = 8,
    /**
     * Usage value to use when the usage is notification for a
     * non-immediate type of communication such as e-mail.
     *
     * Value reserved for system use only. HALs must never return this value to
     * the system or accept it from the system.
     */
    SYS_RESERVED_NOTIFICATION_COMMUNICATION_DELAYED = 9,
    /**
     * Usage value to use when the usage is to attract the user's attention,
     * such as a reminder or low battery warning.
     */
    NOTIFICATION_EVENT = 10,
    /**
     * Usage value to use when the usage is for accessibility, such as with
     * a screen reader.
     */
    ASSISTANCE_ACCESSIBILITY = 11,
    /**
     * Usage value to use when the usage is driving or navigation directions.
     */
    ASSISTANCE_NAVIGATION_GUIDANCE = 12,
    /**
     * Usage value to use when the usage is sonification, such as  with user
     * interface sounds.
     */
    ASSISTANCE_SONIFICATION = 13,
    /**
     * Usage value to use when the usage is for game audio.
     */
    GAME = 14,
    /**
     * Usage value to use when feeding audio to the platform and replacing
     * "traditional" audio source, such as audio capture devices.
     */
    VIRTUAL_SOURCE = 15,
    /**
     * Usage value to use for audio responses to user queries, audio
     * instructions or help utterances.
     */
    ASSISTANT = 16,
    /**
     * Usage value to use for assistant voice interaction with remote caller on
     * Cell and VoIP calls.
     */
    CALL_ASSISTANT = 17,
    /**
     * Usage value to use when the usage is an emergency.
     */
    EMERGENCY = 1000,
    /**
     * Usage value to use when the usage is a safety sound.
     */
    SAFETY = 1001,
    /**
     * Usage value to use when the usage is a vehicle status.
     */
    VEHICLE_STATUS = 1002,
    /**
     * Usage value to use when the usage is an announcement.
     */
    ANNOUNCEMENT = 1003,
}
