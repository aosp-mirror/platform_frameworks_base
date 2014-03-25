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
#ifndef RENDERNODEPROPERTIES_H
#define RENDERNODEPROPERTIES_H

#include <stddef.h>
#include <cutils/compiler.h>
#include <androidfw/ResourceTypes.h>

#include <SkCamera.h>
#include <SkMatrix.h>

#include "Rect.h"
#include "Outline.h"

#define TRANSLATION 0x0001
#define ROTATION    0x0002
#define ROTATION_3D 0x0004
#define SCALE       0x0008
#define PIVOT       0x0010

class SkBitmap;
class SkPaint;
class SkRegion;

namespace android {
namespace uirenderer {

class Matrix4;
class RenderNode;

/*
 * Data structure that holds the properties for a RenderNode
 */
class RenderProperties {
public:
    RenderProperties();
    virtual ~RenderProperties();

    void setClipToBounds(bool clipToBounds) {
        mClipToBounds = clipToBounds;
    }

    void setProjectBackwards(bool shouldProject) {
        mProjectBackwards = shouldProject;
    }

    void setProjectionReceiver(bool shouldRecieve) {
        mProjectionReceiver = shouldRecieve;
    }

    bool isProjectionReceiver() {
        return mProjectionReceiver;
    }

    void setStaticMatrix(SkMatrix* matrix) {
        delete mStaticMatrix;
        mStaticMatrix = new SkMatrix(*matrix);
    }

    // Can return NULL
    SkMatrix* getStaticMatrix() {
        return mStaticMatrix;
    }

    void setAnimationMatrix(SkMatrix* matrix) {
        delete mAnimationMatrix;
        if (matrix) {
            mAnimationMatrix = new SkMatrix(*matrix);
        } else {
            mAnimationMatrix = NULL;
        }
    }

    void setAlpha(float alpha) {
        alpha = fminf(1.0f, fmaxf(0.0f, alpha));
        if (alpha != mAlpha) {
            mAlpha = alpha;
        }
    }

    float getAlpha() const {
        return mAlpha;
    }

    void setHasOverlappingRendering(bool hasOverlappingRendering) {
        mHasOverlappingRendering = hasOverlappingRendering;
    }

    bool hasOverlappingRendering() const {
        return mHasOverlappingRendering;
    }

    void setTranslationX(float translationX) {
        if (translationX != mTranslationX) {
            mTranslationX = translationX;
            onTranslationUpdate();
        }
    }

    float getTranslationX() const {
        return mTranslationX;
    }

    void setTranslationY(float translationY) {
        if (translationY != mTranslationY) {
            mTranslationY = translationY;
            onTranslationUpdate();
        }
    }

    float getTranslationY() const {
        return mTranslationY;
    }

    void setTranslationZ(float translationZ) {
        if (translationZ != mTranslationZ) {
            mTranslationZ = translationZ;
            onTranslationUpdate();
        }
    }

    float getTranslationZ() const {
        return mTranslationZ;
    }

    void setRotation(float rotation) {
        if (rotation != mRotation) {
            mRotation = rotation;
            mMatrixDirty = true;
            if (mRotation == 0.0f) {
                mMatrixFlags &= ~ROTATION;
            } else {
                mMatrixFlags |= ROTATION;
            }
        }
    }

    float getRotation() const {
        return mRotation;
    }

    void setRotationX(float rotationX) {
        if (rotationX != mRotationX) {
            mRotationX = rotationX;
            mMatrixDirty = true;
            if (mRotationX == 0.0f && mRotationY == 0.0f) {
                mMatrixFlags &= ~ROTATION_3D;
            } else {
                mMatrixFlags |= ROTATION_3D;
            }
        }
    }

    float getRotationX() const {
        return mRotationX;
    }

    void setRotationY(float rotationY) {
        if (rotationY != mRotationY) {
            mRotationY = rotationY;
            mMatrixDirty = true;
            if (mRotationX == 0.0f && mRotationY == 0.0f) {
                mMatrixFlags &= ~ROTATION_3D;
            } else {
                mMatrixFlags |= ROTATION_3D;
            }
        }
    }

    float getRotationY() const {
        return mRotationY;
    }

    void setScaleX(float scaleX) {
        if (scaleX != mScaleX) {
            mScaleX = scaleX;
            mMatrixDirty = true;
            if (mScaleX == 1.0f && mScaleY == 1.0f) {
                mMatrixFlags &= ~SCALE;
            } else {
                mMatrixFlags |= SCALE;
            }
        }
    }

    float getScaleX() const {
        return mScaleX;
    }

    void setScaleY(float scaleY) {
        if (scaleY != mScaleY) {
            mScaleY = scaleY;
            mMatrixDirty = true;
            if (mScaleX == 1.0f && mScaleY == 1.0f) {
                mMatrixFlags &= ~SCALE;
            } else {
                mMatrixFlags |= SCALE;
            }
        }
    }

