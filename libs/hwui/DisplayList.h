/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_HWUI_DISPLAY_LIST_H
#define ANDROID_HWUI_DISPLAY_LIST_H

#ifndef LOG_TAG
    #define LOG_TAG "OpenGLRenderer"
#endif

#include <SkCamera.h>
#include <SkMatrix.h>

#include <private/hwui/DrawGlInfo.h>

#include <utils/KeyedVector.h>
#include <utils/LinearAllocator.h>
#include <utils/RefBase.h>
#include <utils/SortedVector.h>
#include <utils/String8.h>
#include <utils/Vector.h>

#include <cutils/compiler.h>

#include <androidfw/ResourceTypes.h>

#include "Debug.h"
#include "Matrix.h"
#include "DeferredDisplayList.h"

#define TRANSLATION 0x0001
#define ROTATION    0x0002
#define ROTATION_3D 0x0004
#define SCALE       0x0008
#define PIVOT       0x0010

class SkBitmap;
class SkPaint;
class SkPath;
class SkRegion;

namespace android {
namespace uirenderer {

class DeferredDisplayList;
class DisplayListOp;
class DisplayListRenderer;
class OpenGLRenderer;
class Rect;
class Layer;
class SkiaShader;

class ClipRectOp;
class SaveLayerOp;
class SaveOp;
class RestoreToCountOp;
class DrawDisplayListOp;

/**
 * Holds data used in the playback a tree of DisplayLists.
 */
class PlaybackStateStruct {
protected:
    PlaybackStateStruct(OpenGLRenderer& renderer, int replayFlags, LinearAllocator* allocator)
            : mRenderer(renderer), mReplayFlags(replayFlags), mAllocator(allocator){}

public:
    OpenGLRenderer& mRenderer;
    const int mReplayFlags;

    // Allocator with the lifetime of a single frame.
    // replay uses an Allocator owned by the struct, while defer shares the DeferredDisplayList's Allocator
    LinearAllocator * const mAllocator;
};

class DeferStateStruct : public PlaybackStateStruct {
public:
    DeferStateStruct(DeferredDisplayList& deferredList, OpenGLRenderer& renderer, int replayFlags)
            : PlaybackStateStruct(renderer, replayFlags, &(deferredList.mAllocator)),
            mDeferredList(deferredList) {}

    DeferredDisplayList& mDeferredList;
};

class ReplayStateStruct : public PlaybackStateStruct {
public:
    ReplayStateStruct(OpenGLRenderer& renderer, Rect& dirty, int replayFlags)
            : PlaybackStateStruct(renderer, replayFlags, &mReplayAllocator),
            mDirty(dirty), mDrawGlStatus(DrawGlInfo::kStatusDone) {}

    Rect& mDirty;
    status_t mDrawGlStatus;
    LinearAllocator mReplayAllocator;
};

/**
 * Data structure that holds the list of commands used in display list stream
 */
class DisplayListData {
public:
    DisplayListData() : projectionReceiveIndex(-1), functorCount(0), hasDrawOps(false) {}
    virtual ~DisplayListData() { cleanupResources(); }

    // allocator into which all ops were allocated
    LinearAllocator allocator;

    // pointers to all ops within display list, pointing into allocator data
    Vector<DisplayListOp*> displayListOps;

    // list of children display lists for quick, non-drawing traversal
    Vector<DrawDisplayListOp*> children;

    // index of DisplayListOp restore, after which projected descendents should be drawn
    int projectionReceiveIndex;

    Vector<const SkBitmap*> bitmapResources;
    Vector<const SkBitmap*> ownedBitmapResources;
    Vector<const Res_png_9patch*> patchResources;

    Vector<const SkPaint*> paints;
    Vector<const SkPath*> paths;
    SortedVector<const SkPath*> sourcePaths;
    Vector<const SkRegion*> regions;
    Vector<const SkMatrix*> matrices;
    Vector<SkiaShader*> shaders;
    Vector<Layer*> layers;
    uint32_t functorCount;
    bool hasDrawOps;

    bool isEmpty() {
        return !displayListOps.size();
    }

private:
    void cleanupResources();
};

/**
 * Primary class for storing recorded canvas commands, as well as per-View/ViewGroup display properties.
 *
 * Recording of canvas commands is somewhat similar to SkPicture, except the canvas-recording
 * functionality is split between DisplayListRenderer (which manages the recording), DisplayListData
 * (which holds the actual data), and DisplayList (which holds properties and performs playback onto
 * a renderer).
 *
 * Note that DisplayListData is swapped out from beneath an individual DisplayList when a view's
 * recorded stream of canvas operations is refreshed. The DisplayList (and its properties) stay
 * attached.
 */
class RenderNode {
public:
    ANDROID_API RenderNode();
    ANDROID_API ~RenderNode();

