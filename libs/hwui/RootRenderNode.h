/*
 * Copyright (C) 2019 The Android Open Source Project
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

#pragma once

#include <set>
#include <vector>

#include "AnimationContext.h"
#include "Animator.h"
#include <IContextFactory.h>
#include "PropertyValuesAnimatorSet.h"
#include "RenderNode.h"

namespace android::uirenderer {

class RootRenderNode : public RenderNode {
public:
    explicit RootRenderNode(std::unique_ptr<ErrorHandler> errorHandler)
            : RenderNode(), mErrorHandler(std::move(errorHandler)) {}

    virtual ~RootRenderNode() {}

    virtual void prepareTree(TreeInfo& info) override;

    void attachAnimatingNode(RenderNode* animatingNode);

    void attachPendingVectorDrawableAnimators();

    void detachAnimators();

    void pauseAnimators();

    void doAttachAnimatingNodes(AnimationContext* context);

    // Run VectorDrawable animators after prepareTree.
    void runVectorDrawableAnimators(AnimationContext* context, TreeInfo& info);

    void trimPausedVDAnimators(AnimationContext* context);

    void pushStagingVectorDrawableAnimators(AnimationContext* context);

    void destroy();

    void addVectorDrawableAnimator(PropertyValuesAnimatorSet* anim);

private:
    const std::unique_ptr<ErrorHandler> mErrorHandler;
    std::vector<sp<RenderNode> > mPendingAnimatingRenderNodes;
    std::set<sp<PropertyValuesAnimatorSet> > mPendingVectorDrawableAnimators;
    std::set<sp<PropertyValuesAnimatorSet> > mRunningVDAnimators;
    // mPausedVDAnimators stores a list of animators that have not yet passed the finish time, but
    // their VectorDrawable targets are no longer in the DisplayList. We skip these animators when
    // render thread runs animators independent of UI thread (i.e. RT_ONLY mode). These animators
    // need to be re-activated once their VD target is added back into DisplayList. Since that could
    // only happen when we do a full sync, we need to make sure to pulse these paused animators at
    // full sync. If any animator's VD target is found in DisplayList during a full sync, we move
    // the animator back to the running list.
    std::set<sp<PropertyValuesAnimatorSet> > mPausedVDAnimators;

    void detachVectorDrawableAnimator(PropertyValuesAnimatorSet* anim);
};

class ContextFactoryImpl : public IContextFactory {
public:
    explicit ContextFactoryImpl(RootRenderNode* rootNode) : mRootNode(rootNode) {}

    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) override;

private:
    RootRenderNode* mRootNode;
};

}  // namespace android::uirenderer
