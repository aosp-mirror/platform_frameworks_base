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

#ifndef ANDROID_MEDIA_AUDIOMIXERATTRIBUTES_H
#define ANDROID_MEDIA_AUDIOMIXERATTRIBUTES_H

#include <system/audio.h>

// Keep sync with AudioMixerAttributes.java
#define MIXER_BEHAVIOR_DEFAULT 0
#define MIXER_BEHAVIOR_BIT_PERFECT 1
// Invalid value is not added in JAVA API, but keep sync with native value
#define MIXER_BEHAVIOR_INVALID -1

static inline audio_mixer_behavior_t audioMixerBehaviorToNative(int mixerBehavior) {
    switch (mixerBehavior) {
        case MIXER_BEHAVIOR_DEFAULT:
            return AUDIO_MIXER_BEHAVIOR_DEFAULT;
        case MIXER_BEHAVIOR_BIT_PERFECT:
            return AUDIO_MIXER_BEHAVIOR_BIT_PERFECT;
        default:
            return AUDIO_MIXER_BEHAVIOR_INVALID;
    }
}

static inline jint audioMixerBehaviorFromNative(audio_mixer_behavior_t mixerBehavior) {
    switch (mixerBehavior) {
        case AUDIO_MIXER_BEHAVIOR_DEFAULT:
            return MIXER_BEHAVIOR_DEFAULT;
        case AUDIO_MIXER_BEHAVIOR_BIT_PERFECT:
            return MIXER_BEHAVIOR_BIT_PERFECT;
        case AUDIO_MIXER_BEHAVIOR_INVALID:
        default:
            return MIXER_BEHAVIOR_INVALID;
    }
}

#endif
