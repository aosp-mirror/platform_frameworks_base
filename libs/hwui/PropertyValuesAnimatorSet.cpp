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
    if (playTime == 0 && mDuration > 0) {
        // Reset all the animators
        for (auto it = mAnimators.rbegin(); it != mAnimators.rend(); it++) {
            // Note that this set may containing animators modifying the same property, so when we
            // reset the animators, we need to make sure the animators that end the first will
            // have the final say on what the property value should be.
            (*it)->setFraction(0);
        }
    } else if (playTime >= mDuration) {
        // Skip all the animators to end
        for (auto& anim : mAnimators) {
            anim->setFraction(1);
        }
    } else {
        for (auto& anim : mAnimators) {
            anim->setCurrentPlayTime(playTime);
        }
    }
}

void PropertyValuesAnimatorSet::start(AnimationListener* listener) {
    init();
    mOneShotListener = listener;
    BaseRenderNodeAnimator::start();
}

void PropertyValuesAnimatorSet::reverse(AnimationListener* listener) {
    init();
    mOneShotListener = listener;
    BaseRenderNodeAnimator::reverse();
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
    mDuration = mAnimators[mAnimators.size() - 1]->getTotalDuration();
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
         float fraction = currentIterationPlayTime / (float) mDuration;
         setFraction(fraction);
    } else if (mLatestFraction < 1.0f && playTime >= mTotalDuration) {
        // This makes sure we only set the fraction = 1 once. It is needed because there might
        // be another animator modifying the same property after this animator finishes, we need
        // to make sure we don't set conflicting values on the same property within one frame.
        setFraction(1.0f);
    }
}

void PropertyAnimator::setFraction(float fraction) {
    mLatestFraction = fraction;
    float interpolatedFraction = mInterpolator->interpolate(fraction);
    mPropertyValuesHolder->setFraction(interpolatedFraction);
}

void PropertyAnimatorSetListener::onAnimationFinished(BaseRenderNodeAnimator* animator) {
    mSet->onFinished(animator);
}

}
}
