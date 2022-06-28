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

import android.media.audio.common.AudioDevice;
import android.media.audio.common.AudioMMapPolicy;

/**
 * Audio MMAP policy info describes how an aaudio MMAP feature can be
 * used on a particular device.
 * {@hide}
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
parcelable AudioMMapPolicyInfo {
    /**
     * The audio device.
     */
    AudioDevice device;
    /**
     * The aaudio mmap policy for the audio device.
     */
    AudioMMapPolicy mmapPolicy = AudioMMapPolicy.UNSPECIFIED;
}
