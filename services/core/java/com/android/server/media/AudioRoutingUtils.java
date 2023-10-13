/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.media;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.audiopolicy.AudioProductStrategy;

/** Holds utils related to routing in the audio framework. */
/* package */ final class AudioRoutingUtils {

    /* package */ static final AudioAttributes ATTRIBUTES_MEDIA =
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();

    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    @Nullable
    /* package */ static AudioProductStrategy getMediaAudioProductStrategy() {
        for (AudioProductStrategy strategy : AudioManager.getAudioProductStrategies()) {
            if (strategy.supportsAudioAttributes(AudioRoutingUtils.ATTRIBUTES_MEDIA)) {
                return strategy;
            }
        }
        return null;
    }

    private AudioRoutingUtils() {
        // no-op to prevent instantiation.
    }
}
