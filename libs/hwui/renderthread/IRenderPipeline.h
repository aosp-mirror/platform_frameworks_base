/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "FrameInfoVisualizer.h"
#include "LayerUpdateQueue.h"
#include "SwapBehavior.h"
#include "hwui/Bitmap.h"
#include "thread/TaskManager.h"

#include <SkRect.h>
#include <utils/RefBase.h>

class GrContext;

namespace android {

class Surface;

namespace uirenderer {

class DeferredLayerUpdater;
class ErrorHandler;

namespace renderthread {

enum class MakeCurrentResult { AlreadyCurrent, Failed, Succeeded };

enum class ColorMode {
    Srgb,
    WideColorGamut,
    // Hdr
};

class Frame;

class IRenderPipeline {
public:
    virtual MakeCurrentResult makeCurrent() = 0;
    virtual Frame getFrame() = 0;
    virtual bool draw(const Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
                      const LightGeometry& lightGeometry,
                      LayerUpdateQueue* layerUpdateQueue, const Rect& contentDrawBounds,
                      bool opaque, bool wideColorGamut, const LightInfo& lightInfo,
                      const std::vector<sp<RenderNode>>& renderNodes,
                      FrameInfoVisualizer* profiler) = 0;
    virtual bool swapBuffers(const Frame& frame, bool drew, const SkRect& screenDirty,
                             FrameInfo* currentFrameInfo, bool* requireSwap) = 0;
    virtual bool copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap) = 0;
    virtual DeferredLayerUpdater* createTextureLayer() = 0;
    virtual bool setSurface(Surface* window, SwapBehavior swapBehavior, ColorMode colorMode) = 0;
    virtual void onStop() = 0;
    virtual bool isSurfaceReady() = 0;
    virtual bool isContextReady() = 0;
    virtual void onDestroyHardwareResources() = 0;
    virtual void renderLayers(const LightGeometry& lightGeometry,
                              LayerUpdateQueue* layerUpdateQueue, bool opaque, bool wideColorGamut,
                              const LightInfo& lightInfo) = 0;
    virtual TaskManager* getTaskManager() = 0;
    virtual bool createOrUpdateLayer(RenderNode* node, const DamageAccumulator& damageAccumulator,
                                     bool wideColorGamut, ErrorHandler* errorHandler) = 0;
    virtual bool pinImages(std::vector<SkImage*>& mutableImages) = 0;
    virtual bool pinImages(LsaVector<sk_sp<Bitmap>>& images) = 0;
    virtual void unpinImages() = 0;
    virtual void onPrepareTree() = 0;

    virtual ~IRenderPipeline() {}
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
