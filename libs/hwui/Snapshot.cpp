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

#include "hwui/Canvas.h"

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
        , alpha(1.0f)
        , roundRectClipState(nullptr)
        , projectionPathMask(nullptr)
        , mClipArea(&mClipAreaRoot) {
    transform = &mTransformRoot;
    mRelativeLightCenter.x = mRelativeLightCenter.y = mRelativeLightCenter.z = 0;
}

/**
 * Copies the specified snapshot/ The specified snapshot is stored as
 * the previous snapshot.
 */
Snapshot::Snapshot(Snapshot* s, int saveFlags)
        : flags(0)
        , previous(s)
        , layer(s->layer)
        , fbo(s->fbo)
        , alpha(s->alpha)
        , roundRectClipState(s->roundRectClipState)
        , projectionPathMask(s->projectionPathMask)
        , mClipArea(nullptr)
        , mViewportData(s->mViewportData)
        , mRelativeLightCenter(s->mRelativeLightCenter) {
    if (saveFlags & SaveFlags::Matrix) {
        mTransformRoot = *s->transform;
        transform = &mTransformRoot;
    } else {
        transform = s->transform;
    }

    if (saveFlags & SaveFlags::Clip) {
        mClipAreaRoot = s->getClipArea();
        mClipArea = &mClipAreaRoot;
    } else {
        mClipArea = s->mClipArea;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Clipping
///////////////////////////////////////////////////////////////////////////////

void Snapshot::clip(const Rect& localClip, SkClipOp op) {
    flags |= Snapshot::kFlagClipSet;
    mClipArea->clipRectWithTransform(localClip, transform, static_cast<SkRegion::Op>(op));
}

void Snapshot::clipPath(const SkPath& path, SkClipOp op) {
    flags |= Snapshot::kFlagClipSet;
    mClipArea->clipPathWithTransform(path, transform, static_cast<SkRegion::Op>(op));
}

void Snapshot::setClip(float left, float top, float right, float bottom) {
    flags |= Snapshot::kFlagClipSet;
    mClipArea->setClip(left, top, right, bottom);
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
// Clipping round rect
///////////////////////////////////////////////////////////////////////////////

void Snapshot::setClippingRoundRect(LinearAllocator& allocator, const Rect& bounds, float radius,
                                    bool highPriority) {
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
    Matrix4 roundRectDrawingMatrix = getOrthoMatrix();
    roundRectDrawingMatrix.multiply(*transform);
    state->matrix.loadInverse(roundRectDrawingMatrix);

    // compute area under rounded corners - only draws overlapping these rects need to be clipped
    for (int i = 0; i < 4; i++) {
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

void Snapshot::setProjectionPathMask(const SkPath* path) {
    projectionPathMask = path;
}

static Snapshot* getClipRoot(Snapshot* target) {
    while (target->previous && target->previous->previous) {
        target = target->previous;
    }
    return target;
}

const ClipBase* Snapshot::serializeIntersectedClip(LinearAllocator& allocator,
                                                   const ClipBase* recordedClip,
                                                   const Matrix4& recordedClipTransform) {
    auto target = this;
    if (CC_UNLIKELY(recordedClip && recordedClip->intersectWithRoot)) {
        // Clip must be intersected with root, instead of current clip.
        target = getClipRoot(this);
    }

    return target->mClipArea->serializeIntersectedClip(allocator, recordedClip,
                                                       recordedClipTransform);
}

void Snapshot::applyClip(const ClipBase* recordedClip, const Matrix4& transform) {
    if (CC_UNLIKELY(recordedClip && recordedClip->intersectWithRoot)) {
        // current clip is being replaced, but must intersect with clip root
        *mClipArea = *(getClipRoot(this)->mClipArea);
    }
    mClipArea->applyClip(recordedClip, transform);
}

///////////////////////////////////////////////////////////////////////////////
// Queries
///////////////////////////////////////////////////////////////////////////////

void Snapshot::dump() const {
    ALOGD("Snapshot %p, flags %x, prev %p, height %d, hasComplexClip %d", this, flags, previous,
          getViewportHeight(), !mClipArea->isSimple());
    const Rect& clipRect(mClipArea->getClipRect());
    ALOGD("  ClipRect %.1f %.1f %.1f %.1f, clip simple %d", clipRect.left, clipRect.top,
          clipRect.right, clipRect.bottom, mClipArea->isSimple());

    ALOGD("  Transform (at %p):", transform);
    transform->dump();
}

};  // namespace uirenderer
};  // namespace android
