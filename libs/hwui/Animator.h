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

#include <memory>
#include <cutils/compiler.h>
#include <utils/RefBase.h>
#include <utils/StrongPointer.h>
#include <utils/Timers.h>

#include "utils/Macros.h"

#include <vector>

namespace android {
namespace uirenderer {

class AnimationContext;
class BaseRenderNodeAnimator;
class CanvasPropertyPrimitive;
class CanvasPropertyPaint;
class Interpolator;
class RenderNode;
class RenderProperties;

class AnimationListener : public VirtualLightRefBase {
public:
    ANDROID_API virtual void onAnimationFinished(BaseRenderNodeAnimator*) = 0;
protected:
    ANDROID_API virtual ~AnimationListener() {}
};

enum class RepeatMode {
    // These are the same values as the RESTART and REVERSE in ValueAnimator.java.
    Restart = 1,
    Reverse = 2
};

class BaseRenderNodeAnimator : public VirtualLightRefBase {
    PREVENT_COPY_AND_ASSIGN(BaseRenderNodeAnimator);
public:
    ANDROID_API void setStartValue(float value);
    ANDROID_API void setInterpolator(Interpolator* interpolator);
    ANDROID_API void setDuration(nsecs_t durationInMs);
    ANDROID_API nsecs_t duration() { return mDuration; }
    ANDROID_API void setStartDelay(nsecs_t startDelayInMs);
    ANDROID_API nsecs_t startDelay() { return mStartDelay; }
    ANDROID_API void setListener(AnimationListener* listener) {
        mListener = listener;
    }
    AnimationListener* listener() { return mListener.get(); }
    ANDROID_API void setAllowRunningAsync(bool mayRunAsync) {
        mMayRunAsync = mayRunAsync;
    }
    bool mayRunAsync() { return mMayRunAsync; }
    ANDROID_API void start();
    ANDROID_API virtual void reset();
    ANDROID_API void reverse();
    // Terminates the animation at its current progress.
    ANDROID_API void cancel();

    // Terminates the animation and skip to the end of the animation.
    ANDROID_API virtual void end();

    void attach(RenderNode* target);
    virtual void onAttached() {}
    void detach() { mTarget = nullptr; }
    ANDROID_API void pushStaging(AnimationContext& context);
    ANDROID_API bool animate(AnimationContext& context);

    // Returns the remaining time in ms for the animation. Note this should only be called during
    // an animation on RenderThread.
    ANDROID_API nsecs_t getRemainingPlayTime();

    bool isRunning() { return mPlayState == PlayState::Running
            || mPlayState == PlayState::Reversing; }
    bool isFinished() { return mPlayState == PlayState::Finished; }
    float finalValue() { return mFinalValue; }

    ANDROID_API virtual uint32_t dirtyMask() = 0;

    void forceEndNow(AnimationContext& context);
    RenderNode* target() { return mTarget; }
    RenderNode* stagingTarget() { return mStagingTarget; }

protected:
    // PlayState is used by mStagingPlayState and mPlayState to track the state initiated from UI
    // thread and Render Thread animation state, respectively.
    // From the UI thread, mStagingPlayState transition looks like
    // NotStarted -> Running/Reversing -> Finished
    //                ^                     |
    //                |                     |
    //                ----------------------
    // Note: For mStagingState, the Finished state (optional) is only set when the animation is
    // terminated by user.
    //
    // On Render Thread, mPlayState transition:
    // NotStart -> Running/Reversing-> Finished
    //                ^                 |
    //                |                 |
    //                ------------------
    // Note that if the animation is in Running/Reversing state, calling start or reverse again
    // would do nothing if the animation has the same play direction as the request; otherwise,
    // the animation would start from where it is and change direction (i.e. Reversing <-> Running)

    enum class PlayState {
        NotStarted,
        Running,
        Reversing,
        Finished,
    };

    BaseRenderNodeAnimator(float finalValue);
    virtual ~BaseRenderNodeAnimator();

    virtual float getValue(RenderNode* target) const = 0;
    virtual void setValue(RenderNode* target, float value) = 0;

    void callOnFinishedListener(AnimationContext& context);

    virtual void onStagingPlayStateChanged() {}
    virtual void onPlayTimeChanged(nsecs_t playTime) {}
    virtual void onPushStaging() {}

    RenderNode* mTarget;
    RenderNode* mStagingTarget;

    float mFinalValue;
    float mDeltaValue;
    float mFromValue;

    std::unique_ptr<Interpolator> mInterpolator;
    PlayState mStagingPlayState;
    PlayState mPlayState;
    bool mHasStartValue;
    nsecs_t mStartTime;
    nsecs_t mDuration;
    nsecs_t mStartDelay;
    bool mMayRunAsync;
    // Play Time tracks the progress of animation, it should always be [0, mDuration], 0 being
    // the beginning of the animation, will reach mDuration at the end of an animation.
    nsecs_t mPlayTime;

    sp<AnimationListener> mListener;

private:
    enum class Request {
        Start,
        Reverse,
        Reset,
        Cancel,
        End
    };

    // Defines different actions upon finish.
    enum class Action {
        // For animations that got canceled or finished normally. no more action needs to be done.
        None,
        // For animations that get reset, the reset will happen in the next animation pulse.
        Reset,
        // For animations being ended, in the next animation pulse the animation will skip to end.
        End
    };

    inline void checkMutable();
    virtual void transitionToRunning(AnimationContext& context);
    void doSetStartValue(float value);
    bool updatePlayTime(nsecs_t playTime);
    void resolveStagingRequest(Request request);

    std::vector<Request> mStagingRequests;
    Action mPendingActionUponFinish = Action::None;
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

    ANDROID_API RenderPropertyAnimator(RenderProperty property, float finalValue);

    ANDROID_API virtual uint32_t dirtyMask();

protected:
    virtual float getValue(RenderNode* target) const override;
    virtual void setValue(RenderNode* target, float value) override;
    virtual void onAttached() override;
    virtual void onStagingPlayStateChanged() override;
    virtual void onPushStaging() override;

private:
    typedef bool (RenderProperties::*SetFloatProperty)(float value);
    typedef float (RenderProperties::*GetFloatProperty)() const;

    struct PropertyAccessors;
    const PropertyAccessors* mPropertyAccess;

    static const PropertyAccessors PROPERTY_ACCESSOR_LUT[];
    bool mShouldSyncPropertyFields = false;
    bool mShouldUpdateStagingProperties = false;
};

class CanvasPropertyPrimitiveAnimator : public BaseRenderNodeAnimator {
public:
    ANDROID_API CanvasPropertyPrimitiveAnimator(CanvasPropertyPrimitive* property,
            float finalValue);

    ANDROID_API virtual uint32_t dirtyMask();

protected:
    virtual float getValue(RenderNode* target) const override;
    virtual void setValue(RenderNode* target, float value) override;
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
            PaintField field, float finalValue);

    ANDROID_API virtual uint32_t dirtyMask();

protected:
    virtual float getValue(RenderNode* target) const override;
    virtual void setValue(RenderNode* target, float value) override;
private:
    sp<CanvasPropertyPaint> mProperty;
    PaintField mField;
};

class RevealAnimator : public BaseRenderNodeAnimator {
public:
    ANDROID_API RevealAnimator(int centerX, int centerY,
            float startValue, float finalValue);

    ANDROID_API virtual uint32_t dirtyMask();

protected:
    virtual float getValue(RenderNode* target) const override;
    virtual void setValue(RenderNode* target, float value) override;

private:
    int mCenterX, mCenterY;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* ANIMATOR_H */
