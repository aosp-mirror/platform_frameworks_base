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

#include "BakedOpDispatcher.h"
#include "BakedOpRenderer.h"
#include "CanvasContext.h"
#include "FrameBuilder.h"
#include "IRenderPipeline.h"

namespace android {
namespace uirenderer {
namespace renderthread {

class OpenGLPipeline : public IRenderPipeline {
public:
    OpenGLPipeline(RenderThread& thread);
    virtual ~OpenGLPipeline() {}

    MakeCurrentResult makeCurrent() override;
    Frame getFrame() override;
    bool draw(const Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
              const FrameBuilder::LightGeometry& lightGeometry, LayerUpdateQueue* layerUpdateQueue,
              const Rect& contentDrawBounds, bool opaque, bool wideColorGamut,
              const BakedOpRenderer::LightInfo& lightInfo,
              const std::vector<sp<RenderNode>>& renderNodes,
              FrameInfoVisualizer* profiler) override;
    bool swapBuffers(const Frame& frame, bool drew, const SkRect& screenDirty,
                     FrameInfo* currentFrameInfo, bool* requireSwap) override;
    bool copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap) override;
    DeferredLayerUpdater* createTextureLayer() override;
    bool setSurface(Surface* window, SwapBehavior swapBehavior, ColorMode colorMode) override;
    void onStop() override;
    bool isSurfaceReady() override;
    bool isContextReady() override;
    void onDestroyHardwareResources() override;
    void renderLayers(const FrameBuilder::LightGeometry& lightGeometry,
                      LayerUpdateQueue* layerUpdateQueue, bool opaque, bool wideColorGamut,
                      const BakedOpRenderer::LightInfo& lightInfo) override;
    TaskManager* getTaskManager() override;
    bool createOrUpdateLayer(RenderNode* node, const DamageAccumulator& damageAccumulator,
                             bool wideColorGamut, ErrorHandler* errorHandler) override;
    bool pinImages(std::vector<SkImage*>& mutableImages) override { return false; }
    bool pinImages(LsaVector<sk_sp<Bitmap>>& images) override;
    void unpinImages() override;
    void onPrepareTree() override {}
    static void destroyLayer(RenderNode* node);
    static void prepareToDraw(const RenderThread& thread, Bitmap* bitmap);
    static void invokeFunctor(const RenderThread& thread, Functor* functor);
    static sk_sp<Bitmap> allocateHardwareBitmap(RenderThread& thread, SkBitmap& skBitmap);

private:
    EglManager& mEglManager;
    EGLSurface mEglSurface = EGL_NO_SURFACE;
    bool mBufferPreserved = false;
    RenderThread& mRenderThread;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
