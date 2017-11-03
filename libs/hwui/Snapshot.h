/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <ui/Region.h>
#include <utils/LinearAllocator.h>
#include <utils/RefBase.h>

#include <SkClipOp.h>
#include <SkRegion.h>

#include "ClipArea.h"
#include "Layer.h"
#include "Matrix.h"
#include "Outline.h"
#include "Rect.h"
#include "utils/Macros.h"

namespace android {
namespace uirenderer {

/**
 * Temporary structure holding information for a single outline clip.
 *
 * These structures are treated as immutable once created, and only exist for a single frame, which
 * is why they may only be allocated with a LinearAllocator.
 */
class RoundRectClipState {
public:
    static void* operator new(size_t size) = delete;
    static void* operator new(size_t size, LinearAllocator& allocator) {
        return allocator.alloc<RoundRectClipState>(size);
    }

    bool areaRequiresRoundRectClip(const Rect& rect) const {
        return rect.intersects(dangerRects[0]) || rect.intersects(dangerRects[1]) ||
               rect.intersects(dangerRects[2]) || rect.intersects(dangerRects[3]);
    }

    bool highPriority;
    Matrix4 matrix;
    Rect dangerRects[4];
    Rect innerRect;
    float radius;
};

/**
 * A snapshot holds information about the current state of the rendering
 * surface. A snapshot is usually created whenever the user calls save()
 * and discarded when the user calls restore(). Once a snapshot is created,
 * it can hold information for deferred rendering.
 *
 * Each snapshot has a link to a previous snapshot, indicating the previous
 * state of the renderer.
 */
class Snapshot {
public:
    Snapshot();
    Snapshot(Snapshot* s, int saveFlags);

    /**
     * Various flags set on ::flags.
     */
    enum Flags {
        /**
         * Indicates that the clip region was modified. When this
         * snapshot is restored so must the clip.
         */
        kFlagClipSet = 0x1,
        /**
         * Indicates that this snapshot was created when saving
         * a new layer.
         */
        kFlagIsLayer = 0x2,
        /**
         * Indicates that this snapshot is a special type of layer
         * backed by an FBO. This flag only makes sense when the
         * flag kFlagIsLayer is also set.
         *
         * Viewport has been modified to fit the new Fbo, and must be
         * restored when this snapshot is restored.
         */
        kFlagIsFboLayer = 0x4,
    };

    /**
     * Modifies the current clip with the new clip rectangle and
     * the specified operation. The specified rectangle is transformed
     * by this snapshot's trasnformation.
     */
    void clip(const Rect& localClip, SkClipOp op);

    /**
     * Modifies the current clip with the new clip rectangle and
     * the specified operation. The specified rectangle is considered
     * already transformed.
     */
    void clipTransformed(const Rect& r, SkClipOp op = SkClipOp::kIntersect);

    /**
     * Modifies the current clip with the specified path and operation.
     */
    void clipPath(const SkPath& path, SkClipOp op);

    /**
     * Sets the current clip.
     */
    void setClip(float left, float top, float right, float bottom);

    /**
     * Returns the current clip in local coordinates. The clip rect is
     * transformed by the inverse transform matrix.
     */
    ANDROID_API const Rect& getLocalClip();

    /**
     * Returns the current clip in render target coordinates.
     */
    const Rect& getRenderTargetClip() const { return mClipArea->getClipRect(); }

    /*
     * Accessor functions so that the clip area can stay private
     */
    bool clipIsEmpty() const { return mClipArea->isEmpty(); }
    const SkRegion& getClipRegion() const { return mClipArea->getClipRegion(); }
    bool clipIsSimple() const { return mClipArea->isSimple(); }
    const ClipArea& getClipArea() const { return *mClipArea; }
    ClipArea& mutateClipArea() { return *mClipArea; }

    WARN_UNUSED_RESULT const ClipBase* serializeIntersectedClip(
            LinearAllocator& allocator, const ClipBase* recordedClip,
            const Matrix4& recordedClipTransform);
    void applyClip(const ClipBase* clip, const Matrix4& transform);

    /**
     * Resets the clip to the specified rect.
     */
    void resetClip(float left, float top, float right, float bottom);

    void initializeViewport(int width, int height) {
        mViewportData.initialize(width, height);
        mClipAreaRoot.setViewportDimensions(width, height);
    }

    int getViewportWidth() const { return mViewportData.mWidth; }
    int getViewportHeight() const { return mViewportData.mHeight; }
    const Matrix4& getOrthoMatrix() const { return mViewportData.mOrthoMatrix; }

    const Vector3& getRelativeLightCenter() const { return mRelativeLightCenter; }
    void setRelativeLightCenter(const Vector3& lightCenter) { mRelativeLightCenter = lightCenter; }

    /**
     * Sets (and replaces) the current clipping outline
     *
     * If the current round rect clip is high priority, the incoming clip is ignored.
     */
    void setClippingRoundRect(LinearAllocator& allocator, const Rect& bounds, float radius,
                              bool highPriority);

    /**
     * Sets (and replaces) the current projection mask
     */
    void setProjectionPathMask(const SkPath* path);

    /**
     * Indicates whether the current transform has perspective components.
     */
    bool hasPerspectiveTransform() const;

    /**
     * Dirty flags.
     */
    int flags;

    /**
     * Previous snapshot.
     */
    Snapshot* previous;

    /**
     * A pointer to the currently active layer.
     *
     * This snapshot does not own the layer, this pointer must not be freed.
     */
    Layer* layer;

    /**
     * Target FBO used for rendering. Set to 0 when rendering directly
     * into the framebuffer.
     */
    GLuint fbo;

    /**
     * Local transformation. Holds the current translation, scale and
     * rotation values.
     *
     * This is a reference to a matrix owned by this snapshot or another
     *  snapshot. This pointer must not be freed. See ::mTransformRoot.
     */
    mat4* transform;

    /**
     * Current alpha value. This value is 1 by default, but may be set by a DisplayList which
     * has translucent rendering in a non-overlapping View. This value will be used by
     * the renderer to set the alpha in the current color being used for ensuing drawing
     * operations. The value is inherited by child snapshots because the same value should
     * be applied to descendants of the current DisplayList (for example, a TextView contains
     * the base alpha value which should be applied to the child DisplayLists used for drawing
     * the actual text).
     */
    float alpha;

    /**
     * Current clipping round rect.
     *
     * Points to data not owned by the snapshot, and may only be replaced by subsequent RR clips,
     * never modified.
     */
    const RoundRectClipState* roundRectClipState;

    /**
     * Current projection masking path - used exclusively to mask projected, tessellated circles.
     */
    const SkPath* projectionPathMask;

    void dump() const;

private:
    struct ViewportData {
        ViewportData() : mWidth(0), mHeight(0) {}
        void initialize(int width, int height) {
            mWidth = width;
            mHeight = height;
            mOrthoMatrix.loadOrtho(0, width, height, 0, -1, 1);
        }

        /*
         * Width and height of current viewport.
         *
         * The viewport is always defined to be (0, 0, width, height).
         */
        int mWidth;
        int mHeight;
        /**
         * Contains the current orthographic, projection matrix.
         */
        mat4 mOrthoMatrix;
    };

    mat4 mTransformRoot;

    ClipArea mClipAreaRoot;
    ClipArea* mClipArea;
    Rect mLocalClip;

    ViewportData mViewportData;
    Vector3 mRelativeLightCenter;

};  // class Snapshot

};  // namespace uirenderer
};  // namespace android
