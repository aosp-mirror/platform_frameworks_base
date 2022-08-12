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

import android.media.audio.common.AudioContentType;
import android.media.audio.common.AudioFlag;
import android.media.audio.common.AudioSource;
import android.media.audio.common.AudioUsage;

/**
 * AudioAttributes give information about an audio stream that is more
 * descriptive than stream type alone.
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
parcelable AudioAttributes {
    /**
     * Classifies the content of the audio signal using categories such as
     * speech or music
     */
    AudioContentType contentType = AudioContentType.UNKNOWN;
    /**
     * Classifies the intended use of the audio signal using categories such as
     * alarm or ringtone
     */
    AudioUsage usage = AudioUsage.UNKNOWN;
    /**
     * Classifies the audio source using categories such as voice uplink or
     * remote submix
     */
    AudioSource source = AudioSource.DEFAULT;
    /**
     * Bitmask describing how playback is to be affected.
     */
    int flags = AudioFlag.NONE;
    /**
     * Tag is an additional use case qualifier complementing AudioUsage and
     * AudioContentType. Tags are set by vendor-specific applications and must
     * be prefixed by "VX_". Vendors must namespace their tag names using the
     * name of their company to avoid conflicts. The namespace must use at least
     * three characters, and must go directly after the "VX_" prefix.
     * For example: "VX_MYCOMPANY_VR".
     */
    @utf8InCpp String[] tags;
}