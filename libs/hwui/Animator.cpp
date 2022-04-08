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

#include "Animator.h"

#include <inttypes.h>
#include <set>

#include "AnimationContext.h"
#include "Interpolator.h"
#include "RenderNode.h"
#include "RenderProperties.h"

namespace android {
namespace uirenderer {

/************************************************************
 *  BaseRenderNodeAnimator
 ************************************************************/

BaseRenderNodeAnimator::BaseRenderNodeAnimator(float finalValue)
        : mTarget(nullptr)
        , mStagingTarget(nullptr)
        , mFinalValue(finalValue)
        , mDeltaValue(0)
        , mFromValue(0)
        , mStagingPlayState(PlayState::NotStarted)
        , mPlayState(PlayState::NotStarted)
        , mHasStartValue(false)
        , mStartTime(0)
        , mDuration(300)
        , mStartDelay(0)
        , mMayRunAsync(true)
        , mPlayTime(0) {}

BaseRenderNodeAnimator::~BaseRenderNodeAnimator() {}

void BaseRenderNodeAnimator::checkMutable() {
    // Should be impossible to hit as the Java-side also has guards for this
    LOG_ALWAYS_FATAL_IF(mStagingPlayState != PlayState::NotStarted,
                        "Animator has already been started!");
}

void BaseRenderNodeAnimator::setInterpolator(Interpolator* interpolator) {
    checkMutable();
    mInterpolator.reset(interpolator);
}

void BaseRenderNodeAnimator::setStartValue(float value) {
    checkMutable();
    doSetStartValue(value);
}

void BaseRenderNodeAnimator::doSetStartValue(float value) {
    mFromValue = value;
    mDeltaValue = (mFinalValue - mFromValue);
    mHasStartValue = true;
}

void BaseRenderNodeAnimator::setDuration(nsecs_t duration) {
    checkMutable();
    mDuration = duration;
}

void BaseRenderNodeAnimator::setStartDelay(nsecs_t startDelay) {
    checkMutable();
    mStartDelay = startDelay;
}

void BaseRenderNodeAnimator::attach(RenderNode* target) {
    mStagingTarget = target;
    onAttached();
}

void BaseRenderNodeAnimator::start() {
    mStagingPlayState = PlayState::Running;
    mStagingRequests.push_back(Request::Start);
    onStagingPlayStateChanged();
}

void BaseRenderNodeAnimator::cancel() {
    mStagingPlayState = PlayState::Finished;
    mStagingRequests.push_back(Request::Cancel);
    onStagingPlayStateChanged();
}

void BaseRenderNodeAnimator::reset() {
    mStagingPlayState = PlayState::Finished;
    mStagingRequests.push_back(Request::Reset);
    onStagingPlayStateChanged();
}

void BaseRenderNodeAnimator::reverse() {
    mStagingPlayState = PlayState::Reversing;
    mStagingRequests.push_back(Request::Reverse);
    onStagingPlayStateChanged();
}

void BaseRenderNodeAnimator::end() {
    mStagingPlayState = PlayState::Finished;
    mStagingRequests.push_back(Request::End);
    onStagingPlayStateChanged();
}

void BaseRenderNodeAnimator::resolveStagingRequest(Request request) {
    switch (request) {
        case Request::Start:
            mPlayTime = (mPlayState == PlayState::Running || mPlayState == PlayState::Reversing)
                                ? mPlayTime
                                : 0;
            mPlayState = PlayState::Running;
            mPendingActionUponFinish = Action::None;
            break;
        case Request::Reverse:
            mPlayTime = (mPlayState == PlayState::Running || mPlayState == PlayState::Reversing)
                                ? mPlayTime
                                : mDuration;
            mPlayState = PlayState::Reversing;
            mPendingActionUponFinish = Action::None;
            break;
        case Request::Reset:
            mPlayTime = 0;
            mPlayState = PlayState::Finished;
            mPendingActionUponFinish = Action::Reset;
            break;
        case Request::Cancel:
            mPlayState = PlayState::Finished;
            mPendingActionUponFinish = Action::None;
            break;
        case Request::End:
            mPlayTime = mPlayState == PlayState::Reversing ? 0 : mDuration;
            mPlayState = PlayState::Finished;
            mPendingActionUponFinish = Action::End;
            break;
        default:
            LOG_ALWAYS_FATAL("Invalid staging request: %d", static_cast<int>(request));
    };
}

void BaseRenderNodeAnimator::pushStaging(AnimationContext& context) {
    if (mStagingTarget) {
        RenderNode* oldTarget = mTarget;
        mTarget = mStagingTarget;
        mStagingTarget = nullptr;
        if (oldTarget && oldTarget != mTarget) {
            oldTarget->onAnimatorTargetChanged(this);
        }
    }

    if (!mHasStartValue) {
        doSetStartValue(getValue(mTarget));
    }

    if (!mStagingRequests.empty()) {
        // No interpolator was set, use the default
        if (mPlayState == PlayState::NotStarted && !mInterpolator) {
            mInterpolator.reset(Interpolator::createDefaultInterpolator());
        }
        // Keep track of the play state and play time before they are changed when
        // staging requests are resolved.
        nsecs_t currentPlayTime = mPlayTime;
        PlayState prevFramePlayState = mPlayState;

        // Resolve staging requests one by one.
        for (Request request : mStagingRequests) {
            resolveStagingRequest(request);
        }
        mStagingRequests.clear();

        if (mStagingPlayState == PlayState::Finished) {
            callOnFinishedListener(context);
        } else if (mStagingPlayState == PlayState::Running ||
                   mStagingPlayState == PlayState::Reversing) {
            bool changed = currentPlayTime != mPlayTime || prevFramePlayState != mStagingPlayState;
            if (prevFramePlayState != mStagingPlayState) {
                transitionToRunning(context);
            }
            if (changed) {
                // Now we need to seek to the stagingPlayTime (i.e. the animation progress that was
                // requested from UI thread). It is achieved by modifying mStartTime, such that
                // current time - mStartTime = stagingPlayTime (or mDuration -stagingPlayTime in the
                // case of reversing)
                nsecs_t currentFrameTime = context.frameTimeMs();
                if (mPlayState == PlayState::Reversing) {
                    // Reverse is not supported for animations with a start delay, so here we
                    // assume no start delay.
                    mStartTime = currentFrameTime - (mDuration - mPlayTime);
                } else {
                    // Animation should play forward
                    if (mPlayTime == 0) {
                        // If the request is to start from the beginning, include start delay.
                        mStartTime = currentFrameTime + mStartDelay;
                    } else {
                        // If the request is to seek to a non-zero play time, then we skip start
                        // delay.
                        mStartTime = currentFrameTime - mPlayTime;
                    }
                }
            }
        }
    }
    onPushStaging();
}

void BaseRenderNodeAnimator::transitionToRunning(AnimationContext& context) {
    nsecs_t frameTimeMs = context.frameTimeMs();
    LOG_ALWAYS_FATAL_IF(frameTimeMs <= 0, "%" PRId64 " isn't a real frame time!", frameTimeMs);
    if (mStartDelay < 0 || mStartDelay > 50000) {
        ALOGW("Your start delay is strange and confusing: %" PRId64, mStartDelay);
    }
    mStartTime = frameTimeMs + mStartDelay;
    if (mStartTime < 0) {
        ALOGW("Ended up with a really weird start time of %" PRId64 " with frame time %" PRId64
              " and start delay %" PRId64,
              mStartTime, frameTimeMs, mStartDelay);
        // Set to 0 so that the animate() basically instantly finishes
        mStartTime = 0;
    }
    if (mDuration < 0) {
        ALOGW("Your duration is strange and confusing: %" PRId64, mDuration);
    }
}

bool BaseRenderNodeAnimator::animate(AnimationContext& context) {
    if (mPlayState < PlayState::Running) {
        return false;
    }
    if (mPlayState == PlayState::Finished) {
        if (mPendingActionUponFinish == Action::Reset) {
            // Skip to start.
            updatePlayTime(0);
        } else if (mPendingActionUponFinish == Action::End) {
            // Skip to end.
            updatePlayTime(mDuration);
        }
        // Reset pending action.
        mPendingActionUponFinish = Action::None;
        return true;
    }

    // This should be set before setValue() so animators can query this time when setValue
    // is called.
    nsecs_t currentPlayTime = context.frameTimeMs() - mStartTime;
    bool finished = updatePlayTime(currentPlayTime);
    if (finished && mPlayState != PlayState::Finished) {
        mPlayState = PlayState::Finished;
        callOnFinishedListener(context);
    }
    return finished;
}

bool BaseRenderNodeAnimator::updatePlayTime(nsecs_t playTime) {
    mPlayTime = mPlayState == PlayState::Reversing ? mDuration - playTime : playTime;
    onPlayTimeChanged(mPlayTime);
    // If BaseRenderNodeAnimator is handling the delay (not typical), then
    // because the staging properties reflect the final value, we always need
    // to call setValue even if the animation isn't yet running or is still
    // being delayed as we need to override the staging value
    if (playTime < 0) {
        setValue(mTarget, mFromValue);
        return false;
    }

    float fraction = 1.0f;
    if ((mPlayState == PlayState::Running || mPlayState == PlayState::Reversing) && mDuration > 0) {
        fraction = mPlayTime / (float)mDuration;
    }
    fraction = MathUtils::clamp(fraction, 0.0f, 1.0f);

    fraction = mInterpolator->interpolate(fraction);
    setValue(mTarget, mFromValue + (mDeltaValue * fraction));

    return playTime >= mDuration;
}

nsecs_t BaseRenderNodeAnimator::getRemainingPlayTime() {
    return mPlayState == PlayState::Reversing ? mPlayTime : mDuration - mPlayTime;
}

void BaseRenderNodeAnimator::forceEndNow(AnimationContext& context) {
    if (mPlayState < PlayState::Finished) {
        mPlayState = PlayState::Finished;
        callOnFinishedListener(context);
    }
}

void BaseRenderNodeAnimator::callOnFinishedListener(AnimationContext& context) {
    if (mListener.get()) {
        context.callOnFinished(this, mListener.get());
    }
}

/************************************************************
 *  RenderPropertyAnimator
 ************************************************************/

struct RenderPropertyAnimator::PropertyAccessors {
    RenderNode::DirtyPropertyMask dirtyMask;
    GetFloatProperty getter;
    SetFloatProperty setter;
};

// Maps RenderProperty enum to accessors
const RenderPropertyAnimator::PropertyAccessors RenderPropertyAnimator::PROPERTY_ACCESSOR_LUT[] = {
        {RenderNode::TRANSLATION_X, &RenderProperties::getTranslationX,
         &RenderProperties::setTranslationX},
        {RenderNode::TRANSLATION_Y, &RenderProperties::getTranslationY,
         &RenderProperties::setTranslationY},
        {RenderNode::TRANSLATION_Z, &RenderProperties::getTranslationZ,
         &RenderProperties::setTranslationZ},
        {RenderNode::SCALE_X, &RenderProperties::getScaleX, &RenderProperties::setScaleX},
        {RenderNode::SCALE_Y, &RenderProperties::getScaleY, &RenderProperties::setScaleY},
        {RenderNode::ROTATION, &RenderProperties::getRotation, &RenderProperties::setRotation},
        {RenderNode::ROTATION_X, &RenderProperties::getRotationX, &RenderProperties::setRotationX},
        {RenderNode::ROTATION_Y, &RenderProperties::getRotationY, &RenderProperties::setRotationY},
        {RenderNode::X, &RenderProperties::getX, &RenderProperties::setX},
        {RenderNode::Y, &RenderProperties::getY, &RenderProperties::setY},
        {RenderNode::Z, &RenderProperties::getZ, &RenderProperties::setZ},
        {RenderNode::ALPHA, &RenderProperties::getAlpha, &RenderProperties::setAlpha},
};

RenderPropertyAnimator::RenderPropertyAnimator(RenderProperty property, float finalValue)
        : BaseRenderNodeAnimator(finalValue), mPropertyAccess(&(PROPERTY_ACCESSOR_LUT[property])) {}

void RenderPropertyAnimator::onAttached() {
    if (!mHasStartValue && mStagingTarget->isPropertyFieldDirty(mPropertyAccess->dirtyMask)) {
        setStartValue((mStagingTarget->stagingProperties().*mPropertyAccess->getter)());
    }
}

void RenderPropertyAnimator::onStagingPlayStateChanged() {
    if (mStagingPlayState == PlayState::Running) {
        if (mStagingTarget) {
            (mStagingTarget->mutateStagingProperties().*mPropertyAccess->setter)(finalValue());
        } else {
            // In the case of start delay where stagingTarget has been sync'ed over and null'ed
            // we delay the properties update to push staging.
            mShouldUpdateStagingProperties = true;
        }
    } else if (mStagingPlayState == PlayState::Finished) {
        // We're being canceled, so make sure that whatever values the UI thread
        // is observing for us is pushed over
        mShouldSyncPropertyFields = true;
    }
}

void RenderPropertyAnimator::onPushStaging() {
    if (mShouldUpdateStagingProperties) {
        (mTarget->mutateStagingProperties().*mPropertyAccess->setter)(finalValue());
        mShouldUpdateStagingProperties = false;
    }

    if (mShouldSyncPropertyFields) {
        mTarget->setPropertyFieldsDirty(dirtyMask());
        mShouldSyncPropertyFields = false;
    }
}

uint32_t RenderPropertyAnimator::dirtyMask() {
    return mPropertyAccess->dirtyMask;
}

float RenderPropertyAnimator::getValue(RenderNode* target) const {
    return (target->properties().*mPropertyAccess->getter)();
}

void RenderPropertyAnimator::setValue(RenderNode* target, float value) {
    (target->animatorProperties().*mPropertyAccess->setter)(value);
}

/************************************************************
 *  CanvasPropertyPrimitiveAnimator
 ************************************************************/

CanvasPropertyPrimitiveAnimator::CanvasPropertyPrimitiveAnimator(CanvasPropertyPrimitive* property,
                                                                 float finalValue)
        : BaseRenderNodeAnimator(finalValue), mProperty(property) {}

float CanvasPropertyPrimitiveAnimator::getValue(RenderNode* target) const {
    return mProperty->value;
}

void CanvasPropertyPrimitiveAnimator::setValue(RenderNode* target, float value) {
    mProperty->value = value;
}

uint32_t CanvasPropertyPrimitiveAnimator::dirtyMask() {
    return RenderNode::DISPLAY_LIST;
}

/************************************************************
 *  CanvasPropertySkPaintAnimator
 ************************************************************/

CanvasPropertyPaintAnimator::CanvasPropertyPaintAnimator(CanvasPropertyPaint* property,
                                                         PaintField field, float finalValue)
        : BaseRenderNodeAnimator(finalValue), mProperty(property), mField(field) {}

float CanvasPropertyPaintAnimator::getValue(RenderNode* target) const {
    switch (mField) {
        case STROKE_WIDTH:
            return mProperty->value.getStrokeWidth();
        case ALPHA:
            return mProperty->value.getAlpha();
    }
    LOG_ALWAYS_FATAL("Unknown field %d", (int)mField);
    return -1;
}

static uint8_t to_uint8(float value) {
    int c = (int)(value + .5f);
    return static_cast<uint8_t>(c < 0 ? 0 : c > 255 ? 255 : c);
}

void CanvasPropertyPaintAnimator::setValue(RenderNode* target, float value) {
    switch (mField) {
        case STROKE_WIDTH:
            mProperty->value.setStrokeWidth(value);
            return;
        case ALPHA:
            mProperty->value.setAlpha(to_uint8(value));
            return;
    }
    LOG_ALWAYS_FATAL("Unknown field %d", (int)mField);
}

uint32_t CanvasPropertyPaintAnimator::dirtyMask() {
    return RenderNode::DISPLAY_LIST;
}

RevealAnimator::RevealAnimator(int centerX, int centerY, float startValue, float finalValue)
        : BaseRenderNodeAnimator(finalValue), mCenterX(centerX), mCenterY(centerY) {
    setStartValue(startValue);
}

float RevealAnimator::getValue(RenderNode* target) const {
    return target->properties().getRevealClip().getRadius();
}

void RevealAnimator::setValue(RenderNode* target, float value) {
    target->animatorProperties().mutableRevealClip().set(true, mCenterX, mCenterY, value);
}

uint32_t RevealAnimator::dirtyMask() {
    return RenderNode::GENERIC;
}

} /* namespace uirenderer */
} /* namespace android */
