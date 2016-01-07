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

#include "PropertyValuesAnimatorSet.h"
#include "RenderNode.h"

namespace android {
namespace uirenderer {

void PropertyValuesAnimatorSet::addPropertyAnimator(PropertyValuesHolder* propertyValuesHolder,
            Interpolator* interpolator, nsecs_t startDelay,
            nsecs_t duration, int repeatCount) {

    PropertyAnimator* animator = new PropertyAnimator(propertyValuesHolder,
            interpolator, startDelay, duration, repeatCount);
    mAnimators.emplace_back(animator);
    setListener(new PropertyAnimatorSetListener(this));
}

PropertyValuesAnimatorSet::PropertyValuesAnimatorSet()
        : BaseRenderNodeAnimator(1.0f) {
    setStartValue(0);
    mLastFraction = 0.0f;
    setInterpolator(new LinearInterpolator());
}

void PropertyValuesAnimatorSet::onFinished(BaseRenderNodeAnimator* animator) {
    if (mOneShotListener.get()) {
        mOneShotListener->onAnimationFinished(animator);
        mOneShotListener = nullptr;
    }
}

float PropertyValuesAnimatorSet::getValue(RenderNode* target) const {
    return mLastFraction;
}

void PropertyValuesAnimatorSet::setValue(RenderNode* target, float value) {
    mLastFraction = value;
}

void PropertyValuesAnimatorSet::onPlayTimeChanged(nsecs_t playTime) {
    for (size_t i = 0; i < mAnimators.size(); i++) {
        mAnimators[i]->setCurrentPlayTime(playTime);
    }
}

void PropertyValuesAnimatorSet::reset() {
    // TODO: implement reset through adding a play state because we need to support reset() even
    // during an animation run.
}

void PropertyValuesAnimatorSet::start(AnimationListener* listener) {
    init();
    mOneShotListener = listener;
    BaseRenderNodeAnimator::start();
}

void PropertyValuesAnimatorSet::reverse(AnimationListener* listener) {
// TODO: implement reverse
}

void PropertyValuesAnimatorSet::init() {
    if (mInitialized) {
        return;
    }
    nsecs_t maxDuration = 0;
    for (size_t i = 0; i < mAnimators.size(); i++) {
        if (maxDuration < mAnimators[i]->getTotalDuration()) {
            maxDuration = mAnimators[i]->getTotalDuration();
        }
    }
    mDuration = maxDuration;
    mInitialized = true;
}

uint32_t PropertyValuesAnimatorSet::dirtyMask() {
    return RenderNode::DISPLAY_LIST;
}

PropertyAnimator::PropertyAnimator(PropertyValuesHolder* holder, Interpolator* interpolator,
        nsecs_t startDelay, nsecs_t duration, int repeatCount)
        : mPropertyValuesHolder(holder), mInterpolator(interpolator), mStartDelay(startDelay),
          mDuration(duration) {
    if (repeatCount < 0) {
        mRepeatCount = UINT32_MAX;
    } else {
        mRepeatCount = repeatCount;
    }
    mTotalDuration = ((nsecs_t) mRepeatCount + 1) * mDuration + mStartDelay;
}

void PropertyAnimator::setCurrentPlayTime(nsecs_t playTime) {
    if (playTime >= mStartDelay && playTime < mTotalDuration) {
         nsecs_t currentIterationPlayTime = (playTime - mStartDelay) % mDuration;
         mLatestFraction = currentIterationPlayTime / (float) mDuration;
    } else if (mLatestFraction < 1.0f && playTime >= mTotalDuration) {
        mLatestFraction = 1.0f;
    } else {
        return;
    }

    setFraction(mLatestFraction);
}

void PropertyAnimator::setFraction(float fraction) {
    float interpolatedFraction = mInterpolator->interpolate(mLatestFraction);
    mPropertyValuesHolder->setFraction(interpolatedFraction);
}

void PropertyAnimatorSetListener::onAnimationFinished(BaseRenderNodeAnimator* animator) {
    mSet->onFinished(animator);
}

}
}
