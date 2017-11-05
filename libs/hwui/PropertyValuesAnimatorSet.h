/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "Animator.h"
#include "Interpolator.h"
#include "PropertyValuesHolder.h"

namespace android {
namespace uirenderer {

class PropertyAnimator {
public:
    PropertyAnimator(PropertyValuesHolder* holder, Interpolator* interpolator, nsecs_t startDelay,
                     nsecs_t duration, int repeatCount, RepeatMode repeatMode);
    void setCurrentPlayTime(nsecs_t playTime);
    nsecs_t getTotalDuration() { return mTotalDuration; }
    // fraction range: [0, 1], iteration range [0, repeatCount]
    void setFraction(float fraction, long iteration);

private:
    std::unique_ptr<PropertyValuesHolder> mPropertyValuesHolder;
    std::unique_ptr<Interpolator> mInterpolator;
    nsecs_t mStartDelay;
    nsecs_t mDuration;
    uint32_t mRepeatCount;
    nsecs_t mTotalDuration;
    RepeatMode mRepeatMode;
    double mLatestFraction = 0;
};

// TODO: This class should really be named VectorDrawableAnimator
class ANDROID_API PropertyValuesAnimatorSet : public BaseRenderNodeAnimator {
public:
    friend class PropertyAnimatorSetListener;
    PropertyValuesAnimatorSet();

    void start(AnimationListener* listener);
    void reverse(AnimationListener* listener);
    virtual void reset() override;
    virtual void end() override;

    void addPropertyAnimator(PropertyValuesHolder* propertyValuesHolder,
                             Interpolator* interpolators, int64_t startDelays, nsecs_t durations,
                             int repeatCount, RepeatMode repeatMode);
    virtual uint32_t dirtyMask();
    bool isInfinite() { return mIsInfinite; }
    void setVectorDrawable(VectorDrawableRoot* vd) { mVectorDrawable = vd; }
    VectorDrawableRoot* getVectorDrawable() const { return mVectorDrawable.get(); }
    AnimationListener* getOneShotListener() { return mOneShotListener.get(); }
    void clearOneShotListener() { mOneShotListener = nullptr; }
    uint32_t getRequestId() const { return mRequestId; }

protected:
    virtual float getValue(RenderNode* target) const override;
    virtual void setValue(RenderNode* target, float value) override;
    virtual void onPlayTimeChanged(nsecs_t playTime) override;

private:
    void init();
    void onFinished(BaseRenderNodeAnimator* animator);
    // Listener set from outside
    sp<AnimationListener> mOneShotListener;
    std::vector<std::unique_ptr<PropertyAnimator> > mAnimators;
    float mLastFraction = 0.0f;
    bool mInitialized = false;
    sp<VectorDrawableRoot> mVectorDrawable;
    bool mIsInfinite = false;
    // This request id gets incremented (on UI thread only) when a new request to modfiy the
    // lifecycle of an animation happens, namely when start/end/reset/reverse is called.
    uint32_t mRequestId = 0;
};

class PropertyAnimatorSetListener : public AnimationListener {
public:
    explicit PropertyAnimatorSetListener(PropertyValuesAnimatorSet* set) : mSet(set) {}
    virtual void onAnimationFinished(BaseRenderNodeAnimator* animator) override;

private:
    PropertyValuesAnimatorSet* mSet;
};

}  // namespace uirenderer
}  // namespace android
