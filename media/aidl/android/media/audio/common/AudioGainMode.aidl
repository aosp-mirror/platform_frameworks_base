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
 * Type of gain control exposed by an audio port. The values are
 * indexes of bits in a bitmask.
 *
 * {@hide}
 */
@VintfStability
@Backing(type="byte")
enum AudioGainMode {
    /** Gain is the same for all channels. */
    JOINT = 0,
    /** The gain is set individually for each channel. */
    CHANNELS = 1,
    /** Ramping is applied. */
    RAMP = 2,
}
