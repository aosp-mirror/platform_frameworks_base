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
#include <SkRegion.h>

#include "Rect.h"
#include "RevealClip.h"
#include "Outline.h"

#define TRANSLATION 0x0001
#define ROTATION    0x0002
#define ROTATION_3D 0x0004
#define SCALE       0x0008
#define PIVOT       0x0010

class SkBitmap;
class SkPaint;

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

    RenderProperties& operator=(const RenderProperties& other);

    void setClipToBounds(bool clipToBounds) {
        mPrimitiveFields.mClipToBounds = clipToBounds;
    }

    void setProjectBackwards(bool shouldProject) {
        mPrimitiveFields.mProjectBackwards = shouldProject;
    }

    void setProjectionReceiver(bool shouldRecieve) {
        mPrimitiveFields.mProjectionReceiver = shouldRecieve;
    }

    bool isProjectionReceiver() const {
        return mPrimitiveFields.mProjectionReceiver;
    }

    void setStaticMatrix(const SkMatrix* matrix) {
        delete mStaticMatrix;
        if (matrix) {
            mStaticMatrix = new SkMatrix(*matrix);
        } else {
            mStaticMatrix = NULL;
        }
    }

    // Can return NULL
    const SkMatrix* getStaticMatrix() const {
        return mStaticMatrix;
    }

    void setAnimationMatrix(const SkMatrix* matrix) {
        delete mAnimationMatrix;
        if (matrix) {
            mAnimationMatrix = new SkMatrix(*matrix);
        } else {
            mAnimationMatrix = NULL;
        }
    }

    void setAlpha(float alpha) {
        alpha = fminf(1.0f, fmaxf(0.0f, alpha));
        if (alpha != mPrimitiveFields.mAlpha) {
            mPrimitiveFields.mAlpha = alpha;
        }
    }

    float getAlpha() const {
        return mPrimitiveFields.mAlpha;
    }

    void setHasOverlappingRendering(bool hasOverlappingRendering) {
        mPrimitiveFields.mHasOverlappingRendering = hasOverlappingRendering;
    }

    bool hasOverlappingRendering() const {
        return mPrimitiveFields.mHasOverlappingRendering;
    }

    void setTranslationX(float translationX) {
        if (translationX != mPrimitiveFields.mTranslationX) {
            mPrimitiveFields.mTranslationX = translationX;
            onTranslationUpdate();
        }
    }

    float getTranslationX() const {
        return mPrimitiveFields.mTranslationX;
    }

    void setTranslationY(float translationY) {
        if (translationY != mPrimitiveFields.mTranslationY) {
            mPrimitiveFields.mTranslationY = translationY;
            onTranslationUpdate();
        }
    }

    float getTranslationY() const {
        return mPrimitiveFields.mTranslationY;
    }

    void setTranslationZ(float translationZ) {
        if (translationZ != mPrimitiveFields.mTranslationZ) {
            mPrimitiveFields.mTranslationZ = translationZ;
            onTranslationUpdate();
        }
    }

    float getTranslationZ() const {
        return mPrimitiveFields.mTranslationZ;
    }

    void setRotation(float rotation) {
        if (rotation != mPrimitiveFields.mRotation) {
            mPrimitiveFields.mRotation = rotation;
            mPrimitiveFields.mMatrixDirty = true;
            if (mPrimitiveFields.mRotation == 0.0f) {
                mPrimitiveFields.mMatrixFlags &= ~ROTATION;
            } else {
                mPrimitiveFields.mMatrixFlags |= ROTATION;
            }
        }
    }

    float getRotation() const {
        return mPrimitiveFields.mRotation;
    }

    void setRotationX(float rotationX) {
        if (rotationX != mPrimitiveFields.mRotationX) {
            mPrimitiveFields.mRotationX = rotationX;
            mPrimitiveFields.mMatrixDirty = true;
            if (mPrimitiveFields.mRotationX == 0.0f && mPrimitiveFields.mRotationY == 0.0f) {
                mPrimitiveFields.mMatrixFlags &= ~ROTATION_3D;
            } else {
                mPrimitiveFields.mMatrixFlags |= ROTATION_3D;
            }
        }
    }

    float getRotationX() const {
        return mPrimitiveFields.mRotationX;
    }

    void setRotationY(float rotationY) {
        if (rotationY != mPrimitiveFields.mRotationY) {
            mPrimitiveFields.mRotationY = rotationY;
            mPrimitiveFields.mMatrixDirty = true;
            if (mPrimitiveFields.mRotationX == 0.0f && mPrimitiveFields.mRotationY == 0.0f) {
                mPrimitiveFields.mMatrixFlags &= ~ROTATION_3D;
            } else {
                mPrimitiveFields.mMatrixFlags |= ROTATION_3D;
            }
        }
    }

    float getRotationY() const {
        return mPrimitiveFields.mRotationY;
    }

    void setScaleX(float scaleX) {
        if (scaleX != mPrimitiveFields.mScaleX) {
            mPrimitiveFields.mScaleX = scaleX;
            mPrimitiveFields.mMatrixDirty = true;
            if (mPrimitiveFields.mScaleX == 1.0f && mPrimitiveFields.mScaleY == 1.0f) {
                mPrimitiveFields.mMatrixFlags &= ~SCALE;
            } else {
                mPrimitiveFields.mMatrixFlags |= SCALE;
            }
        }
    }

    float getScaleX() const {
        return mPrimitiveFields.mScaleX;
    }

    void setScaleY(float scaleY) {
        if (scaleY != mPrimitiveFields.mScaleY) {
            mPrimitiveFields.mScaleY = scaleY;
            mPrimitiveFields.mMatrixDirty = true;
            if (mPrimitiveFields.mScaleX == 1.0f && mPrimitiveFields.mScaleY == 1.0f) {
                mPrimitiveFields.mMatrixFlags &= ~SCALE;
            } else {
                mPrimitiveFields.mMatrixFlags |= SCALE;
            }
        }
    }

    float getScaleY() const {
        return mPrimitiveFields.mScaleY;
    }

    void setPivotX(float pivotX) {
        mPrimitiveFields.mPivotX = pivotX;
        mPrimitiveFields.mMatrixDirty = true;
        if (mPrimitiveFields.mPivotX == 0.0f && mPrimitiveFields.mPivotY == 0.0f) {
            mPrimitiveFields.mMatrixFlags &= ~PIVOT;
        } else {
            mPrimitiveFields.mMatrixFlags |= PIVOT;
        }
        mPrimitiveFields.mPivotExplicitlySet = true;
    }

    /* Note that getPivotX and getPivotY are adjusted by updateMatrix(),
     * so the value returned mPrimitiveFields.may be stale if the RenderProperties has been
     * mPrimitiveFields.modified since the last call to updateMatrix()
     */
    float getPivotX() const {
        return mPrimitiveFields.mPivotX;
    }

    void setPivotY(float pivotY) {
        mPrimitiveFields.mPivotY = pivotY;
        mPrimitiveFields.mMatrixDirty = true;
        if (mPrimitiveFields.mPivotX == 0.0f && mPrimitiveFields.mPivotY == 0.0f) {
            mPrimitiveFields.mMatrixFlags &= ~PIVOT;
        } else {
            mPrimitiveFields.mMatrixFlags |= PIVOT;
        }
        mPrimitiveFields.mPivotExplicitlySet = true;
    }

    float getPivotY() const {
        return mPrimitiveFields.mPivotY;
    }

    void setCameraDistance(float distance) {
        if (distance != mCameraDistance) {
            mCameraDistance = distance;
            mPrimitiveFields.mMatrixDirty = true;
            if (!mComputedFields.mTransformCamera) {
                mComputedFields.mTransformCamera = new Sk3DView();
                mComputedFields.mTransformMatrix3D = new SkMatrix();
            }
            mComputedFields.mTransformCamera->setCameraLocation(0, 0, distance);
        }
    }

    float getCameraDistance() const {
        return mCameraDistance;
    }

    void setLeft(int left) {
        if (left != mPrimitiveFields.mLeft) {
            mPrimitiveFields.mLeft = left;
            mPrimitiveFields.mWidth = mPrimitiveFields.mRight - mPrimitiveFields.mLeft;
            if (mPrimitiveFields.mMatrixFlags > TRANSLATION && !mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixDirty = true;
            }
        }
    }

    float getLeft() const {
        return mPrimitiveFields.mLeft;
    }

    void setTop(int top) {
        if (top != mPrimitiveFields.mTop) {
            mPrimitiveFields.mTop = top;
            mPrimitiveFields.mHeight = mPrimitiveFields.mBottom - mPrimitiveFields.mTop;
            if (mPrimitiveFields.mMatrixFlags > TRANSLATION && !mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixDirty = true;
            }
        }
    }

    float getTop() const {
        return mPrimitiveFields.mTop;
    }

    void setRight(int right) {
        if (right != mPrimitiveFields.mRight) {
            mPrimitiveFields.mRight = right;
            mPrimitiveFields.mWidth = mPrimitiveFields.mRight - mPrimitiveFields.mLeft;
            if (mPrimitiveFields.mMatrixFlags > TRANSLATION && !mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixDirty = true;
            }
        }
    }

    float getRight() const {
        return mPrimitiveFields.mRight;
    }

    void setBottom(int bottom) {
        if (bottom != mPrimitiveFields.mBottom) {
            mPrimitiveFields.mBottom = bottom;
            mPrimitiveFields.mHeight = mPrimitiveFields.mBottom - mPrimitiveFields.mTop;
            if (mPrimitiveFields.mMatrixFlags > TRANSLATION && !mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixDirty = true;
            }
        }
    }

    float getBottom() const {
        return mPrimitiveFields.mBottom;
    }

    void setLeftTop(int left, int top) {
        if (left != mPrimitiveFields.mLeft || top != mPrimitiveFields.mTop) {
            mPrimitiveFields.mLeft = left;
            mPrimitiveFields.mTop = top;
            mPrimitiveFields.mWidth = mPrimitiveFields.mRight - mPrimitiveFields.mLeft;
            mPrimitiveFields.mHeight = mPrimitiveFields.mBottom - mPrimitiveFields.mTop;
            if (mPrimitiveFields.mMatrixFlags > TRANSLATION && !mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixDirty = true;
            }
        }
    }

    void setLeftTopRightBottom(int left, int top, int right, int bottom) {
        if (left != mPrimitiveFields.mLeft || top != mPrimitiveFields.mTop || right != mPrimitiveFields.mRight || bottom != mPrimitiveFields.mBottom) {
            mPrimitiveFields.mLeft = left;
            mPrimitiveFields.mTop = top;
            mPrimitiveFields.mRight = right;
            mPrimitiveFields.mBottom = bottom;
            mPrimitiveFields.mWidth = mPrimitiveFields.mRight - mPrimitiveFields.mLeft;
            mPrimitiveFields.mHeight = mPrimitiveFields.mBottom - mPrimitiveFields.mTop;
            if (mPrimitiveFields.mMatrixFlags > TRANSLATION && !mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixDirty = true;
            }
        }
    }

    void offsetLeftRight(float offset) {
        if (offset != 0) {
            mPrimitiveFields.mLeft += offset;
            mPrimitiveFields.mRight += offset;
            if (mPrimitiveFields.mMatrixFlags > TRANSLATION && !mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixDirty = true;
            }
        }
    }

    void offsetTopBottom(float offset) {
        if (offset != 0) {
            mPrimitiveFields.mTop += offset;
            mPrimitiveFields.mBottom += offset;
            if (mPrimitiveFields.mMatrixFlags > TRANSLATION && !mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixDirty = true;
            }
        }
    }

    void setCaching(bool caching) {
        mPrimitiveFields.mCaching = caching;
    }

    int getWidth() const {
        return mPrimitiveFields.mWidth;
    }

    int getHeight() const {
        return mPrimitiveFields.mHeight;
    }

    const SkMatrix* getAnimationMatrix() const {
        return mAnimationMatrix;
    }

    uint32_t getMatrixFlags() const {
        return mPrimitiveFields.mMatrixFlags;
    }

    const Matrix4* getTransformMatrix() const {
        return mComputedFields.mTransformMatrix;
    }

    bool getCaching() const {
        return mPrimitiveFields.mCaching;
    }

    bool getClipToBounds() const {
        return mPrimitiveFields.mClipToBounds;
    }

    bool getHasOverlappingRendering() const {
        return mPrimitiveFields.mHasOverlappingRendering;
    }

    const Outline& getOutline() const {
        return mPrimitiveFields.mOutline;
    }

    const RevealClip& getRevealClip() const {
        return mPrimitiveFields.mRevealClip;
    }

    bool getProjectBackwards() const {
        return mPrimitiveFields.mProjectBackwards;
    }

    void debugOutputProperties(const int level) const;

    ANDROID_API void updateMatrix();

    ANDROID_API void updateClipPath();

    // signals that mComputedFields.mClipPath is up to date, and should be used for clipping
    bool hasClippingPath() const {
        return mPrimitiveFields.mOutline.willClip() || mPrimitiveFields.mRevealClip.willClip();
    }

    const SkPath* getClippingPath() const {
        return hasClippingPath() ? mComputedFields.mClipPath : NULL;
    }

    SkRegion::Op getClippingPathOp() const {
        return mComputedFields.mClipPathOp;
    }

    Outline& mutableOutline() {
        return mPrimitiveFields.mOutline;
    }

    RevealClip& mutableRevealClip() {
        return mPrimitiveFields.mRevealClip;
    }

