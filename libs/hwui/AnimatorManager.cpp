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
#include "RenderNode.h"

namespace android {
namespace uirenderer {

using namespace std;

static void unref(BaseRenderNodeAnimator* animator) {
    animator->detach();
    animator->decStrong(0);
}

AnimatorManager::AnimatorManager(RenderNode& parent)
        : mParent(parent)
        , mAnimationHandle(NULL) {
}

AnimatorManager::~AnimatorManager() {
    for_each(mNewAnimators.begin(), mNewAnimators.end(), unref);
    for_each(mAnimators.begin(), mAnimators.end(), unref);
}

void AnimatorManager::addAnimator(const sp<BaseRenderNodeAnimator>& animator) {
    animator->incStrong(0);
    animator->attach(&mParent);
    mNewAnimators.push_back(animator.get());
}

void AnimatorManager::setAnimationHandle(AnimationHandle* handle) {
    LOG_ALWAYS_FATAL_IF(mAnimationHandle && handle, "Already have an AnimationHandle!");
    mAnimationHandle = handle;
}

template<typename T>
static void move_all(T& source, T& dest) {
    dest.reserve(source.size() + dest.size());
    for (typename T::iterator it = source.begin(); it != source.end(); it++) {
        dest.push_back(*it);
    }
    source.clear();
}

void AnimatorManager::pushStaging() {
    if (mNewAnimators.size()) {
        // Since this is a straight move, we don't need to inc/dec the ref count
        move_all(mNewAnimators, mAnimators);
    }
    for (vector<BaseRenderNodeAnimator*>::iterator it = mAnimators.begin(); it != mAnimators.end(); it++) {
        (*it)->pushStaging(mAnimationHandle->context());
    }
}

class AnimateFunctor {
public:
    AnimateFunctor(TreeInfo& info, AnimationContext& context)
            : dirtyMask(0), mInfo(info), mContext(context) {}

    bool operator() (BaseRenderNodeAnimator* animator) {
        dirtyMask |= animator->dirtyMask();
        bool remove = animator->animate(mContext);
        if (remove) {
            animator->decStrong(0);
        } else {
            if (animator->isRunning()) {
                mInfo.out.hasAnimations = true;
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

    mParent.mProperties.updateMatrix();
    info.damageAccumulator->pushTransform(&mParent);
    mParent.damageSelf(info);

    return dirty;
}

void AnimatorManager::animateNoDamage(TreeInfo& info) {
    if (!mAnimators.size()) return;

    animateCommon(info);
}

uint32_t AnimatorManager::animateCommon(TreeInfo& info) {
    AnimateFunctor functor(info, mAnimationHandle->context());
    std::vector< BaseRenderNodeAnimator* >::iterator newEnd;
    newEnd = std::remove_if(mAnimators.begin(), mAnimators.end(), functor);
    mAnimators.erase(newEnd, mAnimators.end());
    mAnimationHandle->notifyAnimationsRan();
    return functor.dirtyMask;
}

class EndAnimatorsFunctor {
public:
    EndAnimatorsFunctor(AnimationContext& context) : mContext(context) {}

    void operator() (BaseRenderNodeAnimator* animator) {
        animator->end();
        animator->pushStaging(mContext);
        animator->animate(mContext);
        animator->decStrong(0);
    }

private:
    AnimationContext& mContext;
};

static void endAnimatorsHard(BaseRenderNodeAnimator* animator) {
    animator->end();
    if (animator->listener()) {
        animator->listener()->onAnimationFinished(animator);
    }
    animator->decStrong(0);
}

void AnimatorManager::endAllAnimators() {
    if (mNewAnimators.size()) {
        // Since this is a straight move, we don't need to inc/dec the ref count
        move_all(mNewAnimators, mAnimators);
    }
    // First try gracefully ending them
    if (mAnimationHandle) {
        EndAnimatorsFunctor functor(mAnimationHandle->context());
        for_each(mAnimators.begin(), mAnimators.end(), functor);
    } else {
        // We have no context, so bust out the sledgehammer
        // This works because this state can only happen on the UI thread,
        // which means we're already on the right thread to invoke listeners
        for_each(mAnimators.begin(), mAnimators.end(), endAnimatorsHard);
    }
    mAnimators.clear();
}

} /* namespace uirenderer */
} /* namespace android */
