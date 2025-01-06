/*
 * Copyright 2024 The Android Open Source Project
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

#pragma once

#include <SkBitmap.h>
#include <SkColorSpace.h>
#include <SkColorType.h>
#include <cutils/compiler.h>
#include <hwui/Bitmap.h>

namespace android {
namespace uirenderer {

ANDROID_API void logBitmapDecode(const SkImageInfo& info, bool hasGainmap);

ANDROID_API void logBitmapDecode(const Bitmap& bitmap);

}  // namespace uirenderer
}  // namespace android
