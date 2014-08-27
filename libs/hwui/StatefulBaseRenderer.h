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

#ifndef ANDROID_HWUI_STATEFUL_BASE_RENDERER_H
#define ANDROID_HWUI_STATEFUL_BASE_RENDERER_H

#include <utils/RefBase.h>

#include "Renderer.h"
#include "Snapshot.h"

namespace android {
namespace uirenderer {

/**
 * Abstract Renderer subclass, which implements Canvas state methods.
 *
 * Manages the Snapshot stack, implementing matrix, save/restore, and clipping methods in the
 * Renderer interface. Drawing and recording classes that extend StatefulBaseRenderer will have
 * different use cases:
 *
 * Drawing subclasses (i.e. OpenGLRenderer) can query attributes (such as transform) or hook into
 * changes (e.g. save/restore) with minimal surface area for manipulating the stack itself.
 *
 * Recording subclasses (i.e. DisplayListRenderer) can both record and pass through state operations
 * to StatefulBaseRenderer, so that not only will querying operations work (getClip/Matrix), but so
 * that quickRejection can also be used.
 */
class StatefulBaseRenderer : public Renderer {
public:
    StatefulBaseRenderer();

    virtual status_t prepare(bool opaque) {
        return prepareDirty(0.0f, 0.0f, mWidth, mHeight, opaque);
    }

    /**
     * Initialize the first snapshot, computing the projection matrix, and stores the dimensions of
     * the render target.
     */
    virtual void setViewport(int width, int height);
    void initializeSaveStack(float clipLeft, float clipTop, float clipRight, float clipBottom,
            const Vector3& lightCenter);

    // getters
    bool hasRectToRectTransform() const {
        return CC_LIKELY(currentTransform()->rectToRect());
    }

    // Save (layer)
    virtual int getSaveCount() const { return mSaveCount; }
    virtual int save(int flags);
    virtual void restore();
    virtual void restoreToCount(int saveCount);
    //virtual int saveLayer(float left, float top, float right, float bottom,
    //        int alpha, SkXfermode::Mode mode, int flags);

    // Matrix
    virtual void getMatrix(SkMatrix* outMatrix) const;
    virtual void translate(float dx, float dy, float dz = 0.0f);
    virtual void rotate(float degrees);
    virtual void scale(float sx, float sy);
    virtual void skew(float sx, float sy);

    virtual void setMatrix(const SkMatrix& matrix);
    void setMatrix(const Matrix4& matrix); // internal only convenience method
    virtual void concatMatrix(const SkMatrix& matrix);
    void concatMatrix(const Matrix4& matrix); // internal only convenience method

    // Clip
    virtual const Rect& getLocalClipBounds() const { return mSnapshot->getLocalClip(); }

    virtual bool quickRejectConservative(float left, float top, float right, float bottom) const;

    virtual bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op);
    virtual bool clipPath(const SkPath* path, SkRegion::Op op);
    virtual bool clipRegion(const SkRegion* region, SkRegion::Op op);

    /**
     * Does not support different clipping Ops (that is, every call to setClippingOutline is
     * effectively using SkRegion::kReplaceOp)
     *
     * The clipping outline is independent from the regular clip.
     */
    void setClippingOutline(LinearAllocator& allocator, const Outline* outline);
    void setClippingRoundRect(LinearAllocator& allocator,
            const Rect& rect, float radius);

    inline const mat4* currentTransform() const {
        return mSnapshot->transform;
    }

protected:
    const Rect& getRenderTargetClipBounds() const { return mSnapshot->getRenderTargetClip(); }

    int getWidth() { return mWidth; }
    int getHeight() { return mHeight; }

    // Save
    int saveSnapshot(int flags);
    void restoreSnapshot();

    // allows subclasses to control what value is stored in snapshot's fbo field in
    // initializeSaveStack
    virtual GLuint getTargetFbo() const {
        return -1;
    }

    // Clip
    bool calculateQuickRejectForScissor(float left, float top, float right, float bottom,
            bool* clipRequired, bool* roundRectClipRequired, bool snapOut) const;

    /**
     * Called just after a restore has occurred. The 'removed' snapshot popped from the stack,
     * 'restored' snapshot has become the top/current.
     *
     * Subclasses can override this method to handle layer restoration
     */
    virtual void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) {};

    virtual void onViewportInitialized() {};

    inline const Rect* currentClipRect() const {
        return mSnapshot->clipRect;
    }

    inline const Snapshot* currentSnapshot() const {
        return mSnapshot != NULL ? mSnapshot.get() : mFirstSnapshot.get();
    }

    inline const Snapshot* firstSnapshot() const {
        return mFirstSnapshot.get();
    }

    // indicites that the clip has been changed since the last time it was consumed
    bool mDirtyClip;

private:
    // Dimensions of the drawing surface
    int mWidth, mHeight;

    // Number of saved states
    int mSaveCount;

    // Base state
    sp<Snapshot> mFirstSnapshot;

protected:
    // Current state
    // TODO: should become private, once hooks needed by OpenGLRenderer are added
    sp<Snapshot> mSnapshot;
}; // class StatefulBaseRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_STATEFUL_BASE_RENDERER_H