    float getScaleY() const {
        return mScaleY;
    }

    void setPivotX(float pivotX) {
        mPivotX = pivotX;
        mMatrixDirty = true;
        if (mPivotX == 0.0f && mPivotY == 0.0f) {
            mMatrixFlags &= ~PIVOT;
        } else {
            mMatrixFlags |= PIVOT;
        }
        mPivotExplicitlySet = true;
    }

    ANDROID_API float getPivotX();

    void setPivotY(float pivotY) {
        mPivotY = pivotY;
        mMatrixDirty = true;
        if (mPivotX == 0.0f && mPivotY == 0.0f) {
            mMatrixFlags &= ~PIVOT;
        } else {
            mMatrixFlags |= PIVOT;
        }
        mPivotExplicitlySet = true;
    }

    ANDROID_API float getPivotY();

    void setCameraDistance(float distance) {
        if (distance != mCameraDistance) {
            mCameraDistance = distance;
            mMatrixDirty = true;
            if (!mTransformCamera) {
                mTransformCamera = new Sk3DView();
                mTransformMatrix3D = new SkMatrix();
            }
            mTransformCamera->setCameraLocation(0, 0, distance);
        }
    }

    float getCameraDistance() const {
        return mCameraDistance;
    }

    void setLeft(int left) {
        if (left != mLeft) {
            mLeft = left;
            mWidth = mRight - mLeft;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    float getLeft() const {
        return mLeft;
    }

    void setTop(int top) {
        if (top != mTop) {
            mTop = top;
            mHeight = mBottom - mTop;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    float getTop() const {
        return mTop;
    }

    void setRight(int right) {
        if (right != mRight) {
            mRight = right;
            mWidth = mRight - mLeft;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    float getRight() const {
        return mRight;
    }

    void setBottom(int bottom) {
        if (bottom != mBottom) {
            mBottom = bottom;
            mHeight = mBottom - mTop;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    float getBottom() const {
        return mBottom;
    }

    void setLeftTop(int left, int top) {
        if (left != mLeft || top != mTop) {
            mLeft = left;
            mTop = top;
            mWidth = mRight - mLeft;
            mHeight = mBottom - mTop;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    void setLeftTopRightBottom(int left, int top, int right, int bottom) {
        if (left != mLeft || top != mTop || right != mRight || bottom != mBottom) {
            mLeft = left;
            mTop = top;
            mRight = right;
            mBottom = bottom;
            mWidth = mRight - mLeft;
            mHeight = mBottom - mTop;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    void offsetLeftRight(float offset) {
        if (offset != 0) {
            mLeft += offset;
            mRight += offset;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    void offsetTopBottom(float offset) {
        if (offset != 0) {
            mTop += offset;
            mBottom += offset;
            if (mMatrixFlags > TRANSLATION && !mPivotExplicitlySet) {
                mMatrixDirty = true;
            }
        }
    }

    void setCaching(bool caching) {
        mCaching = caching;
    }

    int getWidth() const {
        return mWidth;
    }

    int getHeight() const {
        return mHeight;
    }

    Outline& outline() {
        return mOutline;
    }

private:
    void onTranslationUpdate() {
        mMatrixDirty = true;
        if (mTranslationX == 0.0f && mTranslationY == 0.0f && mTranslationZ == 0.0f) {
            mMatrixFlags &= ~TRANSLATION;
        } else {
            mMatrixFlags |= TRANSLATION;
        }
    }

    void updateMatrix();

    // Rendering properties
    Outline mOutline;
    bool mClipToBounds;
    bool mProjectBackwards;
    bool mProjectionReceiver;
    float mAlpha;
    bool mHasOverlappingRendering;
    float mTranslationX, mTranslationY, mTranslationZ;
    float mRotation, mRotationX, mRotationY;
    float mScaleX, mScaleY;
    float mPivotX, mPivotY;
    float mCameraDistance;
    int mLeft, mTop, mRight, mBottom;
    int mWidth, mHeight;
    int mPrevWidth, mPrevHeight;
    bool mPivotExplicitlySet;
    bool mMatrixDirty;
    bool mMatrixIsIdentity;

    /**
     * Stores the total transformation of the DisplayList based upon its scalar
     * translate/rotate/scale properties.
     *
     * In the common translation-only case, the matrix isn't allocated and the mTranslation
     * properties are used directly.
     */
    Matrix4* mTransformMatrix;
    uint32_t mMatrixFlags;
    Sk3DView* mTransformCamera;
    SkMatrix* mTransformMatrix3D;
    SkMatrix* mStaticMatrix;
    SkMatrix* mAnimationMatrix;
    bool mCaching;

    friend class RenderNode;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* RENDERNODEPROPERTIES_H */
