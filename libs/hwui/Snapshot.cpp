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
        , previous(nullptr)
        , layer(nullptr)
        , fbo(0)
        , invisible(false)
        , empty(false)
        , alpha(1.0f)
        , roundRectClipState(nullptr)
        , projectionPathMask(nullptr)
        , mClipArea(&mClipAreaRoot) {
    transform = &mTransformRoot;
    region = nullptr;
    mRelativeLightCenter.x = mRelativeLightCenter.y = mRelativeLightCenter.z = 0;
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
        , projectionPathMask(s->projectionPathMask)
        , mClipArea(nullptr)
        , mViewportData(s->mViewportData)
        , mRelativeLightCenter(s->mRelativeLightCenter) {
    if (saveFlags & SkCanvas::kMatrix_SaveFlag) {
        mTransformRoot.load(*s->transform);
        transform = &mTransformRoot;
    } else {
        transform = s->transform;
    }

    if (saveFlags & SkCanvas::kClip_SaveFlag) {
        mClipAreaRoot = s->getClipArea();
        mClipArea = &mClipAreaRoot;
    } else {
        mClipArea = s->mClipArea;
    }

    if (s->flags & Snapshot::kFlagFboTarget) {
        flags |= Snapshot::kFlagFboTarget;
        region = s->region;
    } else {
        region = nullptr;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Clipping
///////////////////////////////////////////////////////////////////////////////

bool Snapshot::clipRegionTransformed(const SkRegion& region, SkRegion::Op op) {
    flags |= Snapshot::kFlagClipSet;
    return mClipArea->clipRegion(region, op);
}

bool Snapshot::clip(float left, float top, float right, float bottom, SkRegion::Op op) {
    flags |= Snapshot::kFlagClipSet;
    return mClipArea->clipRectWithTransform(left, top, right, bottom, transform, op);
}

bool Snapshot::clipPath(const SkPath& path, SkRegion::Op op) {
    flags |= Snapshot::kFlagClipSet;
    return mClipArea->clipPathWithTransform(path, transform, op);
}

void Snapshot::setClip(float left, float top, float right, float bottom) {
    mClipArea->setClip(left, top, right, bottom);
    flags |= Snapshot::kFlagClipSet;
}

bool Snapshot::hasPerspectiveTransform() const {
    return transform->isPerspective();
}

const Rect& Snapshot::getLocalClip() {
    mat4 inverse;
    inverse.loadInverse(*transform);

    mLocalClip.set(mClipArea->getClipRect());
    inverse.mapRect(mLocalClip);

    return mLocalClip;
}

void Snapshot::resetClip(float left, float top, float right, float bottom) {
    // TODO: This is incorrect, when we start rendering into a new layer,
    // we may have to modify the previous snapshot's clip rect and clip
    // region if the previous restore() call did not restore the clip
    mClipArea = &mClipAreaRoot;
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

void Snapshot::buildScreenSpaceTransform(Matrix4* outTransform) const {
    // build (reverse ordered) list of the stack of snapshots, terminated with a NULL
    Vector<const Snapshot*> snapshotList;
    snapshotList.push(nullptr);
    const Snapshot* current = this;
    do {
        snapshotList.push(current);
        current = current->previous.get();
    } while (current);

    // traverse the list, adding in each transform that contributes to the total transform
    outTransform->loadIdentity();
    for (size_t i = snapshotList.size() - 1; i > 0; i--) {
        // iterate down the stack
        const Snapshot* current = snapshotList[i];
        const Snapshot* next = snapshotList[i - 1];
        if (current->flags & kFlagIsFboLayer) {
            // if we've hit a layer, translate by the layer's draw offset
            outTransform->translate(current->layer->layer.left, current->layer->layer.top);
        }
        if (!next || (next->flags & kFlagIsFboLayer)) {
            // if this snapshot is last, or if this snapshot is last before an
            // FBO layer (which reset the transform), apply it
            outTransform->multiply(*(current->transform));
        }
    }
}

///////////////////////////////////////////////////////////////////////////////
// Clipping round rect
///////////////////////////////////////////////////////////////////////////////

void Snapshot::setClippingRoundRect(LinearAllocator& allocator, const Rect& bounds,
        float radius, bool highPriority) {
    if (bounds.isEmpty()) {
        mClipArea->setEmpty();
        return;
    }

    if (roundRectClipState && roundRectClipState->highPriority) {
        // ignore, don't replace, already have a high priority clip
        return;
    }

    RoundRectClipState* state = new (allocator) RoundRectClipState;

    state->highPriority = highPriority;

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

void Snapshot::setProjectionPathMask(LinearAllocator& allocator, const SkPath* path) {
    if (path) {
        ProjectionPathMask* mask = new (allocator) ProjectionPathMask;
        mask->projectionMask = path;
        buildScreenSpaceTransform(&(mask->projectionMaskTransform));

        projectionPathMask = mask;
    } else {
        projectionPathMask = nullptr;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Queries
///////////////////////////////////////////////////////////////////////////////

bool Snapshot::isIgnored() const {
    return invisible || empty;
}

void Snapshot::dump() const {
    ALOGD("Snapshot %p, flags %x, prev %p, height %d, ignored %d, hasComplexClip %d",
            this, flags, previous.get(), getViewportHeight(), isIgnored(), !mClipArea->isSimple());
    const Rect& clipRect(mClipArea->getClipRect());
    ALOGD("  ClipRect %.1f %.1f %.1f %.1f, clip simple %d",
            clipRect.left, clipRect.top, clipRect.right, clipRect.bottom, mClipArea->isSimple());

    ALOGD("  Transform (at %p):", transform);
    transform->dump();
}

}; // namespace uirenderer
}; // namespace android
