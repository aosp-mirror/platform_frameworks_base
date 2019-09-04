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

#pragma once

#include "DeviceInfo.h"
#include "Outline.h"
#include "Rect.h"
#include "RevealClip.h"
#include "utils/MathUtils.h"
#include "utils/PaintUtils.h"

#include <SkBlendMode.h>
#include <SkCamera.h>
#include <SkColor.h>
#include <SkMatrix.h>
#include <SkRegion.h>

#include <androidfw/ResourceTypes.h>
#include <cutils/compiler.h>
#include <stddef.h>
#include <utils/Log.h>
#include <algorithm>
#include <ostream>
#include <vector>

class SkBitmap;
class SkColorFilter;
class SkPaint;

namespace android {
namespace uirenderer {

class Matrix4;
class RenderNode;
class RenderProperties;

// The __VA_ARGS__ will be executed if a & b are not equal
#define RP_SET(a, b, ...) ((a) != (b) ? ((a) = (b), ##__VA_ARGS__, true) : false)
#define RP_SET_AND_DIRTY(a, b) RP_SET(a, b, mPrimitiveFields.mMatrixOrPivotDirty = true)

// Keep in sync with View.java:LAYER_TYPE_*
enum class LayerType {
    None = 0,
    // We cannot build the software layer directly (must be done at record time) and all management
    // of software layers is handled in Java.
    Software = 1,
    RenderLayer = 2,
};

enum ClippingFlags {
    CLIP_TO_BOUNDS = 0x1 << 0,
    CLIP_TO_CLIP_BOUNDS = 0x1 << 1,
};

class ANDROID_API LayerProperties {
public:
    bool setType(LayerType type) {
        if (RP_SET(mType, type)) {
            reset();
            return true;
        }
        return false;
    }

    bool setOpaque(bool opaque) { return RP_SET(mOpaque, opaque); }

    bool opaque() const { return mOpaque; }

    bool setAlpha(uint8_t alpha) { return RP_SET(mAlpha, alpha); }

    uint8_t alpha() const { return mAlpha; }

    bool setXferMode(SkBlendMode mode) { return RP_SET(mMode, mode); }

    SkBlendMode xferMode() const { return mMode; }

    SkColorFilter* getColorFilter() const { return mColorFilter.get(); }

    // Sets alpha, xfermode, and colorfilter from an SkPaint
    // paint may be NULL, in which case defaults will be set
    bool setFromPaint(const SkPaint* paint);

    bool needsBlending() const { return !opaque() || alpha() < 255; }

    LayerProperties& operator=(const LayerProperties& other);

    // Strongly recommend using effectiveLayerType instead
    LayerType type() const { return mType; }

private:
    LayerProperties();
    ~LayerProperties();
    void reset();
    bool setColorFilter(SkColorFilter* filter);

    friend class RenderProperties;

    LayerType mType = LayerType::None;
    // Whether or not that Layer's content is opaque, doesn't include alpha
    bool mOpaque;
    uint8_t mAlpha;
    SkBlendMode mMode;
    sk_sp<SkColorFilter> mColorFilter;
};

/*
 * Data structure that holds the properties for a RenderNode
 */
class ANDROID_API RenderProperties {
public:
    RenderProperties();
    virtual ~RenderProperties();

    static bool setFlag(int flag, bool newValue, int* outFlags) {
        if (newValue) {
            if (!(flag & *outFlags)) {
                *outFlags |= flag;
                return true;
            }
            return false;
        } else {
            if (flag & *outFlags) {
                *outFlags &= ~flag;
                return true;
            }
            return false;
        }
    }

    /**
     * Set internal layer state based on whether this layer
     *
     * Additionally, returns true if child RenderNodes with functors will need to use a layer
     * to support clipping.
     */
    bool prepareForFunctorPresence(bool willHaveFunctor, bool ancestorDictatesFunctorsNeedLayer) {
        // parent may have already dictated that a descendant layer is needed
        bool functorsNeedLayer =
                ancestorDictatesFunctorsNeedLayer
                || CC_UNLIKELY(isClipMayBeComplex())

                // Round rect clipping forces layer for functors
                || CC_UNLIKELY(getOutline().willRoundRectClip()) ||
                CC_UNLIKELY(getRevealClip().willClip())

                // Complex matrices forces layer, due to stencil clipping
                || CC_UNLIKELY(getTransformMatrix() && !getTransformMatrix()->isScaleTranslate()) ||
                CC_UNLIKELY(getAnimationMatrix() && !getAnimationMatrix()->isScaleTranslate()) ||
                CC_UNLIKELY(getStaticMatrix() && !getStaticMatrix()->isScaleTranslate());

        mComputedFields.mNeedLayerForFunctors = (willHaveFunctor && functorsNeedLayer);

        // If on a layer, will have consumed the need for isolating functors from stencil.
        // Thus, it's safe to reset the flag until some descendent sets it.
        return CC_LIKELY(effectiveLayerType() == LayerType::None) && functorsNeedLayer;
    }

