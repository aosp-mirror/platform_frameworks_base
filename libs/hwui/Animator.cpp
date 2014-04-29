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

#define LOG_TAG "RT-Animator"

#include "Animator.h"

#include <set>

#include "RenderProperties.h"

namespace android {
namespace uirenderer {

/************************************************************
 *  Private header
 ************************************************************/

typedef void (RenderProperties::*SetFloatProperty)(float value);
typedef float (RenderProperties::*GetFloatProperty)() const;

struct PropertyAccessors {
    GetFloatProperty getter;
    SetFloatProperty setter;
};

// Maps RenderProperty enum to accessors
static const PropertyAccessors PROPERTY_ACCESSOR_LUT[] = {
    {&RenderProperties::getTranslationX, &RenderProperties::setTranslationX },
    {&RenderProperties::getTranslationY, &RenderProperties::setTranslationY },
    {&RenderProperties::getTranslationZ, &RenderProperties::setTranslationZ },
    {&RenderProperties::getScaleX, &RenderProperties::setScaleX },
    {&RenderProperties::getScaleY, &RenderProperties::setScaleY },
    {&RenderProperties::getRotation, &RenderProperties::setRotation },
    {&RenderProperties::getRotationX, &RenderProperties::setRotationX },
    {&RenderProperties::getRotationY, &RenderProperties::setRotationY },
    {&RenderProperties::getX, &RenderProperties::setX },
    {&RenderProperties::getY, &RenderProperties::setY },
    {&RenderProperties::getZ, &RenderProperties::setZ },
    {&RenderProperties::getAlpha, &RenderProperties::setAlpha },
};

// Helper class to contain generic animator helpers
class BaseAnimator {
public:
    BaseAnimator();
    virtual ~BaseAnimator();

    void setInterpolator(Interpolator* interpolator);
    void setDuration(nsecs_t durationInMs);

    bool isFinished() { return mPlayState == FINISHED; }

protected:
    // This is the main animation entrypoint that subclasses should call
    // to generate the onAnimation* lifecycle events
    // Returns true if the animation has finished, false otherwise
    bool animateFrame(nsecs_t frameTime);

    // Called when PlayState switches from PENDING to RUNNING
    virtual void onAnimationStarted() {}
    virtual void onAnimationUpdated(float fraction) = 0;
    virtual void onAnimationFinished() {}

private:
    enum PlayState {
        PENDING,
        RUNNING,
        FINISHED,
    };

    Interpolator* mInterpolator;
    PlayState mPlayState;
    long mStartTime;
    long mDuration;
};

// Hide the base classes & private bits from the exported RenderPropertyAnimator
// in this Impl class so that subclasses of RenderPropertyAnimator don't require
// knowledge of the inner guts but only the public virtual methods.
// Animates a single property
class RenderPropertyAnimatorImpl : public BaseAnimator {
public:
    RenderPropertyAnimatorImpl(GetFloatProperty getter, SetFloatProperty setter,
            RenderPropertyAnimator::DeltaValueType deltaType, float delta);
    ~RenderPropertyAnimatorImpl();

    bool animate(RenderProperties* target, TreeInfo& info);

protected:
    virtual void onAnimationStarted();
    virtual void onAnimationUpdated(float fraction);

private:
    // mTarget is only valid inside animate()
    RenderProperties* mTarget;
    GetFloatProperty mGetter;
    SetFloatProperty mSetter;

    RenderPropertyAnimator::DeltaValueType mDeltaValueType;
    float mDeltaValue;
    float mFromValue;
};

RenderPropertyAnimator::RenderPropertyAnimator(RenderProperty property,
        DeltaValueType deltaType, float deltaValue) {
    PropertyAccessors pa = PROPERTY_ACCESSOR_LUT[property];
    mImpl = new RenderPropertyAnimatorImpl(pa.getter, pa.setter, deltaType, deltaValue);
}

RenderPropertyAnimator::~RenderPropertyAnimator() {
    delete mImpl;
    mImpl = NULL;
}

void RenderPropertyAnimator::setInterpolator(Interpolator* interpolator) {
    mImpl->setInterpolator(interpolator);
}

void RenderPropertyAnimator::setDuration(nsecs_t durationInMs) {
    mImpl->setDuration(durationInMs);
}

bool RenderPropertyAnimator::isFinished() {
    return mImpl->isFinished();
}

bool RenderPropertyAnimator::animate(RenderProperties* target, TreeInfo& info) {
    return mImpl->animate(target, info);
}


/************************************************************
 *  Base animator
 ************************************************************/

BaseAnimator::BaseAnimator()
        : mInterpolator(0)
        , mPlayState(PENDING)
        , mStartTime(0)
        , mDuration(300) {

}

BaseAnimator::~BaseAnimator() {
    setInterpolator(NULL);
}

void BaseAnimator::setInterpolator(Interpolator* interpolator) {
    delete mInterpolator;
    mInterpolator = interpolator;
}

void BaseAnimator::setDuration(nsecs_t duration) {
    mDuration = duration;
}

bool BaseAnimator::animateFrame(nsecs_t frameTime) {
    if (mPlayState == PENDING) {
        mPlayState = RUNNING;
        mStartTime = frameTime;
        // No interpolator was set, use the default
        if (!mInterpolator) {
            setInterpolator(Interpolator::createDefaultInterpolator());
        }
        onAnimationStarted();
    }

    float fraction = 1.0f;
    if (mPlayState == RUNNING) {
        fraction = mDuration > 0 ? (float)(frameTime - mStartTime) / mDuration : 1.0f;
        if (fraction >= 1.0f) {
            fraction = 1.0f;
            mPlayState = FINISHED;
        }
    }
    fraction = mInterpolator->interpolate(fraction);
    onAnimationUpdated(fraction);

    if (mPlayState == FINISHED) {
        onAnimationFinished();
        return true;
    }
    return false;
}

/************************************************************
 *  RenderPropertyAnimator
 ************************************************************/

RenderPropertyAnimatorImpl::RenderPropertyAnimatorImpl(
                GetFloatProperty getter, SetFloatProperty setter,
                RenderPropertyAnimator::DeltaValueType deltaType, float delta)
        : mTarget(0)
        , mGetter(getter)
        , mSetter(setter)
        , mDeltaValueType(deltaType)
        , mDeltaValue(delta)
        , mFromValue(-1) {
}

RenderPropertyAnimatorImpl::~RenderPropertyAnimatorImpl() {
}

bool RenderPropertyAnimatorImpl::animate(RenderProperties* target, TreeInfo& info) {
    mTarget = target;
    bool finished = animateFrame(info.frameTimeMs);
    mTarget = NULL;
    return finished;
}

void RenderPropertyAnimatorImpl::onAnimationStarted() {
    mFromValue = (mTarget->*mGetter)();

    if (mDeltaValueType == RenderPropertyAnimator::ABSOLUTE) {
        mDeltaValue = (mDeltaValue - mFromValue);
        mDeltaValueType = RenderPropertyAnimator::DELTA;
    }
}

void RenderPropertyAnimatorImpl::onAnimationUpdated(float fraction) {
    float value = mFromValue + (mDeltaValue * fraction);
    (mTarget->*mSetter)(value);
}

} /* namespace uirenderer */
} /* namespace android */
