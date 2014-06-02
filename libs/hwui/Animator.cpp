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

#include "RenderNode.h"
#include "RenderProperties.h"

namespace android {
namespace uirenderer {

/************************************************************
 *  BaseRenderNodeAnimator
 ************************************************************/

BaseRenderNodeAnimator::BaseRenderNodeAnimator(float finalValue)
        : mFinalValue(finalValue)
        , mDeltaValue(0)
        , mFromValue(0)
        , mInterpolator(0)
        , mPlayState(NEEDS_START)
        , mStartTime(0)
        , mDelayUntil(0)
        , mDuration(300)
        , mStartDelay(0) {

}

BaseRenderNodeAnimator::~BaseRenderNodeAnimator() {
    setInterpolator(NULL);
}

void BaseRenderNodeAnimator::setInterpolator(Interpolator* interpolator) {
    delete mInterpolator;
    mInterpolator = interpolator;
}

void BaseRenderNodeAnimator::setStartValue(float value) {
    LOG_ALWAYS_FATAL_IF(mPlayState != NEEDS_START,
            "Cannot set the start value after the animator has started!");
    mFromValue = value;
    mDeltaValue = (mFinalValue - mFromValue);
    mPlayState = PENDING;
}

void BaseRenderNodeAnimator::setupStartValueIfNecessary(RenderNode* target, TreeInfo& info) {
    if (mPlayState == NEEDS_START) {
        setStartValue(getValue(target));
    }
}

void BaseRenderNodeAnimator::setDuration(nsecs_t duration) {
    mDuration = duration;
}

void BaseRenderNodeAnimator::setStartDelay(nsecs_t startDelay) {
    mStartDelay = startDelay;
}

bool BaseRenderNodeAnimator::animate(RenderNode* target, TreeInfo& info) {
    if (mPlayState == PENDING && mStartDelay > 0 && mDelayUntil == 0) {
        mDelayUntil = info.frameTimeMs + mStartDelay;
        return false;
    }

    if (mDelayUntil > info.frameTimeMs) {
        return false;
    }

    if (mPlayState == PENDING) {
        mPlayState = RUNNING;
        mStartTime = info.frameTimeMs;
        // No interpolator was set, use the default
        if (!mInterpolator) {
            setInterpolator(Interpolator::createDefaultInterpolator());
        }
    }

    float fraction = 1.0f;
    if (mPlayState == RUNNING) {
        fraction = mDuration > 0 ? (float)(info.frameTimeMs - mStartTime) / mDuration : 1.0f;
        if (fraction >= 1.0f) {
            fraction = 1.0f;
            mPlayState = FINISHED;
        }
    }
    fraction = mInterpolator->interpolate(fraction);
    setValue(target, mFromValue + (mDeltaValue * fraction));

    if (mPlayState == FINISHED) {
        callOnFinishedListener(info);
        return true;
    }
    return false;
}

void BaseRenderNodeAnimator::callOnFinishedListener(TreeInfo& info) {
    if (mListener.get()) {
        if (!info.animationHook) {
            mListener->onAnimationFinished(this);
        } else {
            info.animationHook->callOnFinished(this, mListener.get());
        }
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
    {RenderNode::TRANSLATION_X, &RenderProperties::getTranslationX, &RenderProperties::setTranslationX },
    {RenderNode::TRANSLATION_Y, &RenderProperties::getTranslationY, &RenderProperties::setTranslationY },
    {RenderNode::TRANSLATION_X, &RenderProperties::getTranslationZ, &RenderProperties::setTranslationZ },
    {RenderNode::SCALE_X, &RenderProperties::getScaleX, &RenderProperties::setScaleX },
    {RenderNode::SCALE_Y, &RenderProperties::getScaleY, &RenderProperties::setScaleY },
    {RenderNode::ROTATION, &RenderProperties::getRotation, &RenderProperties::setRotation },
    {RenderNode::ROTATION_X, &RenderProperties::getRotationX, &RenderProperties::setRotationX },
    {RenderNode::ROTATION_Y, &RenderProperties::getRotationY, &RenderProperties::setRotationY },
    {RenderNode::X, &RenderProperties::getX, &RenderProperties::setX },
    {RenderNode::Y, &RenderProperties::getY, &RenderProperties::setY },
    {RenderNode::Z, &RenderProperties::getZ, &RenderProperties::setZ },
    {RenderNode::ALPHA, &RenderProperties::getAlpha, &RenderProperties::setAlpha },
};

RenderPropertyAnimator::RenderPropertyAnimator(RenderProperty property, float finalValue)
        : BaseRenderNodeAnimator(finalValue)
        , mPropertyAccess(&(PROPERTY_ACCESSOR_LUT[property])) {
}

void RenderPropertyAnimator::onAttached(RenderNode* target) {
    if (mPlayState == NEEDS_START
            && target->isPropertyFieldDirty(mPropertyAccess->dirtyMask)) {
        setStartValue((target->stagingProperties().*mPropertyAccess->getter)());
    }
    (target->mutateStagingProperties().*mPropertyAccess->setter)(finalValue());
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

CanvasPropertyPrimitiveAnimator::CanvasPropertyPrimitiveAnimator(
                CanvasPropertyPrimitive* property, float finalValue)
        : BaseRenderNodeAnimator(finalValue)
        , mProperty(property) {
}

float CanvasPropertyPrimitiveAnimator::getValue(RenderNode* target) const {
    return mProperty->value;
}

void CanvasPropertyPrimitiveAnimator::setValue(RenderNode* target, float value) {
    mProperty->value = value;
}

/************************************************************
 *  CanvasPropertySkPaintAnimator
 ************************************************************/

CanvasPropertyPaintAnimator::CanvasPropertyPaintAnimator(
                CanvasPropertyPaint* property, PaintField field, float finalValue)
        : BaseRenderNodeAnimator(finalValue)
        , mProperty(property)
        , mField(field) {
}

float CanvasPropertyPaintAnimator::getValue(RenderNode* target) const {
    switch (mField) {
    case STROKE_WIDTH:
        return mProperty->value.getStrokeWidth();
    case ALPHA:
        return mProperty->value.getAlpha();
    }
    LOG_ALWAYS_FATAL("Unknown field %d", (int) mField);
    return -1;
}

static uint8_t to_uint8(float value) {
    int c = (int) (value + .5f);
    return static_cast<uint8_t>( c < 0 ? 0 : c > 255 ? 255 : c );
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
    LOG_ALWAYS_FATAL("Unknown field %d", (int) mField);
}

} /* namespace uirenderer */
} /* namespace android */
