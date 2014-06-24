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

#include "RenderNode.h"

namespace android {
namespace uirenderer {

using namespace std;

static void unref(BaseRenderNodeAnimator* animator) {
    animator->decStrong(0);
}

AnimatorManager::AnimatorManager(RenderNode& parent)
        : mParent(parent) {
}

AnimatorManager::~AnimatorManager() {
    for_each(mNewAnimators.begin(), mNewAnimators.end(), unref);
    for_each(mAnimators.begin(), mAnimators.end(), unref);
}

void AnimatorManager::addAnimator(const sp<BaseRenderNodeAnimator>& animator) {
    animator->incStrong(0);
    animator->onAttached(&mParent);
    mNewAnimators.push_back(animator.get());
}

template<typename T>
static void move_all(T& source, T& dest) {
    dest.reserve(source.size() + dest.size());
    for (typename T::iterator it = source.begin(); it != source.end(); it++) {
        dest.push_back(*it);
    }
    source.clear();
}

void AnimatorManager::pushStaging(TreeInfo& info) {
    if (mNewAnimators.size()) {
        // Since this is a straight move, we don't need to inc/dec the ref count
        move_all(mNewAnimators, mAnimators);
    }
    for (vector<BaseRenderNodeAnimator*>::iterator it = mAnimators.begin(); it != mAnimators.end(); it++) {
        (*it)->pushStaging(&mParent, info);
    }
}

class AnimateFunctor {
public:
    AnimateFunctor(RenderNode& target, TreeInfo& info)
            : mTarget(target), mInfo(info) {}

    bool operator() (BaseRenderNodeAnimator* animator) {
        bool remove = animator->animate(&mTarget, mInfo);
        if (remove) {
            animator->decStrong(0);
        }
        return remove;
    }
private:
    RenderNode& mTarget;
    TreeInfo& mInfo;
};

void AnimatorManager::animate(TreeInfo& info) {
    if (!mAnimators.size()) return;

    // TODO: Can we target this better? For now treat it like any other staging
    // property push and just damage self before and after animators are run

    mParent.damageSelf(info);
    info.damageAccumulator->popTransform();

    AnimateFunctor functor(mParent, info);
    std::vector< BaseRenderNodeAnimator* >::iterator newEnd;
    newEnd = std::remove_if(mAnimators.begin(), mAnimators.end(), functor);
    mAnimators.erase(newEnd, mAnimators.end());

    mParent.mProperties.updateMatrix();
    info.damageAccumulator->pushTransform(&mParent);
    mParent.damageSelf(info);
}

} /* namespace uirenderer */
} /* namespace android */
