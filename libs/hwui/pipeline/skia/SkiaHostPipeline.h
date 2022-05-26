/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "SkiaPipeline.h"

namespace android {

namespace uirenderer {
namespace skiapipeline {

class SkiaHostPipeline : public SkiaPipeline {
public:
    SkiaHostPipeline(renderthread::RenderThread& thread) : SkiaPipeline(thread) {}
    virtual ~SkiaHostPipeline() {}

    renderthread::MakeCurrentResult makeCurrent() override;
    renderthread::Frame getFrame() override;
    bool draw(const renderthread::Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
              const LightGeometry& lightGeometry, LayerUpdateQueue* layerUpdateQueue,
              const Rect& contentDrawBounds, bool opaque, const LightInfo& lightInfo,
              const std::vector<sp<RenderNode> >& renderNodes,
              FrameInfoVisualizer* profiler) override;
    DeferredLayerUpdater* createTextureLayer() override;
    bool swapBuffers(const renderthread::Frame& frame, bool drew, const SkRect& screenDirty,
                     FrameInfo* currentFrameInfo, bool* requireSwap) override;
    bool setSurface(ANativeWindow* surface, renderthread::SwapBehavior swapBehavior) override;
    void onStop() override;
    bool isSurfaceReady() override;
    bool isContextReady() override;

private:
    sk_sp<SkSurface> mSurface;
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
