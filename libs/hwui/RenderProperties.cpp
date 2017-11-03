/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you mPrimitiveFields.may not use this file except in compliance with the License.
 * You mPrimitiveFields.may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "RenderProperties.h"

#include <utils/Trace.h>

#include <SkColorFilter.h>
#include <SkMatrix.h>
#include <SkPath.h>
#include <SkPathOps.h>

#include "Matrix.h"
#include "hwui/Canvas.h"
#include "utils/MathUtils.h"

namespace android {
namespace uirenderer {

LayerProperties::LayerProperties() {
    reset();
}

LayerProperties::~LayerProperties() {
    setType(LayerType::None);
}

void LayerProperties::reset() {
    mOpaque = false;
    setFromPaint(nullptr);
}

bool LayerProperties::setColorFilter(SkColorFilter* filter) {
    if (mColorFilter == filter) return false;
    SkRefCnt_SafeAssign(mColorFilter, filter);
    return true;
}

bool LayerProperties::setFromPaint(const SkPaint* paint) {
    bool changed = false;
    changed |= setAlpha(static_cast<uint8_t>(PaintUtils::getAlphaDirect(paint)));
    changed |= setXferMode(PaintUtils::getBlendModeDirect(paint));
    changed |= setColorFilter(paint ? paint->getColorFilter() : nullptr);
    return changed;
}

LayerProperties& LayerProperties::operator=(const LayerProperties& other) {
    setType(other.type());
    setOpaque(other.opaque());
    setAlpha(other.alpha());
    setXferMode(other.xferMode());
    setColorFilter(other.colorFilter());
    return *this;
}

RenderProperties::ComputedFields::ComputedFields() : mTransformMatrix(nullptr) {}

RenderProperties::ComputedFields::~ComputedFields() {
    delete mTransformMatrix;
}

RenderProperties::RenderProperties() : mStaticMatrix(nullptr), mAnimationMatrix(nullptr) {}

RenderProperties::~RenderProperties() {
    delete mStaticMatrix;
    delete mAnimationMatrix;
}

RenderProperties& RenderProperties::operator=(const RenderProperties& other) {
    if (this != &other) {
        mPrimitiveFields = other.mPrimitiveFields;
        setStaticMatrix(other.getStaticMatrix());
        setAnimationMatrix(other.getAnimationMatrix());
        setCameraDistance(other.getCameraDistance());
        mLayerProperties = other.layerProperties();

        // Force recalculation of the matrix, since other's dirty bit may be clear
        mPrimitiveFields.mMatrixOrPivotDirty = true;
        updateMatrix();
    }
    return *this;
}

static void dumpMatrix(std::ostream& output, std::string& indent, const char* label,
                       SkMatrix* matrix) {
    if (matrix) {
        output << indent << "(" << label << " " << matrix << ": ";
        output << std::fixed << std::setprecision(2);
        output << "[" << matrix->get(0) << " " << matrix->get(1) << " " << matrix->get(2) << "]";
        output << " [" << matrix->get(3) << " " << matrix->get(4) << " " << matrix->get(5) << "]";
        output << " [" << matrix->get(6) << " " << matrix->get(7) << " " << matrix->get(8) << "]";
        output << ")" << std::endl;
    }
}

void RenderProperties::debugOutputProperties(std::ostream& output, const int level) const {
    auto indent = std::string(level * 2, ' ');
    if (mPrimitiveFields.mLeft != 0 || mPrimitiveFields.mTop != 0) {
        output << indent << "(Translate (left, top) " << mPrimitiveFields.mLeft << ", "
               << mPrimitiveFields.mTop << ")" << std::endl;
    }
    dumpMatrix(output, indent, "ConcatMatrix (static)", mStaticMatrix);
    dumpMatrix(output, indent, "ConcatMatrix (animation)", mAnimationMatrix);

    output << std::fixed << std::setprecision(2);
    if (hasTransformMatrix()) {
        if (isTransformTranslateOnly()) {
            output << indent << "(Translate " << getTranslationX() << ", " << getTranslationY()
                   << ", " << getZ() << ")" << std::endl;
        } else {
            dumpMatrix(output, indent, "ConcatMatrix ", mComputedFields.mTransformMatrix);
        }
    }

    const bool isLayer = effectiveLayerType() != LayerType::None;
    int clipFlags = getClippingFlags();
    if (mPrimitiveFields.mAlpha < 1 && !MathUtils::isZero(mPrimitiveFields.mAlpha)) {
        if (isLayer) {
            clipFlags &= ~CLIP_TO_BOUNDS;  // bounds clipping done by layer
        }

        if (CC_LIKELY(isLayer || !getHasOverlappingRendering())) {
            // simply scale rendering content's alpha
            output << indent << "(ScaleAlpha " << mPrimitiveFields.mAlpha << ")" << std::endl;
        } else {
            // savelayeralpha to create an offscreen buffer to apply alpha
            Rect layerBounds(0, 0, getWidth(), getHeight());
            if (clipFlags) {
                getClippingRectForFlags(clipFlags, &layerBounds);
                clipFlags = 0;  // all clipping done by savelayer
            }
            output << indent << "(SaveLayerAlpha " << (int)layerBounds.left << ", "
                   << (int)layerBounds.top << ", " << (int)layerBounds.right << ", "
                   << (int)layerBounds.bottom << ", " << (int)(mPrimitiveFields.mAlpha * 255)
                   << ", 0x" << std::hex << (SaveFlags::HasAlphaLayer | SaveFlags::ClipToLayer)
                   << ")" << std::dec << std::endl;
        }
    }

    if (clipFlags) {
        Rect clipRect;
        getClippingRectForFlags(clipFlags, &clipRect);
        output << indent << "(ClipRect " << (int)clipRect.left << ", " << (int)clipRect.top << ", "
               << (int)clipRect.right << ", " << (int)clipRect.bottom << ")" << std::endl;
    }

    if (getRevealClip().willClip()) {
        Rect bounds;
        getRevealClip().getBounds(&bounds);
        output << indent << "(Clip to reveal clip with bounds " << bounds.left << ", " << bounds.top
               << ", " << bounds.right << ", " << bounds.bottom << ")" << std::endl;
    }

    auto& outline = mPrimitiveFields.mOutline;
    if (outline.getShouldClip()) {
        if (outline.isEmpty()) {
            output << indent << "(Clip to empty outline)";
        } else if (outline.willClip()) {
            const Rect& bounds = outline.getBounds();
            output << indent << "(Clip to outline with bounds " << bounds.left << ", " << bounds.top
                   << ", " << bounds.right << ", " << bounds.bottom << ")" << std::endl;
        }
    }
}

void RenderProperties::updateMatrix() {
    if (mPrimitiveFields.mMatrixOrPivotDirty) {
        if (!mComputedFields.mTransformMatrix) {
            // only allocate a mPrimitiveFields.matrix if we have a complex transform
            mComputedFields.mTransformMatrix = new SkMatrix();
        }
        if (!mPrimitiveFields.mPivotExplicitlySet) {
            mPrimitiveFields.mPivotX = mPrimitiveFields.mWidth / 2.0f;
            mPrimitiveFields.mPivotY = mPrimitiveFields.mHeight / 2.0f;
        }
        SkMatrix* transform = mComputedFields.mTransformMatrix;
        transform->reset();
        if (MathUtils::isZero(getRotationX()) && MathUtils::isZero(getRotationY())) {
            transform->setTranslate(getTranslationX(), getTranslationY());
            transform->preRotate(getRotation(), getPivotX(), getPivotY());
            transform->preScale(getScaleX(), getScaleY(), getPivotX(), getPivotY());
        } else {
            SkMatrix transform3D;
            mComputedFields.mTransformCamera.save();
            transform->preScale(getScaleX(), getScaleY(), getPivotX(), getPivotY());
            mComputedFields.mTransformCamera.rotateX(mPrimitiveFields.mRotationX);
            mComputedFields.mTransformCamera.rotateY(mPrimitiveFields.mRotationY);
            mComputedFields.mTransformCamera.rotateZ(-mPrimitiveFields.mRotation);
            mComputedFields.mTransformCamera.getMatrix(&transform3D);
            transform3D.preTranslate(-getPivotX(), -getPivotY());
            transform3D.postTranslate(getPivotX() + getTranslationX(),
                                      getPivotY() + getTranslationY());
            transform->postConcat(transform3D);
            mComputedFields.mTransformCamera.restore();
        }
        mPrimitiveFields.mMatrixOrPivotDirty = false;
    }
}

} /* namespace uirenderer */
} /* namespace android */