    RenderProperties& operator=(const RenderProperties& other);

    bool setClipToBounds(bool clipToBounds) {
        return setFlag(CLIP_TO_BOUNDS, clipToBounds, &mPrimitiveFields.mClippingFlags);
    }

    bool setClipBounds(const Rect& clipBounds) {
        bool ret = setFlag(CLIP_TO_CLIP_BOUNDS, true, &mPrimitiveFields.mClippingFlags);
        return RP_SET(mPrimitiveFields.mClipBounds, clipBounds) || ret;
    }

    bool setClipBoundsEmpty() {
        return setFlag(CLIP_TO_CLIP_BOUNDS, false, &mPrimitiveFields.mClippingFlags);
    }

    bool setProjectBackwards(bool shouldProject) {
        return RP_SET(mPrimitiveFields.mProjectBackwards, shouldProject);
    }

    bool setProjectionReceiver(bool shouldReceive) {
        return RP_SET(mPrimitiveFields.mProjectionReceiver, shouldReceive);
    }

    bool isProjectionReceiver() const { return mPrimitiveFields.mProjectionReceiver; }

    bool setClipMayBeComplex(bool isClipMayBeComplex) {
        return RP_SET(mPrimitiveFields.mClipMayBeComplex, isClipMayBeComplex);
    }

    bool isClipMayBeComplex() const { return mPrimitiveFields.mClipMayBeComplex; }

    bool setStaticMatrix(const SkMatrix* matrix) {
        delete mStaticMatrix;
        if (matrix) {
            mStaticMatrix = new SkMatrix(*matrix);
        } else {
            mStaticMatrix = nullptr;
        }
        return true;
    }

    // Can return NULL
    const SkMatrix* getStaticMatrix() const { return mStaticMatrix; }

    bool setAnimationMatrix(const SkMatrix* matrix) {
        delete mAnimationMatrix;
        if (matrix) {
            mAnimationMatrix = new SkMatrix(*matrix);
        } else {
            mAnimationMatrix = nullptr;
        }
        return true;
    }

    bool setAlpha(float alpha) {
        alpha = MathUtils::clampAlpha(alpha);
        return RP_SET(mPrimitiveFields.mAlpha, alpha);
    }

    float getAlpha() const { return mPrimitiveFields.mAlpha; }

    bool setHasOverlappingRendering(bool hasOverlappingRendering) {
        return RP_SET(mPrimitiveFields.mHasOverlappingRendering, hasOverlappingRendering);
    }

    bool hasOverlappingRendering() const { return mPrimitiveFields.mHasOverlappingRendering; }

    bool setElevation(float elevation) {
        return RP_SET(mPrimitiveFields.mElevation, elevation);
        // Don't dirty matrix/pivot, since they don't respect Z
    }

    float getElevation() const { return mPrimitiveFields.mElevation; }

    bool setTranslationX(float translationX) {
        return RP_SET_AND_DIRTY(mPrimitiveFields.mTranslationX, translationX);
    }

    float getTranslationX() const { return mPrimitiveFields.mTranslationX; }

    bool setTranslationY(float translationY) {
        return RP_SET_AND_DIRTY(mPrimitiveFields.mTranslationY, translationY);
    }

    float getTranslationY() const { return mPrimitiveFields.mTranslationY; }

    bool setTranslationZ(float translationZ) {
        return RP_SET(mPrimitiveFields.mTranslationZ, translationZ);
        // mMatrixOrPivotDirty not set, since matrix doesn't respect Z
    }

    float getTranslationZ() const { return mPrimitiveFields.mTranslationZ; }

    // Animation helper
    bool setX(float value) { return setTranslationX(value - getLeft()); }

    // Animation helper
    float getX() const { return getLeft() + getTranslationX(); }

    // Animation helper
    bool setY(float value) { return setTranslationY(value - getTop()); }

