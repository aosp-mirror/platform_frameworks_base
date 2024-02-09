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

#include <SkBlendMode.h>
#include <SkImageFilter.h>
#include <hwui/Paint.h>

#include "TypeCast.h"
#include "android/graphics/paint.h"
#include "include/effects/SkImageFilters.h"

using namespace android;


APaint* APaint_createPaint() {
    return TypeCast::toAPaint(new Paint());
}

void APaint_destroyPaint(APaint* paint) {
    delete TypeCast::toPaint(paint);
}

static SkBlendMode convertBlendMode(ABlendMode blendMode) {
    switch (blendMode) {
        case ABLEND_MODE_CLEAR:
            return SkBlendMode::kClear;
        case ABLEND_MODE_SRC_OVER:
            return SkBlendMode::kSrcOver;
        case ABLEND_MODE_SRC:
            return SkBlendMode::kSrc;
    }
}

static sk_sp<SkImageFilter> convertImageFilter(AImageFilter imageFilter) {
    switch (imageFilter) {
        case AIMAGE_FILTER_DROP_SHADOW_FOR_POINTER_ICON:
            // Material Elevation Level 1 Drop Shadow.
            sk_sp<SkImageFilter> key_shadow = SkImageFilters::DropShadow(
                    0.0f, 1.0f, 2.0f, 2.0f, SkColorSetARGB(0x4D, 0x00, 0x00, 0x00), nullptr);
            sk_sp<SkImageFilter> ambient_shadow = SkImageFilters::DropShadow(
                    0.0f, 1.0f, 3.0f, 3.0f, SkColorSetARGB(0x26, 0x00, 0x00, 0x00), nullptr);
            return SkImageFilters::Compose(ambient_shadow, key_shadow);
    }
}

void APaint_setBlendMode(APaint* paint, ABlendMode blendMode) {
    TypeCast::toPaint(paint)->setBlendMode(convertBlendMode(blendMode));
}

void APaint_setImageFilter(APaint* paint, AImageFilter imageFilter) {
    TypeCast::toPaint(paint)->setImageFilter(convertImageFilter(imageFilter));
}
