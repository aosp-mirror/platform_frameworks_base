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

#define LOG_TAG "OpenGLRenderer"

#include <SkCanvas.h>

#include "StatefulBaseRenderer.h"

#include "utils/MathUtils.h"

namespace android {
namespace uirenderer {

StatefulBaseRenderer::StatefulBaseRenderer()
        : mDirtyClip(false)
        , mWidth(-1)
        , mHeight(-1)
        , mSaveCount(1)
        , mFirstSnapshot(new Snapshot)
        , mSnapshot(mFirstSnapshot) {
}

void StatefulBaseRenderer::initializeSaveStack(float clipLeft, float clipTop,
        float clipRight, float clipBottom, const Vector3& lightCenter) {
    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSnapshot->setClip(clipLeft, clipTop, clipRight, clipBottom);
    mSnapshot->fbo = getTargetFbo();
    mSnapshot->setRelativeLightCenter(lightCenter);
    mSaveCount = 1;
}

void StatefulBaseRenderer::setViewport(int width, int height) {
    mWidth = width;
    mHeight = height;
    mFirstSnapshot->initializeViewport(width, height);
    onViewportInitialized();
}

///////////////////////////////////////////////////////////////////////////////
// Save (layer)
///////////////////////////////////////////////////////////////////////////////

/**
 * Non-virtual implementation of save, guaranteed to save without side-effects
 *
 * The approach here and in restoreSnapshot(), allows subclasses to directly manipulate the save
 * stack, and ensures restoreToCount() doesn't call back into subclass overrides.
 */
int StatefulBaseRenderer::saveSnapshot(int flags) {
    mSnapshot = new Snapshot(mSnapshot, flags);
    return mSaveCount++;
}

int StatefulBaseRenderer::save(int flags) {
    return saveSnapshot(flags);
}

/**
 * Non-virtual implementation of restore, guaranteed to restore without side-effects.
 */
void StatefulBaseRenderer::restoreSnapshot() {
    sp<Snapshot> toRemove = mSnapshot;
    sp<Snapshot> toRestore = mSnapshot->previous;

    mSaveCount--;
    mSnapshot = toRestore;

    // subclass handles restore implementation
    onSnapshotRestored(*toRemove, *toRestore);
}

void StatefulBaseRenderer::restore() {
    if (mSaveCount > 1) {
        restoreSnapshot();
    }
}

void StatefulBaseRenderer::restoreToCount(int saveCount) {
    if (saveCount < 1) saveCount = 1;

    while (mSaveCount > saveCount) {
        restoreSnapshot();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Matrix
///////////////////////////////////////////////////////////////////////////////

void StatefulBaseRenderer::getMatrix(Matrix4* matrix) const {
    matrix->load(*(mSnapshot->transform));
}

void StatefulBaseRenderer::getMatrix(SkMatrix* matrix) const {
    mSnapshot->transform->copyTo(*matrix);
}

void StatefulBaseRenderer::translate(float dx, float dy, float dz) {
    mSnapshot->transform->translate(dx, dy, dz);
}

void StatefulBaseRenderer::rotate(float degrees) {
    mSnapshot->transform->rotate(degrees, 0.0f, 0.0f, 1.0f);
}

void StatefulBaseRenderer::scale(float sx, float sy) {
    mSnapshot->transform->scale(sx, sy, 1.0f);
}

void StatefulBaseRenderer::skew(float sx, float sy) {
    mSnapshot->transform->skew(sx, sy);
}

void StatefulBaseRenderer::setMatrix(const SkMatrix& matrix) {
    mSnapshot->transform->load(matrix);
}

void StatefulBaseRenderer::setMatrix(const Matrix4& matrix) {
    mSnapshot->transform->load(matrix);
}

void StatefulBaseRenderer::concatMatrix(const SkMatrix& matrix) {
    mat4 transform(matrix);
    mSnapshot->transform->multiply(transform);
}

void StatefulBaseRenderer::concatMatrix(const Matrix4& matrix) {
    mSnapshot->transform->multiply(matrix);
}

///////////////////////////////////////////////////////////////////////////////
// Clip
///////////////////////////////////////////////////////////////////////////////

bool StatefulBaseRenderer::clipRect(float left, float top, float right, float bottom, SkRegion::Op op) {
    if (CC_LIKELY(currentTransform()->rectToRect())) {
        mDirtyClip |= mSnapshot->clip(left, top, right, bottom, op);
        return !mSnapshot->clipRect->isEmpty();
    }

    SkPath path;
    path.addRect(left, top, right, bottom);

    return StatefulBaseRenderer::clipPath(&path, op);
}

bool StatefulBaseRenderer::clipPath(const SkPath* path, SkRegion::Op op) {
    SkMatrix transform;
    currentTransform()->copyTo(transform);

    SkPath transformed;
    path->transform(transform, &transformed);

    SkRegion clip;
    if (!mSnapshot->previous->clipRegion->isEmpty()) {
        clip.setRegion(*mSnapshot->previous->clipRegion);
    } else {
        if (mSnapshot->previous == firstSnapshot()) {
            clip.setRect(0, 0, getWidth(), getHeight());
        } else {
            Rect* bounds = mSnapshot->previous->clipRect;
            clip.setRect(bounds->left, bounds->top, bounds->right, bounds->bottom);
        }
    }

    SkRegion region;
    region.setPath(transformed, clip);

    // region is the transformed input path, masked by the previous clip
    mDirtyClip |= mSnapshot->clipRegionTransformed(region, op);
    return !mSnapshot->clipRect->isEmpty();
}

bool StatefulBaseRenderer::clipRegion(const SkRegion* region, SkRegion::Op op) {
    mDirtyClip |= mSnapshot->clipRegionTransformed(*region, op);
    return !mSnapshot->clipRect->isEmpty();
}

void StatefulBaseRenderer::setClippingOutline(LinearAllocator& allocator, const Outline* outline) {
    Rect bounds;
    float radius;
    if (!outline->getAsRoundRect(&bounds, &radius)) return; // only RR supported

    if (!MathUtils::isPositive(radius)) {
        // TODO: consider storing this rect separately, so that this can't be replaced with clip ops
        clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom, SkRegion::kIntersect_Op);
        return;
    }
    setClippingRoundRect(allocator, bounds, radius);
}

void StatefulBaseRenderer::setClippingRoundRect(LinearAllocator& allocator,
        const Rect& rect, float radius) {
    mSnapshot->setClippingRoundRect(allocator, rect, radius);
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
bool StatefulBaseRenderer::calculateQuickRejectForScissor(float left, float top,
        float right, float bottom,
        bool* clipRequired, bool* roundRectClipRequired,
        bool snapOut) const {
    if (mSnapshot->isIgnored() || bottom <= top || right <= left) {
        return true;
    }

    Rect r(left, top, right, bottom);
    currentTransform()->mapRect(r);
    r.snapGeometryToPixelBoundaries(snapOut);

    Rect clipRect(*currentClipRect());
    clipRect.snapToPixelBoundaries();

    if (!clipRect.intersects(r)) return true;

    // clip is required if geometry intersects clip rect
    if (clipRequired) {
        *clipRequired = !clipRect.contains(r);
    }

    // round rect clip is required if RR clip exists, and geometry intersects its corners
    if (roundRectClipRequired) {
        *roundRectClipRequired = mSnapshot->roundRectClipState != NULL
                && mSnapshot->roundRectClipState->areaRequiresRoundRectClip(r);
    }
    return false;
}

/**
 * Returns false if drawing won't be clipped out.
 *
 * Makes the decision conservatively, by rounding out the mapped rect before comparing with the
 * clipRect. To be used when perfect, pixel accuracy is not possible (esp. with tessellation) but
 * rejection is still desired.
 *
 * This function, unlike quickRejectSetupScissor, should be used where precise geometry information
 * isn't known (esp. when geometry adjusts based on scale). Generally, this will be first pass
 * rejection where precise rejection isn't important, or precise information isn't available.
 */
bool StatefulBaseRenderer::quickRejectConservative(float left, float top,
        float right, float bottom) const {
    if (mSnapshot->isIgnored() || bottom <= top || right <= left) {
        return true;
    }

    Rect r(left, top, right, bottom);
    currentTransform()->mapRect(r);
    r.roundOut(); // rounded out to be conservative

    Rect clipRect(*currentClipRect());
    clipRect.snapToPixelBoundaries();

    if (!clipRect.intersects(r)) return true;

    return false;
}

}; // namespace uirenderer
}; // namespace android