    // Animation helper
    float getY() const { return getTop() + getTranslationY(); }

    // Animation helper
    bool setZ(float value) { return setTranslationZ(value - getElevation()); }

    float getZ() const { return getElevation() + getTranslationZ(); }

    bool setRotation(float rotation) {
        return RP_SET_AND_DIRTY(mPrimitiveFields.mRotation, rotation);
    }

    float getRotation() const { return mPrimitiveFields.mRotation; }

    bool setRotationX(float rotationX) {
        return RP_SET_AND_DIRTY(mPrimitiveFields.mRotationX, rotationX);
    }

    float getRotationX() const { return mPrimitiveFields.mRotationX; }

    bool setRotationY(float rotationY) {
        return RP_SET_AND_DIRTY(mPrimitiveFields.mRotationY, rotationY);
    }

    float getRotationY() const { return mPrimitiveFields.mRotationY; }

    bool setScaleX(float scaleX) { return RP_SET_AND_DIRTY(mPrimitiveFields.mScaleX, scaleX); }

    float getScaleX() const { return mPrimitiveFields.mScaleX; }

    bool setScaleY(float scaleY) { return RP_SET_AND_DIRTY(mPrimitiveFields.mScaleY, scaleY); }

    float getScaleY() const { return mPrimitiveFields.mScaleY; }

