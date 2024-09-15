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

#include "pipeline/skia/SkiaPipeline.h"
#include "renderthread/Frame.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

class SkiaGpuPipeline : public SkiaPipeline {
public:
    SkiaGpuPipeline(renderthread::RenderThread& thread) : SkiaPipeline(thread) {}
    ~SkiaGpuPipeline() {}

    bool pinImages(std::vector<SkImage*>& mutableImages) override { return false; }
    bool pinImages(LsaVector<sk_sp<Bitmap>>& images) override { return false; }
    void unpinImages() override {}

    // If the given node didn't have a layer surface, or had one of the wrong size, this method
    // creates a new one and returns true. Otherwise does nothing and returns false.
    bool createOrUpdateLayer(RenderNode* node, const DamageAccumulator& damageAccumulator,
                             ErrorHandler* errorHandler) override {
        return false;
    }
    void renderLayersImpl(const LayerUpdateQueue& layers, bool opaque) override {}
    void setHardwareBuffer(AHardwareBuffer* hardwareBuffer) override {}
    bool hasHardwareBuffer() override { return false; }

    renderthread::MakeCurrentResult makeCurrent() override {
        return renderthread::MakeCurrentResult::Failed;
    }
    renderthread::Frame getFrame() override { return renderthread::Frame(0, 0, 0); }
    renderthread::IRenderPipeline::DrawResult draw(
            const renderthread::Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
            const LightGeometry& lightGeometry, LayerUpdateQueue* layerUpdateQueue,
            const Rect& contentDrawBounds, bool opaque, const LightInfo& lightInfo,
            const std::vector<sp<RenderNode>>& renderNodes, FrameInfoVisualizer* profiler,
            const renderthread::HardwareBufferRenderParams& bufferParams,
            std::mutex& profilerLock) override {
        return {false, IRenderPipeline::DrawResult::kUnknownTime, android::base::unique_fd(-1)};
    }
    bool swapBuffers(const renderthread::Frame& frame, IRenderPipeline::DrawResult& drawResult,
                     const SkRect& screenDirty, FrameInfo* currentFrameInfo,
                     bool* requireSwap) override {
        return false;
    }
    DeferredLayerUpdater* createTextureLayer() override { return nullptr; }
    bool setSurface(ANativeWindow* surface, renderthread::SwapBehavior swapBehavior) override {
        return false;
    }
    [[nodiscard]] android::base::unique_fd flush() override {
        return android::base::unique_fd(-1);
    };
    void onStop() override {}
    bool isSurfaceReady() override { return false; }
    bool isContextReady() override { return false; }

    const SkM44& getPixelSnapMatrix() const override {
        static const SkM44 sSnapMatrix = SkM44();
        return sSnapMatrix;
    }
    static void prepareToDraw(const renderthread::RenderThread& thread, Bitmap* bitmap) {}
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
