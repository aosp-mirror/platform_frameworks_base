/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef ANDROID_MEDIA_MICROPHONEINFO_H
#define ANDROID_MEDIA_MICROPHONEINFO_H

#include <system/audio.h>
#include <media/MicrophoneInfo.h>

#include "jni.h"

namespace android {

// Conversion from C++ MicrophoneInfo object to Java MicrophoneInfo object

extern jint convertMicrophoneInfoFromNative(JNIEnv *env, jobject *jMicrophoneInfo,
        const media::MicrophoneInfo *microphoneInfo);
} // namespace android

#endif