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

// The __VA_ARGS__ will be executed if a & b are not equal
#define RP_SET(a, b, ...) (a != b ? (a = b, ##__VA_ARGS__, true) : false)
#define RP_SET_AND_DIRTY(a, b) RP_SET(a, b, mPrimitiveFields.mMatrixOrPivotDirty = true)

/*
 * Data structure that holds the properties for a RenderNode
 */
class RenderProperties {
public:
    RenderProperties();
    virtual ~RenderProperties();

    RenderProperties& operator=(const RenderProperties& other);

    bool setClipToBounds(bool clipToBounds) {
        return RP_SET(mPrimitiveFields.mClipToBounds, clipToBounds);
    }

    bool setProjectBackwards(bool shouldProject) {
        return RP_SET(mPrimitiveFields.mProjectBackwards, shouldProject);
    }

    bool setProjectionReceiver(bool shouldRecieve) {
        return RP_SET(mPrimitiveFields.mProjectionReceiver, shouldRecieve);
    }

    bool isProjectionReceiver() const {
        return mPrimitiveFields.mProjectionReceiver;
    }

    bool setStaticMatrix(const SkMatrix* matrix) {
        delete mStaticMatrix;
        if (matrix) {
            mStaticMatrix = new SkMatrix(*matrix);
        } else {
            mStaticMatrix = NULL;
        }
        return true;
    }

    // Can return NULL
    const SkMatrix* getStaticMatrix() const {
        return mStaticMatrix;
    }

    bool setAnimationMatrix(const SkMatrix* matrix) {
        delete mAnimationMatrix;
        if (matrix) {
            mAnimationMatrix = new SkMatrix(*matrix);
        } else {
            mAnimationMatrix = NULL;
        }
        return true;
    }

    bool setAlpha(float alpha) {
        alpha = fminf(1.0f, fmaxf(0.0f, alpha));
        return RP_SET(mPrimitiveFields.mAlpha, alpha);
    }

    float getAlpha() const {
        return mPrimitiveFields.mAlpha;
    }

    bool setHasOverlappingRendering(bool hasOverlappingRendering) {
        return RP_SET(mPrimitiveFields.mHasOverlappingRendering, hasOverlappingRendering);
    }

    bool hasOverlappingRendering() const {
        return mPrimitiveFields.mHasOverlappingRendering;
    }

    bool setElevation(float elevation) {
        return RP_SET(mPrimitiveFields.mElevation, elevation);
        // Don't dirty matrix/pivot, since they don't respect Z
    }

    float getElevation() const {
        return mPrimitiveFields.mElevation;
    }

    bool setTranslationX(float translationX) {
        return RP_SET_AND_DIRTY(mPrimitiveFields.mTranslationX, translationX);
    }

    float getTranslationX() const {
        return mPrimitiveFields.mTranslationX;
    }

    bool setTranslationY(float translationY) {
        return RP_SET_AND_DIRTY(mPrimitiveFields.mTranslationY, translationY);
    }

    float getTranslationY() const {
        return mPrimitiveFields.mTranslationY;
    }

    bool setTranslationZ(float translationZ) {
        return RP_SET(mPrimitiveFields.mTranslationZ, translationZ);
        // mMatrixOrPivotDirty not set, since matrix doesn't respect Z
    }

    float getTranslationZ() const {
        return mPrimitiveFields.mTranslationZ;
    }

    // Animation helper
    bool setX(float value) {
        return setTranslationX(value - getLeft());
    }

    // Animation helper
    float getX() const {
        return getLeft() + getTranslationX();
    }

    // Animation helper
    bool setY(float value) {
        return setTranslationY(value - getTop());
    }

    // Animation helper
    float getY() const {
        return getTop() + getTranslationY();
    }

    // Animation helper
    bool setZ(float value) {
        return setTranslationZ(value - getElevation());
    }

    float getZ() const {
        return getElevation() + getTranslationZ();
    }

    bool setRotation(float rotation) {
        return RP_SET_AND_DIRTY(mPrimitiveFields.mRotation, rotation);
    }

    float getRotation() const {
        return mPrimitiveFields.mRotation;
    }

    bool setRotationX(float rotationX) {
        return RP_SET_AND_DIRTY(mPrimitiveFields.mRotationX, rotationX);
    }

    float getRotationX() const {
        return mPrimitiveFields.mRotationX;
    }

    bool setRotationY(float rotationY) {
        return RP_SET_AND_DIRTY(mPrimitiveFields.mRotationY, rotationY);
    }

    float getRotationY() const {
        return mPrimitiveFields.mRotationY;
    }

    bool setScaleX(float scaleX) {
        return RP_SET_AND_DIRTY(mPrimitiveFields.mScaleX, scaleX);
    }

    float getScaleX() const {
        return mPrimitiveFields.mScaleX;
    }

