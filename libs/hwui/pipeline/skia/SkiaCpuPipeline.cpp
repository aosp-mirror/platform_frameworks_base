/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "pipeline/skia/SkiaCpuPipeline.h"

#include <system/window.h>

#include "DeviceInfo.h"
#include "LightingInfo.h"
#include "renderthread/Frame.h"
#include "utils/Color.h"

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {
namespace skiapipeline {

void SkiaCpuPipeline::renderLayersImpl(const LayerUpdateQueue& layers, bool opaque) {
    // Render all layers that need to be updated, in order.
    for (size_t i = 0; i < layers.entries().size(); i++) {
        RenderNode* layerNode = layers.entries()[i].renderNode.get();
        // only schedule repaint if node still on layer - possible it may have been
        // removed during a dropped frame, but layers may still remain scheduled so
        // as not to lose info on what portion is damaged
        if (CC_UNLIKELY(layerNode->getLayerSurface() == nullptr)) {
            continue;
        }
        bool rendered = renderLayerImpl(layerNode, layers.entries()[i].damage);
        if (!rendered) {
            return;
        }
    }
}

// If the given node didn't have a layer surface, or had one of the wrong size, this method
// creates a new one and returns true. Otherwise does nothing and returns false.
bool SkiaCpuPipeline::createOrUpdateLayer(RenderNode* node,
                                          const DamageAccumulator& damageAccumulator,
                                          ErrorHandler* errorHandler) {
    // compute the size of the surface (i.e. texture) to be allocated for this layer
    const int surfaceWidth = ceilf(node->getWidth() / float(LAYER_SIZE)) * LAYER_SIZE;
    const int surfaceHeight = ceilf(node->getHeight() / float(LAYER_SIZE)) * LAYER_SIZE;

    SkSurface* layer = node->getLayerSurface();
    if (!layer || layer->width() != surfaceWidth || layer->height() != surfaceHeight) {
        SkImageInfo info;
        info = SkImageInfo::Make(surfaceWidth, surfaceHeight, getSurfaceColorType(),
                                 kPremul_SkAlphaType, getSurfaceColorSpace());
        SkSurfaceProps props(0, kUnknown_SkPixelGeometry);
        node->setLayerSurface(SkSurfaces::Raster(info, &props));
        if (node->getLayerSurface()) {
            // update the transform in window of the layer to reset its origin wrt light source
            // position
            Matrix4 windowTransform;
            damageAccumulator.computeCurrentTransform(&windowTransform);
            node->getSkiaLayer()->inverseTransformInWindow.loadInverse(windowTransform);
        } else {
            String8 cachesOutput;
            mRenderThread.cacheManager().dumpMemoryUsage(cachesOutput,
                                                         &mRenderThread.renderState());
            ALOGE("%s", cachesOutput.c_str());
            if (errorHandler) {
                std::ostringstream err;
                err << "Unable to create layer for " << node->getName();
                const int maxTextureSize = DeviceInfo::get()->maxTextureSize();
                err << ", size " << info.width() << "x" << info.height() << " max size "
                    << maxTextureSize << " color type " << (int)info.colorType() << " has context "
                    << (int)(mRenderThread.getGrContext() != nullptr);
                errorHandler->onError(err.str());
            }
        }
        return true;
    }
    return false;
}

MakeCurrentResult SkiaCpuPipeline::makeCurrent() {
    return MakeCurrentResult::AlreadyCurrent;
}

Frame SkiaCpuPipeline::getFrame() {
    return Frame(mSurface->width(), mSurface->height(), 0);
}

IRenderPipeline::DrawResult SkiaCpuPipeline::draw(
        const Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
        const LightGeometry& lightGeometry, LayerUpdateQueue* layerUpdateQueue,
        const Rect& contentDrawBounds, bool opaque, const LightInfo& lightInfo,
        const std::vector<sp<RenderNode>>& renderNodes, FrameInfoVisualizer* profiler,
        const HardwareBufferRenderParams& bufferParams, std::mutex& profilerLock) {
    LightingInfo::updateLighting(lightGeometry, lightInfo);
    renderFrame(*layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, mSurface,
                SkMatrix::I());
    return {true, IRenderPipeline::DrawResult::kUnknownTime, android::base::unique_fd{}};
}

bool SkiaCpuPipeline::setSurface(ANativeWindow* surface, SwapBehavior swapBehavior) {
    if (surface) {
        ANativeWindowBuffer* buffer;
        surface->dequeueBuffer(surface, &buffer, nullptr);
        int width, height;
        surface->query(surface, NATIVE_WINDOW_WIDTH, &width);
        surface->query(surface, NATIVE_WINDOW_HEIGHT, &height);
        SkImageInfo imageInfo =
                SkImageInfo::Make(width, height, mSurfaceColorType,
                                  SkAlphaType::kPremul_SkAlphaType, mSurfaceColorSpace);
        size_t widthBytes = width * imageInfo.bytesPerPixel();
        void* pixels = buffer->reserved[0];
        mSurface = SkSurfaces::WrapPixels(imageInfo, pixels, widthBytes);
    } else {
        mSurface = sk_sp<SkSurface>();
    }
    return true;
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
