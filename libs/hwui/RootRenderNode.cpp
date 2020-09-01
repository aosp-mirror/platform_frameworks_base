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

#include "RootRenderNode.h"

#ifdef __ANDROID__ // Layoutlib does not support Looper (windows)
#include <utils/Looper.h>
#endif

namespace android::uirenderer {

#ifdef __ANDROID__ // Layoutlib does not support Looper
class FinishAndInvokeListener : public MessageHandler {
public:
    explicit FinishAndInvokeListener(PropertyValuesAnimatorSet* anim) : mAnimator(anim) {
        mListener = anim->getOneShotListener();
        mRequestId = anim->getRequestId();
    }

    virtual void handleMessage(const Message& message) {
        if (mAnimator->getRequestId() == mRequestId) {
            // Request Id has not changed, meaning there's no animation lifecyle change since the
            // message is posted, so go ahead and call finish to make sure the PlayState is properly
            // updated. This is needed because before the next frame comes in from UI thread to
            // trigger an animation update, there could be reverse/cancel etc. So we need to update
            // the playstate in time to ensure all the subsequent events get chained properly.
            mAnimator->end();
        }
        mListener->onAnimationFinished(nullptr);
    }

private:
    sp<PropertyValuesAnimatorSet> mAnimator;
    sp<AnimationListener> mListener;
    uint32_t mRequestId;
};

void RootRenderNode::prepareTree(TreeInfo& info) {
    info.errorHandler = mErrorHandler.get();

    for (auto& anim : mRunningVDAnimators) {
        // Assume that the property change in VD from the animators will not be consumed. Mark
        // otherwise if the VDs are found in the display list tree. For VDs that are not in
        // the display list tree, we stop providing animation pulses by 1) removing them from
        // the animation list, 2) post a delayed message to end them at end time so their
        // listeners can receive the corresponding callbacks.
        anim->getVectorDrawable()->setPropertyChangeWillBeConsumed(false);
        // Mark the VD dirty so it will damage itself during prepareTree.
        anim->getVectorDrawable()->markDirty();
    }
    if (info.mode == TreeInfo::MODE_FULL) {
        for (auto& anim : mPausedVDAnimators) {
            anim->getVectorDrawable()->setPropertyChangeWillBeConsumed(false);
            anim->getVectorDrawable()->markDirty();
        }
    }
    // TODO: This is hacky
    info.updateWindowPositions = true;
    RenderNode::prepareTree(info);
    info.updateWindowPositions = false;
    info.errorHandler = nullptr;
}

void RootRenderNode::attachAnimatingNode(RenderNode* animatingNode) {
    mPendingAnimatingRenderNodes.push_back(animatingNode);
}

void RootRenderNode::attachPendingVectorDrawableAnimators() {
    mRunningVDAnimators.insert(mPendingVectorDrawableAnimators.begin(),
                               mPendingVectorDrawableAnimators.end());
    mPendingVectorDrawableAnimators.clear();
}

void RootRenderNode::detachAnimators() {
    // Remove animators from the list and post a delayed message in future to end the animator
    // For infinite animators, remove the listener so we no longer hold a global ref to the AVD
    // java object, and therefore the AVD objects in both native and Java can be properly
    // released.
    for (auto& anim : mRunningVDAnimators) {
        detachVectorDrawableAnimator(anim.get());
        anim->clearOneShotListener();
    }
    for (auto& anim : mPausedVDAnimators) {
        anim->clearOneShotListener();
    }
    mRunningVDAnimators.clear();
    mPausedVDAnimators.clear();
}

// Move all the animators to the paused list, and send a delayed message to notify the finished
// listener.
void RootRenderNode::pauseAnimators() {
    mPausedVDAnimators.insert(mRunningVDAnimators.begin(), mRunningVDAnimators.end());
    for (auto& anim : mRunningVDAnimators) {
        detachVectorDrawableAnimator(anim.get());
    }
    mRunningVDAnimators.clear();
}

void RootRenderNode::doAttachAnimatingNodes(AnimationContext* context) {
    for (size_t i = 0; i < mPendingAnimatingRenderNodes.size(); i++) {
        RenderNode* node = mPendingAnimatingRenderNodes[i].get();
        context->addAnimatingRenderNode(*node);
    }
    mPendingAnimatingRenderNodes.clear();
}

// Run VectorDrawable animators after prepareTree.
void RootRenderNode::runVectorDrawableAnimators(AnimationContext* context, TreeInfo& info) {
    // Push staging.
    if (info.mode == TreeInfo::MODE_FULL) {
        pushStagingVectorDrawableAnimators(context);
    }

    // Run the animators in the running list.
    for (auto it = mRunningVDAnimators.begin(); it != mRunningVDAnimators.end();) {
        if ((*it)->animate(*context)) {
            it = mRunningVDAnimators.erase(it);
        } else {
            it++;
        }
    }

    // Run the animators in paused list during full sync.
    if (info.mode == TreeInfo::MODE_FULL) {
        // During full sync we also need to pulse paused animators, in case their targets
        // have been added back to the display list. All the animators that passed the
        // scheduled finish time will be removed from the paused list.
        for (auto it = mPausedVDAnimators.begin(); it != mPausedVDAnimators.end();) {
            if ((*it)->animate(*context)) {
                // Animator has finished, remove from the list.
                it = mPausedVDAnimators.erase(it);
            } else {
                it++;
            }
        }
    }

    // Move the animators with a target not in DisplayList to paused list.
    for (auto it = mRunningVDAnimators.begin(); it != mRunningVDAnimators.end();) {
        if (!(*it)->getVectorDrawable()->getPropertyChangeWillBeConsumed()) {
            // Vector Drawable is not in the display list, we should remove this animator from
            // the list, put it in the paused list, and post a delayed message to end the
            // animator.
            detachVectorDrawableAnimator(it->get());
            mPausedVDAnimators.insert(*it);
            it = mRunningVDAnimators.erase(it);
        } else {
            it++;
        }
    }

    // Move the animators with a target in DisplayList from paused list to running list, and
    // trim paused list.
    if (info.mode == TreeInfo::MODE_FULL) {
        // Check whether any paused animator's target is back in Display List. If so, put the
        // animator back in the running list.
        for (auto it = mPausedVDAnimators.begin(); it != mPausedVDAnimators.end();) {
            if ((*it)->getVectorDrawable()->getPropertyChangeWillBeConsumed()) {
                mRunningVDAnimators.insert(*it);
                it = mPausedVDAnimators.erase(it);
            } else {
                it++;
            }
        }
        // Trim paused VD animators at full sync, so that when Java loses reference to an
        // animator, we know we won't be requested to animate it any more, then we remove such
        // animators from the paused list so they can be properly freed. We also remove the
        // animators from paused list when the time elapsed since start has exceeded duration.
        trimPausedVDAnimators(context);
    }

    info.out.hasAnimations |= !mRunningVDAnimators.empty();
}

void RootRenderNode::trimPausedVDAnimators(AnimationContext* context) {
    // Trim paused vector drawable animator list.
    for (auto it = mPausedVDAnimators.begin(); it != mPausedVDAnimators.end();) {
        // Remove paused VD animator if no one else is referencing it. Note that animators that
        // have passed scheduled finish time are removed from list when they are being pulsed
        // before prepare tree.
        // TODO: this is a bit hacky, need to figure out a better way to track when the paused
        // animators should be freed.
        if ((*it)->getStrongCount() == 1) {
            it = mPausedVDAnimators.erase(it);
        } else {
            it++;
        }
    }
}

void RootRenderNode::pushStagingVectorDrawableAnimators(AnimationContext* context) {
    for (auto& anim : mRunningVDAnimators) {
        anim->pushStaging(*context);
    }
}

void RootRenderNode::destroy() {
    for (auto& renderNode : mPendingAnimatingRenderNodes) {
        renderNode->animators().endAllStagingAnimators();
    }
    mPendingAnimatingRenderNodes.clear();
    mPendingVectorDrawableAnimators.clear();
}

void RootRenderNode::addVectorDrawableAnimator(PropertyValuesAnimatorSet* anim) {
    mPendingVectorDrawableAnimators.insert(anim);
}

void RootRenderNode::detachVectorDrawableAnimator(PropertyValuesAnimatorSet* anim) {
    if (anim->isInfinite() || !anim->isRunning()) {
        // Do not need to post anything if the animation is infinite (i.e. no meaningful
        // end listener action), or if the animation has already ended.
        return;
    }
    nsecs_t remainingTimeInMs = anim->getRemainingPlayTime();
    // Post a delayed onFinished event that is scheduled to be handled when the animator ends.
    if (anim->getOneShotListener()) {
        // VectorDrawable's oneshot listener is updated when there are user triggered animation
        // lifecycle changes, such as start(), end(), etc. By using checking and clearing
        // one shot listener, we ensure the same end listener event gets posted only once.
        // Therefore no duplicates. Another benefit of using one shot listener is that no
        // removal is necessary: the end time of animation will not change unless triggered by
        // user events, in which case the already posted listener's id will become stale, and
        // the onFinished callback will then be ignored.
        sp<FinishAndInvokeListener> message = new FinishAndInvokeListener(anim);
        auto looper = Looper::getForThread();
        LOG_ALWAYS_FATAL_IF(looper == nullptr, "Not on a looper thread?");
        looper->sendMessageDelayed(ms2ns(remainingTimeInMs), message, 0);
        anim->clearOneShotListener();
    }
}

class AnimationContextBridge : public AnimationContext {
public:
    AnimationContextBridge(renderthread::TimeLord& clock, RootRenderNode* rootNode)
            : AnimationContext(clock), mRootNode(rootNode) {}

