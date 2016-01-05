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

#ifndef ANDROID_HWUI_BAKED_OP_STATE_H
#define ANDROID_HWUI_BAKED_OP_STATE_H

#include "Matrix.h"
#include "RecordedOp.h"
#include "Rect.h"
#include "Snapshot.h"

namespace android {
namespace uirenderer {

namespace OpClipSideFlags {
    enum {
        None = 0x0,
        Left = 0x1,
        Top = 0x2,
        Right = 0x4,
        Bottom = 0x8,
        Full = 0xF,
        // ConservativeFull = 0x1F  needed?
    };
}

/**
 * Holds a list of BakedOpStates of ops that can be drawn together
 */
struct MergedBakedOpList {
    const BakedOpState*const* states;
    size_t count;
    int clipSideFlags;
    Rect clip;
};

/**
 * Holds the resolved clip, transform, and bounds of a recordedOp, when replayed with a snapshot
 */
class ResolvedRenderState {
public:
    ResolvedRenderState(LinearAllocator& allocator, Snapshot& snapshot,
            const RecordedOp& recordedOp, bool expandForStroke);

    // Constructor for unbounded ops without transform/clip (namely shadows)
    ResolvedRenderState(LinearAllocator& allocator, Snapshot& snapshot);

    Rect computeLocalSpaceClip() const {
        Matrix4 inverse;
        inverse.loadInverse(transform);

        Rect outClip(clipRect());
        inverse.mapRect(outClip);
        return outClip;
    }

    Matrix4 transform;
    const Rect& clipRect() const {
        return clipState->rect;
    }
    bool requiresClip() const {
        return clipSideFlags != OpClipSideFlags::None
                || CC_UNLIKELY(clipState->mode != ClipMode::Rectangle);
    }

    // returns the clip if it's needed to draw the operation, otherwise nullptr
    const ClipBase* getClipIfNeeded() const {
        return requiresClip() ? clipState : nullptr;
    }
    const ClipBase* clipState = nullptr;
    int clipSideFlags = 0;
    Rect clippedBounds;
};

/**
 * Self-contained op wrapper, containing all resolved state required to draw the op.
 *
 * Stashed pointers within all point to longer lived objects, with no ownership implied.
 */
class BakedOpState {
public:
    static BakedOpState* tryConstruct(LinearAllocator& allocator,
            Snapshot& snapshot, const RecordedOp& recordedOp) {
        BakedOpState* bakedState = new (allocator) BakedOpState(
                allocator, snapshot, recordedOp, false);
        if (bakedState->computedState.clippedBounds.isEmpty()) {
            // bounds are empty, so op is rejected
            allocator.rewindIfLastAlloc(bakedState);
            return nullptr;
        }
        return bakedState;
    }

    enum class StrokeBehavior {
        // stroking is forced, regardless of style on paint
        Forced,
        // stroking is defined by style on paint
        StyleDefined,
    };

    static BakedOpState* tryStrokeableOpConstruct(LinearAllocator& allocator,
            Snapshot& snapshot, const RecordedOp& recordedOp, StrokeBehavior strokeBehavior) {
        bool expandForStroke = (strokeBehavior == StrokeBehavior::StyleDefined)
                ? (recordedOp.paint && recordedOp.paint->getStyle() != SkPaint::kFill_Style)
                : true;

        BakedOpState* bakedState = new (allocator) BakedOpState(
                allocator, snapshot, recordedOp, expandForStroke);
        if (bakedState->computedState.clippedBounds.isEmpty()) {
            // bounds are empty, so op is rejected
            allocator.rewindIfLastAlloc(bakedState);
            return nullptr;
        }
        return bakedState;
    }

    static BakedOpState* tryShadowOpConstruct(LinearAllocator& allocator,
            Snapshot& snapshot, const ShadowOp* shadowOpPtr) {
        if (snapshot.getRenderTargetClip().isEmpty()) return nullptr;

        // clip isn't empty, so construct the op
        return new (allocator) BakedOpState(allocator, snapshot, shadowOpPtr);
    }

    static void* operator new(size_t size, LinearAllocator& allocator) {
        return allocator.alloc(size);
    }

    // computed state:
    const ResolvedRenderState computedState;

    // simple state (straight pointer/value storage):
    const float alpha;
    const RoundRectClipState* roundRectClipState;
    const ProjectionPathMask* projectionPathMask;
    const RecordedOp* op;

private:
    BakedOpState(LinearAllocator& allocator, Snapshot& snapshot,
            const RecordedOp& recordedOp, bool expandForStroke)
            : computedState(allocator, snapshot, recordedOp, expandForStroke)
            , alpha(snapshot.alpha)
            , roundRectClipState(snapshot.roundRectClipState)
            , projectionPathMask(snapshot.projectionPathMask)
            , op(&recordedOp) {}

    BakedOpState(LinearAllocator& allocator, Snapshot& snapshot, const ShadowOp* shadowOpPtr)
            : computedState(allocator, snapshot)
            , alpha(snapshot.alpha)
            , roundRectClipState(snapshot.roundRectClipState)
            , projectionPathMask(snapshot.projectionPathMask)
            , op(shadowOpPtr) {}
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_BAKED_OP_STATE_H
