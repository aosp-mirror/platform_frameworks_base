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

#include "android/graphics/paint.h"

#include "TypeCast.h"

#include <hwui/Paint.h>

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

void APaint_setBlendMode(APaint* paint, ABlendMode blendMode) {
    TypeCast::toPaint(paint)->setBlendMode(convertBlendMode(blendMode));
}
