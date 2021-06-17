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

#ifndef ANDROID_MEDIA_EXTRAAUDIODESCRIPTOR_H
#define ANDROID_MEDIA_EXTRAAUDIODESCRIPTOR_H

#include <system/audio.h>
#include <utils/Errors.h>

namespace android {

// keep these values in sync with ExtraAudioDescriptor.java
#define STANDARD_NONE 0
#define STANDARD_EDID 1

static inline status_t audioStandardFromNative(audio_standard_t nStandard, int* standard) {
    status_t result = NO_ERROR;
    switch (nStandard) {
        case AUDIO_STANDARD_NONE:
            *standard = STANDARD_NONE;
            break;
        case AUDIO_STANDARD_EDID:
            *standard = STANDARD_EDID;
            break;
        default:
            result = BAD_VALUE;
    }
    return result;
}

} // namespace android

#endif // ANDROID_MEDIA_EXTRAAUDIODESCRIPTOR_H