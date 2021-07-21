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
#include "TransformCanvas.h"
#include "HolePunch.h"
#include "SkData.h"
#include "SkDrawable.h"

using namespace android::uirenderer::skiapipeline;

void TransformCanvas::onDrawAnnotation(const SkRect& rect, const char* key, SkData* value) {
    if (HOLE_PUNCH_ANNOTATION == key) {
        auto* rectParams = reinterpret_cast<const float*>(value->data());
        float radiusX = rectParams[0];
        float radiusY = rectParams[1];
        SkRRect roundRect = SkRRect::MakeRectXY(rect, radiusX, radiusY);

        SkPaint paint;
        paint.setColor(SkColors::kBlack);
        paint.setBlendMode(mHolePunchBlendMode);
        mWrappedCanvas->drawRRect(roundRect, paint);
    }
}

void TransformCanvas::onDrawDrawable(SkDrawable* drawable, const SkMatrix* matrix) {
    drawable->draw(this, matrix);
}

bool TransformCanvas::onFilter(SkPaint& paint) const {
    return false;
}
