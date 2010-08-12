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

#ifndef ANDROID_UI_SNAPSHOT_H
#define ANDROID_UI_SNAPSHOT_H

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <utils/RefBase.h>

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
    Snapshot(): flags(0), previous(NULL), layer(NULL), fbo(0) { }

    /**
     * Copies the specified snapshot. Only the transform and clip rectangle
     * are copied. The layer information is set to 0 and the transform is
     * assumed to be dirty. The specified snapshot is stored as the previous
     * snapshot.
     */
    Snapshot(const sp<Snapshot>& s):
            height(s->height),
            transform(s->transform),
            clipRect(s->clipRect),
            flags(0),
            previous(s),
            layer(NULL),
            fbo(s->fbo) {
        if ((s->flags & Snapshot::kFlagClipSet) &&
                !(s->flags & Snapshot::kFlagDirtyLocalClip)) {
            localClip.set(s->localClip);
        } else {
            flags |= Snapshot::kFlagDirtyLocalClip;
        }
    }

    /**
     * Various flags set on #flags.
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
         * Indicates that this snapshot has changed the ortho matrix.
         */
        kFlagDirtyOrtho = 0x4,
        /**
         * Indicates that the local clip should be recomputed.
         */
        kFlagDirtyLocalClip = 0x8,
    };

    /**
     * Intersects the current clip with the new clip rectangle.
     */
    bool clip(float left, float top, float right, float bottom, SkRegion::Op op) {
        bool clipped = false;

        SkRect sr;
        sr.set(left, top, right, bottom);

        SkMatrix m;
        transform.copyTo(m);
        m.mapRect(&sr);

        Rect r(sr.fLeft, sr.fTop, sr.fRight, sr.fBottom);
        switch (op) {
            case SkRegion::kDifference_Op:
                break;
            case SkRegion::kIntersect_Op:
                clipped = clipRect.intersect(r);
                break;
            case SkRegion::kUnion_Op:
                clipped = clipRect.unionWith(r);
                break;
            case SkRegion::kXOR_Op:
                break;
            case SkRegion::kReverseDifference_Op:
                break;
            case SkRegion::kReplace_Op:
                clipRect.set(r);
                clipped = true;
                break;
        }

        if (clipped) {
            flags |= Snapshot::kFlagClipSet | Snapshot::kFlagDirtyLocalClip;
        }

        return clipped;
    }

    /**
     * Sets the current clip.
     */
    void setClip(float left, float top, float right, float bottom) {
        clipRect.set(left, top, right, bottom);
        flags |= Snapshot::kFlagClipSet | Snapshot::kFlagDirtyLocalClip;
    }

    const Rect& getLocalClip() {
        if (flags & Snapshot::kFlagDirtyLocalClip) {
            mat4 inverse;
            inverse.loadInverse(transform);

            SkRect sr;
            sr.set(clipRect.left, clipRect.top, clipRect.right, clipRect.bottom);

            SkMatrix m;
            inverse.copyTo(m);
            m.mapRect(&sr);

            localClip.set(sr.fLeft, sr.fTop, sr.fRight, sr.fBottom);

            flags &= ~Snapshot::kFlagDirtyLocalClip;
        }
        return localClip;
    }

    /**
     * Height of the framebuffer the snapshot is rendering into.
     */
    int height;

    /**
     * Local transformation. Holds the current translation, scale and
     * rotation values.
     */
    mat4 transform;

    /**
     * Current clip region. The clip is stored in canvas-space coordinates,
     * (screen-space coordinates in the regular case.)
     */
    Rect clipRect;

    /**
     * Dirty flags.
     */
    int flags;

    /**
     * Previous snapshot.
     */
    sp<Snapshot> previous;

    /**
     * Only set when the flag kFlagIsLayer is set.
     */
    Layer* layer;
    GLuint fbo;

    /**
     * Contains the previous ortho matrix.
     */
    mat4 orthoMatrix;

private:
    Rect localClip;

}; // class Snapshot

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_SNAPSHOT_H
