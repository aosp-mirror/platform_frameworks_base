/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include "Layer.h"

#include "renderstate/RenderState.h"
#include "utils/Color.h"
#include "utils/MathUtils.h"

namespace android {
namespace uirenderer {

Layer::Layer(RenderState& renderState, sk_sp<SkColorFilter> colorFilter, int alpha,
        SkBlendMode mode)
        : mRenderState(renderState)
        , mColorFilter(colorFilter)
        , alpha(alpha)
        , mode(mode) {
    // TODO: This is a violation of Android's typical ref counting, but it
    // preserves the old inc/dec ref locations. This should be changed...
    incStrong(nullptr);
    renderState.registerLayer(this);
    texTransform.setIdentity();
    transform.setIdentity();
}

Layer::~Layer() {
    mRenderState.unregisterLayer(this);
}

void Layer::postDecStrong() {
    mRenderState.postDecStrong(this);
}

SkBlendMode Layer::getMode() const {
    if (mBlend || mode != SkBlendMode::kSrcOver) {
        return mode;
    } else {
        return SkBlendMode::kSrc;
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

void Layer::draw(SkCanvas* canvas) {
    GrRecordingContext* context = canvas->recordingContext();
    if (context == nullptr) {
        SkDEBUGF(("Attempting to draw LayerDrawable into an unsupported surface"));
        return;
    }
    SkMatrix layerTransform = getTransform();
    //sk_sp<SkImage> layerImage = getImage();
    const int layerWidth = getWidth();
    const int layerHeight = getHeight();
    if (layerImage) {
        SkMatrix textureMatrixInv;
        textureMatrixInv = getTexTransform();
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
        matrix = SkMatrix::Concat(layerTransform, textureMatrix);

        SkPaint paint;
        paint.setAlpha(getAlpha());
        paint.setBlendMode(getMode());
        paint.setColorFilter(getColorFilter());
        const bool nonIdentityMatrix = !matrix.isIdentity();
        if (nonIdentityMatrix) {
            canvas->save();
            canvas->concat(matrix);
        }
        const SkMatrix& totalMatrix = canvas->getTotalMatrix();

        SkRect imageRect = SkRect::MakeIWH(layerImage->width(), layerImage->height());
        SkSamplingOptions sampling;
        if (getForceFilter() || shouldFilterRect(totalMatrix, imageRect, imageRect)) {
            sampling = SkSamplingOptions(SkFilterMode::kLinear);
        }
        canvas->drawImage(layerImage.get(), 0, 0, sampling, &paint);
        // restore the original matrix
        if (nonIdentityMatrix) {
            canvas->restore();
        }
    }
}

}  // namespace uirenderer
}  // namespace android
