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

import android.media.audio.common.AudioChannelLayout;
import android.media.audio.common.AudioEncapsulationType;
import android.media.audio.common.AudioFormatDescription;

/**
 * AudioProfile describes a set of configurations supported for a certain
 * audio format. A profile can be either "static" which means all the
 * configurations are predefined, or "dynamic" which means configurations
 * are queried at run time. Dynamic profiles generally used with detachable
 * devices, e.g. HDMI or USB devices.
 *
 * {@hide}
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
parcelable AudioProfile {
    /** Name is commonly used with static profiles. Can be empty. */
    @utf8InCpp String name;
    /** If the format is set to 'DEFAULT', this indicates a dynamic profile. */
    AudioFormatDescription format;
    /** Can be empty if channel masks are "dynamic". */
    AudioChannelLayout[] channelMasks;
    /** Can be empty if sample rates are "dynamic". */
    int[] sampleRates;
    /** For encoded audio formats, an encapsulation can be specified. */
    AudioEncapsulationType encapsulationType = AudioEncapsulationType.NONE;
}
