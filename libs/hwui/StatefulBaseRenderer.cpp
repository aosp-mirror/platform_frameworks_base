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

#include "StatefulBaseRenderer.h"

namespace android {
namespace uirenderer {

StatefulBaseRenderer::StatefulBaseRenderer() :
        mSaveCount(1), mFirstSnapshot(new Snapshot), mSnapshot(mFirstSnapshot) {
}

void StatefulBaseRenderer::initializeSaveStack(float clipLeft, float clipTop,
        float clipRight, float clipBottom) {
    mSnapshot = new Snapshot(mFirstSnapshot,
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    mSnapshot->setClip(clipLeft, clipTop, clipRight, clipBottom);
    mSnapshot->fbo = getTargetFbo();
    mSaveCount = 1;
}

void StatefulBaseRenderer::initializeViewport(int width, int height) {
    mWidth = width;
    mHeight = height;

    mFirstSnapshot->height = height;
    mFirstSnapshot->viewport.set(0, 0, width, height);
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

void StatefulBaseRenderer::setMatrix(SkMatrix* matrix) {
    if (matrix) {
        mSnapshot->transform->load(*matrix);
    } else {
        mSnapshot->transform->loadIdentity();
    }
}

void StatefulBaseRenderer::setMatrix(const Matrix4& matrix) {
    mSnapshot->transform->load(matrix);
}

void StatefulBaseRenderer::concatMatrix(SkMatrix* matrix) {
    mat4 transform(*matrix);
    mSnapshot->transform->multiply(transform);
}

void StatefulBaseRenderer::concatMatrix(const Matrix4& matrix) {
    mSnapshot->transform->multiply(matrix);
}

///////////////////////////////////////////////////////////////////////////////
// Clip
///////////////////////////////////////////////////////////////////////////////


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
        float right, float bottom, bool* clipRequired, bool snapOut) const {
    if (mSnapshot->isIgnored() || bottom <= top || right <= left) {
        return true;
    }

    Rect r(left, top, right, bottom);
    currentTransform().mapRect(r);
    r.snapGeometryToPixelBoundaries(snapOut);

    Rect clipRect(currentClipRect());
    clipRect.snapToPixelBoundaries();

    if (!clipRect.intersects(r)) return true;

    // clip is required if geometry intersects clip rect
    if (clipRequired) *clipRequired = !clipRect.contains(r);
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
    currentTransform().mapRect(r);
    r.roundOut(); // rounded out to be conservative

    Rect clipRect(currentClipRect());
    clipRect.snapToPixelBoundaries();

    if (!clipRect.intersects(r)) return true;

    return false;
}

}; // namespace uirenderer
}; // namespace android
