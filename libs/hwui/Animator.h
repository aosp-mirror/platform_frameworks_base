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
#ifndef ANIMATOR_H
#define ANIMATOR_H

#include <cutils/compiler.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>

#include "CanvasProperty.h"
#include "Interpolator.h"
#include "TreeInfo.h"
#include "utils/Macros.h"

namespace android {
namespace uirenderer {

class RenderNode;
class RenderProperties;

class AnimationListener : public VirtualLightRefBase {
public:
    ANDROID_API virtual void onAnimationFinished(BaseAnimator*) = 0;
protected:
    ANDROID_API virtual ~AnimationListener() {}
};

// Helper class to contain generic animator helpers
class BaseAnimator : public VirtualLightRefBase {
    PREVENT_COPY_AND_ASSIGN(BaseAnimator);
public:

    ANDROID_API void setInterpolator(Interpolator* interpolator);
    ANDROID_API void setDuration(nsecs_t durationInMs);
    ANDROID_API nsecs_t duration() { return mDuration; }
    ANDROID_API void setListener(AnimationListener* listener) {
        mListener = listener;
    }

    bool isFinished() { return mPlayState == FINISHED; }

protected:
    BaseAnimator();
    virtual ~BaseAnimator();

    // This is the main animation entrypoint that subclasses should call
    // to generate the onAnimation* lifecycle events
    // Returns true if the animation has finished, false otherwise
    bool animateFrame(TreeInfo& info);

    // Called when PlayState switches from PENDING to RUNNING
    virtual void onAnimationStarted() {}
    virtual void onAnimationUpdated(float fraction) = 0;
    virtual void onAnimationFinished() {}

private:
    void callOnFinishedListener(TreeInfo& info);

    enum PlayState {
        PENDING,
        RUNNING,
        FINISHED,
    };

    Interpolator* mInterpolator;
    PlayState mPlayState;
    long mStartTime;
    long mDuration;

   sp<AnimationListener> mListener;
};

class BaseRenderNodeAnimator : public BaseAnimator {
public:
    // Since the UI thread doesn't necessarily know what the current values
    // actually are and thus can't do the calculations, this is used to inform
    // the animator how to lazy-resolve the input value
    enum DeltaValueType {
        // The delta value represents an absolute value endpoint
        // mDeltaValue needs to be recalculated to be mDelta = (mDelta - fromValue)
        // in onAnimationStarted()
        ABSOLUTE = 0,
        // The final value represents an offset from the current value
        // No recalculation is needed
        DELTA,
    };

    bool animate(RenderNode* target, TreeInfo& info);

protected:
    BaseRenderNodeAnimator(DeltaValueType deltaType, float deltaValue);

    RenderNode* target() const { return mTarget; }
    virtual float getValue() const = 0;
    virtual void setValue(float value) = 0;

private:
    virtual void onAnimationStarted();
    virtual void onAnimationUpdated(float fraction);

    // mTarget is only valid inside animate()
    RenderNode* mTarget;

    BaseRenderNodeAnimator::DeltaValueType mDeltaValueType;
    float mDeltaValue;
    float mFromValue;
};

class RenderPropertyAnimator : public BaseRenderNodeAnimator {
public:
    enum RenderProperty {
        TRANSLATION_X = 0,
        TRANSLATION_Y,
        TRANSLATION_Z,
        SCALE_X,
        SCALE_Y,
        ROTATION,
        ROTATION_X,
        ROTATION_Y,
        X,
        Y,
        Z,
        ALPHA,
    };

    ANDROID_API RenderPropertyAnimator(RenderProperty property,
                DeltaValueType deltaType, float deltaValue);

protected:
    ANDROID_API virtual float getValue() const;
    ANDROID_API virtual void setValue(float value);

private:
    typedef void (RenderProperties::*SetFloatProperty)(float value);
    typedef float (RenderProperties::*GetFloatProperty)() const;

    struct PropertyAccessors {
        GetFloatProperty getter;
        SetFloatProperty setter;
    };

    PropertyAccessors mPropertyAccess;

    static const PropertyAccessors PROPERTY_ACCESSOR_LUT[];
};

class CanvasPropertyPrimitiveAnimator : public BaseRenderNodeAnimator {
public:
    ANDROID_API CanvasPropertyPrimitiveAnimator(CanvasPropertyPrimitive* property,
            DeltaValueType deltaType, float deltaValue);
protected:
    ANDROID_API virtual float getValue() const;
    ANDROID_API virtual void setValue(float value);
private:
    sp<CanvasPropertyPrimitive> mProperty;
};

class CanvasPropertyPaintAnimator : public BaseRenderNodeAnimator {
public:
    enum PaintField {
        STROKE_WIDTH = 0,
        ALPHA,
    };

    ANDROID_API CanvasPropertyPaintAnimator(CanvasPropertyPaint* property,
            PaintField field, DeltaValueType deltaType, float deltaValue);
protected:
    ANDROID_API virtual float getValue() const;
    ANDROID_API virtual void setValue(float value);
private:
    sp<CanvasPropertyPaint> mProperty;
    PaintField mField;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* ANIMATOR_H */
