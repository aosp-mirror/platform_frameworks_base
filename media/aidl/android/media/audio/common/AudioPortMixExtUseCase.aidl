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

import android.media.audio.common.AudioSource;
import android.media.audio.common.AudioStreamType;

/**
 * Provides additional information depending on the type of the audio port
 * when it is used in the mix role.
 *
 * {@hide}
 */
@JavaDerive(equals=true, toString=true)
@VintfStability
union AudioPortMixExtUseCase {
    /**
     * This is the default case for this union. The value is ignored.
     */
    boolean unspecified;
    /**
     * This case applies when the audio port is a source.
     * The value specifies the destination stream type.
     */
    AudioStreamType stream;
    /**
     * This case applies when the audio port is a sink.
     * The value specifies the source.
     */
    AudioSource source;
}
