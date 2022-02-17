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

import android.media.audio.common.AudioPortMixExtUseCase;

/**
 * Extra parameters which are specified when the audio port is in the mix role.
 *
 * {@hide}
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
parcelable AudioPortMixExt {
    /** I/O handle of the input/output stream. */
    int handle;
    /** Parameters specific to the mix use case. */
    AudioPortMixExtUseCase usecase;
    /**
     * Maximum number of input or output streams that can be simultaneously
     * opened for this port.
     */
    int maxOpenStreamCount;
    /**
     * Maximum number of input or output streams that can be simultaneously
     * active for this port.
     */
    int maxActiveStreamCount;
    /** Mute duration while changing device, when used for output. */
    int recommendedMuteDurationMs;
}
