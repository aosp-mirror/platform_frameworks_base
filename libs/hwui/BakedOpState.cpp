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

static int computeClipSideFlags(const Rect& clip, const Rect& bounds) {
    int clipSideFlags = 0;
    if (clip.left > bounds.left) clipSideFlags |= OpClipSideFlags::Left;
    if (clip.top > bounds.top) clipSideFlags |= OpClipSideFlags::Top;
    if (clip.right < bounds.right) clipSideFlags |= OpClipSideFlags::Right;
    if (clip.bottom < bounds.bottom) clipSideFlags |= OpClipSideFlags::Bottom;
    return clipSideFlags;
}

ResolvedRenderState::ResolvedRenderState(LinearAllocator& allocator, Snapshot& snapshot,
                                         const RecordedOp& recordedOp, bool expandForStroke,
                                         bool expandForPathTexture) {
    // resolvedMatrix = parentMatrix * localMatrix
    transform.loadMultiply(*snapshot.transform, recordedOp.localMatrix);

    // resolvedClippedBounds = intersect(resolvedMatrix * opBounds, resolvedClipRect)
    clippedBounds = recordedOp.unmappedBounds;
    if (CC_UNLIKELY(expandForStroke)) {
        // account for non-hairline stroke
        clippedBounds.outset(recordedOp.paint->getStrokeWidth() * 0.5f);
    } else if (CC_UNLIKELY(expandForPathTexture)) {
        clippedBounds.outset(1);
    }
    transform.mapRect(clippedBounds);
    if (CC_UNLIKELY(expandForStroke &&
                    (!transform.isPureTranslate() || recordedOp.paint->getStrokeWidth() < 1.0f))) {
        // account for hairline stroke when stroke may be < 1 scaled pixel
        // Non translate || strokeWidth < 1 is conservative, but will cover all cases
        clippedBounds.outset(0.5f);
    }

    // resolvedClipRect = intersect(parentMatrix * localClip, parentClip)
    clipState = snapshot.serializeIntersectedClip(allocator, recordedOp.localClip,
                                                  *(snapshot.transform));
    LOG_ALWAYS_FATAL_IF(!clipState, "must clip!");

    const Rect& clipRect = clipState->rect;
    if (CC_UNLIKELY(clipRect.isEmpty() || !clippedBounds.intersects(clipRect))) {
        // Rejected based on either empty clip, or bounds not intersecting with clip

        // Note: we could rewind the clipState object in situations where the clipRect is empty,
        // but *only* if the caching logic within ClipArea was aware of the rewind.
        clipState = nullptr;
        clippedBounds.setEmpty();
    } else {
        // Not rejected! compute true clippedBounds, clipSideFlags, and path mask
        clipSideFlags = computeClipSideFlags(clipRect, clippedBounds);
        clippedBounds.doIntersect(clipRect);

        if (CC_UNLIKELY(snapshot.projectionPathMask)) {
            // map projection path mask from render target space into op space,
            // so intersection with op geometry is possible
            Matrix4 inverseTransform;
            inverseTransform.loadInverse(transform);
            SkMatrix skInverseTransform;
            inverseTransform.copyTo(skInverseTransform);

            auto localMask = allocator.create<SkPath>();
            snapshot.projectionPathMask->transform(skInverseTransform, localMask);
            localProjectionPathMask = localMask;
        }
    }
}

ResolvedRenderState::ResolvedRenderState(LinearAllocator& allocator, Snapshot& snapshot,
                                         const Matrix4& localTransform, const ClipBase* localClip) {
    transform.loadMultiply(*snapshot.transform, localTransform);
    clipState = snapshot.serializeIntersectedClip(allocator, localClip, *(snapshot.transform));
    clippedBounds = clipState->rect;
    clipSideFlags = OpClipSideFlags::Full;
    localProjectionPathMask = nullptr;
}

