/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "SkiaProfileRenderer.h"

namespace android {
namespace uirenderer {

void SkiaProfileRenderer::drawRect(float left, float top, float right, float bottom,
                                   const SkPaint& paint) {
    SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
    mCanvas->drawRect(rect, paint);
}

void SkiaProfileRenderer::drawRects(const float* rects, int count, const SkPaint& paint) {
    for (int index = 0; index + 4 <= count; index += 4) {
        SkRect rect = SkRect::MakeLTRB(rects[index + 0], rects[index + 1], rects[index + 2],
                                       rects[index + 3]);
        mCanvas->drawRect(rect, paint);
    }
}

uint32_t SkiaProfileRenderer::getViewportWidth() {
    return mCanvas->imageInfo().width();
}

uint32_t SkiaProfileRenderer::getViewportHeight() {
    return mCanvas->imageInfo().height();
}

} /* namespace uirenderer */
} /* namespace android */
