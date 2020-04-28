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

// This is a less-strict matrix.isTranslate() that will still report being translate-only
// on imperceptibly small scaleX & scaleY values.
static bool isBasicallyTranslate(const SkMatrix& matrix) {
    if (!matrix.isScaleTranslate()) return false;
    return MathUtils::isOne(matrix.getScaleX()) && MathUtils::isOne(matrix.getScaleY());
}

static bool shouldFilter(const SkMatrix& matrix) {
    if (!matrix.isScaleTranslate()) return true;

    // We only care about meaningful scale here
    bool noScale = MathUtils::isOne(matrix.getScaleX()) && MathUtils::isOne(matrix.getScaleY());
    bool pixelAligned =
            SkScalarIsInt(matrix.getTranslateX()) && SkScalarIsInt(matrix.getTranslateY());
    return !(noScale && pixelAligned);
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
            // If (matrix is identity or an integer translation) and (src/dst buffers size match),
            // then use nearest neighbor, otherwise use bilerp sampling.
            // Integer translation is defined as when src rect and dst rect align fractionally.
            // Skia TextureOp has the above logic build-in, but not NonAAFillRectOp. TextureOp works
            // only for SrcOver blending and without color filter (readback uses Src blending).
            bool isIntegerTranslate =
                    isBasicallyTranslate(totalMatrix) &&
                    SkScalarFraction(skiaDestRect.fLeft + totalMatrix[SkMatrix::kMTransX]) ==
                            SkScalarFraction(skiaSrcRect.fLeft) &&
                    SkScalarFraction(skiaDestRect.fTop + totalMatrix[SkMatrix::kMTransY]) ==
                            SkScalarFraction(skiaSrcRect.fTop);
            if (layer->getForceFilter() || !isIntegerTranslate) {
                paint.setFilterQuality(kLow_SkFilterQuality);
            }
            canvas->drawImageRect(layerImage.get(), skiaSrcRect, skiaDestRect, &paint,
                                  SkCanvas::kFast_SrcRectConstraint);
        } else {
            if (layer->getForceFilter() || shouldFilter(totalMatrix)) {
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
