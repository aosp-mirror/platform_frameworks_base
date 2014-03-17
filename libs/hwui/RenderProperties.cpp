/*
 * Copyright (C) 2014 The Android Open Source Project
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
#include "RenderProperties.h"

#include <SkMatrix.h>

#include "Matrix.h"

namespace android {
namespace uirenderer {

RenderProperties::RenderProperties()
        : mClipToBounds(true)
        , mProjectBackwards(false)
        , mProjectionReceiver(false)
        , mClipToOutline(false)
        , mCastsShadow(false)
        , mUsesGlobalCamera(false) // TODO: respect value when rendering
        , mAlpha(1)
        , mHasOverlappingRendering(true)
        , mTranslationX(0), mTranslationY(0), mTranslationZ(0)
        , mRotation(0), mRotationX(0), mRotationY(0)
        , mScaleX(1), mScaleY(1)
        , mPivotX(0), mPivotY(0)
        , mCameraDistance(0)
        , mLeft(0), mTop(0), mRight(0), mBottom(0)
        , mWidth(0), mHeight(0)
        , mPrevWidth(-1), mPrevHeight(-1)
        , mPivotExplicitlySet(false)
        , mMatrixDirty(false)
        , mMatrixIsIdentity(true)
        , mTransformMatrix(NULL)
        , mMatrixFlags(0)
        , mTransformCamera(NULL)
        , mTransformMatrix3D(NULL)
        , mStaticMatrix(NULL)
        , mAnimationMatrix(NULL)
        , mCaching(false) {
    mOutline.rewind();
}

RenderProperties::~RenderProperties() {
    delete mTransformMatrix;
    delete mTransformCamera;
    delete mTransformMatrix3D;
    delete mStaticMatrix;
    delete mAnimationMatrix;
}

float RenderProperties::getPivotX() {
    updateMatrix();
    return mPivotX;
}

float RenderProperties::getPivotY() {
    updateMatrix();
    return mPivotY;
}

void RenderProperties::updateMatrix() {
    if (mMatrixDirty) {
        // NOTE: mTransformMatrix won't be up to date if a DisplayList goes from a complex transform
        // to a pure translate. This is safe because the matrix isn't read in pure translate cases.
        if (mMatrixFlags && mMatrixFlags != TRANSLATION) {
            if (!mTransformMatrix) {
                // only allocate a matrix if we have a complex transform
                mTransformMatrix = new Matrix4();
            }
            if (!mPivotExplicitlySet) {
                if (mWidth != mPrevWidth || mHeight != mPrevHeight) {
                    mPrevWidth = mWidth;
                    mPrevHeight = mHeight;
                    mPivotX = mPrevWidth / 2.0f;
                    mPivotY = mPrevHeight / 2.0f;
                }
            }

            if ((mMatrixFlags & ROTATION_3D) == 0) {
                mTransformMatrix->loadTranslate(
                        mPivotX + mTranslationX,
                        mPivotY + mTranslationY,
                        0);
                mTransformMatrix->rotate(mRotation, 0, 0, 1);
                mTransformMatrix->scale(mScaleX, mScaleY, 1);
                mTransformMatrix->translate(-mPivotX, -mPivotY);
            } else {
                if (!mTransformCamera) {
                    mTransformCamera = new Sk3DView();
                    mTransformMatrix3D = new SkMatrix();
                }
                SkMatrix transformMatrix;
                transformMatrix.reset();
                mTransformCamera->save();
                transformMatrix.preScale(mScaleX, mScaleY, mPivotX, mPivotY);
                mTransformCamera->rotateX(mRotationX);
                mTransformCamera->rotateY(mRotationY);
                mTransformCamera->rotateZ(-mRotation);
                mTransformCamera->getMatrix(mTransformMatrix3D);
                mTransformMatrix3D->preTranslate(-mPivotX, -mPivotY);
                mTransformMatrix3D->postTranslate(mPivotX + mTranslationX,
                        mPivotY + mTranslationY);
                transformMatrix.postConcat(*mTransformMatrix3D);
                mTransformCamera->restore();

                mTransformMatrix->load(transformMatrix);
            }
        }
        mMatrixDirty = false;
    }
}

} /* namespace uirenderer */
} /* namespace android */
