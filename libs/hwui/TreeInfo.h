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

#include <string>

#include <utils/Timers.h>

#include "DamageAccumulator.h"
#include "utils/Macros.h"

namespace android {
namespace uirenderer {

class BaseRenderNodeAnimator;
class AnimationListener;
class OpenGLRenderer;
class RenderState;

class AnimationHook {
public:
    virtual void callOnFinished(BaseRenderNodeAnimator* animator, AnimationListener* listener) = 0;
protected:
    ~AnimationHook() {}
};

class ErrorHandler {
public:
    virtual void onError(const std::string& message) = 0;
protected:
    ~ErrorHandler() {}
};

// This would be a struct, but we want to PREVENT_COPY_AND_ASSIGN
class TreeInfo {
    PREVENT_COPY_AND_ASSIGN(TreeInfo);
public:
    enum TraversalMode {
        // The full monty - sync, push, run animators, etc... Used by DrawFrameTask
        // May only be used if both the UI thread and RT thread are blocked on the
        // prepare
        MODE_FULL,
        // Run only what can be done safely on RT thread. Currently this only means
        // animators, but potentially things like SurfaceTexture updates
        // could be handled by this as well if there are no listeners
        MODE_RT_ONLY,
    };

    explicit TreeInfo(TraversalMode mode, RenderState& renderState)
        : mode(mode)
        , frameTimeMs(0)
        , animationHook(NULL)
        , prepareTextures(mode == MODE_FULL)
        , runAnimations(true)
        , damageAccumulator(NULL)
        , renderState(renderState)
        , renderer(NULL)
        , errorHandler(NULL)
    {}

    explicit TreeInfo(TraversalMode mode, const TreeInfo& clone)
        : mode(mode)
        , frameTimeMs(clone.frameTimeMs)
        , animationHook(clone.animationHook)
        , prepareTextures(mode == MODE_FULL)
        , runAnimations(clone.runAnimations)
        , damageAccumulator(clone.damageAccumulator)
        , renderState(clone.renderState)
        , renderer(clone.renderer)
        , errorHandler(clone.errorHandler)
    {}

    const TraversalMode mode;
    nsecs_t frameTimeMs;
    AnimationHook* animationHook;
    // TODO: Remove this? Currently this is used to signal to stop preparing
    // textures if we run out of cache space.
    bool prepareTextures;
    // TODO: buildLayer uses this to suppress running any animations, but this
    // should probably be refactored somehow. The reason this is done is
    // because buildLayer is not setup for injecting the animationHook, as well
    // as this being otherwise wasted work as all the animators will be
    // re-evaluated when the frame is actually drawn
    bool runAnimations;

    // Must not be null during actual usage
    DamageAccumulator* damageAccumulator;
    RenderState& renderState;
    // The renderer that will be drawing the next frame. Use this to push any
    // layer updates or similar. May be NULL.
    OpenGLRenderer* renderer;
    ErrorHandler* errorHandler;

    struct Out {
        Out()
            : hasFunctors(false)
            , hasAnimations(false)
            , requiresUiRedraw(false)
            , canDrawThisFrame(true)
        {}
        bool hasFunctors;
        // This is only updated if evaluateAnimations is true
        bool hasAnimations;
        // This is set to true if there is an animation that RenderThread cannot
        // animate itself, such as if hasFunctors is true
        // This is only set if hasAnimations is true
        bool requiresUiRedraw;
        // This is set to true if draw() can be called this frame
        // false means that we must delay until the next vsync pulse as frame
        // production is outrunning consumption
        // NOTE that if this is false CanvasContext will set either requiresUiRedraw
        // *OR* will post itself for the next vsync automatically, use this
        // only to avoid calling draw()
        bool canDrawThisFrame;
    } out;

    // TODO: Damage calculations
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* TREEINFO_H */
