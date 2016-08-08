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

#include <algorithm>

namespace android {
namespace uirenderer {

void PropertyValuesAnimatorSet::addPropertyAnimator(PropertyValuesHolder* propertyValuesHolder,
        Interpolator* interpolator, nsecs_t startDelay, nsecs_t duration, int repeatCount,
        RepeatMode repeatMode) {

    PropertyAnimator* animator = new PropertyAnimator(propertyValuesHolder,
            interpolator, startDelay, duration, repeatCount, repeatMode);
    mAnimators.emplace_back(animator);

    // Check whether any child animator is infinite after adding it them to the set.
    if (repeatCount == -1) {
        mIsInfinite = true;
    }
}

PropertyValuesAnimatorSet::PropertyValuesAnimatorSet()
        : BaseRenderNodeAnimator(1.0f) {
    setStartValue(0);
    mLastFraction = 0.0f;
    setInterpolator(new LinearInterpolator());
    setListener(new PropertyAnimatorSetListener(this));
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
    if (playTime == 0 && mDuration > 0) {
        // Reset all the animators
        for (auto it = mAnimators.rbegin(); it != mAnimators.rend(); it++) {
            // Note that this set may containing animators modifying the same property, so when we
            // reset the animators, we need to make sure the animators that end the first will
            // have the final say on what the property value should be.
            (*it)->setFraction(0, 0);
        }
    } else  {
        for (auto& anim : mAnimators) {
            anim->setCurrentPlayTime(playTime);
        }
    }
}

void PropertyValuesAnimatorSet::start(AnimationListener* listener) {
    init();
    mOneShotListener = listener;
    mRequestId++;
    BaseRenderNodeAnimator::start();
}

void PropertyValuesAnimatorSet::reverse(AnimationListener* listener) {
    init();
    mOneShotListener = listener;
    mRequestId++;
    BaseRenderNodeAnimator::reverse();
}

void PropertyValuesAnimatorSet::reset() {
    mRequestId++;
    BaseRenderNodeAnimator::reset();
}

void PropertyValuesAnimatorSet::end() {
    mRequestId++;
    BaseRenderNodeAnimator::end();
}

void PropertyValuesAnimatorSet::init() {
    if (mInitialized) {
        return;
    }

    // Sort the animators by their total duration. Note that all the animators in the set start at
    // the same time, so the ones with longer total duration (which includes start delay) will
    // be the ones that end later.
    std::sort(mAnimators.begin(), mAnimators.end(), [](auto& a, auto&b) {
        return a->getTotalDuration() < b->getTotalDuration();
    });
    mDuration = mAnimators.empty() ? 0 : mAnimators[mAnimators.size() - 1]->getTotalDuration();
    mInitialized = true;
}

uint32_t PropertyValuesAnimatorSet::dirtyMask() {
    return RenderNode::DISPLAY_LIST;
}

PropertyAnimator::PropertyAnimator(PropertyValuesHolder* holder, Interpolator* interpolator,
        nsecs_t startDelay, nsecs_t duration, int repeatCount,
        RepeatMode repeatMode)
        : mPropertyValuesHolder(holder), mInterpolator(interpolator), mStartDelay(startDelay),
          mDuration(duration) {
    if (repeatCount < 0) {
        mRepeatCount = UINT32_MAX;
    } else {
        mRepeatCount = repeatCount;
    }
    mRepeatMode = repeatMode;
    mTotalDuration = ((nsecs_t) mRepeatCount + 1) * mDuration + mStartDelay;
}

void PropertyAnimator::setCurrentPlayTime(nsecs_t playTime) {
    if (playTime < mStartDelay) {
        return;
    }

    float currentIterationFraction;
    long iteration;
    if (playTime >= mTotalDuration) {
        // Reached the end of the animation.
        iteration = mRepeatCount;
        currentIterationFraction = 1.0f;
    } else {
        // play time here is in range [mStartDelay, mTotalDuration)
        iteration = (playTime - mStartDelay) / mDuration;
        currentIterationFraction = ((playTime - mStartDelay) % mDuration) / (float) mDuration;
    }
    setFraction(currentIterationFraction, iteration);
}

void PropertyAnimator::setFraction(float fraction, long iteration) {
    double totalFraction = fraction + iteration;
    // This makes sure we only set the fraction = repeatCount + 1 once. It is needed because there
    // might be another animator modifying the same property after this animator finishes, we need
    // to make sure we don't set conflicting values on the same property within one frame.
    if ((mLatestFraction == mRepeatCount + 1.0) && (totalFraction >= mRepeatCount + 1.0)) {
        return;
    }

    mLatestFraction = totalFraction;
    // Check the play direction (i.e. reverse or restart) every other iteration, and calculate the
    // fraction based on the play direction.
    if (iteration % 2 && mRepeatMode == RepeatMode::Reverse) {
        fraction = 1.0f - fraction;
    }
    float interpolatedFraction = mInterpolator->interpolate(fraction);
    mPropertyValuesHolder->setFraction(interpolatedFraction);
}

void PropertyAnimatorSetListener::onAnimationFinished(BaseRenderNodeAnimator* animator) {
    mSet->onFinished(animator);
}

}
}
