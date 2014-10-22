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

#ifndef ANDROID_MEDIA_AUDIOERRORS_H
#define ANDROID_MEDIA_AUDIOERRORS_H

#include <utils/Errors.h>

namespace android {
// status codes used by JAVA APIs. Translation from native error codes is done by
// nativeToJavaStatus()
// must be kept in sync with values in
// frameworks/base/media/java/android/media/AudioSystem.java.
enum {
    AUDIO_JAVA_SUCCESS            = 0,
    AUDIO_JAVA_ERROR              = -1,
    AUDIO_JAVA_BAD_VALUE          = -2,
    AUDIO_JAVA_INVALID_OPERATION  = -3,
    AUDIO_JAVA_PERMISSION_DENIED  = -4,
    AUDIO_JAVA_NO_INIT            = -5,
    AUDIO_JAVA_DEAD_OBJECT        = -6,
};

static inline jint nativeToJavaStatus(status_t status) {
    switch (status) {
    case NO_ERROR:
        return AUDIO_JAVA_SUCCESS;
    case BAD_VALUE:
        return AUDIO_JAVA_BAD_VALUE;
    case INVALID_OPERATION:
        return AUDIO_JAVA_INVALID_OPERATION;
    case PERMISSION_DENIED:
        return AUDIO_JAVA_PERMISSION_DENIED;
    case NO_INIT:
        return AUDIO_JAVA_NO_INIT;
    case DEAD_OBJECT:
        return AUDIO_JAVA_DEAD_OBJECT;
    default:
        return AUDIO_JAVA_ERROR;
    }
}
}; // namespace android
#endif // ANDROID_MEDIA_AUDIOERRORS_H