    virtual ~AnimationContextBridge() {}

    // Marks the start of a frame, which will update the frame time and move all
    // next frame animations into the current frame
    virtual void startFrame(TreeInfo::TraversalMode mode) {
        if (mode == TreeInfo::MODE_FULL) {
            mRootNode->doAttachAnimatingNodes(this);
            mRootNode->attachPendingVectorDrawableAnimators();
        }
        AnimationContext::startFrame(mode);
    }

    // Runs any animations still left in mCurrentFrameAnimations
    virtual void runRemainingAnimations(TreeInfo& info) {
        AnimationContext::runRemainingAnimations(info);
        mRootNode->runVectorDrawableAnimators(this, info);
    }

    virtual void pauseAnimators() override { mRootNode->pauseAnimators(); }

    virtual void callOnFinished(BaseRenderNodeAnimator* animator, AnimationListener* listener) {
        listener->onAnimationFinished(animator);
    }

    virtual void destroy() {
        AnimationContext::destroy();
        mRootNode->detachAnimators();
    }

private:
    sp<RootRenderNode> mRootNode;
};

AnimationContext* ContextFactoryImpl::createAnimationContext(renderthread::TimeLord& clock) {
    return new AnimationContextBridge(clock, mRootNode);
}
#else

void RootRenderNode::prepareTree(TreeInfo& info) {
    info.errorHandler = mErrorHandler.get();
    info.updateWindowPositions = true;
    RenderNode::prepareTree(info);
    info.updateWindowPositions = false;
    info.errorHandler = nullptr;
}

void RootRenderNode::attachAnimatingNode(RenderNode* animatingNode) { }

void RootRenderNode::destroy() { }

void RootRenderNode::addVectorDrawableAnimator(PropertyValuesAnimatorSet* anim) { }

#endif

}  // namespace android::uirenderer
