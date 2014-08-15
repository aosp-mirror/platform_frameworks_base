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

#define LOG_TAG "OpenGLRenderer"

#include "Snapshot.h"

#include <SkCanvas.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors
///////////////////////////////////////////////////////////////////////////////

Snapshot::Snapshot()
        : flags(0)
        , previous(NULL)
        , layer(NULL)
        , fbo(0)
        , invisible(false)
        , empty(false)
        , alpha(1.0f)
        , roundRectClipState(NULL) {
    transform = &mTransformRoot;
    clipRect = &mClipRectRoot;
    region = NULL;
    clipRegion = &mClipRegionRoot;
}

/**
 * Copies the specified snapshot/ The specified snapshot is stored as
 * the previous snapshot.
 */
Snapshot::Snapshot(const sp<Snapshot>& s, int saveFlags)
        : flags(0)
        , previous(s)
        , layer(s->layer)
        , fbo(s->fbo)
        , invisible(s->invisible)
        , empty(false)
        , alpha(s->alpha)
        , roundRectClipState(s->roundRectClipState)
        , mViewportData(s->mViewportData)
        , mRelativeLightCenter(s->mRelativeLightCenter) {
    if (saveFlags & SkCanvas::kMatrix_SaveFlag) {
        mTransformRoot.load(*s->transform);
        transform = &mTransformRoot;
    } else {
        transform = s->transform;
    }

    if (saveFlags & SkCanvas::kClip_SaveFlag) {
        mClipRectRoot.set(*s->clipRect);
        clipRect = &mClipRectRoot;
        if (!s->clipRegion->isEmpty()) {
            mClipRegionRoot.op(*s->clipRegion, SkRegion::kUnion_Op);
        }
        clipRegion = &mClipRegionRoot;
    } else {
        clipRect = s->clipRect;
        clipRegion = s->clipRegion;
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

void Snapshot::ensureClipRegion() {
    if (clipRegion->isEmpty()) {
        clipRegion->setRect(clipRect->left, clipRect->top, clipRect->right, clipRect->bottom);
    }
}

void Snapshot::copyClipRectFromRegion() {
    if (!clipRegion->isEmpty()) {
        const SkIRect& bounds = clipRegion->getBounds();
        clipRect->set(bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom);

        if (clipRegion->isRect()) {
            clipRegion->setEmpty();
        }
    } else {
        clipRect->setEmpty();
    }
}

bool Snapshot::clipRegionOp(float left, float top, float right, float bottom, SkRegion::Op op) {
    SkIRect tmp;
    tmp.set(left, top, right, bottom);
    clipRegion->op(tmp, op);
    copyClipRectFromRegion();
    return true;
}

bool Snapshot::clipRegionTransformed(const SkRegion& region, SkRegion::Op op) {
    ensureClipRegion();
    clipRegion->op(region, op);
    copyClipRectFromRegion();
    flags |= Snapshot::kFlagClipSet;
    return true;
}

bool Snapshot::clip(float left, float top, float right, float bottom, SkRegion::Op op) {
    Rect r(left, top, right, bottom);
    transform->mapRect(r);
    return clipTransformed(r, op);
}

bool Snapshot::clipTransformed(const Rect& r, SkRegion::Op op) {
    bool clipped = false;

    switch (op) {
        case SkRegion::kIntersect_Op: {
            if (CC_UNLIKELY(!clipRegion->isEmpty())) {
                ensureClipRegion();
                clipped = clipRegionOp(r.left, r.top, r.right, r.bottom, SkRegion::kIntersect_Op);
            } else {
                clipped = clipRect->intersect(r);
                if (!clipped) {
                    clipRect->setEmpty();
                    clipped = true;
                }
            }
            break;
        }
        case SkRegion::kReplace_Op: {
            setClip(r.left, r.top, r.right, r.bottom);
            clipped = true;
            break;
        }
        default: {
            ensureClipRegion();
            clipped = clipRegionOp(r.left, r.top, r.right, r.bottom, op);
            break;
        }
    }

    if (clipped) {
        flags |= Snapshot::kFlagClipSet;
    }

    return clipped;
}

void Snapshot::setClip(float left, float top, float right, float bottom) {
    clipRect->set(left, top, right, bottom);
    if (!clipRegion->isEmpty()) {
        clipRegion->setEmpty();
    }
    flags |= Snapshot::kFlagClipSet;
}

bool Snapshot::hasPerspectiveTransform() const {
    return transform->isPerspective();
}

const Rect& Snapshot::getLocalClip() {
    mat4 inverse;
    inverse.loadInverse(*transform);

    mLocalClip.set(*clipRect);
    inverse.mapRect(mLocalClip);

    return mLocalClip;
}

void Snapshot::resetClip(float left, float top, float right, float bottom) {
    // TODO: This is incorrect, when we start rendering into a new layer,
    // we may have to modify the previous snapshot's clip rect and clip
    // region if the previous restore() call did not restore the clip
    clipRect = &mClipRectRoot;
    clipRegion = &mClipRegionRoot;
    setClip(left, top, right, bottom);
}

///////////////////////////////////////////////////////////////////////////////
// Transforms
///////////////////////////////////////////////////////////////////////////////

void Snapshot::resetTransform(float x, float y, float z) {
    // before resetting, map current light pos with inverse of current transform
    Vector3 center = mRelativeLightCenter;
    mat4 inverse;
    inverse.loadInverse(*transform);
    inverse.mapPoint3d(center);
    mRelativeLightCenter = center;

    transform = &mTransformRoot;
    transform->loadTranslate(x, y, z);
}

///////////////////////////////////////////////////////////////////////////////
// Clipping round rect
///////////////////////////////////////////////////////////////////////////////

void Snapshot::setClippingRoundRect(LinearAllocator& allocator, const Rect& bounds, float radius) {
    if (bounds.isEmpty()) {
        clipRect->setEmpty();
        return;
    }

    RoundRectClipState* state = new (allocator) RoundRectClipState;

    // store the inverse drawing matrix
    Matrix4 roundRectDrawingMatrix;
    roundRectDrawingMatrix.load(getOrthoMatrix());
    roundRectDrawingMatrix.multiply(*transform);
    state->matrix.loadInverse(roundRectDrawingMatrix);

    // compute area under rounded corners - only draws overlapping these rects need to be clipped
    for (int i = 0 ; i < 4; i++) {
        state->dangerRects[i] = bounds;
    }
    state->dangerRects[0].bottom = state->dangerRects[1].bottom = bounds.top + radius;
    state->dangerRects[0].right = state->dangerRects[2].right = bounds.left + radius;
    state->dangerRects[1].left = state->dangerRects[3].left = bounds.right - radius;
    state->dangerRects[2].top = state->dangerRects[3].top = bounds.bottom - radius;
    for (int i = 0; i < 4; i++) {
        transform->mapRect(state->dangerRects[i]);

        // round danger rects out as though they are AA geometry (since they essentially are)
        state->dangerRects[i].snapGeometryToPixelBoundaries(true);
    }

    // store RR area
    state->innerRect = bounds;
    state->innerRect.inset(radius);
    state->radius = radius;

    // store as immutable so, for this frame, pointer uniquely identifies this bundle of shader info
    roundRectClipState = state;
}

///////////////////////////////////////////////////////////////////////////////
// Queries
///////////////////////////////////////////////////////////////////////////////

bool Snapshot::isIgnored() const {
    return invisible || empty;
}

void Snapshot::dump() const {
    ALOGD("Snapshot %p, flags %x, prev %p, height %d, ignored %d, hasComplexClip %d",
            this, flags, previous.get(), getViewportHeight(), isIgnored(), clipRegion && !clipRegion->isEmpty());
    ALOGD("  ClipRect (at %p) %.1f %.1f %.1f %.1f",
            clipRect, clipRect->left, clipRect->top, clipRect->right, clipRect->bottom);
    ALOGD("  Transform (at %p):", transform);
    transform->dump();
}

}; // namespace uirenderer
}; // namespace android