    // See flags defined in DisplayList.java
    enum ReplayFlag {
        kReplayFlag_ClipChildren = 0x1
    };

    ANDROID_API static void destroyDisplayListDeferred(RenderNode* displayList);
    ANDROID_API static void outputLogBuffer(int fd);

    ANDROID_API void setData(DisplayListData* newData);

    void computeOrdering();
    void defer(DeferStateStruct& deferStruct, const int level);
    void replay(ReplayStateStruct& replayStruct, const int level);

    ANDROID_API void output(uint32_t level = 1);

    bool isRenderable() const {
        return mDisplayListData && mDisplayListData->hasDrawOps;
    }

    void setName(const char* name) {
        if (name) {
            char* lastPeriod = strrchr(name, '.');
            if (lastPeriod) {
                mName.setTo(lastPeriod + 1);
            } else {
                mName.setTo(name);
            }
        }
    }

    const char* getName() const {
        return mName.string();
    }

    void setClipToBounds(bool clipToBounds) {
        mClipToBounds = clipToBounds;
    }

    void setIsolatedZVolume(bool shouldIsolate) {
        mIsolatedZVolume = shouldIsolate;
    }

    void setCastsShadow(bool castsShadow) {
        mCastsShadow = castsShadow;
    }

    void setUsesGlobalCamera(bool usesGlobalCamera) {
        mUsesGlobalCamera = usesGlobalCamera;
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

    void setOutline(const SkPath* outline) {
        if (!outline) {
            mOutline.reset();
        } else {
            mOutline = *outline;
        }
    }

    void setClipToOutline(bool clipToOutline) {
        mClipToOutline = clipToOutline;
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

    int getWidth() {
        return mWidth;
    }

    int getHeight() {
        return mHeight;
    }

private:
    typedef key_value_pair_t<float, DrawDisplayListOp*> ZDrawDisplayListOpPair;

    static size_t findNonNegativeIndex(const Vector<ZDrawDisplayListOpPair>& nodes) {
        for (size_t i = 0; i < nodes.size(); i++) {
            if (nodes[i].key >= 0.0f) return i;
        }
        return nodes.size();
    }

    enum ChildrenSelectMode {
        kNegativeZChildren,
        kPositiveZChildren
    };

    void onTranslationUpdate() {
        mMatrixDirty = true;
        if (mTranslationX == 0.0f && mTranslationY == 0.0f && mTranslationZ == 0.0f) {
            mMatrixFlags &= ~TRANSLATION;
        } else {
            mMatrixFlags |= TRANSLATION;
        }
    }

    void outputViewProperties(const int level);

    void applyViewPropertyTransforms(mat4& matrix, bool true3dTransform = false);

    void computeOrderingImpl(DrawDisplayListOp* opState,
            Vector<DrawDisplayListOp*>* compositedChildrenOfProjectionSurface,
            const mat4* transformFromProjectionSurface);

    template <class T>
    inline void setViewProperties(OpenGLRenderer& renderer, T& handler, const int level);

    void buildZSortedChildList(Vector<ZDrawDisplayListOpPair>& zTranslatedNodes);

    template <class T>
    inline void iterate3dChildren(const Vector<ZDrawDisplayListOpPair>& zTranslatedNodes,
            ChildrenSelectMode mode, OpenGLRenderer& renderer, T& handler);

    template <class T>
    inline void iterateProjectedChildren(OpenGLRenderer& renderer, T& handler, const int level);

    template <class T>
    inline void iterate(OpenGLRenderer& renderer, T& handler, const int level);

    void updateMatrix();

    class TextContainer {
    public:
        size_t length() const {
            return mByteLength;
        }

        const char* text() const {
            return (const char*) mText;
        }

        size_t mByteLength;
        const char* mText;
    };

    DisplayListData* mDisplayListData;

    String8 mName;
    bool mDestroyed; // used for debugging crash, TODO: remove once invalid state crash fixed

    // Rendering properties
    bool mClipToBounds;
    bool mIsolatedZVolume;
    bool mProjectBackwards;
    bool mProjectionReceiver;
    SkPath mOutline;
    bool mClipToOutline;
    bool mCastsShadow;
    bool mUsesGlobalCamera; // TODO: respect value when rendering
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

    /**
     * Draw time state - these properties are only set and used during rendering
     */

    // for projection surfaces, contains a list of all children items
    Vector<DrawDisplayListOp*> mProjectedNodes;
}; // class DisplayList

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_OPENGL_RENDERER_H
