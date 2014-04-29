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

#include "Interpolator.h"
#include "TreeInfo.h"
#include "utils/VirtualLightRefBase.h"

namespace android {
namespace uirenderer {

class RenderProperties;
class RenderPropertyAnimatorImpl;

class RenderPropertyAnimator : public VirtualLightRefBase {
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

    ANDROID_API void setInterpolator(Interpolator* interpolator);
    ANDROID_API void setDuration(nsecs_t durationInMs);
    ANDROID_API bool isFinished();

    bool animate(RenderProperties* target, TreeInfo& info);

protected:
    ANDROID_API RenderPropertyAnimator(RenderProperty property, DeltaValueType deltaType,
            float deltaValue);
    ANDROID_API virtual ~RenderPropertyAnimator();

private:
    RenderPropertyAnimatorImpl* mImpl;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* ANIMATOR_H */
