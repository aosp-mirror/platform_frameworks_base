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

#include "hwui/Bitmap.h"

#include <SkCanvas.h>
#include <SkPaintFilterCanvas.h>

#include <memory>

namespace android::uirenderer {

enum class UsageHint {
    Unknown = 0,
    Background = 1,
    Foreground = 2,
    // Contains foreground (usually text), like a button or chip
    Container = 3
};

enum class ColorTransform {
    None,
    Light,
    Dark,
    Invert
};

// True if the paint was modified, false otherwise
bool transformPaint(ColorTransform transform, SkPaint* paint);

bool transformPaint(ColorTransform transform, SkPaint* paint, BitmapPalette palette);

SkColor transformColor(ColorTransform transform, SkColor color);
SkColor transformColorInverse(ColorTransform transform, SkColor color);

}  // namespace android::uirenderer