    bool setScaleY(float scaleY) {
        return RP_SET_AND_DIRTY(mPrimitiveFields.mScaleY, scaleY);
    }

    float getScaleY() const {
        return mPrimitiveFields.mScaleY;
    }

    bool setPivotX(float pivotX) {
        if (RP_SET(mPrimitiveFields.mPivotX, pivotX)
                || !mPrimitiveFields.mPivotExplicitlySet) {
            mPrimitiveFields.mMatrixOrPivotDirty = true;
            mPrimitiveFields.mPivotExplicitlySet = true;
            return true;
        }
        return false;
    }

    /* Note that getPivotX and getPivotY are adjusted by updateMatrix(),
     * so the value returned may be stale if the RenderProperties has been
     * modified since the last call to updateMatrix()
     */
    float getPivotX() const {
        return mPrimitiveFields.mPivotX;
    }

    bool setPivotY(float pivotY) {
        if (RP_SET(mPrimitiveFields.mPivotY, pivotY)
                || !mPrimitiveFields.mPivotExplicitlySet) {
            mPrimitiveFields.mMatrixOrPivotDirty = true;
            mPrimitiveFields.mPivotExplicitlySet = true;
            return true;
        }
        return false;
    }

    float getPivotY() const {
        return mPrimitiveFields.mPivotY;
    }

    bool isPivotExplicitlySet() const {
        return mPrimitiveFields.mPivotExplicitlySet;
    }

    bool setCameraDistance(float distance) {
        if (distance != getCameraDistance()) {
            mPrimitiveFields.mMatrixOrPivotDirty = true;
            mComputedFields.mTransformCamera.setCameraLocation(0, 0, distance);
            return true;
        }
        return false;
    }

    float getCameraDistance() const {
        // TODO: update getCameraLocationZ() to be const
        return const_cast<Sk3DView*>(&mComputedFields.mTransformCamera)->getCameraLocationZ();
    }

    bool setLeft(int left) {
        if (RP_SET(mPrimitiveFields.mLeft, left)) {
            mPrimitiveFields.mWidth = mPrimitiveFields.mRight - mPrimitiveFields.mLeft;
            if (!mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixOrPivotDirty = true;
            }
            return true;
        }
        return false;
    }

    float getLeft() const {
        return mPrimitiveFields.mLeft;
    }

    bool setTop(int top) {
        if (RP_SET(mPrimitiveFields.mTop, top)) {
            mPrimitiveFields.mHeight = mPrimitiveFields.mBottom - mPrimitiveFields.mTop;
            if (!mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixOrPivotDirty = true;
            }
            return true;
        }
        return false;
    }

    float getTop() const {
        return mPrimitiveFields.mTop;
    }

    bool setRight(int right) {
        if (RP_SET(mPrimitiveFields.mRight, right)) {
            mPrimitiveFields.mWidth = mPrimitiveFields.mRight - mPrimitiveFields.mLeft;
            if (!mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixOrPivotDirty = true;
            }
            return true;
        }
        return false;
    }

    float getRight() const {
        return mPrimitiveFields.mRight;
    }

    bool setBottom(int bottom) {
        if (RP_SET(mPrimitiveFields.mBottom, bottom)) {
            mPrimitiveFields.mHeight = mPrimitiveFields.mBottom - mPrimitiveFields.mTop;
            if (!mPrimitiveFields.mPivotExplicitlySet) {
                mPrimitiveFields.mMatrixOrPivotDirty = true;
            }
            return true;
        }
        return false;
    }

    float getBottom() const {
        return mPrimitiveFields.mBottom;
    }

    bool setLeftTop(int left, int top) {
        bool leftResult = setLeft(left);
        bool topResult = setTop(top);
        return leftResult || topResult;
    }

    bool setLeftTopRightBottom(int left, int top, int right, int bottom) {
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
            return true;
        }
        return false;
    }

    bool offsetLeftRight(float offset) {
        if (offset != 0) {
            mPrimitiveFields.mLeft += offset;
            mPrimitiveFields.mRight += offset;
            return true;
        }
        return false;
    }

    bool offsetTopBottom(float offset) {
        if (offset != 0) {
            mPrimitiveFields.mTop += offset;
            mPrimitiveFields.mBottom += offset;
            return true;
        }
        return false;
    }

    bool setScrollX(int scrollX) {
        return RP_SET(mPrimitiveFields.mScrollX, scrollX);
    }

    bool setScrollY(int scrollY) {
        return RP_SET(mPrimitiveFields.mScrollY, scrollY);
    }

    int getScrollX() const {
        return mPrimitiveFields.mScrollX;
    }

    int getScrollY() const {
        return mPrimitiveFields.mScrollY;
    }

    bool setCaching(bool caching) {
        return RP_SET(mPrimitiveFields.mCaching, caching);
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
        int mScrollX, mScrollY;
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
