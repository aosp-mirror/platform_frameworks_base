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
#include "OpenGLRenderer.h"
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
    changed |= setXferMode(PaintUtils::getXfermodeDirect(paint));
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

RenderProperties::ComputedFields::ComputedFields()
        : mTransformMatrix(nullptr) {
}

RenderProperties::ComputedFields::~ComputedFields() {
    delete mTransformMatrix;
}

RenderProperties::RenderProperties()
        : mStaticMatrix(nullptr)
        , mAnimationMatrix(nullptr) {
}

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

void RenderProperties::debugOutputProperties(const int level) const {
    if (mPrimitiveFields.mLeft != 0 || mPrimitiveFields.mTop != 0) {
        ALOGD("%*s(Translate (left, top) %d, %d)", level * 2, "",
                mPrimitiveFields.mLeft, mPrimitiveFields.mTop);
    }
    if (mStaticMatrix) {
        ALOGD("%*s(ConcatMatrix (static) %p: " SK_MATRIX_STRING ")",
                level * 2, "", mStaticMatrix, SK_MATRIX_ARGS(mStaticMatrix));
    }
    if (mAnimationMatrix) {
        ALOGD("%*s(ConcatMatrix (animation) %p: " SK_MATRIX_STRING ")",
                level * 2, "", mAnimationMatrix, SK_MATRIX_ARGS(mAnimationMatrix));
    }
    if (hasTransformMatrix()) {
        if (isTransformTranslateOnly()) {
            ALOGD("%*s(Translate %.2f, %.2f, %.2f)",
                    level * 2, "", getTranslationX(), getTranslationY(), getZ());
        } else {
            ALOGD("%*s(ConcatMatrix %p: " SK_MATRIX_STRING ")",
                    level * 2, "", mComputedFields.mTransformMatrix, SK_MATRIX_ARGS(mComputedFields.mTransformMatrix));
        }
    }

    const bool isLayer = effectiveLayerType() != LayerType::None;
    int clipFlags = getClippingFlags();
    if (mPrimitiveFields.mAlpha < 1
            && !MathUtils::isZero(mPrimitiveFields.mAlpha)) {
        if (isLayer) {
            clipFlags &= ~CLIP_TO_BOUNDS; // bounds clipping done by layer
        }

        if (CC_LIKELY(isLayer || !getHasOverlappingRendering())) {
            // simply scale rendering content's alpha
            ALOGD("%*s(ScaleAlpha %.2f)", level * 2, "", mPrimitiveFields.mAlpha);
        } else {
            // savelayeralpha to create an offscreen buffer to apply alpha
            Rect layerBounds(0, 0, getWidth(), getHeight());
            if (clipFlags) {
                getClippingRectForFlags(clipFlags, &layerBounds);
                clipFlags = 0; // all clipping done by savelayer
            }
            ALOGD("%*s(SaveLayerAlpha %d, %d, %d, %d, %d, 0x%x)", level * 2, "",
                    (int)layerBounds.left, (int)layerBounds.top,
                    (int)layerBounds.right, (int)layerBounds.bottom,
                    (int)(mPrimitiveFields.mAlpha * 255),
                    SaveFlags::HasAlphaLayer | SaveFlags::ClipToLayer);
        }
    }

    if (clipFlags) {
        Rect clipRect;
        getClippingRectForFlags(clipFlags, &clipRect);
        ALOGD("%*s(ClipRect %d, %d, %d, %d)", level * 2, "",
                (int)clipRect.left, (int)clipRect.top, (int)clipRect.right, (int)clipRect.bottom);
    }

    if (getRevealClip().willClip()) {
        Rect bounds;
        getRevealClip().getBounds(&bounds);
        ALOGD("%*s(Clip to reveal clip with bounds %.2f %.2f %.2f %.2f)", level * 2, "",
                RECT_ARGS(bounds));
    }

    auto& outline = mPrimitiveFields.mOutline;
    if (outline.getShouldClip()) {
        if (outline.isEmpty()) {
            ALOGD("%*s(Clip to empty outline)", level * 2, "");
        } else if (outline.willClip()) {
            ALOGD("%*s(Clip to outline with bounds %.2f %.2f %.2f %.2f)", level * 2, "",
                    RECT_ARGS(outline.getBounds()));
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
