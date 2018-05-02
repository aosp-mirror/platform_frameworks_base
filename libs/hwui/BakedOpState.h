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

class BakedOpState;

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
    const BakedOpState* const* states;
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
                        const RecordedOp& recordedOp, bool expandForStroke,
                        bool expandForPathTexture);

    // Constructor for unbounded ops *with* transform/clip
    ResolvedRenderState(LinearAllocator& allocator, Snapshot& snapshot,
                        const Matrix4& localTransform, const ClipBase* localClip);

    // Constructor for unbounded ops without transform/clip (namely shadows)
    ResolvedRenderState(LinearAllocator& allocator, Snapshot& snapshot);

    // Constructor for primitive ops provided clip, and no transform
    ResolvedRenderState(const ClipRect* viewportRect, const Rect& dstRect);

    Rect computeLocalSpaceClip() const {
        Matrix4 inverse;
        inverse.loadInverse(transform);

        Rect outClip(clipRect());
        inverse.mapRect(outClip);
        return outClip;
    }

    const Rect& clipRect() const { return clipState->rect; }

    bool requiresClip() const {
        return clipSideFlags != OpClipSideFlags::None ||
               CC_UNLIKELY(clipState->mode != ClipMode::Rectangle);
    }

    // returns the clip if it's needed to draw the operation, otherwise nullptr
    const ClipBase* getClipIfNeeded() const { return requiresClip() ? clipState : nullptr; }

    Matrix4 transform;
    const ClipBase* clipState = nullptr;
    Rect clippedBounds;
    int clipSideFlags = 0;
    const SkPath* localProjectionPathMask = nullptr;
    bool opaqueOverClippedBounds = false;
};

/**
 * Self-contained op wrapper, containing all resolved state required to draw the op.
 *
 * Stashed pointers within all point to longer lived objects, with no ownership implied.
 */
class BakedOpState {
public:
    static BakedOpState* tryConstruct(LinearAllocator& allocator, Snapshot& snapshot,
                                      const RecordedOp& recordedOp);

    static BakedOpState* tryConstructUnbounded(LinearAllocator& allocator, Snapshot& snapshot,
                                               const RecordedOp& recordedOp);

    enum class StrokeBehavior {
        // stroking is forced, regardless of style on paint (such as for lines)
        Forced,
        // stroking is defined by style on paint
        StyleDefined,
    };

    static BakedOpState* tryStrokeableOpConstruct(LinearAllocator& allocator, Snapshot& snapshot,
                                                  const RecordedOp& recordedOp,
                                                  StrokeBehavior strokeBehavior,
                                                  bool expandForPathTexture);

    static BakedOpState* tryShadowOpConstruct(LinearAllocator& allocator, Snapshot& snapshot,
                                              const ShadowOp* shadowOpPtr);

    static BakedOpState* directConstruct(LinearAllocator& allocator, const ClipRect* clip,
                                         const Rect& dstRect, const RecordedOp& recordedOp);

    // Set opaqueOverClippedBounds. If this method isn't called, the op is assumed translucent.
    void setupOpacity(const SkPaint* paint);

    // computed state:
    ResolvedRenderState computedState;

    // simple state (straight pointer/value storage):
    const float alpha;
    const RoundRectClipState* roundRectClipState;
    const RecordedOp* op;

private:
    friend class LinearAllocator;

    BakedOpState(LinearAllocator& allocator, Snapshot& snapshot, const RecordedOp& recordedOp,
                 bool expandForStroke, bool expandForPathTexture)
            : computedState(allocator, snapshot, recordedOp, expandForStroke, expandForPathTexture)
            , alpha(snapshot.alpha)
            , roundRectClipState(snapshot.roundRectClipState)
            , op(&recordedOp) {}

    // TODO: fix this brittleness
    BakedOpState(LinearAllocator& allocator, Snapshot& snapshot, const RecordedOp& recordedOp)
            : computedState(allocator, snapshot, recordedOp.localMatrix, recordedOp.localClip)
            , alpha(snapshot.alpha)
            , roundRectClipState(snapshot.roundRectClipState)
            , op(&recordedOp) {}

    BakedOpState(LinearAllocator& allocator, Snapshot& snapshot, const ShadowOp* shadowOpPtr)
            : computedState(allocator, snapshot)
            , alpha(snapshot.alpha)
            , roundRectClipState(snapshot.roundRectClipState)
            , op(shadowOpPtr) {}

    BakedOpState(const ClipRect* clipRect, const Rect& dstRect, const RecordedOp& recordedOp)
            : computedState(clipRect, dstRect)
            , alpha(1.0f)
            , roundRectClipState(nullptr)
            , op(&recordedOp) {}
};

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_BAKED_OP_STATE_H
