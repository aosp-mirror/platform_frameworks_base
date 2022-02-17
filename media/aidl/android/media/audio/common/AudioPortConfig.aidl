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
import android.media.audio.common.AudioFormatDescription;
import android.media.audio.common.AudioGainConfig;
import android.media.audio.common.AudioIoFlags;
import android.media.audio.common.AudioPortExt;
import android.media.audio.common.Int;

/**
 * Audio port configuration structure specifies a particular configuration
 * of an audio port.
 *
 * {@hide}
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
parcelable AudioPortConfig {
    /**
     * Port config unique ID. This field is set to a non-zero value when it is
     * needed to select a previously reported port config and apply new
     * configuration to it.
     */
    int id;
    /**
     * The ID of the AudioPort instance this configuration applies to.
     */
    int portId;
    /** Sample rate in Hz. Can be left unspecified. */
    @nullable Int sampleRate;
    /** Channel mask. Can be left unspecified. */
    @nullable AudioChannelLayout channelMask;
    /** Format. Can be left unspecified. */
    @nullable AudioFormatDescription format;
    /** Gain to apply. Can be left unspecified. */
    @nullable AudioGainConfig gain;
    /** I/O feature flags. Can be left unspecified. */
    @nullable AudioIoFlags flags;
    /** Extra parameters depending on the port role. */
    AudioPortExt ext;
}
