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
#ifndef ANIMATORMANAGER_H
#define ANIMATORMANAGER_H

#include <cutils/compiler.h>
#include <utils/StrongPointer.h>

#include <vector>

#include "utils/Macros.h"

namespace android {
namespace uirenderer {

class AnimationHandle;
class BaseRenderNodeAnimator;
class RenderNode;
class TreeInfo;

// Responsible for managing the animators for a single RenderNode
class AnimatorManager {
    PREVENT_COPY_AND_ASSIGN(AnimatorManager);

public:
    explicit AnimatorManager(RenderNode& parent);
    ~AnimatorManager();

    void addAnimator(const sp<BaseRenderNodeAnimator>& animator);
    void removeAnimator(const sp<BaseRenderNodeAnimator>& animator);

    void setAnimationHandle(AnimationHandle* handle);
    bool hasAnimationHandle() { return mAnimationHandle; }

    void pushStaging();
    void onAnimatorTargetChanged(BaseRenderNodeAnimator* animator);

    // Returns the combined dirty mask of all animators run
    uint32_t animate(TreeInfo& info);

    void animateNoDamage(TreeInfo& info);

    // Hard-ends all animators. May only be called on the UI thread.
    void endAllStagingAnimators();

    void forceEndAnimators();

    // Hard-ends all animators that have been pushed. Used for cleanup if
    // the ActivityContext is being destroyed
    void endAllActiveAnimators();

    bool hasAnimators() { return mAnimators.size(); }

private:
    uint32_t animateCommon(TreeInfo& info);

    RenderNode& mParent;
    AnimationHandle* mAnimationHandle;

    // To improve the efficiency of resizing & removing from the vector
    std::vector<sp<BaseRenderNodeAnimator> > mNewAnimators;
    std::vector<sp<BaseRenderNodeAnimator> > mAnimators;

    bool mCancelAllAnimators;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* ANIMATORMANAGER_H */
