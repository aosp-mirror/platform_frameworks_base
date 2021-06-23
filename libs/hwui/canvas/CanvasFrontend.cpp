/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "CanvasFrontend.h"
#include "CanvasOps.h"
#include "CanvasOpBuffer.h"

namespace android::uirenderer {

CanvasStateHelper::CanvasStateHelper(int width, int height) {
    resetState(width, height);
}

void CanvasStateHelper::resetState(int width, int height) {
    mInitialBounds = SkIRect::MakeWH(width, height);
    mSaveStack.clear();
    mClipStack.clear();
    mTransformStack.clear();
    mSaveStack.emplace_back();
    mClipStack.emplace_back().setRect(mInitialBounds);
    mTransformStack.emplace_back();
    mCurrentClipIndex = 0;
    mCurrentTransformIndex = 0;
}

bool CanvasStateHelper::internalSave(SaveEntry saveEntry) {
    mSaveStack.push_back(saveEntry);
    if (saveEntry.matrix) {
        // We need to push before accessing transform() to ensure the reference doesn't move
        // across vector resizes
        mTransformStack.emplace_back() = transform();
        mCurrentTransformIndex += 1;
    }
    if (saveEntry.clip) {
        // We need to push before accessing clip() to ensure the reference doesn't move
        // across vector resizes
        mClipStack.emplace_back() = clip();
        mCurrentClipIndex += 1;
        return true;
    }
    return false;
}

// Assert that the cast from SkClipOp to SkRegion::Op is valid
static_assert(static_cast<int>(SkClipOp::kDifference) == SkRegion::Op::kDifference_Op);
static_assert(static_cast<int>(SkClipOp::kIntersect) == SkRegion::Op::kIntersect_Op);
static_assert(static_cast<int>(SkClipOp::kUnion_deprecated) == SkRegion::Op::kUnion_Op);
static_assert(static_cast<int>(SkClipOp::kXOR_deprecated) == SkRegion::Op::kXOR_Op);
static_assert(static_cast<int>(SkClipOp::kReverseDifference_deprecated) == SkRegion::Op::kReverseDifference_Op);
static_assert(static_cast<int>(SkClipOp::kReplace_deprecated) == SkRegion::Op::kReplace_Op);

void CanvasStateHelper::internalClipRect(const SkRect& rect, SkClipOp op) {
    clip().opRect(rect, transform(), mInitialBounds, (SkRegion::Op)op, false);
}

void CanvasStateHelper::internalClipPath(const SkPath& path, SkClipOp op) {
    clip().opPath(path, transform(), mInitialBounds, (SkRegion::Op)op, true);
}

bool CanvasStateHelper::internalRestore() {
    // Prevent underflows
    if (saveCount() <= 1) {
        return false;
    }

    SaveEntry entry = mSaveStack[mSaveStack.size() - 1];
    mSaveStack.pop_back();
    bool needsRestorePropagation = entry.layer;
    if (entry.matrix) {
        mTransformStack.pop_back();
        mCurrentTransformIndex -= 1;
    }
    if (entry.clip) {
        // We need to push before accessing clip() to ensure the reference doesn't move
        // across vector resizes
        mClipStack.pop_back();
        mCurrentClipIndex -= 1;
        needsRestorePropagation = true;
    }
    return needsRestorePropagation;
}

SkRect CanvasStateHelper::getClipBounds() const {
    SkIRect ibounds = clip().getBounds();

    if (ibounds.isEmpty()) {
        return SkRect::MakeEmpty();
    }

    SkMatrix inverse;
    // if we can't invert the CTM, we can't return local clip bounds
    if (!transform().invert(&inverse)) {
        return SkRect::MakeEmpty();
    }

    SkRect ret = SkRect::MakeEmpty();
    inverse.mapRect(&ret, SkRect::Make(ibounds));
    return ret;
}

bool CanvasStateHelper::quickRejectRect(float left, float top, float right, float bottom) const {
    // TODO: Implement
    return false;
}

bool CanvasStateHelper::quickRejectPath(const SkPath& path) const {
    // TODO: Implement
    return false;
}

} // namespace android::uirenderer
