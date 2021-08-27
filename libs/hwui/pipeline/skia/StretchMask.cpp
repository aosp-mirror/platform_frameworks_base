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
#include "StretchMask.h"
#include "SkSurface.h"
#include "SkCanvas.h"
#include "TransformCanvas.h"
#include "SkiaDisplayList.h"

using android::uirenderer::StretchMask;

void StretchMask::draw(GrRecordingContext* context,
                       const StretchEffect& stretch,
                       const SkRect& bounds,
                       skiapipeline::SkiaDisplayList* displayList,
                       SkCanvas* canvas) {
    float width = bounds.width();
    float height = bounds.height();
    if (mMaskSurface == nullptr || mMaskSurface->width() != width ||
        mMaskSurface->height() != height) {
        // Create a new surface if we don't have one or our existing size does
        // not match.
        mMaskSurface = SkSurface::MakeRenderTarget(
            context,
            SkBudgeted::kYes,
            SkImageInfo::Make(
                width,
                height,
                SkColorType::kAlpha_8_SkColorType,
                SkAlphaType::kPremul_SkAlphaType)
        );
        mIsDirty = true;
    }

    if (mIsDirty) {
        SkCanvas* maskCanvas = mMaskSurface->getCanvas();
        // Make sure to apply target transformation to the mask canvas
        // to ensure the replayed drawing commands generate the same result
        auto previousMatrix = displayList->mParentMatrix;
        displayList->mParentMatrix = maskCanvas->getTotalMatrix();
        maskCanvas->save();
        maskCanvas->drawColor(0, SkBlendMode::kClear);
        TransformCanvas transformCanvas(maskCanvas, SkBlendMode::kSrcOver);
        displayList->draw(&transformCanvas);
        maskCanvas->restore();
        displayList->mParentMatrix = previousMatrix;
    }

    sk_sp<SkImage> maskImage = mMaskSurface->makeImageSnapshot();
    sk_sp<SkShader> maskStretchShader = stretch.getShader(width, height, maskImage, nullptr);

    SkPaint maskPaint;
    maskPaint.setShader(maskStretchShader);
    maskPaint.setBlendMode(SkBlendMode::kDstOut);
    canvas->drawRect(bounds, maskPaint);

    mIsDirty = false;
}