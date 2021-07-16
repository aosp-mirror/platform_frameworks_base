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

#include "LayerDrawable.h"
#include <utils/MathUtils.h>

#include "GrBackendSurface.h"
#include "Matrix.h"
#include "SkColorFilter.h"
#include "SkSurface.h"
#include "gl/GrGLTypes.h"
#include "system/window.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

void LayerDrawable::onDraw(SkCanvas* canvas) {
    Layer* layer = mLayerUpdater->backingLayer();
    if (layer) {
        SkRect srcRect = layer->getCropRect();
        DrawLayer(canvas->recordingContext(), canvas, layer, &srcRect, nullptr, true);
    }
}

static inline SkScalar isIntegerAligned(SkScalar x) {
    return MathUtils::isZero(roundf(x) - x);
}

// Disable filtering when there is no scaling in screen coordinates and the corners have the same
// fraction (for translate) or zero fraction (for any other rect-to-rect transform).
static bool shouldFilterRect(const SkMatrix& matrix, const SkRect& srcRect, const SkRect& dstRect) {
    if (!matrix.rectStaysRect()) return true;
    SkRect dstDevRect = matrix.mapRect(dstRect);
    float dstW, dstH;
    if (MathUtils::isZero(matrix.getScaleX()) && MathUtils::isZero(matrix.getScaleY())) {
        // Has a 90 or 270 degree rotation, although total matrix may also have scale factors
        // in m10 and m01. Those scalings are automatically handled by mapRect so comparing
        // dimensions is sufficient, but swap width and height comparison.
        dstW = dstDevRect.height();
        dstH = dstDevRect.width();
    } else {
        // Handle H/V flips or 180 rotation matrices. Axes may have been mirrored, but
        // dimensions are still safe to compare directly.
        dstW = dstDevRect.width();
        dstH = dstDevRect.height();
    }
    if (!(MathUtils::areEqual(dstW, srcRect.width()) &&
          MathUtils::areEqual(dstH, srcRect.height()))) {
        return true;
    }
    // Device rect and source rect should be integer aligned to ensure there's no difference
    // in how nearest-neighbor sampling is resolved.
    return !(isIntegerAligned(srcRect.x()) &&
             isIntegerAligned(srcRect.y()) &&
             isIntegerAligned(dstDevRect.x()) &&
             isIntegerAligned(dstDevRect.y()));
}

// TODO: Context arg probably doesn't belong here – do debug check at callsite instead.
bool LayerDrawable::DrawLayer(GrRecordingContext* context,
                              SkCanvas* canvas,
                              Layer* layer,
                              const SkRect* srcRect,
                              const SkRect* dstRect,
                              bool useLayerTransform) {
    if (context == nullptr) {
        ALOGD("Attempting to draw LayerDrawable into an unsupported surface");
        return false;
    }
    // transform the matrix based on the layer
    const uint32_t transform = layer->getTextureTransform();
    sk_sp<SkImage> layerImage = layer->getImage();
    const int layerWidth = layer->getWidth();
    const int layerHeight = layer->getHeight();
    SkMatrix layerTransform = layer->getTransform();
    if (layerImage) {
        SkMatrix matrix;
        if (useLayerTransform) {
            matrix = layerTransform;
        }
        SkPaint paint;
        paint.setAlpha(layer->getAlpha());
        paint.setBlendMode(layer->getMode());
        paint.setColorFilter(layer->getColorFilter());
        const bool nonIdentityMatrix = !matrix.isIdentity();
        if (nonIdentityMatrix) {
            canvas->save();
            canvas->concat(matrix);
        }
        const SkMatrix totalMatrix = canvas->getTotalMatrix();
        if (dstRect || srcRect) {
            SkMatrix matrixInv;
            if (!matrix.invert(&matrixInv)) {
                matrixInv = matrix;
            }
            SkRect skiaSrcRect;
            if (srcRect && !srcRect->isEmpty()) {
                skiaSrcRect = *srcRect;
            } else {
                skiaSrcRect = (transform & NATIVE_WINDOW_TRANSFORM_ROT_90)
                                      ? SkRect::MakeIWH(layerHeight, layerWidth)
                                      : SkRect::MakeIWH(layerWidth, layerHeight);
            }
            matrixInv.mapRect(&skiaSrcRect);
            SkRect skiaDestRect;
            if (dstRect && !dstRect->isEmpty()) {
                skiaDestRect = *dstRect;
            } else {
                skiaDestRect = SkRect::MakeIWH(layerWidth, layerHeight);
            }
            matrixInv.mapRect(&skiaDestRect);
            SkSamplingOptions sampling(SkFilterMode::kNearest);
            if (layer->getForceFilter() ||
                shouldFilterRect(totalMatrix, skiaSrcRect, skiaDestRect)) {
                sampling = SkSamplingOptions(SkFilterMode::kLinear);
            }

            const float px = skiaDestRect.centerX();
            const float py = skiaDestRect.centerY();
            if (transform & NATIVE_WINDOW_TRANSFORM_FLIP_H) {
                matrix.postScale(-1.f, 1.f, px, py);
            }
            if (transform & NATIVE_WINDOW_TRANSFORM_FLIP_V) {
                matrix.postScale(1.f, -1.f, px, py);
            }
            if (transform & NATIVE_WINDOW_TRANSFORM_ROT_90) {
                matrix.postRotate(90, 0, 0);
                matrix.postTranslate(skiaDestRect.height(), 0);
            }
            auto constraint = SkCanvas::kFast_SrcRectConstraint;
            if (srcRect && srcRect->isEmpty()) {
                constraint = SkCanvas::kStrict_SrcRectConstraint;
            }
            matrix.postConcat(SkMatrix::MakeRectToRect(skiaSrcRect, skiaDestRect,
                                                       SkMatrix::kFill_ScaleToFit));
            canvas->drawImageRect(layerImage.get(), skiaSrcRect, skiaDestRect, sampling, &paint,
                                  constraint);
        } else {
            SkRect imageRect = SkRect::MakeIWH(layerImage->width(), layerImage->height());
            SkSamplingOptions sampling(SkFilterMode::kNearest);
            if (layer->getForceFilter() || shouldFilterRect(totalMatrix, imageRect, imageRect)) {
                sampling = SkSamplingOptions(SkFilterMode::kLinear);
            }
            canvas->drawImage(layerImage.get(), 0, 0, sampling, &paint);
        }
        // restore the original matrix
        if (nonIdentityMatrix) {
            canvas->restore();
        }
    }
    return layerImage != nullptr;
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
