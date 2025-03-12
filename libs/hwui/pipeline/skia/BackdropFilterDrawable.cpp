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

void BackdropFilterDrawable::onDraw(SkCanvas* canvas) {
    const RenderProperties& properties = mTargetRenderNode->properties();
    auto* backdropFilter = properties.layerProperties().getBackdropImageFilter();
    auto* surface = canvas->getSurface();
    if (!backdropFilter || !surface) {
        return;
    }

    SkRect srcBounds = SkRect::MakeWH(properties.getWidth(), properties.getHeight());

    float alphaMultiplier = 1.0f;
    RenderNodeDrawable::setViewProperties(properties, canvas, &alphaMultiplier, true);
    SkPaint paint;
    paint.setAlpha(properties.layerProperties().alpha() * alphaMultiplier);

    SkRect surfaceSubset;
    canvas->getTotalMatrix().mapRect(&surfaceSubset, srcBounds);
    if (!surfaceSubset.intersect(SkRect::MakeWH(surface->width(), surface->height()))) {
        return;
    }

    auto backdropImage = surface->makeImageSnapshot(surfaceSubset.roundOut());

    SkIRect imageBounds = SkIRect::MakeWH(backdropImage->width(), backdropImage->height());
    SkIPoint offset;
    SkIRect imageSubset;

#ifdef __ANDROID__
    if (canvas->recordingContext()) {
        backdropImage =
                SkImages::MakeWithFilter(canvas->recordingContext(), backdropImage, backdropFilter,
                                         imageBounds, imageBounds, &imageSubset, &offset);
    } else
#endif
    {
        backdropImage = SkImages::MakeWithFilter(backdropImage, backdropFilter, imageBounds,
                                                 imageBounds, &imageSubset, &offset);
    }

    canvas->save();
    canvas->resetMatrix();
    canvas->drawImageRect(backdropImage, SkRect::Make(imageSubset), surfaceSubset,
                          SkSamplingOptions(SkFilterMode::kLinear), &paint,
                          SkCanvas::kFast_SrcRectConstraint);
    canvas->restore();
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
