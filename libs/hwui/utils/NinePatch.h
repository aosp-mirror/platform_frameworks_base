/*
**
** Copyright 2015, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#ifndef ANDROID_GRAPHICS_NINEPATCH_H
#define ANDROID_GRAPHICS_NINEPATCH_H

#include <androidfw/ResourceTypes.h>
#include <cutils/compiler.h>

#include "SkCanvas.h"
#include "SkRegion.h"

namespace android {

class ANDROID_API NinePatch {
public:
    static void Draw(SkCanvas* canvas, const SkRect& bounds, const SkBitmap& bitmap,
            const Res_png_9patch& chunk, const SkPaint* paint, SkRegion** outRegion);
};

} // namespace android

#endif // ANDROID_GRAPHICS_NINEPATCH_H
