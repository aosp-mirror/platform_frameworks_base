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

#include "Animator.h"
#include "AnimationContext.h"
#include "DamageAccumulator.h"
#include "RenderNode.h"

namespace android {
namespace uirenderer {

using namespace std;

static void detach(sp<BaseRenderNodeAnimator>& animator) {
    animator->detach();
}

AnimatorManager::AnimatorManager(RenderNode& parent)
        : mParent(parent)
        , mAnimationHandle(nullptr) {
}

AnimatorManager::~AnimatorManager() {
    for_each(mNewAnimators.begin(), mNewAnimators.end(), detach);
    for_each(mAnimators.begin(), mAnimators.end(), detach);
}

void AnimatorManager::addAnimator(const sp<BaseRenderNodeAnimator>& animator) {
    mNewAnimators.emplace_back(animator.get());
}

void AnimatorManager::setAnimationHandle(AnimationHandle* handle) {
    LOG_ALWAYS_FATAL_IF(mAnimationHandle && handle, "Already have an AnimationHandle!");
    mAnimationHandle = handle;
    LOG_ALWAYS_FATAL_IF(!mAnimationHandle && mAnimators.size(),
            "Lost animation handle on %p (%s) with outstanding animators!",
            &mParent, mParent.getName());
}

void AnimatorManager::pushStaging() {
    if (mNewAnimators.size()) {
        LOG_ALWAYS_FATAL_IF(!mAnimationHandle,
                "Trying to start new animators on %p (%s) without an animation handle!",
                &mParent, mParent.getName());
        // Only add animators that are not already in the on-going animator list.
        for (auto& animator : mNewAnimators) {
            RenderNode* targetRenderNode = animator->target();
            if (targetRenderNode == &mParent) {
                // Animator already in the animator list: skip adding again
                continue;
            }

            if (targetRenderNode){
                // If the animator is already in another RenderNode's animator list, remove animator from
                // that list and add animator to current RenderNode's list.
                targetRenderNode->animators().removeActiveAnimator(animator);
            }
            animator->attach(&mParent);
            mAnimators.push_back(std::move(animator));
        }
        mNewAnimators.clear();
    }
    for (auto& animator : mAnimators) {
        animator->pushStaging(mAnimationHandle->context());
    }
}

class AnimateFunctor {
public:
    AnimateFunctor(TreeInfo& info, AnimationContext& context)
            : dirtyMask(0), mInfo(info), mContext(context) {}

    bool operator() (sp<BaseRenderNodeAnimator>& animator) {
        dirtyMask |= animator->dirtyMask();
        bool remove = animator->animate(mContext);
        if (remove) {
            animator->detach();
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

    uint32_t dirtyMask;

private:
    TreeInfo& mInfo;
    AnimationContext& mContext;
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
    AnimateFunctor functor(info, mAnimationHandle->context());
    auto newEnd = std::remove_if(mAnimators.begin(), mAnimators.end(), functor);
    mAnimators.erase(newEnd, mAnimators.end());
    mAnimationHandle->notifyAnimationsRan();
    mParent.mProperties.updateMatrix();
    return functor.dirtyMask;
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

void AnimatorManager::removeActiveAnimator(const sp<BaseRenderNodeAnimator>& animator) {
    std::remove(mAnimators.begin(), mAnimators.end(), animator);
}

class EndActiveAnimatorsFunctor {
public:
    EndActiveAnimatorsFunctor(AnimationContext& context) : mContext(context) {}

    void operator() (sp<BaseRenderNodeAnimator>& animator) {
        animator->forceEndNow(mContext);
    }

private:
    AnimationContext& mContext;
};

void AnimatorManager::endAllActiveAnimators() {
    ALOGD("endAllActiveAnimators on %p (%s) with handle %p",
            &mParent, mParent.getName(), mAnimationHandle);
    EndActiveAnimatorsFunctor functor(mAnimationHandle->context());
    for_each(mAnimators.begin(), mAnimators.end(), functor);
    mAnimators.clear();
    mAnimationHandle->release();
}

} /* namespace uirenderer */
} /* namespace android */
