/*
 * Copyright (C) 2024 The Android Open Source Project
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

#ifndef RUNTIMEEFFECTUTILS_H
#define RUNTIMEEFFECTUTILS_H

#include "GraphicsJNI.h"
#include "include/effects/SkRuntimeEffect.h"

namespace android {
namespace uirenderer {

void UpdateFloatUniforms(JNIEnv* env, SkRuntimeEffectBuilder* builder, const char* uniformName,
                         const float values[], int count, bool isColor);

void UpdateIntUniforms(JNIEnv* env, SkRuntimeEffectBuilder* builder, const char* uniformName,
                       const int values[], int count);

void UpdateChild(JNIEnv* env, SkRuntimeEffectBuilder* builder, const char* childName,
                 SkFlattenable* childEffect);
}  // namespace uirenderer
}  // namespace android

#endif  // MAIN_RUNTIMEEFFECTUTILS_H
