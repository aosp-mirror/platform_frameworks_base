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

#include <SkCanvas.h>

#include "CanvasState.h"
#include "utils/MathUtils.h"

namespace android {
namespace uirenderer {


CanvasState::CanvasState(CanvasStateClient& renderer)
        : mDirtyClip(false)
        , mWidth(-1)
        , mHeight(-1)
        , mSaveCount(1)
        , mFirstSnapshot(new Snapshot)
        , mCanvas(renderer)
        , mSnapshot(mFirstSnapshot) {

}

CanvasState::~CanvasState() {

}

void CanvasState::initializeSaveStack(float clipLeft, float clipTop,
        float clipRight, float clipBottom, const Vector3& lightCenter) {
    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSnapshot->setClip(clipLeft, clipTop, clipRight, clipBottom);
    mSnapshot->fbo = mCanvas.getTargetFbo();
    mSnapshot->setRelativeLightCenter(lightCenter);
    mSaveCount = 1;
}

void CanvasState::setViewport(int width, int height) {
    mWidth = width;
    mHeight = height;
    mFirstSnapshot->initializeViewport(width, height);
    mCanvas.onViewportInitialized();

    // create a temporary 1st snapshot, so old snapshots are released,
    // and viewport can be queried safely.
    // TODO: remove, combine viewport + save stack initialization
    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSaveCount = 1;
}

///////////////////////////////////////////////////////////////////////////////
// Save (layer)
///////////////////////////////////////////////////////////////////////////////

/**
 * Guaranteed to save without side-effects
 *
 * This approach, here and in restoreSnapshot(), allows subclasses to directly manipulate the save
 * stack, and ensures restoreToCount() doesn't call back into subclass overrides.
 */
int CanvasState::saveSnapshot(int flags) {
    mSnapshot = new Snapshot(mSnapshot, flags);
    return mSaveCount++;
}

int CanvasState::save(int flags) {
    return saveSnapshot(flags);
}

/**
 * Guaranteed to restore without side-effects.
 */
void CanvasState::restoreSnapshot() {
    sp<Snapshot> toRemove = mSnapshot;
    sp<Snapshot> toRestore = mSnapshot->previous;

    mSaveCount--;
    mSnapshot = toRestore;

    // subclass handles restore implementation
    mCanvas.onSnapshotRestored(*toRemove, *toRestore);
}

void CanvasState::restore() {
    if (mSaveCount > 1) {
        restoreSnapshot();
    }
}

void CanvasState::restoreToCount(int saveCount) {
    if (saveCount < 1) saveCount = 1;

    while (mSaveCount > saveCount) {
        restoreSnapshot();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Matrix
///////////////////////////////////////////////////////////////////////////////

void CanvasState::getMatrix(SkMatrix* matrix) const {
    mSnapshot->transform->copyTo(*matrix);
}

void CanvasState::translate(float dx, float dy, float dz) {
    mSnapshot->transform->translate(dx, dy, dz);
}

void CanvasState::rotate(float degrees) {
    mSnapshot->transform->rotate(degrees, 0.0f, 0.0f, 1.0f);
}

void CanvasState::scale(float sx, float sy) {
    mSnapshot->transform->scale(sx, sy, 1.0f);
}

void CanvasState::skew(float sx, float sy) {
    mSnapshot->transform->skew(sx, sy);
}

void CanvasState::setMatrix(const SkMatrix& matrix) {
    mSnapshot->transform->load(matrix);
}

void CanvasState::setMatrix(const Matrix4& matrix) {
    mSnapshot->transform->load(matrix);
}

void CanvasState::concatMatrix(const SkMatrix& matrix) {
    mat4 transform(matrix);
    mSnapshot->transform->multiply(transform);
}

void CanvasState::concatMatrix(const Matrix4& matrix) {
    mSnapshot->transform->multiply(matrix);
}

///////////////////////////////////////////////////////////////////////////////
// Clip
///////////////////////////////////////////////////////////////////////////////

bool CanvasState::clipRect(float left, float top, float right, float bottom, SkRegion::Op op) {
    mDirtyClip |= mSnapshot->clip(left, top, right, bottom, op);
    return !mSnapshot->clipIsEmpty();
}

bool CanvasState::clipPath(const SkPath* path, SkRegion::Op op) {
    mDirtyClip |= mSnapshot->clipPath(*path, op);
    return !mSnapshot->clipIsEmpty();
}

bool CanvasState::clipRegion(const SkRegion* region, SkRegion::Op op) {
    mDirtyClip |= mSnapshot->clipRegionTransformed(*region, op);
    return !mSnapshot->clipIsEmpty();
}

void CanvasState::setClippingOutline(LinearAllocator& allocator, const Outline* outline) {
    Rect bounds;
    float radius;
    if (!outline->getAsRoundRect(&bounds, &radius)) return; // only RR supported

    bool outlineIsRounded = MathUtils::isPositive(radius);
    if (!outlineIsRounded || currentTransform()->isSimple()) {
        // TODO: consider storing this rect separately, so that this can't be replaced with clip ops
        clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom, SkRegion::kIntersect_Op);
    }
    if (outlineIsRounded) {
        setClippingRoundRect(allocator, bounds, radius, false);
    }
}

void CanvasState::setClippingRoundRect(LinearAllocator& allocator,
        const Rect& rect, float radius, bool highPriority) {
    mSnapshot->setClippingRoundRect(allocator, rect, radius, highPriority);
}

void CanvasState::setProjectionPathMask(LinearAllocator& allocator, const SkPath* path) {
    mSnapshot->setProjectionPathMask(allocator, path);
}

///////////////////////////////////////////////////////////////////////////////
// Quick Rejection
///////////////////////////////////////////////////////////////////////////////

/**
 * Calculates whether content drawn within the passed bounds would be outside of, or intersect with
 * the clipRect. Does not modify the scissor.
 *
 * @param clipRequired if not null, will be set to true if element intersects clip
 *         (and wasn't rejected)
 *
 * @param snapOut if set, the geometry will be treated as having an AA ramp.
 *         See Rect::snapGeometryToPixelBoundaries()
 */
bool CanvasState::calculateQuickRejectForScissor(float left, float top,
        float right, float bottom,
        bool* clipRequired, bool* roundRectClipRequired,
        bool snapOut) const {
    if (mSnapshot->isIgnored() || bottom <= top || right <= left) {
        return true;
    }

    Rect r(left, top, right, bottom);
    currentTransform()->mapRect(r);
    r.snapGeometryToPixelBoundaries(snapOut);

    Rect clipRect(currentClipRect());
    clipRect.snapToPixelBoundaries();

    if (!clipRect.intersects(r)) return true;

    // clip is required if geometry intersects clip rect
    if (clipRequired) {
        *clipRequired = !clipRect.contains(r);
    }

    // round rect clip is required if RR clip exists, and geometry intersects its corners
    if (roundRectClipRequired) {
        *roundRectClipRequired = mSnapshot->roundRectClipState != nullptr
                && mSnapshot->roundRectClipState->areaRequiresRoundRectClip(r);
    }
    return false;
}

bool CanvasState::quickRejectConservative(float left, float top,
        float right, float bottom) const {
    if (mSnapshot->isIgnored() || bottom <= top || right <= left) {
        return true;
    }

    Rect r(left, top, right, bottom);
    currentTransform()->mapRect(r);
    r.roundOut(); // rounded out to be conservative

    Rect clipRect(currentClipRect());
    clipRect.snapToPixelBoundaries();

    if (!clipRect.intersects(r)) return true;

    return false;
}

} // namespace uirenderer
} // namespace android