    bool setPivotX(float pivotX) {
        if (RP_SET(mPrimitiveFields.mPivotX, pivotX) || !mPrimitiveFields.mPivotExplicitlySet) {
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
    float getPivotX() const { return mPrimitiveFields.mPivotX; }

    bool setPivotY(float pivotY) {
        if (RP_SET(mPrimitiveFields.mPivotY, pivotY) || !mPrimitiveFields.mPivotExplicitlySet) {
            mPrimitiveFields.mMatrixOrPivotDirty = true;
            mPrimitiveFields.mPivotExplicitlySet = true;
            return true;
        }
        return false;
    }

    float getPivotY() const { return mPrimitiveFields.mPivotY; }

    bool isPivotExplicitlySet() const { return mPrimitiveFields.mPivotExplicitlySet; }

    bool resetPivot() { return RP_SET_AND_DIRTY(mPrimitiveFields.mPivotExplicitlySet, false); }

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

    int getLeft() const { return mPrimitiveFields.mLeft; }

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

    int getTop() const { return mPrimitiveFields.mTop; }

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

    int getRight() const { return mPrimitiveFields.mRight; }

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

    int getBottom() const { return mPrimitiveFields.mBottom; }

    bool setLeftTop(int left, int top) {
        bool leftResult = setLeft(left);
        bool topResult = setTop(top);
        return leftResult || topResult;
    }

    bool setLeftTopRightBottom(int left, int top, int right, int bottom) {
        if (left != mPrimitiveFields.mLeft || top != mPrimitiveFields.mTop ||
            right != mPrimitiveFields.mRight || bottom != mPrimitiveFields.mBottom) {
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

    bool offsetLeftRight(int offset) {
        if (offset != 0) {
            mPrimitiveFields.mLeft += offset;
            mPrimitiveFields.mRight += offset;
            return true;
        }
        return false;
    }

    bool offsetTopBottom(int offset) {
        if (offset != 0) {
            mPrimitiveFields.mTop += offset;
            mPrimitiveFields.mBottom += offset;
            return true;
        }
        return false;
    }

    int getWidth() const { return mPrimitiveFields.mWidth; }

    int getHeight() const { return mPrimitiveFields.mHeight; }

    const SkMatrix* getAnimationMatrix() const { return mAnimationMatrix; }

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

    int getClippingFlags() const { return mPrimitiveFields.mClippingFlags; }

    bool getClipToBounds() const { return mPrimitiveFields.mClippingFlags & CLIP_TO_BOUNDS; }

    const Rect& getClipBounds() const { return mPrimitiveFields.mClipBounds; }

    void getClippingRectForFlags(uint32_t flags, Rect* outRect) const {
        if (flags & CLIP_TO_BOUNDS) {
            outRect->set(0, 0, getWidth(), getHeight());
            if (flags & CLIP_TO_CLIP_BOUNDS) {
                outRect->doIntersect(mPrimitiveFields.mClipBounds);
            }
        } else {
            outRect->set(mPrimitiveFields.mClipBounds);
        }
    }

    bool getHasOverlappingRendering() const { return mPrimitiveFields.mHasOverlappingRendering; }

    const Outline& getOutline() const { return mPrimitiveFields.mOutline; }

    const RevealClip& getRevealClip() const { return mPrimitiveFields.mRevealClip; }

    bool getProjectBackwards() const { return mPrimitiveFields.mProjectBackwards; }

    void debugOutputProperties(std::ostream& output, const int level) const;

    void updateMatrix();

    Outline& mutableOutline() { return mPrimitiveFields.mOutline; }

    RevealClip& mutableRevealClip() { return mPrimitiveFields.mRevealClip; }

    const LayerProperties& layerProperties() const { return mLayerProperties; }

    LayerProperties& mutateLayerProperties() { return mLayerProperties; }

    // Returns true if damage calculations should be clipped to bounds
    // TODO: Figure out something better for getZ(), as children should still be
    // clipped to this RP's bounds. But as we will damage -INT_MAX to INT_MAX
    // for this RP's getZ() anyway, this can be optimized when we have a
    // Z damage estimate instead of INT_MAX
    bool getClipDamageToBounds() const {
        return getClipToBounds() && (getZ() <= 0 || getOutline().isEmpty());
    }

    bool hasShadow() const {
        return getZ() > 0.0f && getOutline().getPath() != nullptr &&
               getOutline().getAlpha() != 0.0f;
    }

    SkColor getSpotShadowColor() const { return mPrimitiveFields.mSpotShadowColor; }

    bool setSpotShadowColor(SkColor shadowColor) {
        return RP_SET(mPrimitiveFields.mSpotShadowColor, shadowColor);
    }

    SkColor getAmbientShadowColor() const { return mPrimitiveFields.mAmbientShadowColor; }

    bool setAmbientShadowColor(SkColor shadowColor) {
        return RP_SET(mPrimitiveFields.mAmbientShadowColor, shadowColor);
    }

    bool fitsOnLayer() const {
        const DeviceInfo* deviceInfo = DeviceInfo::get();
        return mPrimitiveFields.mWidth <= deviceInfo->maxTextureSize() &&
               mPrimitiveFields.mHeight <= deviceInfo->maxTextureSize();
    }

    bool promotedToLayer() const {
        return mLayerProperties.mType == LayerType::None && fitsOnLayer() &&
               (mComputedFields.mNeedLayerForFunctors ||
                (!MathUtils::isZero(mPrimitiveFields.mAlpha) && mPrimitiveFields.mAlpha < 1 &&
                 mPrimitiveFields.mHasOverlappingRendering));
    }

    LayerType effectiveLayerType() const {
        return CC_UNLIKELY(promotedToLayer()) ? LayerType::RenderLayer : mLayerProperties.mType;
    }

    bool setAllowForceDark(bool allow) {
        return RP_SET(mPrimitiveFields.mAllowForceDark, allow);
    }

    bool getAllowForceDark() const {
        return mPrimitiveFields.mAllowForceDark;
    }

private:
    // Rendering properties
    struct PrimitiveFields {
        int mLeft = 0, mTop = 0, mRight = 0, mBottom = 0;
        int mWidth = 0, mHeight = 0;
        int mClippingFlags = CLIP_TO_BOUNDS;
        SkColor mSpotShadowColor = SK_ColorBLACK;
        SkColor mAmbientShadowColor = SK_ColorBLACK;
        float mAlpha = 1;
        float mTranslationX = 0, mTranslationY = 0, mTranslationZ = 0;
        float mElevation = 0;
        float mRotation = 0, mRotationX = 0, mRotationY = 0;
        float mScaleX = 1, mScaleY = 1;
        float mPivotX = 0, mPivotY = 0;
        bool mHasOverlappingRendering = false;
        bool mPivotExplicitlySet = false;
        bool mMatrixOrPivotDirty = false;
        bool mProjectBackwards = false;
        bool mProjectionReceiver = false;
        bool mAllowForceDark = true;
        bool mClipMayBeComplex = false;
        Rect mClipBounds;
        Outline mOutline;
        RevealClip mRevealClip;
    } mPrimitiveFields;

    SkMatrix* mStaticMatrix;
    SkMatrix* mAnimationMatrix;
    LayerProperties mLayerProperties;

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

        // Force layer on for functors to enable render features they don't yet support (clipping)
        bool mNeedLayerForFunctors = false;
    } mComputedFields;
};

} /* namespace uirenderer */
} /* namespace android */
