/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef ANDROID_MEDIA_AUDIOTRACK_H
#define ANDROID_MEDIA_AUDIOTRACK_H

#include "jni.h"

#include <utils/StrongPointer.h>

namespace android {

class AudioTrack;

}; // namespace android

/* Gets the underlying AudioTrack from an AudioTrack Java object. */
extern android::sp<android::AudioTrack> android_media_AudioTrack_getAudioTrack(
        JNIEnv* env, jobject audioTrackObj);

#endif // ANDROID_MEDIA_AUDIOTRACK_H
