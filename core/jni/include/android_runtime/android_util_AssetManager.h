/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef ANDROID_RUNTIME_ASSETMANAGER_H
#define ANDROID_RUNTIME_ASSETMANAGER_H

#include "androidfw/AssetManager2.h"
#include "androidfw/MutexGuard.h"

#include "jni.h"

namespace android {

extern AAssetManager* NdkAssetManagerForJavaObject(JNIEnv* env, jobject jassetmanager);
extern Guarded<AssetManager2>* AssetManagerForJavaObject(JNIEnv* env, jobject jassetmanager);
extern Guarded<AssetManager2>* AssetManagerForNdkAssetManager(AAssetManager* assetmanager);
struct assetmanager_offsets_t
{
    jfieldID mObject;
};
extern assetmanager_offsets_t gAssetManagerOffsets;

}  // namespace android

#endif  // ANDROID_RUNTIME_ASSETMANAGER_H
