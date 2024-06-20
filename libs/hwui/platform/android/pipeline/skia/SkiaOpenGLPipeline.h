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

#include <EGL/egl.h>
#include <system/window.h>

#include "pipeline/skia/SkiaGpuPipeline.h"
#include "renderstate/RenderState.h"
#include "renderthread/HardwareBufferRenderParams.h"

namespace android {

class Bitmap;

namespace uirenderer {
namespace skiapipeline {

class SkiaOpenGLPipeline : public SkiaGpuPipeline, public IGpuContextCallback {
public:
    SkiaOpenGLPipeline(renderthread::RenderThread& thread);
    virtual ~SkiaOpenGLPipeline();

    renderthread::MakeCurrentResult makeCurrent() override;
    renderthread::Frame getFrame() override;
    renderthread::IRenderPipeline::DrawResult draw(
            const renderthread::Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
            const LightGeometry& lightGeometry, LayerUpdateQueue* layerUpdateQueue,
            const Rect& contentDrawBounds, bool opaque, const LightInfo& lightInfo,
            const std::vector<sp<RenderNode> >& renderNodes, FrameInfoVisualizer* profiler,
            const renderthread::HardwareBufferRenderParams& bufferParams,
            std::mutex& profilerLock) override;
    GrSurfaceOrigin getSurfaceOrigin() override { return kBottomLeft_GrSurfaceOrigin; }
    bool swapBuffers(const renderthread::Frame& frame, IRenderPipeline::DrawResult& drawResult,
                     const SkRect& screenDirty, FrameInfo* currentFrameInfo,
                     bool* requireSwap) override;
    DeferredLayerUpdater* createTextureLayer() override;
    bool setSurface(ANativeWindow* surface, renderthread::SwapBehavior swapBehavior) override;
    [[nodiscard]] android::base::unique_fd flush() override;
    void onStop() override;
    bool isSurfaceReady() override;
    bool isContextReady() override;

    const SkM44& getPixelSnapMatrix() const override {
        // Small (~1/16th) nudge to ensure that pixel-aligned non-AA'd draws fill the
        // desired fragment
        static const SkScalar kOffset = 0.063f;
        static const SkM44 sSnapMatrix = SkM44::Translate(kOffset, kOffset);
        return sSnapMatrix;
    }

    static void invokeFunctor(const renderthread::RenderThread& thread, Functor* functor);

protected:
    void onContextDestroyed() override;

private:
    renderthread::EglManager& mEglManager;
    EGLSurface mEglSurface = EGL_NO_SURFACE;
    sp<ANativeWindow> mNativeWindow;
    renderthread::SwapBehavior mSwapBehavior = renderthread::SwapBehavior::kSwap_discardBuffer;
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
