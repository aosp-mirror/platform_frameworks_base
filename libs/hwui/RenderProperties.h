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

#include <algorithm>
#include <stddef.h>
#include <vector>
#include <cutils/compiler.h>
#include <androidfw/ResourceTypes.h>

#include <SkCamera.h>
#include <SkMatrix.h>
#include <SkRegion.h>

#include "Animator.h"
#include "Rect.h"
#include "RevealClip.h"
#include "Outline.h"

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

    void setElevation(float elevation) {
        if (elevation != mPrimitiveFields.mElevation) {
            mPrimitiveFields.mElevation = elevation;
            // mMatrixOrPivotDirty not set, since matrix doesn't respect Z
        }
    }

    float getElevation() const {
        return mPrimitiveFields.mElevation;
    }

    void setTranslationX(float translationX) {
        if (translationX != mPrimitiveFields.mTranslationX) {
            mPrimitiveFields.mTranslationX = translationX;
            mPrimitiveFields.mMatrixOrPivotDirty = true;
        }
    }

    float getTranslationX() const {
        return mPrimitiveFields.mTranslationX;
    }

    void setTranslationY(float translationY) {
        if (translationY != mPrimitiveFields.mTranslationY) {
            mPrimitiveFields.mTranslationY = translationY;
            mPrimitiveFields.mMatrixOrPivotDirty = true;
        }
    }

    float getTranslationY() const {
        return mPrimitiveFields.mTranslationY;
    }

    void setTranslationZ(float translationZ) {
        if (translationZ != mPrimitiveFields.mTranslationZ) {
            mPrimitiveFields.mTranslationZ = translationZ;
            // mMatrixOrPivotDirty not set, since matrix doesn't respect Z
        }
    }

    float getTranslationZ() const {
        return mPrimitiveFields.mTranslationZ;
    }

    // Animation helper
    void setX(float value) {
        setTranslationX(value - getLeft());
    }

    // Animation helper
    float getX() const {
        return getLeft() + getTranslationX();
    }

    // Animation helper
    void setY(float value) {
        setTranslationY(value - getTop());
    }

    // Animation helper
    float getY() const {
        return getTop() + getTranslationY();
    }

    // Animation helper
    void setZ(float value) {
        setTranslationZ(value - getElevation());
    }

    float getZ() const {
        return getElevation() + getTranslationZ();
    }

    void setRotation(float rotation) {
        if (rotation != mPrimitiveFields.mRotation) {
            mPrimitiveFields.mRotation = rotation;
            mPrimitiveFields.mMatrixOrPivotDirty = true;
        }
    }

    float getRotation() const {
        return mPrimitiveFields.mRotation;
    }

    void setRotationX(float rotationX) {
        if (rotationX != mPrimitiveFields.mRotationX) {
            mPrimitiveFields.mRotationX = rotationX;
            mPrimitiveFields.mMatrixOrPivotDirty = true;
        }
    }

    float getRotationX() const {
        return mPrimitiveFields.mRotationX;
    }

    void setRotationY(float rotationY) {
        if (rotationY != mPrimitiveFields.mRotationY) {
            mPrimitiveFields.mRotationY = rotationY;
            mPrimitiveFields.mMatrixOrPivotDirty = true;
        }
    }

    float getRotationY() const {
        return mPrimitiveFields.mRotationY;
    }

    void setScaleX(float scaleX) {
        if (scaleX != mPrimitiveFields.mScaleX) {
            mPrimitiveFields.mScaleX = scaleX;
            mPrimitiveFields.mMatrixOrPivotDirty = true;
        }
    }

    float getScaleX() const {
        return mPrimitiveFields.mScaleX;
    }

    void setScaleY(float scaleY) {
        if (scaleY != mPrimitiveFields.mScaleY) {
            mPrimitiveFields.mScaleY = scaleY;
            mPrimitiveFields.mMatrixOrPivotDirty = true;
        }
    }

    float getScaleY() const {
        return mPrimitiveFields.mScaleY;
    }

    void setPivotX(float pivotX) {
        mPrimitiveFields.mPivotX = pivotX;
        mPrimitiveFields.mMatrixOrPivotDirty = true;
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
        mPrimitiveFields.mMatrixOrPivotDirty = true;
        mPrimitiveFields.mPivotExplicitlySet = true;
    }

    float getPivotY() const {
        return mPrimitiveFields.mPivotY;
    }

    bool isPivotExplicitlySet() const {
        return mPrimitiveFields.mPivotExplicitlySet;
    }

    void setCameraDistance(float distance) {
        if (distance != getCameraDistance()) {
            mPrimitiveFields.mMatrixOrPivotDirty = true;
            mComputedFields.mTransformCamera.setCameraLocation(0, 0, distance);
        }
    }

    float getCameraDistance() const {
        // TODO: update getCameraLocationZ() to be const
        return const_cast<Sk3DView*>(&mComputedFields.mTransformCamera)->getCameraLocationZ();
    }

    void setLeft(int left) {
        if (left != mPrimitiveFields.mLeft) {
            mPrimitiveFields.mLeft = left;
            mPrimitiveFields.mWidth = mPrimitiveFields.mRight - mPrimitiveFields.mLeft;
            if (!mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixOrPivotDirty = true;
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
            if (!mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixOrPivotDirty = true;
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
            if (!mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixOrPivotDirty = true;
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
            if (!mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixOrPivotDirty = true;
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
            if (!mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixOrPivotDirty = true;
            }
        }
    }

    void setLeftTopRightBottom(int left, int top, int right, int bottom) {
        if (left != mPrimitiveFields.mLeft || top != mPrimitiveFields.mTop
                || right != mPrimitiveFields.mRight || bottom != mPrimitiveFields.mBottom) {
            mPrimitiveFields.mLeft = left;
            mPrimitiveFields.mTop = top;
            mPrimitiveFields.mRight = right;
            mPrimitiveFields.mBottom = bottom;
            mPrimitiveFields.mWidth = mPrimitiveFields.mRight - mPrimitiveFields.mLeft;
            mPrimitiveFields.mHeight = mPrimitiveFields.mBottom - mPrimitiveFields.mTop;
            if (!mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixOrPivotDirty = true;
            }
        }
    }

    void offsetLeftRight(float offset) {
        if (offset != 0) {
            mPrimitiveFields.mLeft += offset;
            mPrimitiveFields.mRight += offset;
            if (!mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixOrPivotDirty = true;
            }
        }
    }

    void offsetTopBottom(float offset) {
        if (offset != 0) {
            mPrimitiveFields.mTop += offset;
            mPrimitiveFields.mBottom += offset;
            if (!mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixOrPivotDirty = true;
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

    bool hasTransformMatrix() const {
        return getTransformMatrix() && !getTransformMatrix()->isIdentity();
    }

    // May only call this if hasTransformMatrix() is true
    bool isTransformTranslateOnly() const {
        return getTransformMatrix()->getType() == SkMatrix::kTranslate_Mask;
    }

    const SkMatrix* getTransformMatrix() const {
        LOG_ALWAYS_FATAL_IF(mPrimitiveFields.mMatrixOrPivotDirty, "Cannot get a dirty matrix!");
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

    bool hasClippingPath() const {
        return mPrimitiveFields.mRevealClip.willClip();
    }

    const SkPath* getClippingPath() const {
        return mPrimitiveFields.mRevealClip.getPath();
    }

    SkRegion::Op getClippingPathOp() const {
        return mPrimitiveFields.mRevealClip.isInverseClip()
                ? SkRegion::kDifference_Op : SkRegion::kIntersect_Op;
    }

    Outline& mutableOutline() {
        return mPrimitiveFields.mOutline;
    }

    RevealClip& mutableRevealClip() {
        return mPrimitiveFields.mRevealClip;
    }

private:

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
        float mElevation;
        float mTranslationX, mTranslationY, mTranslationZ;
        float mRotation, mRotationX, mRotationY;
        float mScaleX, mScaleY;
        float mPivotX, mPivotY;
        int mLeft, mTop, mRight, mBottom;
        int mWidth, mHeight;
        bool mPivotExplicitlySet;
        bool mMatrixOrPivotDirty;
        bool mCaching;
    } mPrimitiveFields;

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
         * In the common translation-only case, the matrix isn't necessarily allocated,
         * and the mTranslation properties are used directly.
         */
        SkMatrix* mTransformMatrix;

        Sk3DView mTransformCamera;
    } mComputedFields;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* RENDERNODEPROPERTIES_H */
