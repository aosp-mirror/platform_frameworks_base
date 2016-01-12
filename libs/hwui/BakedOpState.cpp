/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "BakedOpState.h"

#include "ClipArea.h"

namespace android {
namespace uirenderer {

ResolvedRenderState::ResolvedRenderState(LinearAllocator& allocator, Snapshot& snapshot,
        const RecordedOp& recordedOp, bool expandForStroke) {
    // resolvedMatrix = parentMatrix * localMatrix
    transform.loadMultiply(*snapshot.transform, recordedOp.localMatrix);

    // resolvedClippedBounds = intersect(resolvedMatrix * opBounds, resolvedClipRect)
    clippedBounds = recordedOp.unmappedBounds;
    if (CC_UNLIKELY(expandForStroke)) {
        // account for non-hairline stroke
        clippedBounds.outset(recordedOp.paint->getStrokeWidth() * 0.5f);
    }
    transform.mapRect(clippedBounds);
    if (CC_UNLIKELY(expandForStroke
            && (!transform.isPureTranslate() || recordedOp.paint->getStrokeWidth() < 1.0f))) {
        // account for hairline stroke when stroke may be < 1 scaled pixel
        // Non translate || strokeWidth < 1 is conservative, but will cover all cases
        clippedBounds.outset(0.5f);
    }

    // resolvedClipRect = intersect(parentMatrix * localClip, parentClip)
    clipState = snapshot.mutateClipArea().serializeIntersectedClip(allocator,
            recordedOp.localClip, *(snapshot.transform));
    LOG_ALWAYS_FATAL_IF(!clipState, "must clip!");

    const Rect& clipRect = clipState->rect;
    if (CC_UNLIKELY(clipRect.isEmpty() || !clippedBounds.intersects(clipRect))) {
        // Rejected based on either empty clip, or bounds not intersecting with clip

        // Note: we could rewind the clipState object in situations where the clipRect is empty,
        // but *only* if the caching logic within ClipArea was aware of the rewind.
        clipState = nullptr;
        clippedBounds.setEmpty();
    } else {
        // Not rejected! compute true clippedBounds and clipSideFlags
        if (clipRect.left > clippedBounds.left) clipSideFlags |= OpClipSideFlags::Left;
        if (clipRect.top > clippedBounds.top) clipSideFlags |= OpClipSideFlags::Top;
        if (clipRect.right < clippedBounds.right) clipSideFlags |= OpClipSideFlags::Right;
        if (clipRect.bottom < clippedBounds.bottom) clipSideFlags |= OpClipSideFlags::Bottom;
        clippedBounds.doIntersect(clipRect);
    }
}

ResolvedRenderState::ResolvedRenderState(LinearAllocator& allocator, Snapshot& snapshot) {
    transform = *snapshot.transform;

    // Since the op doesn't have known bounds, we conservatively set the mapped bounds
    // to the current clipRect, and clipSideFlags to Full.
    clipState = snapshot.mutateClipArea().serializeClip(allocator);
    LOG_ALWAYS_FATAL_IF(!clipState, "clipState required");
    clippedBounds = clipState->rect;
    transform.mapRect(clippedBounds);
    clipSideFlags = OpClipSideFlags::Full;
}

ResolvedRenderState::ResolvedRenderState(const ClipRect* viewportRect, const Rect& dstRect)
        : transform(Matrix4::identity())
        , clipState(viewportRect)
        , clippedBounds(dstRect)
        , clipSideFlags(OpClipSideFlags::None) {}

} // namespace uirenderer
} // namespace android
