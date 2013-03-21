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

#ifndef ANDROID_HWUI_SNAPSHOT_H
#define ANDROID_HWUI_SNAPSHOT_H

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <utils/RefBase.h>
#include <ui/Region.h>

#include <SkRegion.h>

#include "Layer.h"
#include "Matrix.h"
#include "Rect.h"

namespace android {
namespace uirenderer {

/**
 * A snapshot holds information about the current state of the rendering
 * surface. A snapshot is usually created whenever the user calls save()
 * and discarded when the user calls restore(). Once a snapshot is created,
 * it can hold information for deferred rendering.
 *
 * Each snapshot has a link to a previous snapshot, indicating the previous
 * state of the renderer.
 */
class Snapshot: public LightRefBase<Snapshot> {
public:

    Snapshot();
    Snapshot(const sp<Snapshot>& s, int saveFlags);

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
         */
        kFlagIsFboLayer = 0x4,
        /**
         * Indicates that this snapshot has changed the ortho matrix.
         */
        kFlagDirtyOrtho = 0x8,
        /**
         * Indicates that this snapshot or an ancestor snapshot is
         * an FBO layer.
         */
        kFlagFboTarget = 0x10
    };

    /**
     * Modifies the current clip with the new clip rectangle and
     * the specified operation. The specified rectangle is transformed
     * by this snapshot's trasnformation.
     */
    bool clip(float left, float top, float right, float bottom,
            SkRegion::Op op = SkRegion::kIntersect_Op);

    /**
     * Modifies the current clip with the new clip rectangle and
     * the specified operation. The specified rectangle is considered
     * already transformed.
     */
    bool clipTransformed(const Rect& r, SkRegion::Op op = SkRegion::kIntersect_Op);

    /**
     * Modifies the current clip with the specified region and operation.
     * The specified region is considered already transformed.
     */
    bool clipRegionTransformed(const SkRegion& region, SkRegion::Op op);

    /**
     * Sets the current clip.
     */
    void setClip(float left, float top, float right, float bottom);

    /**
     * Returns the current clip in local coordinates. The clip rect is
     * transformed by the inverse transform matrix.
     */
    const Rect& getLocalClip();

    /**
     * Resets the clip to the specified rect.
     */
    void resetClip(float left, float top, float right, float bottom);

    /**
     * Resets the current transform to a pure 3D translation.
     */
    void resetTransform(float x, float y, float z);

    /**
     * Indicates whether this snapshot should be ignored. A snapshot
     * is typicalled ignored if its layer is invisible or empty.
     */
    bool isIgnored() const;

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
    sp<Snapshot> previous;

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
     * Indicates that this snapshot is invisible and nothing should be drawn
     * inside it. This flag is set only when the layer clips drawing to its
     * bounds and is passed to subsequent snapshots.
     */
    bool invisible;

    /**
     * If set to true, the layer will not be composited. This is similar to
     * invisible but this flag is not passed to subsequent snapshots.
     */
    bool empty;

    /**
     * Current viewport.
     */
    Rect viewport;

    /**
     * Height of the framebuffer the snapshot is rendering into.
     */
    int height;

    /**
     * Contains the previous ortho matrix.
     */
    mat4 orthoMatrix;

    /**
     * Local transformation. Holds the current translation, scale and
     * rotation values.
     *
     * This is a reference to a matrix owned by this snapshot or another
     *  snapshot. This pointer must not be freed. See ::mTransformRoot.
     */
    mat4* transform;

    /**
     * Current clip rect. The clip is stored in canvas-space coordinates,
     * (screen-space coordinates in the regular case.)
     *
     * This is a reference to a rect owned by this snapshot or another
     * snapshot. This pointer must not be freed. See ::mClipRectRoot.
     */
    Rect* clipRect;

    /**
     * Current clip region. The clip is stored in canvas-space coordinates,
     * (screen-space coordinates in the regular case.)
     *
     * This is a reference to a region owned by this snapshot or another
     * snapshot. This pointer must not be freed. See ::mClipRegionRoot.
     */
    SkRegion* clipRegion;

    /**
     * The ancestor layer's dirty region.
     *
     * This is a reference to a region owned by a layer. This pointer must
     * not be freed.
     */
    Region* region;

    /**
     * Current alpha value. This value is 1 by default, but may be set by a DisplayList which
     * has translucent rendering in a non-overlapping View. This value will be used by
     * the renderer to set the alpha in the current color being used for ensuing drawing
     * operations. The value is inherited by child snapshots because the same value should
     * be applied to descendents of the current DisplayList (for example, a TextView contains
     * the base alpha value which should be applied to the child DisplayLists used for drawing
     * the actual text).
     */
    float alpha;

    void dump() const;

private:
    void ensureClipRegion();
    void copyClipRectFromRegion();

    bool clipRegionOp(float left, float top, float right, float bottom, SkRegion::Op op);

    mat4 mTransformRoot;
    Rect mClipRectRoot;
    Rect mLocalClip;

    SkRegion mClipRegionRoot;

}; // class Snapshot

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_SNAPSHOT_H
