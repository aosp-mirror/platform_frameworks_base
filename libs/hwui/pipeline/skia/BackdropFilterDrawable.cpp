/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "BackdropFilterDrawable.h"

#include <SkImage.h>
#include <SkSurface.h>

#include "RenderNode.h"
#include "RenderNodeDrawable.h"
#ifdef __ANDROID__
#include "include/gpu/ganesh/SkImageGanesh.h"
#endif

namespace android {
namespace uirenderer {
namespace skiapipeline {

BackdropFilterDrawable::~BackdropFilterDrawable() {}

bool BackdropFilterDrawable::prepareToDraw(SkCanvas* canvas, const RenderProperties& properties,
                                           int backdropImageWidth, int backdropImageHeight) {
    // the drawing bounds for blurred content.
    mDstBounds.setWH(properties.getWidth(), properties.getHeight());

    float alphaMultiplier = 1.0f;
    RenderNodeDrawable::setViewProperties(properties, canvas, &alphaMultiplier, true);

    // get proper subset for previous content.
    canvas->getTotalMatrix().mapRect(&mImageSubset, mDstBounds);
    SkRect imageSubset(mImageSubset);
    // ensure the subset is inside bounds of previous content.
    if (!mImageSubset.intersect(SkRect::MakeWH(backdropImageWidth, backdropImageHeight))) {
        return false;
    }

    // correct the drawing bounds if subset was changed.
    if (mImageSubset != imageSubset) {
        SkMatrix inverse;
        if (canvas->getTotalMatrix().invert(&inverse)) {
            inverse.mapRect(&mDstBounds, mImageSubset);
        }
    }

    // follow the alpha from the target RenderNode.
    mPaint.setAlpha(properties.layerProperties().alpha() * alphaMultiplier);
    return true;
}

void BackdropFilterDrawable::onDraw(SkCanvas* canvas) {
    const RenderProperties& properties = mTargetRenderNode->properties();
    auto* backdropFilter = properties.layerProperties().getBackdropImageFilter();
    auto* surface = canvas->getSurface();
    if (!backdropFilter || !surface) {
        return;
    }

    auto backdropImage = surface->makeImageSnapshot();
    // sync necessary properties from target RenderNode.
    if (!prepareToDraw(canvas, properties, backdropImage->width(), backdropImage->height())) {
        return;
    }

    auto imageSubset = mImageSubset.roundOut();
#ifdef __ANDROID__
    if (canvas->recordingContext()) {
        backdropImage =
                SkImages::MakeWithFilter(canvas->recordingContext(), backdropImage, backdropFilter,
                                         imageSubset, imageSubset, &mOutSubset, &mOutOffset);
    } else
#endif
    {
        backdropImage = SkImages::MakeWithFilter(backdropImage, backdropFilter, imageSubset,
                                                 imageSubset, &mOutSubset, &mOutOffset);
    }
    canvas->drawImageRect(backdropImage, SkRect::Make(mOutSubset), mDstBounds,
                          SkSamplingOptions(SkFilterMode::kLinear), &mPaint,
                          SkCanvas::kStrict_SrcRectConstraint);
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
