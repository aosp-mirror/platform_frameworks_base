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

import android.media.audio.common.AudioGain;
import android.media.audio.common.AudioIoFlags;
import android.media.audio.common.AudioPortExt;
import android.media.audio.common.AudioProfile;
import android.media.audio.common.ExtraAudioDescriptor;

/**
 * Audio port structure describes the capabilities of an audio port
 * as well as its current configuration.
 *
 * {@hide}
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
parcelable AudioPort {
    /**
     * Unique identifier of the port within a HAL module.
     */
    int id;
    /**
     * Human-readable name describing the function of the port.
     * E.g. "telephony_tx" or "fm_tuner".
     */
    @utf8InCpp String name;
    /**
     * AudioProfiles supported by this port: format, rates, channels.
     */
    AudioProfile[] profiles;
    /**
     * I/O feature flags.
     */
    AudioIoFlags flags;
    /**
     * ExtraAudioDescriptors supported by this port. Used for formats not
     * recognized by the platform. The audio capability is described by a
     * hardware descriptor.
     */
    ExtraAudioDescriptor[] extraAudioDescriptors;
    /** Gain controllers. */
    AudioGain[] gains;
    /** Extra parameters depending on the port role. */
    AudioPortExt ext;
}
