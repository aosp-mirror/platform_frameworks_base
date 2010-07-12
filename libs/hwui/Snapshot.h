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
    Snapshot(): layer(NULL), fbo(0) { }

    /**
     * Copies the specified snapshot. Only the transform and clip rectangle
     * are copied. The layer information is set to 0 and the transform is
     * assumed to be dirty. The specified snapshot is stored as the previous
     * snapshot.
     */
    Snapshot(const sp<Snapshot> s):
            height(s->height),
            transform(s->transform),
            clipRect(s->clipRect),
            flags(kFlagDirtyTransform),
            previous(s),
            layer(NULL),
            fbo(s->fbo) {
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
         * Indicates that the snapshot holds new transform
         * information.
         */
        kFlagDirtyTransform = 0x2,
        /**
         * Indicates that this snapshot was created when saving
         * a new layer.
         */
        kFlagIsLayer = 0x4,
        /**
         * Indicates that this snapshot has changed the ortho matrix.
         */
        kFlagDirtyOrtho = 0x8,
    };

    /**
     * Returns the current clip region mapped by the current transform.
     */
    const Rect& getMappedClip() {
        if (flags & kFlagDirtyTransform) {
            flags &= ~kFlagDirtyTransform;
            mappedClip.set(clipRect);
            transform.mapRect(mappedClip);
        }
        return mappedClip;
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
     * Current clip region.
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
    // Clipping rectangle mapped with the transform
    Rect mappedClip;
}; // class Snapshot

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_SNAPSHOT_H
