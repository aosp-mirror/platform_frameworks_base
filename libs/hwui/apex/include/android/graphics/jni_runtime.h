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
#ifndef ANDROID_GRAPHICS_JNI_RUNTIME_H
#define ANDROID_GRAPHICS_JNI_RUNTIME_H

#include <cutils/compiler.h>
#include <jni.h>

__BEGIN_DECLS

ANDROID_API void init_android_graphics();

ANDROID_API int register_android_graphics_classes(JNIEnv* env);

ANDROID_API int register_android_graphics_GraphicsStatsService(JNIEnv* env);

ANDROID_API void zygote_preload_graphics();

__END_DECLS


#endif // ANDROID_GRAPHICS_JNI_RUNTIME_H