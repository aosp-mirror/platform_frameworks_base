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
 * Major modes for a mobile device. The current mode setting affects audio
 * routing.
 *
 * {@hide}
 */
@Backing(type="int")
@VintfStability
enum AudioMode {
    /**
     * Used as default value in parcelables to indicate that a value was not
     * set. Should never be considered a valid setting, except for backward
     * compatibility scenarios.
     */
    SYS_RESERVED_INVALID = -2,
    /**
     * Value reserved for system use only. HALs must never return this value to
     * the system or accept it from the system.
     */
    SYS_RESERVED_CURRENT = -1,
    /** Normal mode (no call in progress). */
    NORMAL = 0,
    /** Mobile device is receiving an incoming connection request. */
    RINGTONE = 1,
    /** Calls handled by the telephony stack (PSTN). */
    IN_CALL = 2,
    /** Calls handled by apps (VoIP). */
    IN_COMMUNICATION = 3,
    /** Call screening in progress. */
    CALL_SCREEN = 4,
    /** PSTN Call redirection  in progress. */
    SYS_RESERVED_CALL_REDIRECT = 5,
    /** VoIP Call redirection  in progress. */
    SYS_RESERVED_COMMUNICATION_REDIRECT = 6,
}
