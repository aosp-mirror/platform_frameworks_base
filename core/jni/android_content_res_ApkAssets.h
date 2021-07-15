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

#ifndef ANDROID_CONTENT_RES_APKASSETS_H
#define ANDROID_CONTENT_RES_APKASSETS_H

#include "androidfw/ApkAssets.h"
#include "androidfw/MutexGuard.h"

#include "jni.h"

namespace android {

Guarded<std::unique_ptr<const ApkAssets>>& ApkAssetsFromLong(jlong ptr);

} // namespace android

#endif // ANDROID_CONTENT_RES_APKASSETS_H
