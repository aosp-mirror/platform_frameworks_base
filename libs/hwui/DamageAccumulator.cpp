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

#define LOG_TAG "DamageAccumulator"

#include "DamageAccumulator.h"

#include <cutils/log.h>

#include "RenderNode.h"
#include "utils/MathUtils.h"

namespace android {
namespace uirenderer {

struct DirtyStack {
    const RenderNode* node;
    // When this frame is pop'd, this rect is mapped through the above transform
    // and applied to the previous (aka parent) frame
    SkRect pendingDirty;
    DirtyStack* prev;
    DirtyStack* next;
};

DamageAccumulator::DamageAccumulator() {
    mHead = (DirtyStack*) mAllocator.alloc(sizeof(DirtyStack));
    memset(mHead, 0, sizeof(DirtyStack));
    // Create a root that we will not pop off
    mHead->prev = mHead;
}

void DamageAccumulator::pushNode(const RenderNode* node) {
    if (!mHead->next) {
        DirtyStack* nextFrame = (DirtyStack*) mAllocator.alloc(sizeof(DirtyStack));
        nextFrame->next = 0;
        nextFrame->prev = mHead;
        mHead->next = nextFrame;
    }
    mHead = mHead->next;
    mHead->node = node;
    mHead->pendingDirty.setEmpty();
}

void DamageAccumulator::popNode() {
    LOG_ALWAYS_FATAL_IF(mHead->prev == mHead, "Cannot pop the root frame!");
    DirtyStack* dirtyFrame = mHead;
    mHead = mHead->prev;
    if (!dirtyFrame->pendingDirty.isEmpty()) {
        SkRect mappedDirty;
        const RenderProperties& props = dirtyFrame->node->properties();
        const SkMatrix* transform = props.getTransformMatrix();
        if (transform && !transform->isIdentity()) {
            transform->mapRect(&mappedDirty, dirtyFrame->pendingDirty);
        } else {
            mappedDirty = dirtyFrame->pendingDirty;
        }
        if (CC_LIKELY(mHead->node)) {
            const RenderProperties& parentProps = mHead->node->properties();
            mappedDirty.offset(props.getLeft() - parentProps.getScrollX(),
                    props.getTop() - parentProps.getScrollY());
            if (props.getClipToBounds()) {
                if (!mappedDirty.intersect(0, 0, parentProps.getWidth(), parentProps.getHeight())) {
                    mappedDirty.setEmpty();
                }
            }
            if (CC_UNLIKELY(!MathUtils::isZero(props.getTranslationZ()))) {
                // TODO: Can we better bound the shadow damage area? For now
                // match the old damageShadowReceiver() path and just dirty
                // the entire parent bounds
                mappedDirty.join(0, 0, parentProps.getWidth(), parentProps.getHeight());
            }
        } else {
            mappedDirty.offset(props.getLeft(), props.getTop());
        }
        dirty(mappedDirty.fLeft, mappedDirty.fTop, mappedDirty.fRight, mappedDirty.fBottom);
    }
}

void DamageAccumulator::dirty(float left, float top, float right, float bottom) {
    mHead->pendingDirty.join(left, top, right, bottom);
}

void DamageAccumulator::finish(SkRect* totalDirty) {
    LOG_ALWAYS_FATAL_IF(mHead->prev != mHead, "Cannot finish, mismatched push/pop calls! %p vs. %p", mHead->prev, mHead);
    // Root node never has a transform, so this is the fully mapped dirty rect
    *totalDirty = mHead->pendingDirty;
    totalDirty->roundOut();
    mHead->pendingDirty.setEmpty();
}

} /* namespace uirenderer */
} /* namespace android */
