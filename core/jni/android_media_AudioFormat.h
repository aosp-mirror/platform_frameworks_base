/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef ANDROID_MEDIA_AUDIOFORMAT_H
#define ANDROID_MEDIA_AUDIOFORMAT_H

#include <system/audio.h>

// keep these values in sync with AudioFormat.java
#define ENCODING_PCM_16BIT 2
#define ENCODING_PCM_8BIT  3

static inline audio_format_t audioFormatToNative(int audioFormat)
{
    switch (audioFormat) {
    case ENCODING_PCM_16BIT:
        return AUDIO_FORMAT_PCM_16_BIT;
    case ENCODING_PCM_8BIT:
        return AUDIO_FORMAT_PCM_8_BIT;
    default:
        return AUDIO_FORMAT_INVALID;
    }
}

#endif // ANDROID_MEDIA_AUDIOFORMAT_H
