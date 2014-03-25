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

#define LOG_TAG "OpenGLRenderer"

#include "RenderProperties.h"

#include <utils/Trace.h>

#include <SkCanvas.h>
#include <SkMatrix.h>
#include <SkPath.h>
#include <SkPathOps.h>

#include "Matrix.h"

namespace android {
namespace uirenderer {

RenderProperties::PrimitiveFields::PrimitiveFields()
        : mClipToBounds(true)
        , mProjectBackwards(false)
        , mProjectionReceiver(false)
        , mAlpha(1)
        , mHasOverlappingRendering(true)
        , mTranslationX(0), mTranslationY(0), mTranslationZ(0)
        , mRotation(0), mRotationX(0), mRotationY(0)
        , mScaleX(1), mScaleY(1)
        , mPivotX(0), mPivotY(0)
        , mLeft(0), mTop(0), mRight(0), mBottom(0)
        , mWidth(0), mHeight(0)
        , mPrevWidth(-1), mPrevHeight(-1)
        , mPivotExplicitlySet(false)
        , mMatrixDirty(false)
        , mMatrixIsIdentity(true)
        , mMatrixFlags(0)
        , mCaching(false) {
}

RenderProperties::ComputedFields::ComputedFields()
        : mTransformMatrix(NULL)
        , mTransformCamera(NULL)
        , mTransformMatrix3D(NULL)
        , mClipPath(NULL) {
}

RenderProperties::ComputedFields::~ComputedFields() {
    delete mTransformMatrix;
    delete mTransformCamera;
    delete mTransformMatrix3D;
    delete mClipPath;
}

RenderProperties::RenderProperties()
        : mCameraDistance(0)
        , mStaticMatrix(NULL)
        , mAnimationMatrix(NULL) {
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

        // Update the computed fields
        updateMatrix();
        updateClipPath();
    }
    return *this;
}

void RenderProperties::debugOutputProperties(const int level) const {
    if (mPrimitiveFields.mLeft != 0 || mPrimitiveFields.mTop != 0) {
        ALOGD("%*sTranslate (left, top) %d, %d", level * 2, "", mPrimitiveFields.mLeft, mPrimitiveFields.mTop);
    }
    if (mStaticMatrix) {
        ALOGD("%*sConcatMatrix (static) %p: " SK_MATRIX_STRING,
                level * 2, "", mStaticMatrix, SK_MATRIX_ARGS(mStaticMatrix));
    }
    if (mAnimationMatrix) {
        ALOGD("%*sConcatMatrix (animation) %p: " SK_MATRIX_STRING,
                level * 2, "", mAnimationMatrix, SK_MATRIX_ARGS(mAnimationMatrix));
    }
    if (mPrimitiveFields.mMatrixFlags != 0) {
        if (mPrimitiveFields.mMatrixFlags == TRANSLATION) {
            ALOGD("%*sTranslate %.2f, %.2f, %.2f",
                    level * 2, "", mPrimitiveFields.mTranslationX, mPrimitiveFields.mTranslationY, mPrimitiveFields.mTranslationZ);
        } else {
            ALOGD("%*sConcatMatrix %p: " MATRIX_4_STRING,
                    level * 2, "", mComputedFields.mTransformMatrix, MATRIX_4_ARGS(mComputedFields.mTransformMatrix));
        }
    }

    bool clipToBoundsNeeded = mPrimitiveFields.mCaching ? false : mPrimitiveFields.mClipToBounds;
    if (mPrimitiveFields.mAlpha < 1) {
        if (mPrimitiveFields.mCaching) {
            ALOGD("%*sSetOverrideLayerAlpha %.2f", level * 2, "", mPrimitiveFields.mAlpha);
        } else if (!mPrimitiveFields.mHasOverlappingRendering) {
            ALOGD("%*sScaleAlpha %.2f", level * 2, "", mPrimitiveFields.mAlpha);
        } else {
            int flags = SkCanvas::kHasAlphaLayer_SaveFlag;
            if (clipToBoundsNeeded) {
                flags |= SkCanvas::kClipToLayer_SaveFlag;
                clipToBoundsNeeded = false; // clipping done by save layer
            }
            ALOGD("%*sSaveLayerAlpha %d, %d, %d, %d, %d, 0x%x", level * 2, "",
                    0, 0, getWidth(), getHeight(),
                    (int)(mPrimitiveFields.mAlpha * 255), flags);
        }
    }
    if (clipToBoundsNeeded) {
        ALOGD("%*sClipRect %d, %d, %d, %d", level * 2, "",
                0, 0, getWidth(), getHeight());
    }
}

void RenderProperties::updateMatrix() {
    if (mPrimitiveFields.mMatrixDirty) {
        // NOTE: mComputedFields.mTransformMatrix won't be up to date if a DisplayList goes from a complex transform
        // to a pure translate. This is safe because the mPrimitiveFields.matrix isn't read in pure translate cases.
        if (mPrimitiveFields.mMatrixFlags && mPrimitiveFields.mMatrixFlags != TRANSLATION) {
            if (!mComputedFields.mTransformMatrix) {
                // only allocate a mPrimitiveFields.matrix if we have a complex transform
                mComputedFields.mTransformMatrix = new Matrix4();
            }
            if (!mPrimitiveFields.mPivotExplicitlySet) {
                if (mPrimitiveFields.mWidth != mPrimitiveFields.mPrevWidth || mPrimitiveFields.mHeight != mPrimitiveFields.mPrevHeight) {
                    mPrimitiveFields.mPrevWidth = mPrimitiveFields.mWidth;
                    mPrimitiveFields.mPrevHeight = mPrimitiveFields.mHeight;
                    mPrimitiveFields.mPivotX = mPrimitiveFields.mPrevWidth / 2.0f;
                    mPrimitiveFields.mPivotY = mPrimitiveFields.mPrevHeight / 2.0f;
                }
            }

            if ((mPrimitiveFields.mMatrixFlags & ROTATION_3D) == 0) {
                mComputedFields.mTransformMatrix->loadTranslate(
                        mPrimitiveFields.mPivotX + mPrimitiveFields.mTranslationX,
                        mPrimitiveFields.mPivotY + mPrimitiveFields.mTranslationY,
                        0);
                mComputedFields.mTransformMatrix->rotate(mPrimitiveFields.mRotation, 0, 0, 1);
                mComputedFields.mTransformMatrix->scale(mPrimitiveFields.mScaleX, mPrimitiveFields.mScaleY, 1);
                mComputedFields.mTransformMatrix->translate(-mPrimitiveFields.mPivotX, -mPrimitiveFields.mPivotY);
            } else {
                if (!mComputedFields.mTransformCamera) {
                    mComputedFields.mTransformCamera = new Sk3DView();
                    mComputedFields.mTransformMatrix3D = new SkMatrix();
                }
                SkMatrix transformMatrix;
                transformMatrix.reset();
                mComputedFields.mTransformCamera->save();
                transformMatrix.preScale(mPrimitiveFields.mScaleX, mPrimitiveFields.mScaleY, mPrimitiveFields.mPivotX, mPrimitiveFields.mPivotY);
                mComputedFields.mTransformCamera->rotateX(mPrimitiveFields.mRotationX);
                mComputedFields.mTransformCamera->rotateY(mPrimitiveFields.mRotationY);
                mComputedFields.mTransformCamera->rotateZ(-mPrimitiveFields.mRotation);
                mComputedFields.mTransformCamera->getMatrix(mComputedFields.mTransformMatrix3D);
                mComputedFields.mTransformMatrix3D->preTranslate(-mPrimitiveFields.mPivotX, -mPrimitiveFields.mPivotY);
                mComputedFields.mTransformMatrix3D->postTranslate(mPrimitiveFields.mPivotX + mPrimitiveFields.mTranslationX,
                        mPrimitiveFields.mPivotY + mPrimitiveFields.mTranslationY);
                transformMatrix.postConcat(*mComputedFields.mTransformMatrix3D);
                mComputedFields.mTransformCamera->restore();

                mComputedFields.mTransformMatrix->load(transformMatrix);
            }
        }
        mPrimitiveFields.mMatrixDirty = false;
    }
}

void RenderProperties::updateClipPath() {
    const SkPath* outlineClipPath = mPrimitiveFields.mOutline.willClip()
            ? mPrimitiveFields.mOutline.getPath() : NULL;
    const SkPath* revealClipPath = mPrimitiveFields.mRevealClip.getPath();

    if (!outlineClipPath && !revealClipPath) {
        // mComputedFields.mClipPath doesn't need to be updated, since it won't be used
        return;
    }

    if (mComputedFields.mClipPath == NULL) {
        mComputedFields.mClipPath = new SkPath();
    }
    SkPath* clipPath = mComputedFields.mClipPath;
    mComputedFields.mClipPathOp = SkRegion::kIntersect_Op;

    if (outlineClipPath && revealClipPath) {
        SkPathOp op = kIntersect_PathOp;
        if (mPrimitiveFields.mRevealClip.isInverseClip()) {
            op = kDifference_PathOp; // apply difference step in the Op below, instead of draw time
        }

        Op(*outlineClipPath, *revealClipPath, op, clipPath);
    } else if (outlineClipPath) {
        *clipPath = *outlineClipPath;
    } else {
        *clipPath = *revealClipPath;
        if (mPrimitiveFields.mRevealClip.isInverseClip()) {
            // apply difference step at draw time
            mComputedFields.mClipPathOp = SkRegion::kDifference_Op;
        }
    }
}

} /* namespace uirenderer */
} /* namespace android */
