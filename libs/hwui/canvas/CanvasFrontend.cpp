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
    mClipStack.emplace_back();
    mTransformStack.emplace_back();

    clip().bounds = mInitialBounds;
}

bool CanvasStateHelper::internalSave(SaveEntry saveEntry) {
    mSaveStack.push_back(saveEntry);
    if (saveEntry.matrix) {
        pushEntry(&mTransformStack);
    }
    if (saveEntry.clip) {
        pushEntry(&mClipStack);
        return true;
    }
    return false;
}

void CanvasStateHelper::ConservativeClip::apply(SkClipOp op, const SkMatrix& matrix,
                                                const SkRect& bounds, bool aa, bool fillsBounds) {
    this->aa |= aa;

    if (op == SkClipOp::kIntersect) {
        SkRect devBounds;
        bool rect = matrix.mapRect(&devBounds, bounds) && fillsBounds;
        if (!this->bounds.intersect(aa ? devBounds.roundOut() : devBounds.round())) {
            this->bounds.setEmpty();
        }
        this->rect &= rect;
    } else {
        // Difference operations subtracts a region from the clip, so conservatively
        // the bounds remain unchanged and the shape is unlikely to remain a rect.
        this->rect = false;
    }
}

void CanvasStateHelper::internalClipRect(const SkRect& rect, SkClipOp op) {
    clip().apply(op, transform(), rect, /*aa=*/false, /*fillsBounds=*/true);
}

void CanvasStateHelper::internalClipPath(const SkPath& path, SkClipOp op) {
    SkRect bounds = path.getBounds();
    if (path.isInverseFillType()) {
        // Toggle op type if the path is inverse filled
        op = (op == SkClipOp::kIntersect ? SkClipOp::kDifference : SkClipOp::kIntersect);
    }
    clip().apply(op, transform(), bounds, /*aa=*/true, /*fillsBounds=*/false);
}

CanvasStateHelper::ConservativeClip& CanvasStateHelper::clip() {
    return writableEntry(&mClipStack);
}

SkMatrix& CanvasStateHelper::transform() {
    return writableEntry(&mTransformStack);
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
        popEntry(&mTransformStack);
    }
    if (entry.clip) {
        popEntry(&mClipStack);
        needsRestorePropagation = true;
    }
    return needsRestorePropagation;
}

SkRect CanvasStateHelper::getClipBounds() const {
    SkIRect bounds = clip().bounds;

    SkMatrix inverse;
    // if we can't invert the CTM, we can't return local clip bounds
    if (bounds.isEmpty() || !transform().invert(&inverse)) {
        return SkRect::MakeEmpty();
    }

    return inverse.mapRect(SkRect::Make(bounds));
}

bool CanvasStateHelper::ConservativeClip::quickReject(const SkMatrix& matrix,
                                                      const SkRect& bounds) const {
    SkRect devRect = matrix.mapRect(bounds);
    return devRect.isFinite() &&
           SkIRect::Intersects(this->bounds, aa ? devRect.roundOut() : devRect.round());
}

bool CanvasStateHelper::quickRejectRect(float left, float top, float right, float bottom) const {
    return clip().quickReject(transform(), SkRect::MakeLTRB(left, top, right, bottom));
}

bool CanvasStateHelper::quickRejectPath(const SkPath& path) const {
    if (this->isClipEmpty()) {
        // reject everything (prioritized above path inverse fill type).
        return true;
    } else {
        // Don't reject inverse-filled paths, since even if they are "empty" of points/verbs,
        // they fill out the entire clip.
        return !path.isInverseFillType() && clip().quickReject(transform(), path.getBounds());
    }
}

} // namespace android::uirenderer
