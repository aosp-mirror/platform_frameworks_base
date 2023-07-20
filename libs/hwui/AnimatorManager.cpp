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
#include "AnimatorManager.h"

#include <algorithm>

#include "AnimationContext.h"
#include "Animator.h"
#include "DamageAccumulator.h"
#include "RenderNode.h"

namespace android {
namespace uirenderer {

using namespace std;

static void detach(sp<BaseRenderNodeAnimator>& animator) {
    animator->detach();
}

AnimatorManager::AnimatorManager(RenderNode& parent)
        : mParent(parent), mAnimationHandle(nullptr), mCancelAllAnimators(false) {}

AnimatorManager::~AnimatorManager() {
    for_each(mNewAnimators.begin(), mNewAnimators.end(), detach);
    for_each(mAnimators.begin(), mAnimators.end(), detach);
}

void AnimatorManager::addAnimator(const sp<BaseRenderNodeAnimator>& animator) {
    RenderNode* stagingTarget = animator->stagingTarget();
    if (stagingTarget == &mParent) {
        return;
    }
    mNewAnimators.emplace_back(animator.get());
    // If the animator is already attached to other RenderNode, remove it from that RenderNode's
    // new animator list. This ensures one animator only ends up in one newAnimatorList during one
    // frame, even when it's added multiple times to multiple targets.
    if (stagingTarget) {
        stagingTarget->removeAnimator(animator);
    }
    animator->attach(&mParent);
}

void AnimatorManager::removeAnimator(const sp<BaseRenderNodeAnimator>& animator) {
    mNewAnimators.erase(std::remove(mNewAnimators.begin(), mNewAnimators.end(), animator),
                        mNewAnimators.end());
}

void AnimatorManager::setAnimationHandle(AnimationHandle* handle) {
    LOG_ALWAYS_FATAL_IF(mAnimationHandle && handle, "Already have an AnimationHandle!");
    mAnimationHandle = handle;
    LOG_ALWAYS_FATAL_IF(!mAnimationHandle && mAnimators.size(),
                        "Lost animation handle on %p (%s) with outstanding animators!", &mParent,
                        mParent.getName());
}

void AnimatorManager::pushStaging() {
    if (mNewAnimators.size()) {
        if (CC_UNLIKELY(!mAnimationHandle)) {
            ALOGW("Trying to start new animators on %p (%s) without an animation handle!", &mParent,
                  mParent.getName());
            return;
        }

        // Only add new animators that are not already in the mAnimators list
        for (auto& anim : mNewAnimators) {
            if (anim && anim->target() != &mParent) {
                mAnimators.push_back(std::move(anim));
            }
        }
        mNewAnimators.clear();
    }

    if (mCancelAllAnimators) {
        for (auto& animator : mAnimators) {
            if (animator)
                animator->forceEndNow(mAnimationHandle->context());
        }
        mCancelAllAnimators = false;
    } else {
        for (auto& animator : mAnimators) {
            if (animator)
                animator->pushStaging(mAnimationHandle->context());
        }
    }
}

void AnimatorManager::onAnimatorTargetChanged(BaseRenderNodeAnimator* animator) {
    LOG_ALWAYS_FATAL_IF(animator->target() == &mParent, "Target has not been changed");
    mAnimators.erase(std::remove(mAnimators.begin(), mAnimators.end(), animator), mAnimators.end());
}

class AnimateFunctor {
public:
    AnimateFunctor(TreeInfo& info, AnimationContext& context, uint32_t* outDirtyMask)
            : mInfo(info), mContext(context), mDirtyMask(outDirtyMask) {}

    bool operator()(sp<BaseRenderNodeAnimator>& animator) {
        *mDirtyMask |= animator->dirtyMask();
        bool remove = animator->animate(mContext);
        if (remove) {
            if (animator) {
                animator->detach();
            }
        } else {
            if (animator->isRunning()) {
                mInfo.out.hasAnimations = true;
            }
            if (CC_UNLIKELY(!animator->mayRunAsync())) {
                mInfo.out.requiresUiRedraw = true;
            }
        }
        return remove;
    }

private:
    TreeInfo& mInfo;
    AnimationContext& mContext;
    uint32_t* mDirtyMask;
};

uint32_t AnimatorManager::animate(TreeInfo& info) {
    if (!mAnimators.size()) return 0;

    // TODO: Can we target this better? For now treat it like any other staging
    // property push and just damage self before and after animators are run

    mParent.damageSelf(info);
    info.damageAccumulator->popTransform();

    uint32_t dirty = animateCommon(info);

    info.damageAccumulator->pushTransform(&mParent);
    mParent.damageSelf(info);

    return dirty;
}

void AnimatorManager::animateNoDamage(TreeInfo& info) {
    animateCommon(info);
}

uint32_t AnimatorManager::animateCommon(TreeInfo& info) {
    uint32_t dirtyMask = 0;
    AnimateFunctor functor(info, mAnimationHandle->context(), &dirtyMask);
    auto newEnd = std::remove_if(mAnimators.begin(), mAnimators.end(), functor);
    mAnimators.erase(newEnd, mAnimators.end());
    mAnimationHandle->notifyAnimationsRan();
    mParent.mProperties.updateMatrix();
    return dirtyMask;
}

static void endStagingAnimator(sp<BaseRenderNodeAnimator>& animator) {
    animator->cancel();
    if (animator->listener()) {
        animator->listener()->onAnimationFinished(animator.get());
    }
}

void AnimatorManager::endAllStagingAnimators() {
    ALOGD("endAllStagingAnimators on %p (%s)", &mParent, mParent.getName());
    // This works because this state can only happen on the UI thread,
    // which means we're already on the right thread to invoke listeners
    for_each(mNewAnimators.begin(), mNewAnimators.end(), endStagingAnimator);
    mNewAnimators.clear();
}

class EndActiveAnimatorsFunctor {
public:
    explicit EndActiveAnimatorsFunctor(AnimationContext& context) : mContext(context) {}

    void operator()(sp<BaseRenderNodeAnimator>& animator) { animator->forceEndNow(mContext); }

private:
    AnimationContext& mContext;
};

void AnimatorManager::endAllActiveAnimators() {
    ALOGD("endAllActiveAnimators on %p (%s) with handle %p", &mParent, mParent.getName(),
          mAnimationHandle);
    EndActiveAnimatorsFunctor functor(mAnimationHandle->context());
    for_each(mAnimators.begin(), mAnimators.end(), functor);
    mAnimators.clear();
    mAnimationHandle->release();
}

void AnimatorManager::forceEndAnimators() {
    mCancelAllAnimators = true;
}

} /* namespace uirenderer */
} /* namespace android */
