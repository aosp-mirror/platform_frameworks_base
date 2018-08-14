/*
 * Copyright (C) 2014 The Android Open Source Project
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
#include "DeferredLayerUpdater.h"

#include "GlLayer.h"
#include "VkLayer.h"
#include "renderstate/RenderState.h"
#include "renderthread/EglManager.h"
#include "renderthread/RenderTask.h"
#include "utils/PaintUtils.h"

namespace android {
namespace uirenderer {

DeferredLayerUpdater::DeferredLayerUpdater(RenderState& renderState, CreateLayerFn createLayerFn,
                                           Layer::Api layerApi)
        : mRenderState(renderState)
        , mBlend(false)
        , mSurfaceTexture(nullptr)
        , mTransform(nullptr)
        , mGLContextAttached(false)
        , mUpdateTexImage(false)
        , mLayer(nullptr)
        , mLayerApi(layerApi)
        , mCreateLayerFn(createLayerFn) {
    renderState.registerDeferredLayerUpdater(this);
}

DeferredLayerUpdater::~DeferredLayerUpdater() {
    setTransform(nullptr);
    mRenderState.unregisterDeferredLayerUpdater(this);
    destroyLayer();
}

void DeferredLayerUpdater::destroyLayer() {
    if (!mLayer) {
        return;
    }

    if (mSurfaceTexture.get() && mLayerApi == Layer::Api::OpenGL && mGLContextAttached) {
        status_t err = mSurfaceTexture->detachFromContext();
        mGLContextAttached = false;
        if (err != 0) {
            // TODO: Elevate to fatal exception
            ALOGE("Failed to detach SurfaceTexture from context %d", err);
        }
    }

    mLayer->postDecStrong();
    mLayer = nullptr;
}

void DeferredLayerUpdater::setPaint(const SkPaint* paint) {
    mAlpha = PaintUtils::getAlphaDirect(paint);
    mMode = PaintUtils::getBlendModeDirect(paint);
    if (paint) {
        mColorFilter = paint->refColorFilter();
    } else {
        mColorFilter.reset();
    }
}

void DeferredLayerUpdater::apply() {
    if (!mLayer) {
        mLayer = mCreateLayerFn(mRenderState, mWidth, mHeight, mColorFilter, mAlpha, mMode, mBlend);
    }

    mLayer->setColorFilter(mColorFilter);
    mLayer->setAlpha(mAlpha, mMode);

    if (mSurfaceTexture.get()) {
        if (mLayer->getApi() == Layer::Api::Vulkan) {
            if (mUpdateTexImage) {
                mUpdateTexImage = false;
                doUpdateVkTexImage();
            }
        } else {
            LOG_ALWAYS_FATAL_IF(mLayer->getApi() != Layer::Api::OpenGL,
                                "apply surfaceTexture with non GL backend %x, GL %x, VK %x",
                                mLayer->getApi(), Layer::Api::OpenGL, Layer::Api::Vulkan);
            if (!mGLContextAttached) {
                mGLContextAttached = true;
                mUpdateTexImage = true;
                mSurfaceTexture->attachToContext(static_cast<GlLayer*>(mLayer)->getTextureId());
            }
            if (mUpdateTexImage) {
                mUpdateTexImage = false;
                doUpdateTexImage();
            }
            GLenum renderTarget = mSurfaceTexture->getCurrentTextureTarget();
            static_cast<GlLayer*>(mLayer)->setRenderTarget(renderTarget);
        }
        if (mTransform) {
            mLayer->getTransform().load(*mTransform);
            setTransform(nullptr);
        }
    }
}

void DeferredLayerUpdater::doUpdateTexImage() {
    LOG_ALWAYS_FATAL_IF(mLayer->getApi() != Layer::Api::OpenGL,
                        "doUpdateTexImage non GL backend %x, GL %x, VK %x", mLayer->getApi(),
                        Layer::Api::OpenGL, Layer::Api::Vulkan);
    if (mSurfaceTexture->updateTexImage() == NO_ERROR) {
        float transform[16];

        int64_t frameNumber = mSurfaceTexture->getFrameNumber();
        // If the GLConsumer queue is in synchronous mode, need to discard all
        // but latest frame, using the frame number to tell when we no longer
        // have newer frames to target. Since we can't tell which mode it is in,
        // do this unconditionally.
        int dropCounter = 0;
        while (mSurfaceTexture->updateTexImage() == NO_ERROR) {
            int64_t newFrameNumber = mSurfaceTexture->getFrameNumber();
            if (newFrameNumber == frameNumber) break;
            frameNumber = newFrameNumber;
            dropCounter++;
        }

        bool forceFilter = false;
        sp<GraphicBuffer> buffer = mSurfaceTexture->getCurrentBuffer();
        if (buffer != nullptr) {
            // force filtration if buffer size != layer size
            forceFilter = mWidth != static_cast<int>(buffer->getWidth()) ||
                          mHeight != static_cast<int>(buffer->getHeight());
        }

#if DEBUG_RENDERER
        if (dropCounter > 0) {
            RENDERER_LOGD("Dropped %d frames on texture layer update", dropCounter);
        }
#endif
        mSurfaceTexture->getTransformMatrix(transform);

        updateLayer(forceFilter, transform, mSurfaceTexture->getCurrentDataSpace());
    }
}

void DeferredLayerUpdater::doUpdateVkTexImage() {
    LOG_ALWAYS_FATAL_IF(mLayer->getApi() != Layer::Api::Vulkan,
                        "updateLayer non Vulkan backend %x, GL %x, VK %x", mLayer->getApi(),
                        Layer::Api::OpenGL, Layer::Api::Vulkan);

    static const mat4 identityMatrix;
    updateLayer(false, identityMatrix.data, HAL_DATASPACE_UNKNOWN);

    VkLayer* vkLayer = static_cast<VkLayer*>(mLayer);
    vkLayer->updateTexture();
}

void DeferredLayerUpdater::updateLayer(bool forceFilter, const float* textureTransform,
                                       android_dataspace dataspace) {
    mLayer->setBlend(mBlend);
    mLayer->setForceFilter(forceFilter);
    mLayer->setSize(mWidth, mHeight);
    mLayer->getTexTransform().load(textureTransform);
    mLayer->setDataSpace(dataspace);
}

void DeferredLayerUpdater::detachSurfaceTexture() {
    if (mSurfaceTexture.get()) {
        destroyLayer();
        mSurfaceTexture = nullptr;
    }
}

} /* namespace uirenderer */
} /* namespace android */
