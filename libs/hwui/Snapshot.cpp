/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include "Snapshot.h"

#include <SkCanvas.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors
///////////////////////////////////////////////////////////////////////////////

Snapshot::Snapshot(): flags(0), previous(NULL), layer(NULL), fbo(0),
        invisible(false), empty(false) {

    transform = &mTransformRoot;
    clipRect = &mClipRectRoot;
    region = NULL;
}

/**
 * Copies the specified snapshot/ The specified snapshot is stored as
 * the previous snapshot.
 */
Snapshot::Snapshot(const sp<Snapshot>& s, int saveFlags):
        flags(0), previous(s), layer(NULL), fbo(s->fbo),
        invisible(s->invisible), empty(false),
        viewport(s->viewport), height(s->height) {

    if (saveFlags & SkCanvas::kMatrix_SaveFlag) {
        mTransformRoot.load(*s->transform);
        transform = &mTransformRoot;
    } else {
        transform = s->transform;
    }

    if (saveFlags & SkCanvas::kClip_SaveFlag) {
        mClipRectRoot.set(*s->clipRect);
        clipRect = &mClipRectRoot;
    } else {
        clipRect = s->clipRect;
    }

    if (s->flags & Snapshot::kFlagFboTarget) {
        flags |= Snapshot::kFlagFboTarget;
        region = s->region;
    } else {
        region = NULL;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Clipping
///////////////////////////////////////////////////////////////////////////////

bool Snapshot::clip(float left, float top, float right, float bottom, SkRegion::Op op) {
    Rect r(left, top, right, bottom);
    transform->mapRect(r);
    return clipTransformed(r, op);
}

bool Snapshot::clipTransformed(const Rect& r, SkRegion::Op op) {
    bool clipped = false;

    // NOTE: The unimplemented operations require support for regions
    // Supporting regions would require using a stencil buffer instead
    // of the scissor. The stencil buffer itself is not too expensive
    // (memory cost excluded) but on fillrate limited devices, managing
    // the stencil might have a negative impact on the framerate.
    switch (op) {
        case SkRegion::kDifference_Op:
            break;
        case SkRegion::kIntersect_Op:
            clipped = clipRect->intersect(r);
            if (!clipped) {
                clipRect->setEmpty();
                clipped = true;
            }
            break;
        case SkRegion::kUnion_Op:
            clipped = clipRect->unionWith(r);
            break;
        case SkRegion::kXOR_Op:
            break;
        case SkRegion::kReverseDifference_Op:
            break;
        case SkRegion::kReplace_Op:
            clipRect->set(r);
            clipped = true;
            break;
    }

    if (clipped) {
        flags |= Snapshot::kFlagClipSet;
    }

    return clipped;
}

void Snapshot::setClip(float left, float top, float right, float bottom) {
    clipRect->set(left, top, right, bottom);
    flags |= Snapshot::kFlagClipSet;
}

const Rect& Snapshot::getLocalClip() {
    mat4 inverse;
    inverse.loadInverse(*transform);

    mLocalClip.set(*clipRect);
    inverse.mapRect(mLocalClip);

    return mLocalClip;
}

void Snapshot::resetClip(float left, float top, float right, float bottom) {
    clipRect = &mClipRectRoot;
    clipRect->set(left, top, right, bottom);
    flags |= Snapshot::kFlagClipSet;
}

///////////////////////////////////////////////////////////////////////////////
// Transforms
///////////////////////////////////////////////////////////////////////////////

void Snapshot::resetTransform(float x, float y, float z) {
    transform = &mTransformRoot;
    transform->loadTranslate(x, y, z);
}

///////////////////////////////////////////////////////////////////////////////
// Queries
///////////////////////////////////////////////////////////////////////////////

bool Snapshot::isIgnored() const {
    return invisible || empty;
}

}; // namespace uirenderer
}; // namespace android
