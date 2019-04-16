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

#include "DamageAccumulator.h"
#include "FrameInfoVisualizer.h"
#include "LayerUpdateQueue.h"
#include "Lighting.h"
#include "SwapBehavior.h"
#include "hwui/Bitmap.h"

#include <SkRect.h>
#include <utils/RefBase.h>

class GrContext;

struct ANativeWindow;

namespace android {

namespace uirenderer {

class DeferredLayerUpdater;
class ErrorHandler;
class TaskManager;

namespace renderthread {

enum class MakeCurrentResult { AlreadyCurrent, Failed, Succeeded };

enum class ColorMode {
    // SRGB means HWUI will produce buffer in SRGB color space.
    SRGB,
    // WideColorGamut means HWUI would support rendering scRGB non-linear into
    // a signed buffer with enough range to support the wide color gamut of the
    // display.
    WideColorGamut,
    // Hdr
};

class Frame;

class IRenderPipeline {
public:
    virtual MakeCurrentResult makeCurrent() = 0;
    virtual Frame getFrame() = 0;
    virtual bool draw(const Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
                      const LightGeometry& lightGeometry, LayerUpdateQueue* layerUpdateQueue,
                      const Rect& contentDrawBounds, bool opaque, const LightInfo& lightInfo,
                      const std::vector<sp<RenderNode>>& renderNodes,
                      FrameInfoVisualizer* profiler) = 0;
    virtual bool swapBuffers(const Frame& frame, bool drew, const SkRect& screenDirty,
                             FrameInfo* currentFrameInfo, bool* requireSwap) = 0;
    virtual DeferredLayerUpdater* createTextureLayer() = 0;
    virtual bool setSurface(ANativeWindow* window, SwapBehavior swapBehavior, ColorMode colorMode,
                            uint32_t extraBuffers) = 0;
    virtual void onStop() = 0;
    virtual bool isSurfaceReady() = 0;
    virtual bool isContextReady() = 0;
    virtual void onDestroyHardwareResources() = 0;
    virtual void renderLayers(const LightGeometry& lightGeometry,
                              LayerUpdateQueue* layerUpdateQueue, bool opaque,
                              const LightInfo& lightInfo) = 0;
    virtual bool createOrUpdateLayer(RenderNode* node, const DamageAccumulator& damageAccumulator,
                                     ErrorHandler* errorHandler) = 0;
    virtual bool pinImages(std::vector<SkImage*>& mutableImages) = 0;
    virtual bool pinImages(LsaVector<sk_sp<Bitmap>>& images) = 0;
    virtual void unpinImages() = 0;
    virtual void onPrepareTree() = 0;
    virtual SkColorType getSurfaceColorType() const = 0;
    virtual sk_sp<SkColorSpace> getSurfaceColorSpace() = 0;
    virtual GrSurfaceOrigin getSurfaceOrigin() = 0;
    virtual void setPictureCapturedCallback(
            const std::function<void(sk_sp<SkPicture>&&)>& callback) = 0;

    virtual ~IRenderPipeline() {}
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
