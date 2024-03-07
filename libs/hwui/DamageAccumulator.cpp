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

#include "DamageAccumulator.h"

#include <log/log.h>

#include "RenderNode.h"
#include "utils/MathUtils.h"

namespace android {
namespace uirenderer {

enum TransformType {
    TransformInvalid = 0,
    TransformRenderNode,
    TransformMatrix4,
    TransformNone,
};

struct DirtyStack {
    TransformType type;
    union {
        const RenderNode* renderNode;
        const Matrix4* matrix4;
    };
    // When this frame is pop'd, this rect is mapped through the above transform
    // and applied to the previous (aka parent) frame
    SkRect pendingDirty;
    DirtyStack* prev;
    DirtyStack* next;
};

DamageAccumulator::DamageAccumulator() {
    mHead = mAllocator.create_trivial<DirtyStack>();
    memset(mHead, 0, sizeof(DirtyStack));
    // Create a root that we will not pop off
    mHead->prev = mHead;
    mHead->type = TransformNone;
}

static void computeTransformImpl(const DirtyStack* currentFrame, Matrix4* outMatrix) {
    if (currentFrame->prev != currentFrame) {
        computeTransformImpl(currentFrame->prev, outMatrix);
    }
    switch (currentFrame->type) {
        case TransformRenderNode:
            currentFrame->renderNode->applyViewPropertyTransforms(*outMatrix);
            break;
        case TransformMatrix4:
            outMatrix->multiply(*currentFrame->matrix4);
            break;
        case TransformNone:
            // nothing to be done
            break;
        default:
            LOG_ALWAYS_FATAL("Tried to compute transform with an invalid type: %d",
                             currentFrame->type);
    }
}

void DamageAccumulator::computeCurrentTransform(Matrix4* outMatrix) const {
    outMatrix->loadIdentity();
    computeTransformImpl(mHead, outMatrix);
}

void DamageAccumulator::pushCommon() {
    if (!mHead->next) {
        DirtyStack* nextFrame = mAllocator.create_trivial<DirtyStack>();
        nextFrame->next = nullptr;
        nextFrame->prev = mHead;
        mHead->next = nextFrame;
    }
    mHead = mHead->next;
    mHead->pendingDirty.setEmpty();
}

void DamageAccumulator::pushTransform(const RenderNode* transform) {
    pushCommon();
    mHead->type = TransformRenderNode;
    mHead->renderNode = transform;
}

void DamageAccumulator::pushTransform(const Matrix4* transform) {
    pushCommon();
    mHead->type = TransformMatrix4;
    mHead->matrix4 = transform;
}

void DamageAccumulator::popTransform() {
    LOG_ALWAYS_FATAL_IF(mHead->prev == mHead, "Cannot pop the root frame!");
    DirtyStack* dirtyFrame = mHead;
    mHead = mHead->prev;
    switch (dirtyFrame->type) {
        case TransformRenderNode:
            applyRenderNodeTransform(dirtyFrame);
            break;
        case TransformMatrix4:
            applyMatrix4Transform(dirtyFrame);
            break;
        case TransformNone:
            mHead->pendingDirty.join(dirtyFrame->pendingDirty);
            break;
        default:
            LOG_ALWAYS_FATAL("Tried to pop an invalid type: %d", dirtyFrame->type);
    }
}

static inline void mapRect(const Matrix4* matrix, const SkRect& in, SkRect* out) {
    if (in.isEmpty()) return;
    Rect temp(in);
    if (CC_LIKELY(!matrix->isPerspective())) {
        matrix->mapRect(temp);
    } else {
        // Don't attempt to calculate damage for a perspective transform
        // as the numbers this works with can break the perspective
        // calculations. Just give up and expand to DIRTY_MIN/DIRTY_MAX
        temp.set(DIRTY_MIN, DIRTY_MIN, DIRTY_MAX, DIRTY_MAX);
    }
    out->join({RECT_ARGS(temp)});
}

void DamageAccumulator::applyMatrix4Transform(DirtyStack* frame) {
    mapRect(frame->matrix4, frame->pendingDirty, &mHead->pendingDirty);
}

static inline void applyMatrix(const SkMatrix* transform, SkRect* rect) {
    if (transform && !transform->isIdentity()) {
        if (CC_LIKELY(!transform->hasPerspective())) {
            transform->mapRect(rect);
        } else {
            // Don't attempt to calculate damage for a perspective transform
            // as the numbers this works with can break the perspective
            // calculations. Just give up and expand to DIRTY_MIN/DIRTY_MAX
            rect->setLTRB(DIRTY_MIN, DIRTY_MIN, DIRTY_MAX, DIRTY_MAX);
        }
    }
}

static inline void applyMatrix(const SkMatrix& transform, SkRect* rect) {
    return applyMatrix(&transform, rect);
}

static inline void mapRect(const RenderProperties& props, const SkRect& in, SkRect* out) {
    if (in.isEmpty()) return;
    SkRect temp(in);
    if (Properties::getStretchEffectBehavior() == StretchEffectBehavior::UniformScale) {
        const StretchEffect& stretch = props.layerProperties().getStretchEffect();
        if (!stretch.isEmpty()) {
            applyMatrix(stretch.makeLinearStretch(props.getWidth(), props.getHeight()), &temp);
        }
    }
    applyMatrix(props.getTransformMatrix(), &temp);
    if (props.getStaticMatrix()) {
        applyMatrix(props.getStaticMatrix(), &temp);
    } else if (props.getAnimationMatrix()) {
        applyMatrix(props.getAnimationMatrix(), &temp);
    }
    temp.offset(props.getLeft(), props.getTop());
    out->join(temp);
}

static DirtyStack* findParentRenderNode(DirtyStack* frame) {
    while (frame->prev != frame) {
        frame = frame->prev;
        if (frame->type == TransformRenderNode) {
            return frame;
        }
    }
    return nullptr;
}

static DirtyStack* findProjectionReceiver(DirtyStack* frame) {
    if (frame) {
        while (frame->prev != frame) {
            frame = frame->prev;
            if (frame->type == TransformRenderNode && frame->renderNode->hasProjectionReceiver()) {
                return frame;
            }
        }
    }
    return nullptr;
}

static void applyTransforms(DirtyStack* frame, DirtyStack* end) {
    SkRect* rect = &frame->pendingDirty;
    while (frame != end) {
        if (frame->type == TransformRenderNode) {
            mapRect(frame->renderNode->properties(), *rect, rect);
        } else {
            mapRect(frame->matrix4, *rect, rect);
        }
        frame = frame->prev;
    }
}

void DamageAccumulator::applyRenderNodeTransform(DirtyStack* frame) {
    if (frame->pendingDirty.isEmpty()) {
        return;
    }

    const RenderProperties& props = frame->renderNode->properties();
    if (props.getAlpha() <= 0) {
        return;
    }

    // Perform clipping
    if (props.getClipDamageToBounds()) {
        if (!frame->pendingDirty.intersect(SkRect::MakeIWH(props.getWidth(), props.getHeight()))) {
            frame->pendingDirty.setEmpty();
        }
    }

    // apply all transforms
    mapRect(props, frame->pendingDirty, &mHead->pendingDirty);

    // project backwards if necessary
    if (props.getProjectBackwards() && !frame->pendingDirty.isEmpty()) {
        // First, find our parent RenderNode:
        DirtyStack* parentNode = findParentRenderNode(frame);
        // Find our parent's projection receiver, which is what we project onto
        DirtyStack* projectionReceiver = findProjectionReceiver(parentNode);
        if (projectionReceiver) {
            applyTransforms(frame, projectionReceiver);
            projectionReceiver->pendingDirty.join(frame->pendingDirty);
        }

        frame->pendingDirty.setEmpty();
    }
}

SkRect DamageAccumulator::computeClipAndTransform(const SkRect& bounds, Matrix4* outMatrix) const {
    const DirtyStack* frame = mHead;
    Matrix4 transform;
    SkRect pretransformResult = bounds;
    while (true) {
        SkRect currentBounds = pretransformResult;
        pretransformResult.setEmpty();
        switch (frame->type) {
            case TransformRenderNode: {
                const RenderProperties& props = frame->renderNode->properties();
                // Perform clipping
                if (props.getClipDamageToBounds() && !currentBounds.isEmpty()) {
                    if (!currentBounds.intersect(
                                SkRect::MakeIWH(props.getWidth(), props.getHeight()))) {
                        currentBounds.setEmpty();
                    }
                }

                // apply all transforms
                mapRect(props, currentBounds, &pretransformResult);
                frame->renderNode->applyViewPropertyTransforms(transform);
            } break;
            case TransformMatrix4:
                mapRect(frame->matrix4, currentBounds, &pretransformResult);
                transform.multiply(*frame->matrix4);
                break;
            default:
                pretransformResult = currentBounds;
                break;
        }
        if (frame->prev == frame) break;
        frame = frame->prev;
    }
    SkRect result;
    Matrix4 globalToLocal;
    globalToLocal.loadInverse(transform);
    mapRect(&globalToLocal, pretransformResult, &result);
    *outMatrix = transform;
    return result;
}

void DamageAccumulator::dirty(float left, float top, float right, float bottom) {
    mHead->pendingDirty.join({left, top, right, bottom});
}

void DamageAccumulator::peekAtDirty(SkRect* dest) const {
    *dest = mHead->pendingDirty;
}

void DamageAccumulator::finish(SkRect* totalDirty) {
    LOG_ALWAYS_FATAL_IF(mHead->prev != mHead, "Cannot finish, mismatched push/pop calls! %p vs. %p",
                        mHead->prev, mHead);
    // Root node never has a transform, so this is the fully mapped dirty rect
    *totalDirty = mHead->pendingDirty;
    totalDirty->roundOut(totalDirty);
    mHead->pendingDirty.setEmpty();
}

DamageAccumulator::StretchResult DamageAccumulator::findNearestStretchEffect() const {
    DirtyStack* frame = mHead;
    while (frame->prev != frame) {
        if (frame->type == TransformRenderNode) {
            const auto& renderNode = frame->renderNode;
            const auto& frameRenderNodeProperties = renderNode->properties();
            const auto& effect =
                    frameRenderNodeProperties.layerProperties().getStretchEffect();
            const float width = (float) frameRenderNodeProperties.getWidth();
            const float height = (float) frameRenderNodeProperties.getHeight();
            if (!effect.isEmpty()) {
                Matrix4 stretchMatrix;
                computeTransformImpl(frame, &stretchMatrix);
                Rect stretchRect = Rect(0.f, 0.f, width, height);
                stretchMatrix.mapRect(stretchRect);

                return StretchResult{
                        .stretchEffect = &effect,
                        .parentBounds = SkRect::MakeLTRB(stretchRect.left, stretchRect.top,
                                                         stretchRect.right, stretchRect.bottom),
                        .width = width,
                        .height = height};
            }
        }
        frame = frame->prev;
    }
    return StretchResult{};
}

} /* namespace uirenderer */
} /* namespace android */
