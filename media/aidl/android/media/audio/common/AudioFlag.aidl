/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * Defines the audio flags that are used in AudioAttributes
 */
@Backing(type="int")
@VintfStability
enum AudioFlag {
    NONE = 0x0,
    /**
     * Flag defining a behavior where the audibility of the sound will be
     * ensured by the system. To ensure sound audibility, the system only uses
     * built-in speakers or wired headphones and specifically excludes wireless
     * audio devices. Note this flag should only be used for sounds subject to
     * regulatory behaviors in some countries, such as for camera shutter sound,
     * and not for routing behaviors.
     */
    AUDIBILITY_ENFORCED = 0x1 << 0,
    /**
     * Skipping 0x1 << 1. This was previously used for SECURE flag, but because
     * the security feature was never implemented using this flag, and the flag
     * was never made public, this value may be used for another flag.
     */
    /**
     * Flag to enable when the stream is associated with SCO usage.
     * Internal use only for dealing with legacy STREAM_BLUETOOTH_SCO
     */
    SCO = 0x1 << 2,
    /**
     * Flag defining a behavior where the system ensures that the playback of
     * the sound will be compatible with its use as a broadcast for surrounding
     * people and/or devices. Ensures audibility with no or minimal
     * post-processing applied.
     */
    BEACON = 0x1 << 3,
    /**
     * Flag requesting the use of an output stream supporting hardware A/V
     * synchronization.
     */
    HW_AV_SYNC = 0x1 << 4,
    /**
     * Flag requesting capture from the source used for hardware hotword
     * detection. To be used with capture preset MediaRecorder.AudioSource
     * HOTWORD or MediaRecorder.AudioSource.VOICE_RECOGNITION.
     */
    HW_HOTWORD = 0x1 << 5,
    /**
     * Flag requesting audible playback even under limited interruptions.
     */
    BYPASS_INTERRUPTION_POLICY = 0x1 << 6,
    /**
     * Flag requesting audible playback even when the underlying stream is muted
     */
    BYPASS_MUTE = 0x1 << 7,
    /**
     * Flag requesting a low latency path when creating an AudioTrack.
     * When using this flag, the sample rate must match the native sample rate
     * of the device. Effects processing is also unavailable.
     */
    LOW_LATENCY = 0x1 << 8,
    /**
     * Flag requesting a deep buffer path when creating an AudioTrack.
     *
     * A deep buffer path, if available, may consume less power and is
     * suitable for media playback where latency is not a concern.
     */
    DEEP_BUFFER = 0x1 << 9,
    /**
     * Flag specifying that the audio shall not be captured by third-party apps
     * with a MediaProjection.
     */
    NO_MEDIA_PROJECTION = 0x1 << 10,
    /**
     * Flag indicating force muting haptic channels.
     */
    MUTE_HAPTIC = 0x1 << 11,
    /**
     * Flag specifying that the audio shall not be captured by any apps, not
     * even system apps.
     */
    NO_SYSTEM_CAPTURE = 0x1 << 12,
    /**
     * Flag requesting private audio capture.
     */
    CAPTURE_PRIVATE = 0x1 << 13,
    /**
     * Flag indicating the audio content has been processed to provide a virtual
     * multichannel audio experience.
     */
    CONTENT_SPATIALIZED = 0x1 << 14,
    /**
     * Flag indicating the audio content is never to be spatialized.
     */
    NEVER_SPATIALIZE = 0x1 << 15,
    /**
     * Flag indicating the audio is part of a call redirection.
     * Valid for playback and capture.
     */
    CALL_REDIRECTION = 0x1 << 16,
}