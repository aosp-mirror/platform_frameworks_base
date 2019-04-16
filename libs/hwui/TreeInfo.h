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

#pragma once

#include "Properties.h"
#include "utils/Macros.h"

#include <utils/Timers.h>
#include "SkSize.h"

#include <string>

namespace android {
namespace uirenderer {

namespace renderthread {
class CanvasContext;
}

class DamageAccumulator;
class LayerUpdateQueue;
class RenderNode;
class RenderState;

class ErrorHandler {
public:
    virtual void onError(const std::string& message) = 0;

protected:
    virtual ~ErrorHandler() = default;
};

class TreeObserver {
public:
    // Called when a RenderNode's parent count hits 0.
    // Due to the unordered nature of tree pushes, once prepareTree
    // is finished it is possible that the node was "resurrected" and has
    // a non-zero parent count.
    virtual void onMaybeRemovedFromTree(RenderNode* node) = 0;

protected:
    virtual ~TreeObserver() = default;
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

    TreeInfo(TraversalMode mode, renderthread::CanvasContext& canvasContext);

    TraversalMode mode;
    // TODO: Remove this? Currently this is used to signal to stop preparing
    // textures if we run out of cache space.
    bool prepareTextures;
    renderthread::CanvasContext& canvasContext;
    // TODO: buildLayer uses this to suppress running any animations, but this
    // should probably be refactored somehow. The reason this is done is
    // because buildLayer is not setup for injecting the animationHook, as well
    // as this being otherwise wasted work as all the animators will be
    // re-evaluated when the frame is actually drawn
    bool runAnimations = true;

    // Must not be null during actual usage
    DamageAccumulator* damageAccumulator = nullptr;
    int64_t damageGenerationId = 0;

    LayerUpdateQueue* layerUpdateQueue = nullptr;
    ErrorHandler* errorHandler = nullptr;

    bool updateWindowPositions = false;

    int disableForceDark;

    const SkISize screenSize;

    struct Out {
        bool hasFunctors = false;
        // This is only updated if evaluateAnimations is true
        bool hasAnimations = false;
        // This is set to true if there is an animation that RenderThread cannot
        // animate itself, such as if hasFunctors is true
        // This is only set if hasAnimations is true
        bool requiresUiRedraw = false;
        // This is set to true if draw() can be called this frame
        // false means that we must delay until the next vsync pulse as frame
        // production is outrunning consumption
        // NOTE that if this is false CanvasContext will set either requiresUiRedraw
        // *OR* will post itself for the next vsync automatically, use this
        // only to avoid calling draw()
        bool canDrawThisFrame = true;
        // Sentinel for animatedImageDelay meaning there is no need to post such
        // a message.
        static constexpr nsecs_t kNoAnimatedImageDelay = -1;
        // This is used to post a message to redraw when it is time to draw the
        // next frame of an AnimatedImageDrawable.
        nsecs_t animatedImageDelay = kNoAnimatedImageDelay;
    } out;

    // This flag helps to disable projection for receiver nodes that do not have any backward
    // projected children.
    bool hasBackwardProjectedNodes = false;
    // TODO: Damage calculations
};

} /* namespace uirenderer */
} /* namespace android */
