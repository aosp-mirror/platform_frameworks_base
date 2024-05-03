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

#include "SkiaHostPipeline.h"

#include "LightingInfo.h"
#include "renderthread/Frame.h"

#include "SkColor.h"
#include "SkPaint.h"

#include <strings.h>
#include <system/window.h>

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {
namespace skiapipeline {

MakeCurrentResult SkiaHostPipeline::makeCurrent() {
    return MakeCurrentResult::Succeeded;
}

Frame SkiaHostPipeline::getFrame() {
    return Frame(mSurface->width(), mSurface->height(), 0);
}

bool SkiaHostPipeline::draw(const Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
                            const LightGeometry& lightGeometry, LayerUpdateQueue* layerUpdateQueue,
                            const Rect& contentDrawBounds, bool opaque, const LightInfo& lightInfo,
                            const std::vector<sp<RenderNode>>& renderNodes,
                            FrameInfoVisualizer* profiler) {
    LightingInfo::updateLighting(lightGeometry, lightInfo);
    renderFrame(*layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, mSurface,
                SkMatrix::I());

    return true;
}

DeferredLayerUpdater* SkiaHostPipeline::createTextureLayer() {
    return nullptr;
}

bool SkiaHostPipeline::swapBuffers(const Frame& frame, bool drew, const SkRect& screenDirty,
                                   FrameInfo* currentFrameInfo, bool* requireSwap) {
    return false;
}

void SkiaHostPipeline::onStop() {}

bool SkiaHostPipeline::setSurface(ANativeWindow* surface, SwapBehavior swapBehavior) {
    if (surface) {
        ANativeWindowBuffer* buffer;
        surface->dequeueBuffer(surface, &buffer, nullptr);
        int width, height;
        surface->query(surface, NATIVE_WINDOW_WIDTH, &width);
        surface->query(surface, NATIVE_WINDOW_HEIGHT, &height);
        // The ColorType param here must match the ColorType used by
        // Bitmap.Config.ARGB_8888. Android Bitmap objects use kN32_SkColorType
        // by default for Bitmap.Config.ARGB_8888. The value of this is
        // determined at compile time based on architecture (either BGRA or
        // RGBA). If other Android Bitmap configs are used, 'kN32_SkColorType'
        // may not be correct.
        SkImageInfo imageInfo = SkImageInfo::Make(width, height, kN32_SkColorType,
                                                  SkAlphaType::kPremul_SkAlphaType);
        size_t widthBytes = width * 4;
        void* pixels = buffer->reserved[0];
        mSurface = SkSurface::MakeRasterDirect(imageInfo, pixels, widthBytes);
    } else {
        mSurface = sk_sp<SkSurface>();
    }
    return true;
}

bool SkiaHostPipeline::isSurfaceReady() {
    return CC_UNLIKELY(mSurface.get() != nullptr);
}

bool SkiaHostPipeline::isContextReady() {
    return true;
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