ResolvedRenderState::ResolvedRenderState(LinearAllocator& allocator, Snapshot& snapshot)
        : transform(*snapshot.transform)
        , clipState(snapshot.mutateClipArea().serializeClip(allocator))
        , clippedBounds(clipState->rect)
        , clipSideFlags(OpClipSideFlags::Full)
        , localProjectionPathMask(nullptr) {}

ResolvedRenderState::ResolvedRenderState(const ClipRect* clipRect, const Rect& dstRect)
        : transform(Matrix4::identity())
        , clipState(clipRect)
        , clippedBounds(dstRect)
        , clipSideFlags(computeClipSideFlags(clipRect->rect, dstRect))
        , localProjectionPathMask(nullptr) {
    clippedBounds.doIntersect(clipRect->rect);
}

BakedOpState* BakedOpState::tryConstruct(LinearAllocator& allocator, Snapshot& snapshot,
                                         const RecordedOp& recordedOp) {
    if (CC_UNLIKELY(snapshot.getRenderTargetClip().isEmpty())) return nullptr;
    BakedOpState* bakedState =
            allocator.create_trivial<BakedOpState>(allocator, snapshot, recordedOp, false, false);
    if (bakedState->computedState.clippedBounds.isEmpty()) {
        // bounds are empty, so op is rejected
        allocator.rewindIfLastAlloc(bakedState);
        return nullptr;
    }
    return bakedState;
}

BakedOpState* BakedOpState::tryConstructUnbounded(LinearAllocator& allocator, Snapshot& snapshot,
                                                  const RecordedOp& recordedOp) {
    if (CC_UNLIKELY(snapshot.getRenderTargetClip().isEmpty())) return nullptr;
    return allocator.create_trivial<BakedOpState>(allocator, snapshot, recordedOp);
}

BakedOpState* BakedOpState::tryStrokeableOpConstruct(LinearAllocator& allocator, Snapshot& snapshot,
                                                     const RecordedOp& recordedOp,
                                                     StrokeBehavior strokeBehavior,
                                                     bool expandForPathTexture) {
    if (CC_UNLIKELY(snapshot.getRenderTargetClip().isEmpty())) return nullptr;
    bool expandForStroke =
            (strokeBehavior == StrokeBehavior::Forced ||
             (recordedOp.paint && recordedOp.paint->getStyle() != SkPaint::kFill_Style));

    BakedOpState* bakedState = allocator.create_trivial<BakedOpState>(
            allocator, snapshot, recordedOp, expandForStroke, expandForPathTexture);
    if (bakedState->computedState.clippedBounds.isEmpty()) {
        // bounds are empty, so op is rejected
        // NOTE: this won't succeed if a clip was allocated
        allocator.rewindIfLastAlloc(bakedState);
        return nullptr;
    }
    return bakedState;
}

BakedOpState* BakedOpState::tryShadowOpConstruct(LinearAllocator& allocator, Snapshot& snapshot,
                                                 const ShadowOp* shadowOpPtr) {
    if (CC_UNLIKELY(snapshot.getRenderTargetClip().isEmpty())) return nullptr;

    // clip isn't empty, so construct the op
    return allocator.create_trivial<BakedOpState>(allocator, snapshot, shadowOpPtr);
}

BakedOpState* BakedOpState::directConstruct(LinearAllocator& allocator, const ClipRect* clip,
                                            const Rect& dstRect, const RecordedOp& recordedOp) {
    return allocator.create_trivial<BakedOpState>(clip, dstRect, recordedOp);
}

void BakedOpState::setupOpacity(const SkPaint* paint) {
    computedState.opaqueOverClippedBounds = computedState.transform.isSimple() &&
                                            computedState.clipState->mode == ClipMode::Rectangle &&
                                            MathUtils::areEqual(alpha, 1.0f) &&
                                            !roundRectClipState && PaintUtils::isOpaquePaint(paint);
}

}  // namespace uirenderer
}  // namespace android
