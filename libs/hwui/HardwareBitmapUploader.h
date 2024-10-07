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

#pragma once

#include <hwui/Bitmap.h>
#include <SkRefCnt.h>

class SkBitmap;

namespace android::uirenderer {

class HardwareBitmapUploader {
public:
    static void initialize();
    static void terminate();

    static sk_sp<Bitmap> allocateHardwareBitmap(const SkBitmap& sourceBitmap);

#ifdef __ANDROID__
    static bool hasFP16Support();
    static bool has1010102Support();
    static bool has10101010Support();
    static bool hasAlpha8Support();
#else
    static bool hasFP16Support() {
        return true;
    }
    static bool has1010102Support() { return true; }
    static bool has10101010Support() { return true; }
    static bool hasAlpha8Support() { return true; }
#endif
};

}  // namespace android::uirenderer
