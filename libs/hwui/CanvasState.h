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

#ifndef ANDROID_HWUI_CANVAS_STATE_H
#define ANDROID_HWUI_CANVAS_STATE_H

#include "Snapshot.h"

#include <SkMatrix.h>
#include <SkPath.h>
#include <SkRegion.h>

namespace android {
namespace uirenderer {

/**
 * Abstract base class for any class containing CanvasState.
 * Defines three mandatory callbacks.
 */
class CanvasStateClient {
public:
    CanvasStateClient() { }
    virtual ~CanvasStateClient() { }

    /**
     * Callback allowing embedder to take actions in the middle of a
     * setViewport() call.
     */
    virtual void onViewportInitialized() = 0;

    /**
     * Callback allowing embedder to take actions in the middle of a
     * restore() call.  May be called several times sequentially.
     */
    virtual void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) = 0;

    /**
     * Allows subclasses to control what value is stored in snapshot's
     * fbo field in * initializeSaveStack.
     */
    virtual GLuint getTargetFbo() const = 0;

}; // class CanvasStateClient

/**
 * Implements Canvas state methods on behalf of Renderers.
 *
 * Manages the Snapshot stack, implementing matrix, save/restore, and clipping methods in the
 * Renderer interface. Drawing and recording classes that include a CanvasState will have
 * different use cases:
 *
 * Drawing code maintaining canvas state (i.e. OpenGLRenderer) can query attributes (such as
 * transform) or hook into changes (e.g. save/restore) with minimal surface area for manipulating
 * the stack itself.
 *
 * Recording code maintaining canvas state (i.e. DisplayListCanvas) can both record and pass
 * through state operations to CanvasState, so that not only will querying operations work
 * (getClip/Matrix), but so that quickRejection can also be used.
 */

class CanvasState {
public:
    CanvasState(CanvasStateClient& renderer);
    ~CanvasState();

    /**
     * Initializes the first snapshot, computing the projection matrix,
     * and stores the dimensions of the render target.
     */
    void initializeRecordingSaveStack(int viewportWidth, int viewportHeight);

    /**
     * Initializes the first snapshot, computing the projection matrix,
     * and stores the dimensions of the render target.
     */
    void initializeSaveStack(int viewportWidth, int viewportHeight,
            float clipLeft, float clipTop, float clipRight, float clipBottom,
            const Vector3& lightCenter);

    bool hasRectToRectTransform() const {
        return CC_LIKELY(currentTransform()->rectToRect());
    }

    // Save (layer)
    int getSaveCount() const { return mSaveCount; }
    int save(int flags);
    void restore();
    void restoreToCount(int saveCount);

    // Save/Restore without side-effects
    int saveSnapshot(int flags);
    void restoreSnapshot();

    // Matrix
    void getMatrix(SkMatrix* outMatrix) const;
    void translate(float dx, float dy, float dz = 0.0f);
    void rotate(float degrees);
    void scale(float sx, float sy);
    void skew(float sx, float sy);

    void setMatrix(const SkMatrix& matrix);
    void setMatrix(const Matrix4& matrix); // internal only convenience method
    void concatMatrix(const SkMatrix& matrix);
    void concatMatrix(const Matrix4& matrix); // internal only convenience method

    // Clip
    const Rect& getLocalClipBounds() const { return mSnapshot->getLocalClip(); }
    const Rect& getRenderTargetClipBounds() const { return mSnapshot->getRenderTargetClip(); }

    bool quickRejectConservative(float left, float top, float right, float bottom) const;

    bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op);
    bool clipPath(const SkPath* path, SkRegion::Op op);
    bool clipRegion(const SkRegion* region, SkRegion::Op op);

    /**
     * Sets a "clipping outline", which is independent from the regular clip.
     * Currently only supports rectangles or rounded rectangles; passing in a
     * more complicated outline fails silently. Replaces any previous clipping
     * outline.
     */
    void setClippingOutline(LinearAllocator& allocator, const Outline* outline);
    void setClippingRoundRect(LinearAllocator& allocator,
            const Rect& rect, float radius, bool highPriority = true);
    void setProjectionPathMask(LinearAllocator& allocator, const SkPath* path);

    /**
     * Returns true if drawing in the rectangle (left, top, right, bottom)
     * will be clipped out. Is conservative: might return false when subpixel-
     * perfect tests would return true.
     */
    bool calculateQuickRejectForScissor(float left, float top, float right, float bottom,
            bool* clipRequired, bool* roundRectClipRequired, bool snapOut) const;

    void setDirtyClip(bool opaque) { mDirtyClip = opaque; }
    bool getDirtyClip() const { return mDirtyClip; }

    void scaleAlpha(float alpha) { mSnapshot->alpha *= alpha; }
    void setEmpty(bool value) { mSnapshot->empty = value; }
    void setInvisible(bool value) { mSnapshot->invisible = value; }

    inline const mat4* currentTransform() const { return currentSnapshot()->transform; }
    inline const Rect& currentRenderTargetClip() const { return currentSnapshot()->getRenderTargetClip(); }
    inline Region* currentRegion() const { return currentSnapshot()->region; }
    inline int currentFlags() const { return currentSnapshot()->flags; }
    const Vector3& currentLightCenter() const { return currentSnapshot()->getRelativeLightCenter(); }
    inline bool currentlyIgnored() const { return currentSnapshot()->isIgnored(); }
    int getViewportWidth() const { return currentSnapshot()->getViewportWidth(); }
    int getViewportHeight() const { return currentSnapshot()->getViewportHeight(); }
    int getWidth() const { return mWidth; }
    int getHeight() const { return mHeight; }
    bool clipIsSimple() const { return currentSnapshot()->clipIsSimple(); }

    inline const Snapshot* currentSnapshot() const { return mSnapshot; }
    inline Snapshot* writableSnapshot() { return mSnapshot; }
    inline const Snapshot* firstSnapshot() const { return &mFirstSnapshot; }

private:
    Snapshot* allocSnapshot(Snapshot* previous, int savecount);
    void freeSnapshot(Snapshot* snapshot);
    void freeAllSnapshots();

    /// indicates that the clip has been changed since the last time it was consumed
    // TODO: delete when switching to HWUI_NEW_OPS
    bool mDirtyClip;

    /// Dimensions of the drawing surface
    int mWidth, mHeight;

    /// Number of saved states
    int mSaveCount;

    /// Base state
    Snapshot mFirstSnapshot;

    /// Host providing callbacks
    CanvasStateClient& mCanvas;

    /// Current state
    Snapshot* mSnapshot;

    // Pool of allocated snapshots to re-use
    // NOTE: The dtors have already been invoked!
    Snapshot* mSnapshotPool = nullptr;
    int mSnapshotPoolCount = 0;

}; // class CanvasState

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_CANVAS_STATE_H
