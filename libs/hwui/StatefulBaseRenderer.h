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
 * Implementation for Renderer state methods
 *
 * Eventually, this class should have abstract protected methods
 * for allowing subclasses to hook into save/saveLayer and restore
 */
class StatefulBaseRenderer : public Renderer {
public:
    StatefulBaseRenderer();

    virtual status_t prepare(bool opaque) {
        return prepareDirty(0.0f, 0.0f, mWidth, mHeight, opaque);
    }
    void initializeViewport(int width, int height);
    void initializeSaveStack(float clipLeft, float clipTop, float clipRight, float clipBottom);

    // getters
    bool hasRectToRectTransform() const {
        return CC_LIKELY(currentTransform().rectToRect());
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

    virtual void setMatrix(SkMatrix* matrix);
    void setMatrix(const Matrix4& matrix); // internal only convenience method
    virtual void concatMatrix(SkMatrix* matrix);
    void concatMatrix(const Matrix4& matrix); // internal only convenience method

    // Clip
    const Rect& getClipBounds() const { return mSnapshot->getLocalClip(); }
    virtual bool quickRejectConservative(float left, float top, float right, float bottom) const;

    // TODO: implement these with hooks to enable scissor/stencil usage in OpenGLRenderer
    // virtual bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op);
    // virtual bool clipPath(SkPath* path, SkRegion::Op op);
    // virtual bool clipRegion(SkRegion* region, SkRegion::Op op);

protected:
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
            bool* clipRequired, bool snapOut) const;

    /**
     * Called just after a restore has occurred. The 'removed' snapshot popped from the stack,
     * 'restored' snapshot has become the top/current.
     *
     * Subclasses can override this method to handle layer restoration
     */
    virtual void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) {};

    inline const Rect& currentClipRect() const {
        return *(mSnapshot->clipRect);
    }

    inline const mat4& currentTransform() const {
        return *(mSnapshot->transform);
    }

    inline const Snapshot& currentSnapshot() const {
        return mSnapshot != NULL ? *mSnapshot : *mFirstSnapshot;
    }

    // TODO: below should be private so that snapshot stack manipulation
    // goes though (mostly) public methods

    // Number of saved states
    int mSaveCount;

    // Base state
    sp<Snapshot> mFirstSnapshot;

    // Current state
    sp<Snapshot> mSnapshot;

private:
    // Dimensions of the drawing surface
    int mWidth, mHeight;

}; // class StatefulBaseRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_STATEFUL_BASE_RENDERER_H
