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
#include "PropertyValuesHolder.h"
#include "Interpolator.h"

namespace android {
namespace uirenderer {

class PropertyAnimator {
public:
    PropertyAnimator(PropertyValuesHolder* holder, Interpolator* interpolator, nsecs_t startDelay,
            nsecs_t duration, int repeatCount);
    void setCurrentPlayTime(nsecs_t playTime);
    nsecs_t getTotalDuration() {
        return mTotalDuration;
    }
    void setFraction(float fraction);

private:
    std::unique_ptr<PropertyValuesHolder> mPropertyValuesHolder;
    std::unique_ptr<Interpolator> mInterpolator;
    nsecs_t mStartDelay;
    nsecs_t mDuration;
    uint32_t mRepeatCount;
    nsecs_t mTotalDuration;
    float mLatestFraction = 0.0f;
};

class ANDROID_API PropertyValuesAnimatorSet : public BaseRenderNodeAnimator {
public:
    friend class PropertyAnimatorSetListener;
    PropertyValuesAnimatorSet();

    void start(AnimationListener* listener);
    void reverse(AnimationListener* listener);

    void addPropertyAnimator(PropertyValuesHolder* propertyValuesHolder,
            Interpolator* interpolators, int64_t startDelays,
            nsecs_t durations, int repeatCount);
    virtual uint32_t dirtyMask();

protected:
    virtual float getValue(RenderNode* target) const override;
    virtual void setValue(RenderNode* target, float value) override;
    virtual void onPlayTimeChanged(nsecs_t playTime) override;

private:
    void init();
    void onFinished(BaseRenderNodeAnimator* animator);
    // Listener set from outside
    sp<AnimationListener> mOneShotListener;
    std::vector< std::unique_ptr<PropertyAnimator> > mAnimators;
    float mLastFraction = 0.0f;
    bool mInitialized = false;
};

class PropertyAnimatorSetListener : public AnimationListener {
public:
    PropertyAnimatorSetListener(PropertyValuesAnimatorSet* set) : mSet(set) {}
    virtual void onAnimationFinished(BaseRenderNodeAnimator* animator) override;

private:
    PropertyValuesAnimatorSet* mSet;
};

} // namespace uirenderer
} // namespace android
