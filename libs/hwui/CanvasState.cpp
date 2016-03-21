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

#include "CanvasState.h"
#include "hwui/Canvas.h"
#include "utils/MathUtils.h"

namespace android {
namespace uirenderer {


CanvasState::CanvasState(CanvasStateClient& renderer)
        : mDirtyClip(false)
        , mWidth(-1)
        , mHeight(-1)
        , mSaveCount(1)
        , mCanvas(renderer)
        , mSnapshot(&mFirstSnapshot) {
}

CanvasState::~CanvasState() {
    // First call freeSnapshot on all but mFirstSnapshot
    // to invoke all the dtors
    freeAllSnapshots();

    // Now actually release the memory
    while (mSnapshotPool) {
        void* temp = mSnapshotPool;
        mSnapshotPool = mSnapshotPool->previous;
        free(temp);
    }
}

void CanvasState::initializeRecordingSaveStack(int viewportWidth, int viewportHeight) {
    if (mWidth != viewportWidth || mHeight != viewportHeight) {
        mWidth = viewportWidth;
        mHeight = viewportHeight;
        mFirstSnapshot.initializeViewport(viewportWidth, viewportHeight);
        mCanvas.onViewportInitialized();
    }

    freeAllSnapshots();
    mSnapshot = allocSnapshot(&mFirstSnapshot, SaveFlags::MatrixClip);
    mSnapshot->setRelativeLightCenter(Vector3());
    mSaveCount = 1;
}

void CanvasState::initializeSaveStack(
        int viewportWidth, int viewportHeight,
        float clipLeft, float clipTop,
        float clipRight, float clipBottom, const Vector3& lightCenter) {
    if (mWidth != viewportWidth || mHeight != viewportHeight) {
        mWidth = viewportWidth;
        mHeight = viewportHeight;
        mFirstSnapshot.initializeViewport(viewportWidth, viewportHeight);
        mCanvas.onViewportInitialized();
    }

    freeAllSnapshots();
    mSnapshot = allocSnapshot(&mFirstSnapshot, SaveFlags::MatrixClip);
    mSnapshot->setClip(clipLeft, clipTop, clipRight, clipBottom);
    mSnapshot->fbo = mCanvas.getTargetFbo();
    mSnapshot->setRelativeLightCenter(lightCenter);
    mSaveCount = 1;
}

Snapshot* CanvasState::allocSnapshot(Snapshot* previous, int savecount) {
    void* memory;
    if (mSnapshotPool) {
        memory = mSnapshotPool;
        mSnapshotPool = mSnapshotPool->previous;
        mSnapshotPoolCount--;
    } else {
        memory = malloc(sizeof(Snapshot));
    }
    return new (memory) Snapshot(previous, savecount);
}

void CanvasState::freeSnapshot(Snapshot* snapshot) {
    snapshot->~Snapshot();
    // Arbitrary number, just don't let this grown unbounded
    if (mSnapshotPoolCount > 10) {
        free((void*) snapshot);
    } else {
        snapshot->previous = mSnapshotPool;
        mSnapshotPool = snapshot;
        mSnapshotPoolCount++;
    }
}

void CanvasState::freeAllSnapshots() {
    while (mSnapshot != &mFirstSnapshot) {
        Snapshot* temp = mSnapshot;
        mSnapshot = mSnapshot->previous;
        freeSnapshot(temp);
    }
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
    mSnapshot = allocSnapshot(mSnapshot, flags);
    return mSaveCount++;
}

int CanvasState::save(int flags) {
    return saveSnapshot(flags);
}

/**
 * Guaranteed to restore without side-effects.
 */
void CanvasState::restoreSnapshot() {
    Snapshot* toRemove = mSnapshot;
    Snapshot* toRestore = mSnapshot->previous;

    mSaveCount--;
    mSnapshot = toRestore;

    // subclass handles restore implementation
    mCanvas.onSnapshotRestored(*toRemove, *toRestore);

    freeSnapshot(toRemove);
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
    *(mSnapshot->transform) = matrix;
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
    mSnapshot->clip(Rect(left, top, right, bottom), op);
    mDirtyClip = true;
    return !mSnapshot->clipIsEmpty();
}

bool CanvasState::clipPath(const SkPath* path, SkRegion::Op op) {
    mSnapshot->clipPath(*path, op);
    mDirtyClip = true;
    return !mSnapshot->clipIsEmpty();
}

bool CanvasState::clipRegion(const SkRegion* region, SkRegion::Op op) {
    mSnapshot->clipRegionTransformed(*region, op);
    mDirtyClip = true;
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

    Rect clipRect(currentRenderTargetClip());
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

    Rect clipRect(currentRenderTargetClip());
    clipRect.snapToPixelBoundaries();

    if (!clipRect.intersects(r)) return true;

    return false;
}

} // namespace uirenderer
} // namespace android
