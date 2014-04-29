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
#ifndef TREEINFO_H
#define TREEINFO_H

#include <cutils/compiler.h>
#include <utils/Timers.h>
#include <utils/StrongPointer.h>

namespace android {
namespace uirenderer {

class RenderPropertyAnimator;

class AnimationListener {
public:
    ANDROID_API virtual void onAnimationFinished(const sp<RenderPropertyAnimator>&) = 0;
protected:
    ANDROID_API virtual ~AnimationListener() {}
};

struct TreeInfo {
    // The defaults here should be safe for everyone but DrawFrameTask to use as-is.
    TreeInfo()
            : hasFunctors(false)
            , prepareTextures(false)
            , performStagingPush(true)
            , frameTimeMs(0)
            , evaluateAnimations(false)
            , hasAnimations(false)
            , animationListener(0)
    {}

    bool hasFunctors;
    bool prepareTextures;
    bool performStagingPush;

    // Animations
    nsecs_t frameTimeMs;
    bool evaluateAnimations;
    // This is only updated if evaluateAnimations is true
    bool hasAnimations;
    AnimationListener* animationListener;

    // TODO: Damage calculations
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* TREEINFO_H */
