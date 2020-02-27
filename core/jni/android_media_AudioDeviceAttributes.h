/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef ANDROID_MEDIA_AUDIODEVICEATTRIBUTES_H
#define ANDROID_MEDIA_AUDIODEVICEATTRIBUTES_H

#include <media/AudioDeviceTypeAddr.h>
#include <system/audio.h>

#include "jni.h"

namespace android {

// Create a Java AudioDeviceAttributes instance from a C++ AudioDeviceTypeAddress

extern jint createAudioDeviceAttributesFromNative(JNIEnv *env, jobject *jAudioDeviceAttributes,
                                        const AudioDeviceTypeAddr *devTypeAddr);
} // namespace android

#endif