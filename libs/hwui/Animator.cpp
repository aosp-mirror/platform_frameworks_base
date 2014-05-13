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

bool BaseAnimator::animateFrame(TreeInfo& info) {
    if (mPlayState == PENDING) {
        mPlayState = RUNNING;
        mStartTime = info.frameTimeMs;
        // No interpolator was set, use the default
        if (!mInterpolator) {
            setInterpolator(Interpolator::createDefaultInterpolator());
        }
        onAnimationStarted();
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
    onAnimationUpdated(fraction);

    if (mPlayState == FINISHED) {
        onAnimationFinished();
        callOnFinishedListener(info);
        return true;
    }
    return false;
}

void BaseAnimator::callOnFinishedListener(TreeInfo& info) {
    if (mListener.get()) {
        if (!info.animationHook) {
            mListener->onAnimationFinished(this);
        } else {
            info.animationHook->callOnFinished(this, mListener.get());
        }
    }
}

/************************************************************
 *  BaseRenderNodeAnimator
 ************************************************************/

BaseRenderNodeAnimator::BaseRenderNodeAnimator(
                BaseRenderNodeAnimator::DeltaValueType deltaType, float delta)
        : mTarget(0)
        , mDeltaValueType(deltaType)
        , mDeltaValue(delta)
        , mFromValue(-1) {
}

bool BaseRenderNodeAnimator::animate(RenderNode* target, TreeInfo& info) {
    mTarget = target;
    bool finished = animateFrame(info);
    mTarget = NULL;
    return finished;
}

void BaseRenderNodeAnimator::onAnimationStarted() {
    mFromValue = getValue();

    if (mDeltaValueType == BaseRenderNodeAnimator::ABSOLUTE) {
        mDeltaValue = (mDeltaValue - mFromValue);
        mDeltaValueType = BaseRenderNodeAnimator::DELTA;
    }
}

void BaseRenderNodeAnimator::onAnimationUpdated(float fraction) {
    float value = mFromValue + (mDeltaValue * fraction);
    setValue(value);
}

/************************************************************
 *  RenderPropertyAnimator
 ************************************************************/

// Maps RenderProperty enum to accessors
const RenderPropertyAnimator::PropertyAccessors RenderPropertyAnimator::PROPERTY_ACCESSOR_LUT[] = {
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

RenderPropertyAnimator::RenderPropertyAnimator(RenderProperty property,
                DeltaValueType deltaType, float deltaValue)
        : BaseRenderNodeAnimator(deltaType, deltaValue)
        , mPropertyAccess(PROPERTY_ACCESSOR_LUT[property]) {
}

float RenderPropertyAnimator::getValue() const {
    return (target()->animatorProperties().*mPropertyAccess.getter)();
}

void RenderPropertyAnimator::setValue(float value) {
    (target()->animatorProperties().*mPropertyAccess.setter)(value);
}

/************************************************************
 *  CanvasPropertyPrimitiveAnimator
 ************************************************************/

CanvasPropertyPrimitiveAnimator::CanvasPropertyPrimitiveAnimator(
                CanvasPropertyPrimitive* property, DeltaValueType deltaType, float deltaValue)
        : BaseRenderNodeAnimator(deltaType, deltaValue)
        , mProperty(property) {
}

float CanvasPropertyPrimitiveAnimator::getValue() const {
    return mProperty->value;
}

void CanvasPropertyPrimitiveAnimator::setValue(float value) {
    mProperty->value = value;
}

/************************************************************
 *  CanvasPropertySkPaintAnimator
 ************************************************************/

CanvasPropertyPaintAnimator::CanvasPropertyPaintAnimator(
                CanvasPropertyPaint* property, PaintField field,
                DeltaValueType deltaType, float deltaValue)
        : BaseRenderNodeAnimator(deltaType, deltaValue)
        , mProperty(property)
        , mField(field) {
}

float CanvasPropertyPaintAnimator::getValue() const {
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

void CanvasPropertyPaintAnimator::setValue(float value) {
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
