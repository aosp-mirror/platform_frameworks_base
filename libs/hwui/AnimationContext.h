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
#ifndef TREEANIMATIONTRACKER_H_
#define TREEANIMATIONTRACKER_H_

#include <cutils/compiler.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>

#include "TreeInfo.h"
#include "renderthread/TimeLord.h"
#include "utils/Macros.h"

namespace android {
namespace uirenderer {

class AnimationContext;
class AnimationListener;
class BaseRenderNodeAnimator;
class RenderNode;

/*
 * AnimationHandle is several classes merged into one.
 * 1: It maintains the reference to the AnimationContext required to run animators.
 * 2: It keeps a strong reference to RenderNodes with animators so that
 *    we don't lose them if they are no longer in the display tree. This is
 *    required so that we can keep animating them, and properly notify listeners
 *    of onAnimationFinished.
 * 3: It forms a doubly linked list so that we can cheaply move between states.
 */
class AnimationHandle {
    PREVENT_COPY_AND_ASSIGN(AnimationHandle);

public:
    AnimationContext& context() { return mContext; }

    // Called by the RenderNode when it has internally pulsed its own animations
    // this frame and does not need to be run again this frame.
    void notifyAnimationsRan();

    // Stops tracking the RenderNode and destroys the handle. The node must be
    // re-attached to the AnimationContext to receive managed animation
    // pulses.
    void release();

private:
    friend class AnimationContext;
    explicit AnimationHandle(AnimationContext& context);
    AnimationHandle(RenderNode& animatingNode, AnimationContext& context);
    ~AnimationHandle();

    void insertAfter(AnimationHandle* prev);
    void removeFromList();

    sp<RenderNode> mRenderNode;

    AnimationContext& mContext;

    AnimationHandle* mPreviousHandle;
    AnimationHandle* mNextHandle;
};

class AnimationContext {
    PREVENT_COPY_AND_ASSIGN(AnimationContext);

public:
    explicit AnimationContext(renderthread::TimeLord& clock);
    virtual ~AnimationContext();

    nsecs_t frameTimeMs() { return mFrameTimeMs; }
    bool hasAnimations() {
        return mCurrentFrameAnimations.mNextHandle || mNextFrameAnimations.mNextHandle;
    }

    // Will always add to the next frame list, which is swapped when
    // startFrame() is called
    void addAnimatingRenderNode(RenderNode& node);

    // Marks the start of a frame, which will update the frame time and move all
    // next frame animations into the current frame
    virtual void startFrame(TreeInfo::TraversalMode mode);

    // Runs any animations still left in mCurrentFrameAnimations that were not run
    // as part of the standard RenderNode:prepareTree pass.
    virtual void runRemainingAnimations(TreeInfo& info);

    virtual void callOnFinished(BaseRenderNodeAnimator* animator,
                                            AnimationListener* listener);

    virtual void destroy();

    virtual void pauseAnimators() {}

private:
    friend class AnimationHandle;
    void addAnimationHandle(AnimationHandle* handle);

    renderthread::TimeLord& mClock;

    // Animations left to run this frame, at the end of the frame this should
    // be null
    AnimationHandle mCurrentFrameAnimations;
    // Animations queued for next frame
    AnimationHandle mNextFrameAnimations;

    nsecs_t mFrameTimeMs;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* TREEANIMATIONTRACKER_H_ */
