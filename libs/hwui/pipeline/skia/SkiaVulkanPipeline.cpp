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

#include "SkiaVulkanPipeline.h"

#include "DeferredLayerUpdater.h"
#include "renderthread/EglManager.h" // needed for Frame
#include "Readback.h"
#include "renderstate/RenderState.h"
#include "SkiaPipeline.h"
#include "SkiaProfileRenderer.h"

#include <SkTypes.h>
#include <WindowContextFactory_android.h>
#include <VulkanWindowContext.h>

#include <android/native_window.h>
#include <cutils/properties.h>
#include <strings.h>

using namespace android::uirenderer::renderthread;
using namespace sk_app;

namespace android {
namespace uirenderer {
namespace skiapipeline {

MakeCurrentResult SkiaVulkanPipeline::makeCurrent() {
    return (mWindowContext != nullptr) ?
        MakeCurrentResult::AlreadyCurrent : MakeCurrentResult::Failed;
}

Frame SkiaVulkanPipeline::getFrame() {
    LOG_ALWAYS_FATAL_IF(mWindowContext == nullptr, "Tried to draw into null vulkan context!");
    mBackbuffer = mWindowContext->getBackbufferSurface();
    if (mBackbuffer.get() == nullptr) {
        // try recreating the context?
        SkDebugf("failed to get backbuffer");
        return Frame(-1, -1, 0);
    }

    // TODO: support buffer age if Vulkan API can do it
    Frame frame(mBackbuffer->width(), mBackbuffer->height(), 0);
    return frame;
}

bool SkiaVulkanPipeline::draw(const Frame& frame, const SkRect& screenDirty,
        const SkRect& dirty,
        const FrameBuilder::LightGeometry& lightGeometry,
        LayerUpdateQueue* layerUpdateQueue,
        const Rect& contentDrawBounds, bool opaque,
        const BakedOpRenderer::LightInfo& lightInfo,
        const std::vector<sp<RenderNode>>& renderNodes,
        FrameInfoVisualizer* profiler) {

    if (mBackbuffer.get() == nullptr) {
        return false;
    }
    renderFrame(*layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, mBackbuffer);
    layerUpdateQueue->clear();

    // Draw visual debugging features
    if (CC_UNLIKELY(Properties::showDirtyRegions
            || ProfileType::None != Properties::getProfileType())) {
        SkCanvas* profileCanvas = mBackbuffer->getCanvas();
        SkiaProfileRenderer profileRenderer(profileCanvas);
        profiler->draw(profileRenderer);
        profileCanvas->flush();
    }

    // Log memory statistics
    if (CC_UNLIKELY(Properties::debugLevel != kDebugDisabled)) {
        dumpResourceCacheUsage();
    }

    return true;
}

bool SkiaVulkanPipeline::swapBuffers(const Frame& frame, bool drew,
        const SkRect& screenDirty, FrameInfo* currentFrameInfo, bool* requireSwap) {

    *requireSwap = drew;

    // Even if we decided to cancel the frame, from the perspective of jank
    // metrics the frame was swapped at this point
    currentFrameInfo->markSwapBuffers();

    if (*requireSwap) {
        mWindowContext->swapBuffers();
    }

    mBackbuffer.reset();

    return *requireSwap;
}

bool SkiaVulkanPipeline::copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap) {
    // TODO: implement copyLayerInto for vulkan.
    return false;
}

DeferredLayerUpdater* SkiaVulkanPipeline::createTextureLayer() {
    Layer* layer = new Layer(mRenderThread.renderState(), 0, 0);
    return new DeferredLayerUpdater(layer);
}

void SkiaVulkanPipeline::onStop() {
}

bool SkiaVulkanPipeline::setSurface(Surface* surface, SwapBehavior swapBehavior) {

    if (mWindowContext) {
        delete mWindowContext;
        mWindowContext = nullptr;
    }

    if (surface) {
        DisplayParams displayParams;
        mWindowContext = window_context_factory::NewVulkanForAndroid(surface, displayParams);
        if (mWindowContext) {
            DeviceInfo::initialize(mWindowContext->getGrContext()->caps()->maxRenderTargetSize());
        }
    }


    // this doesn't work for if there is more than one CanvasContext available at one time!
    mRenderThread.setGrContext(mWindowContext ? mWindowContext->getGrContext() : nullptr);

    return mWindowContext != nullptr;
}

bool SkiaVulkanPipeline::isSurfaceReady() {
    return CC_LIKELY(mWindowContext != nullptr) && mWindowContext->isValid();
}

bool SkiaVulkanPipeline::isContextReady() {
    return CC_LIKELY(mWindowContext != nullptr);
}

void SkiaVulkanPipeline::invokeFunctor(const RenderThread& thread, Functor* functor) {
    // TODO: we currently don't support OpenGL WebView's
    DrawGlInfo::Mode mode = DrawGlInfo::kModeProcessNoContext;
    (*functor)(mode, nullptr);
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
