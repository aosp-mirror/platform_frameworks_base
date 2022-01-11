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
 * Defines the audio source. An audio source defines both a default physical
 * source of audio signal, and a recording configuration. This enum corresponds
 * to MediaRecorder.AudioSource.* constants in the SDK.
 *
 * {@hide}
 */
@Backing(type="int")
@VintfStability
enum AudioSource {
    /**
     * Used as default value in parcelables to indicate that a value was not
     * set. Should never be considered a valid setting, except for backward
     * compatibility scenarios.
     */
    SYS_RESERVED_INVALID = -1,
    /** Default audio source. */
    DEFAULT = 0,
    /** Microphone audio source. */
    MIC = 1,
    /** Voice call uplink (Tx) audio source. */
    VOICE_UPLINK = 2,
    /** Voice call downlink (Rx) audio source. */
    VOICE_DOWNLINK = 3,
    /** Voice call uplink + downlink (duplex) audio source. */
    VOICE_CALL = 4,
    /**
     * Microphone audio source tuned for video recording, with the same
     * orientation as the camera if available.
     */
    CAMCORDER = 5,
    /** Microphone audio source tuned for voice recognition. */
    VOICE_RECOGNITION = 6,
    /**
     * Microphone audio source tuned for voice communications such as VoIP. It
     * will for instance take advantage of echo cancellation or automatic gain
     * control if available.
     */
    VOICE_COMMUNICATION = 7,
    /**
     * Audio source for a submix of audio streams to be presented remotely. An
     * application can use this audio source to capture a mix of audio streams
     * that should be transmitted to a remote receiver such as a Wifi display.
     * While recording is active, these audio streams are redirected to the
     * remote submix instead of being played on the device speaker or headset.
     */
    REMOTE_SUBMIX = 8,
    /**
     * Microphone audio source tuned for unprocessed (raw) sound if available,
     * behaves like DEFAULT otherwise.
     */
    UNPROCESSED = 9,
    /**
     * Source for capturing audio meant to be processed in real time and played
     * back for live performance (e.g karaoke). The capture path will minimize
     * latency and coupling with playback path.
     */
    VOICE_PERFORMANCE = 10,
    /**
     * Source for an echo canceller to capture the reference signal to be
     * canceled. The echo reference signal will be captured as close as
     * possible to the DAC in order to include all post processing applied to
     * the playback path.
     */
    ECHO_REFERENCE = 1997,
    /** Audio source for capturing broadcast FM tuner output. */
    FM_TUNER = 1998,
    /**
     * A low-priority, preemptible audio source for for background software
     * hotword detection. Same tuning as VOICE_RECOGNITION.
     */
    HOTWORD = 1999,
}
