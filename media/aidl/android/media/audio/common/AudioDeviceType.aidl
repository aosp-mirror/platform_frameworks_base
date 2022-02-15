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
 * The type of the audio device. Only used as part of 'AudioDeviceDescription'
 * structure.
 *
 * Types are divided into "input" and "output" categories. Audio devices that
 * have both audio input and output, for example, headsets, are represented by a
 * pair of input and output device types.
 *
 * The 'AudioDeviceType' intentionally binds together directionality and 'kind'
 * of the device to avoid making them fully orthogonal. This is because not all
 * types of devices are bidirectional, for example, speakers can only be used
 * for output and microphones can only be used for input (at least, in the
 * context of the audio framework).
 *
 * {@hide}
 */
@VintfStability
@Backing(type="int")
enum AudioDeviceType {
    /**
     * "None" type is a "null" value. All fields of 'AudioDeviceDescription'
     * must have default / empty / null values.
     */
    NONE = 0,
    /**
     * The "default" device is used when the client does not have any
     * preference for a particular device.
     */
    IN_DEFAULT = 1,
    /**
     * A device implementing Android Open Accessory protocol.
     * Note: AOAv2 audio support has been deprecated in Android 8.0.
     */
    IN_ACCESSORY = 2,
    /**
     * Input from a DSP front-end proxy device.
     */
    IN_AFE_PROXY = 3,
    /**
     * Used when only the connection protocol is known, e.g. a "HDMI Device."
     */
    IN_DEVICE = 4,
    /**
     * A device providing reference input for echo canceller.
     */
    IN_ECHO_REFERENCE = 5,
    /**
     * FM Tuner input.
     */
    IN_FM_TUNER = 6,
    /**
     * A microphone of a headset.
     */
    IN_HEADSET = 7,
    /**
     * Loopback input.
     */
    IN_LOOPBACK = 8,
    /**
     * The main microphone (the frontal mic on mobile devices).
     */
    IN_MICROPHONE = 9,
    /**
     * The secondary microphone (the back mic on mobile devices).
     */
    IN_MICROPHONE_BACK = 10,
    /**
     * Input from a submix of other streams.
     */
    IN_SUBMIX = 11,
    /**
     * Audio received via the telephone line.
     */
    IN_TELEPHONY_RX = 12,
    /**
     * TV Tuner audio input.
     */
    IN_TV_TUNER = 13,
    /**
     * Input from a phone / table dock.
     */
    IN_DOCK = 14,
    /**
     * The "default" device is used when the client does not have any
     * preference for a particular device.
     */
    OUT_DEFAULT = 129,
    /**
     * A device implementing Android Open Accessory protocol.
     * Note: AOAv2 audio support has been deprecated in Android 8.0.
     */
    OUT_ACCESSORY = 130,
    /**
     * Output from a DSP front-end proxy device.
     */
    OUT_AFE_PROXY = 131,
    /**
     * Car audio system.
     */
    OUT_CARKIT = 132,
    /**
     * Used when only the connection protocol is known, e.g. a "HDMI Device."
     */
    OUT_DEVICE = 133,
    /**
     * The echo canceller device.
     */
    OUT_ECHO_CANCELLER = 134,
    /**
     * The FM Tuner device.
     */
    OUT_FM = 135,
    /**
     * Headphones.
     */
    OUT_HEADPHONE = 136,
    /**
     * Headphones of a headset.
     */
    OUT_HEADSET = 137,
    /**
     * Hearing aid.
     */
    OUT_HEARING_AID = 138,
    /**
     * Secondary line level output.
     */
    OUT_LINE_AUX = 139,
    /**
     * The main speaker.
     */
    OUT_SPEAKER = 140,
    /**
     * The speaker of a mobile device in the case when it is close to the ear.
     */
    OUT_SPEAKER_EARPIECE = 141,
    /**
     * The main speaker with overload / overheating protection.
     */
    OUT_SPEAKER_SAFE = 142,
    /**
     * Output into a submix.
     */
    OUT_SUBMIX = 143,
    /**
     * Output into a telephone line.
     */
    OUT_TELEPHONY_TX = 144,
    /**
     * Output into a speaker of a phone / table dock.
     */
    OUT_DOCK = 145,
    /**
     * Output to a broadcast group.
     */
    OUT_BROADCAST = 146,
}
