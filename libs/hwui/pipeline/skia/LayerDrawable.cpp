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
#include "SkColorFilter.h"
#include "SkSurface.h"
#include "gl/GrGLTypes.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

void LayerDrawable::onDraw(SkCanvas* canvas) {
    Layer* layer = mLayerUpdater->backingLayer();
    if (layer) {
        DrawLayer(canvas->getGrContext(), canvas, layer, nullptr, nullptr, true);
    }
}

// Disable filtering when there is no scaling in screen coordinates and the corners have the same
// fraction (for translate) or zero fraction (for any other rect-to-rect transform).
static bool shouldFilterRect(const SkMatrix& matrix, const SkRect& srcRect, const SkRect& dstRect) {
    if (!matrix.rectStaysRect()) return true;
    SkRect dstDevRect = matrix.mapRect(dstRect);
    float dstW, dstH;
    bool requiresIntegerTranslate = false;
    if (MathUtils::isZero(matrix.getScaleX()) && MathUtils::isZero(matrix.getScaleY())) {
        // Has a 90 or 270 degree rotation, although total matrix may also have scale factors
        // in m10 and m01. Those scalings are automatically handled by mapRect so comparing
        // dimensions is sufficient, but swap width and height comparison.
        dstW = dstDevRect.height();
        dstH = dstDevRect.width();
        requiresIntegerTranslate = true;
    } else {
        // Handle H/V flips or 180 rotation matrices. Axes may have been mirrored, but
        // dimensions are still safe to compare directly.
        dstW = dstDevRect.width();
        dstH = dstDevRect.height();
        requiresIntegerTranslate =
                matrix.getScaleX() < -NON_ZERO_EPSILON || matrix.getScaleY() < -NON_ZERO_EPSILON;
    }
    if (!(MathUtils::areEqual(dstW, srcRect.width()) &&
          MathUtils::areEqual(dstH, srcRect.height()))) {
        return true;
    }
    if (requiresIntegerTranslate) {
        // Device rect and source rect should be integer aligned to ensure there's no difference
        // in how nearest-neighbor sampling is resolved.
        return !(MathUtils::isZero(SkScalarFraction(srcRect.x())) &&
                 MathUtils::isZero(SkScalarFraction(srcRect.y())) &&
                 MathUtils::isZero(SkScalarFraction(dstDevRect.x())) &&
                 MathUtils::isZero(SkScalarFraction(dstDevRect.y())));
    } else {
        // As long as src and device rects are translated by the same fractional amount,
        // filtering won't be needed
        return !(MathUtils::areEqual(SkScalarFraction(srcRect.x()),
                                     SkScalarFraction(dstDevRect.x())) &&
                 MathUtils::areEqual(SkScalarFraction(srcRect.y()),
                                     SkScalarFraction(dstDevRect.y())));
    }
}

bool LayerDrawable::DrawLayer(GrContext* context, SkCanvas* canvas, Layer* layer,
                              const SkRect* srcRect, const SkRect* dstRect,
                              bool useLayerTransform) {
    if (context == nullptr) {
        SkDEBUGF(("Attempting to draw LayerDrawable into an unsupported surface"));
        return false;
    }
    // transform the matrix based on the layer
    SkMatrix layerTransform = layer->getTransform();
    sk_sp<SkImage> layerImage = layer->getImage();
    const int layerWidth = layer->getWidth();
    const int layerHeight = layer->getHeight();

    if (layerImage) {
        SkMatrix textureMatrixInv;
        textureMatrixInv = layer->getTexTransform();
        // TODO: after skia bug https://bugs.chromium.org/p/skia/issues/detail?id=7075 is fixed
        // use bottom left origin and remove flipV and invert transformations.
        SkMatrix flipV;
        flipV.setAll(1, 0, 0, 0, -1, 1, 0, 0, 1);
        textureMatrixInv.preConcat(flipV);
        textureMatrixInv.preScale(1.0f / layerWidth, 1.0f / layerHeight);
        textureMatrixInv.postScale(layerImage->width(), layerImage->height());
        SkMatrix textureMatrix;
        if (!textureMatrixInv.invert(&textureMatrix)) {
            textureMatrix = textureMatrixInv;
        }

        SkMatrix matrix;
        if (useLayerTransform) {
            matrix = SkMatrix::Concat(layerTransform, textureMatrix);
        } else {
            matrix = textureMatrix;
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
        const SkMatrix& totalMatrix = canvas->getTotalMatrix();
        if (dstRect || srcRect) {
            SkMatrix matrixInv;
            if (!matrix.invert(&matrixInv)) {
                matrixInv = matrix;
            }
            SkRect skiaSrcRect;
            if (srcRect) {
                skiaSrcRect = *srcRect;
            } else {
                skiaSrcRect = SkRect::MakeIWH(layerWidth, layerHeight);
            }
            matrixInv.mapRect(&skiaSrcRect);
            SkRect skiaDestRect;
            if (dstRect) {
                skiaDestRect = *dstRect;
            } else {
                skiaDestRect = SkRect::MakeIWH(layerWidth, layerHeight);
            }
            matrixInv.mapRect(&skiaDestRect);
            // If (matrix is a rect-to-rect transform)
            // and (src/dst buffers size match in screen coordinates)
            // and (src/dst corners align fractionally),
            // then use nearest neighbor, otherwise use bilerp sampling.
            // Skia TextureOp has the above logic build-in, but not NonAAFillRectOp. TextureOp works
            // only for SrcOver blending and without color filter (readback uses Src blending).
            if (layer->getForceFilter() ||
                shouldFilterRect(totalMatrix, skiaSrcRect, skiaDestRect)) {
                paint.setFilterQuality(kLow_SkFilterQuality);
            }
            canvas->drawImageRect(layerImage.get(), skiaSrcRect, skiaDestRect, &paint,
                                  SkCanvas::kFast_SrcRectConstraint);
        } else {
            SkRect imageRect = SkRect::MakeIWH(layerImage->width(), layerImage->height());
            if (layer->getForceFilter() || shouldFilterRect(totalMatrix, imageRect, imageRect)) {
                paint.setFilterQuality(kLow_SkFilterQuality);
            }
            canvas->drawImage(layerImage.get(), 0, 0, &paint);
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
