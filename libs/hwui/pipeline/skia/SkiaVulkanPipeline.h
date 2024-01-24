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

#include "SkRefCnt.h"
#include "SkiaPipeline.h"
#include "renderstate/RenderState.h"
#include "renderthread/HardwareBufferRenderParams.h"
#include "renderthread/VulkanManager.h"
#include "renderthread/VulkanSurface.h"

class SkBitmap;
struct SkRect;

namespace android {
namespace uirenderer {
namespace skiapipeline {

class SkiaVulkanPipeline : public SkiaPipeline, public IGpuContextCallback {
public:
    explicit SkiaVulkanPipeline(renderthread::RenderThread& thread);
    virtual ~SkiaVulkanPipeline();

    renderthread::MakeCurrentResult makeCurrent() override;
    renderthread::Frame getFrame() override;
    renderthread::IRenderPipeline::DrawResult draw(
            const renderthread::Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
            const LightGeometry& lightGeometry, LayerUpdateQueue* layerUpdateQueue,
            const Rect& contentDrawBounds, bool opaque, const LightInfo& lightInfo,
            const std::vector<sp<RenderNode> >& renderNodes, FrameInfoVisualizer* profiler,
            const renderthread::HardwareBufferRenderParams& bufferParams,
            std::mutex& profilerLock) override;
    GrSurfaceOrigin getSurfaceOrigin() override { return kTopLeft_GrSurfaceOrigin; }
    bool swapBuffers(const renderthread::Frame& frame, bool drew, const SkRect& screenDirty,
                     FrameInfo* currentFrameInfo, bool* requireSwap) override;
    DeferredLayerUpdater* createTextureLayer() override;
    [[nodiscard]] android::base::unique_fd flush() override;

    bool setSurface(ANativeWindow* surface, renderthread::SwapBehavior swapBehavior) override;
    void onStop() override;
    bool isSurfaceReady() override;
    bool isContextReady() override;
    void setTargetSdrHdrRatio(float ratio) override;
    const SkM44& getPixelSnapMatrix() const override;

    static void invokeFunctor(const renderthread::RenderThread& thread, Functor* functor);
    static sk_sp<Bitmap> allocateHardwareBitmap(renderthread::RenderThread& thread,
                                                SkBitmap& skBitmap);

protected:
    void onContextDestroyed() override;

private:
    renderthread::VulkanManager& vulkanManager();
    renderthread::VulkanSurface* mVkSurface = nullptr;
    sp<ANativeWindow> mNativeWindow;
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
