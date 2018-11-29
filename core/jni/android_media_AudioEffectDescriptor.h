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

#ifndef ANDROID_MEDIA_AUDIOEFFECT_DESCRIPTOR_H
#define ANDROID_MEDIA_AUDIOEFFECT_DESCRIPTOR_H

#include <system/audio.h>
#include <system/audio_effect.h>

#include "jni.h"

namespace android {

// Conversion from C effect_descriptor_t to Java AudioEffect.Descriptor object

extern jclass audioEffectDescriptorClass();

extern jint convertAudioEffectDescriptorFromNative(JNIEnv *env, jobject *jDescriptor,
        const effect_descriptor_t *nDescriptor);

extern void convertAudioEffectDescriptorVectorFromNative(JNIEnv *env, jobjectArray *jDescriptors,
        const std::vector<effect_descriptor_t>& nDescriptors);
} // namespace android

#endif