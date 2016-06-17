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
#include "AnimationContext.h"

#include "Animator.h"
#include "RenderNode.h"
#include "renderthread/TimeLord.h"

namespace android {
namespace uirenderer {

AnimationContext::AnimationContext(renderthread::TimeLord& clock)
        : mClock(clock)
        , mCurrentFrameAnimations(*this)
        , mNextFrameAnimations(*this)
        , mFrameTimeMs(0) {
}

AnimationContext::~AnimationContext() {
}

void AnimationContext::destroy() {
    startFrame(TreeInfo::MODE_RT_ONLY);
    while (mCurrentFrameAnimations.mNextHandle) {
        AnimationHandle* current = mCurrentFrameAnimations.mNextHandle;
        AnimatorManager& animators = current->mRenderNode->animators();
        animators.endAllActiveAnimators();
        LOG_ALWAYS_FATAL_IF(mCurrentFrameAnimations.mNextHandle == current,
                "endAllAnimators failed to remove from current frame list!");
    }
}

void AnimationContext::addAnimatingRenderNode(RenderNode& node) {
    if (!node.animators().hasAnimationHandle()) {
        AnimationHandle* handle = new AnimationHandle(node, *this);
        addAnimationHandle(handle);
    }
}

void AnimationContext::addAnimationHandle(AnimationHandle* handle) {
    handle->insertAfter(&mNextFrameAnimations);
}

void AnimationContext::startFrame(TreeInfo::TraversalMode mode) {
    LOG_ALWAYS_FATAL_IF(mCurrentFrameAnimations.mNextHandle,
            "Missed running animations last frame!");
    AnimationHandle* head = mNextFrameAnimations.mNextHandle;
    if (head) {
        mNextFrameAnimations.mNextHandle = nullptr;
        mCurrentFrameAnimations.mNextHandle = head;
        head->mPreviousHandle = &mCurrentFrameAnimations;
    }
    mFrameTimeMs = ns2ms(mClock.latestVsync());
}

void AnimationContext::runRemainingAnimations(TreeInfo& info) {
    while (mCurrentFrameAnimations.mNextHandle) {
        AnimationHandle* current = mCurrentFrameAnimations.mNextHandle;
        AnimatorManager& animators = current->mRenderNode->animators();
        animators.pushStaging();
        animators.animateNoDamage(info);
        LOG_ALWAYS_FATAL_IF(mCurrentFrameAnimations.mNextHandle == current,
                "Animate failed to remove from current frame list!");
    }
}

void AnimationContext::callOnFinished(BaseRenderNodeAnimator* animator,
        AnimationListener* listener) {
    listener->onAnimationFinished(animator);
}

AnimationHandle::AnimationHandle(AnimationContext& context)
        : mContext(context)
        , mPreviousHandle(nullptr)
        , mNextHandle(nullptr) {
}

AnimationHandle::AnimationHandle(RenderNode& animatingNode, AnimationContext& context)
        : mRenderNode(&animatingNode)
        , mContext(context)
        , mPreviousHandle(nullptr)
        , mNextHandle(nullptr) {
    mRenderNode->animators().setAnimationHandle(this);
}

AnimationHandle::~AnimationHandle() {
    LOG_ALWAYS_FATAL_IF(mPreviousHandle || mNextHandle,
            "AnimationHandle destroyed while still animating!");
}

void AnimationHandle::notifyAnimationsRan() {
    removeFromList();
    if (mRenderNode->animators().hasAnimators()) {
        mContext.addAnimationHandle(this);
    } else {
        release();
    }
}

void AnimationHandle::release() {
    LOG_ALWAYS_FATAL_IF(mRenderNode->animators().hasAnimators(),
            "Releasing the handle for an RenderNode with outstanding animators!");
    removeFromList();
    mRenderNode->animators().setAnimationHandle(nullptr);
    delete this;
}

void AnimationHandle::insertAfter(AnimationHandle* prev) {
    removeFromList();
    mNextHandle = prev->mNextHandle;
    if (mNextHandle) {
        mNextHandle->mPreviousHandle = this;
    }
    prev->mNextHandle = this;
    mPreviousHandle = prev;
}

void AnimationHandle::removeFromList() {
    if (mPreviousHandle) {
        mPreviousHandle->mNextHandle = mNextHandle;
    }
    if (mNextHandle) {
        mNextHandle->mPreviousHandle = mPreviousHandle;
    }
    mPreviousHandle = nullptr;
    mNextHandle = nullptr;
}

} /* namespace uirenderer */
} /* namespace android */
