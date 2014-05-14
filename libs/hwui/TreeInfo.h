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

#include <utils/Timers.h>

namespace android {
namespace uirenderer {

class BaseRenderNodeAnimator;
class AnimationListener;

class AnimationHook {
public:
    virtual void callOnFinished(BaseRenderNodeAnimator* animator, AnimationListener* listener) = 0;
protected:
    ~AnimationHook() {}
};

struct TreeInfo {
    // The defaults here should be safe for everyone but DrawFrameTask to use as-is.
    TreeInfo()
        : frameTimeMs(0)
        , animationHook(NULL)
        , prepareTextures(false)
        , performStagingPush(true)
        , evaluateAnimations(false)
    {}

    nsecs_t frameTimeMs;
    AnimationHook* animationHook;
    bool prepareTextures;
    bool performStagingPush;
    bool evaluateAnimations;

    struct Out {
        Out()
            : hasFunctors(false)
            , hasAnimations(false)
            , requiresUiRedraw(false)
        {}
        bool hasFunctors;
        // This is only updated if evaluateAnimations is true
        bool hasAnimations;
        // This is set to true if there is an animation that RenderThread cannot
        // animate itself, such as if hasFunctors is true
        // This is only set if hasAnimations is true
        bool requiresUiRedraw;
    } out;

    // TODO: Damage calculations
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* TREEINFO_H */
