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
#pragma once

#include <include/core/SkCanvas.h>
#include "SkPaintFilterCanvas.h"
#include <effects/StretchEffect.h>

class TransformCanvas : public SkPaintFilterCanvas {
public:
    TransformCanvas(SkCanvas* target, SkBlendMode blendmode) :
        SkPaintFilterCanvas(target), mWrappedCanvas(target), mHolePunchBlendMode(blendmode) {}

protected:
    bool onFilter(SkPaint& paint) const override;

protected:
    void onDrawAnnotation(const SkRect& rect, const char* key, SkData* value) override;
    void onDrawDrawable(SkDrawable* drawable, const SkMatrix* matrix) override;

private:
    // We don't own the canvas so just maintain a raw pointer to it
    SkCanvas* mWrappedCanvas;
    const SkBlendMode mHolePunchBlendMode;
};