private:
    void onTranslationUpdate() {
        mPrimitiveFields.mMatrixDirty = true;
        if (mPrimitiveFields.mTranslationX == 0.0f && mPrimitiveFields.mTranslationY == 0.0f && mPrimitiveFields.mTranslationZ == 0.0f) {
            mPrimitiveFields.mMatrixFlags &= ~TRANSLATION;
        } else {
            mPrimitiveFields.mMatrixFlags |= TRANSLATION;
        }
    }

    // Rendering properties
    struct PrimitiveFields {
        PrimitiveFields();

        Outline mOutline;
        RevealClip mRevealClip;
        bool mClipToBounds;
        bool mProjectBackwards;
        bool mProjectionReceiver;
        float mAlpha;
        bool mHasOverlappingRendering;
        float mTranslationX, mTranslationY, mTranslationZ;
        float mRotation, mRotationX, mRotationY;
        float mScaleX, mScaleY;
        float mPivotX, mPivotY;
        int mLeft, mTop, mRight, mBottom;
        int mWidth, mHeight;
        int mPrevWidth, mPrevHeight;
        bool mPivotExplicitlySet;
        bool mMatrixDirty;
        bool mMatrixIsIdentity;
        uint32_t mMatrixFlags;
        bool mCaching;
    } mPrimitiveFields;

    // mCameraDistance isn't in mPrimitiveFields as it has a complex setter
    float mCameraDistance;
    SkMatrix* mStaticMatrix;
    SkMatrix* mAnimationMatrix;

    /**
     * These fields are all generated from other properties and are not set directly.
     */
    struct ComputedFields {
        ComputedFields();
        ~ComputedFields();

        /**
         * Stores the total transformation of the DisplayList based upon its scalar
         * translate/rotate/scale properties.
         *
         * In the common translation-only case, the matrix isn't allocated and the mTranslation
         * properties are used directly.
         */
        Matrix4* mTransformMatrix;
        Sk3DView* mTransformCamera;
        SkMatrix* mTransformMatrix3D;
        SkPath* mClipPath; // TODO: remove this, create new ops for efficient/special case clipping
        SkRegion::Op mClipPathOp;
    } mComputedFields;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* RENDERNODEPROPERTIES_H */
