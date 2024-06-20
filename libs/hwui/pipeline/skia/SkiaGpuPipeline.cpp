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

#include "pipeline/skia/SkiaGpuPipeline.h"

#include <SkImageAndroid.h>
#include <gui/TraceUtils.h>
#include <include/android/SkSurfaceAndroid.h>
#include <include/gpu/ganesh/SkSurfaceGanesh.h>

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {
namespace skiapipeline {

SkiaGpuPipeline::SkiaGpuPipeline(RenderThread& thread) : SkiaPipeline(thread) {}

SkiaGpuPipeline::~SkiaGpuPipeline() {
    unpinImages();
}

void SkiaGpuPipeline::renderLayersImpl(const LayerUpdateQueue& layers, bool opaque) {
    sk_sp<GrDirectContext> cachedContext;

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
        // cache the current context so that we can defer flushing it until
        // either all the layers have been rendered or the context changes
        GrDirectContext* currentContext =
                GrAsDirectContext(layerNode->getLayerSurface()->getCanvas()->recordingContext());
        if (cachedContext.get() != currentContext) {
            if (cachedContext.get()) {
                ATRACE_NAME("flush layers (context changed)");
                cachedContext->flushAndSubmit();
            }
            cachedContext.reset(SkSafeRef(currentContext));
        }
    }
    if (cachedContext.get()) {
        ATRACE_NAME("flush layers");
        cachedContext->flushAndSubmit();
    }
}

// If the given node didn't have a layer surface, or had one of the wrong size, this method
// creates a new one and returns true. Otherwise does nothing and returns false.
bool SkiaGpuPipeline::createOrUpdateLayer(RenderNode* node,
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
        SkASSERT(mRenderThread.getGrContext() != nullptr);
        node->setLayerSurface(SkSurfaces::RenderTarget(mRenderThread.getGrContext(),
                                                       skgpu::Budgeted::kYes, info, 0,
                                                       this->getSurfaceOrigin(), &props));
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

bool SkiaGpuPipeline::pinImages(std::vector<SkImage*>& mutableImages) {
    if (!mRenderThread.getGrContext()) {
        ALOGD("Trying to pin an image with an invalid GrContext");
        return false;
    }
    for (SkImage* image : mutableImages) {
        if (skgpu::ganesh::PinAsTexture(mRenderThread.getGrContext(), image)) {
            mPinnedImages.emplace_back(sk_ref_sp(image));
        } else {
            return false;
        }
    }
    return true;
}

void SkiaGpuPipeline::unpinImages() {
    for (auto& image : mPinnedImages) {
        skgpu::ganesh::UnpinTexture(mRenderThread.getGrContext(), image.get());
    }
    mPinnedImages.clear();
}

void SkiaGpuPipeline::prepareToDraw(const RenderThread& thread, Bitmap* bitmap) {
    GrDirectContext* context = thread.getGrContext();
    if (context && !bitmap->isHardware()) {
        ATRACE_FORMAT("Bitmap#prepareToDraw %dx%d", bitmap->width(), bitmap->height());
        auto image = bitmap->makeImage();
        if (image.get()) {
            skgpu::ganesh::PinAsTexture(context, image.get());
            skgpu::ganesh::UnpinTexture(context, image.get());
            // A submit is necessary as there may not be a frame coming soon, so without a call
            // to submit these texture uploads can just sit in the queue building up until
            // we run out of RAM
            context->flushAndSubmit();
        }
    }
}

sk_sp<SkSurface> SkiaGpuPipeline::getBufferSkSurface(
        const renderthread::HardwareBufferRenderParams& bufferParams) {
    auto bufferColorSpace = bufferParams.getColorSpace();
    if (mBufferSurface == nullptr || mBufferColorSpace == nullptr ||
        !SkColorSpace::Equals(mBufferColorSpace.get(), bufferColorSpace.get())) {
        mBufferSurface = SkSurfaces::WrapAndroidHardwareBuffer(
                mRenderThread.getGrContext(), mHardwareBuffer, kTopLeft_GrSurfaceOrigin,
                bufferColorSpace, nullptr, true);
        mBufferColorSpace = bufferColorSpace;
    }
    return mBufferSurface;
}

void SkiaGpuPipeline::dumpResourceCacheUsage() const {
    int resources;
    size_t bytes;
    mRenderThread.getGrContext()->getResourceCacheUsage(&resources, &bytes);
    size_t maxBytes = mRenderThread.getGrContext()->getResourceCacheLimit();

    SkString log("Resource Cache Usage:\n");
    log.appendf("%8d items\n", resources);
    log.appendf("%8zu bytes (%.2f MB) out of %.2f MB maximum\n", bytes,
                bytes * (1.0f / (1024.0f * 1024.0f)), maxBytes * (1.0f / (1024.0f * 1024.0f)));

    ALOGD("%s", log.c_str());
}

void SkiaGpuPipeline::setHardwareBuffer(AHardwareBuffer* buffer) {
    if (mHardwareBuffer) {
        AHardwareBuffer_release(mHardwareBuffer);
        mHardwareBuffer = nullptr;
    }

    if (buffer) {
        AHardwareBuffer_acquire(buffer);
        mHardwareBuffer = buffer;
    }
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
