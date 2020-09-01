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

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

// TODO: Use public SurfaceTexture APIs once available and include public NDK header file instead.
#include <surfacetexture/surface_texture_platform.h>
#include "AutoBackendTextureRelease.h"
#include "Matrix.h"
#include "Properties.h"
#include "renderstate/RenderState.h"
#include "renderthread/EglManager.h"
#include "renderthread/RenderThread.h"
#include "renderthread/VulkanManager.h"

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {

DeferredLayerUpdater::DeferredLayerUpdater(RenderState& renderState)
        : mRenderState(renderState)
        , mBlend(false)
        , mSurfaceTexture(nullptr, [](ASurfaceTexture*) {})
        , mTransform(nullptr)
        , mGLContextAttached(false)
        , mUpdateTexImage(false)
        , mLayer(nullptr) {
    renderState.registerContextCallback(this);
}

DeferredLayerUpdater::~DeferredLayerUpdater() {
    setTransform(nullptr);
    mRenderState.removeContextCallback(this);
    destroyLayer();
}

void DeferredLayerUpdater::setSurfaceTexture(AutoTextureRelease&& consumer) {
    mSurfaceTexture = std::move(consumer);

    GLenum target = ASurfaceTexture_getCurrentTextureTarget(mSurfaceTexture.get());
    LOG_ALWAYS_FATAL_IF(target != GL_TEXTURE_2D && target != GL_TEXTURE_EXTERNAL_OES,
                        "set unsupported SurfaceTexture with target %x", target);
}

void DeferredLayerUpdater::onContextDestroyed() {
    destroyLayer();
}

void DeferredLayerUpdater::destroyLayer() {
    if (!mLayer) {
        return;
    }

    if (mSurfaceTexture.get() && mGLContextAttached) {
        ASurfaceTexture_releaseConsumerOwnership(mSurfaceTexture.get());
        mGLContextAttached = false;
    }

    mLayer->postDecStrong();

    mLayer = nullptr;

    mImageSlots.clear();
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

static status_t createReleaseFence(bool useFenceSync, EGLSyncKHR* eglFence, EGLDisplay* display,
                                   int* releaseFence, void* handle) {
    *display = EGL_NO_DISPLAY;
    RenderState* renderState = (RenderState*)handle;
    status_t err;
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL) {
        EglManager& eglManager = renderState->getRenderThread().eglManager();
        *display = eglManager.eglDisplay();
        err = eglManager.createReleaseFence(useFenceSync, eglFence, releaseFence);
    } else {
        err = renderState->getRenderThread().vulkanManager().createReleaseFence(
                releaseFence, renderState->getRenderThread().getGrContext());
    }
    return err;
}

static status_t fenceWait(int fence, void* handle) {
    // Wait on the producer fence for the buffer to be ready.
    status_t err;
    RenderState* renderState = (RenderState*)handle;
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL) {
        err = renderState->getRenderThread().eglManager().fenceWait(fence);
    } else {
        err = renderState->getRenderThread().vulkanManager().fenceWait(
                fence, renderState->getRenderThread().getGrContext());
    }
    return err;
}

void DeferredLayerUpdater::apply() {
    if (!mLayer) {
        mLayer = new Layer(mRenderState, mColorFilter, mAlpha, mMode);
    }

    mLayer->setColorFilter(mColorFilter);
    mLayer->setAlpha(mAlpha, mMode);

    if (mSurfaceTexture.get()) {
        if (!mGLContextAttached) {
            mGLContextAttached = true;
            mUpdateTexImage = true;
            ASurfaceTexture_takeConsumerOwnership(mSurfaceTexture.get());
        }
        if (mUpdateTexImage) {
            mUpdateTexImage = false;
            float transformMatrix[16];
            android_dataspace dataspace;
            int slot;
            bool newContent = false;
            // Note: ASurfaceTexture_dequeueBuffer discards all but the last frame. This
            // is necessary if the SurfaceTexture queue is in synchronous mode, and we
            // cannot tell which mode it is in.
            AHardwareBuffer* hardwareBuffer = ASurfaceTexture_dequeueBuffer(
                    mSurfaceTexture.get(), &slot, &dataspace, transformMatrix, &newContent,
                    createReleaseFence, fenceWait, &mRenderState);

            if (hardwareBuffer) {
                sk_sp<SkImage> layerImage = mImageSlots[slot].createIfNeeded(
                        hardwareBuffer, dataspace, newContent,
                        mRenderState.getRenderThread().getGrContext());
                // unref to match the ref added by ASurfaceTexture_dequeueBuffer. eglCreateImageKHR
                // (invoked by createIfNeeded) will add a ref to the AHardwareBuffer.
                AHardwareBuffer_release(hardwareBuffer);
                if (layerImage.get()) {
                    SkMatrix textureTransform;
                    mat4(transformMatrix).copyTo(textureTransform);
                    // force filtration if buffer size != layer size
                    bool forceFilter =
                            mWidth != layerImage->width() || mHeight != layerImage->height();
                    updateLayer(forceFilter, textureTransform, layerImage);
                }
            }
        }

        if (mTransform) {
            mLayer->getTransform() = *mTransform;
            setTransform(nullptr);
        }
    }
}

void DeferredLayerUpdater::updateLayer(bool forceFilter, const SkMatrix& textureTransform,
                                       const sk_sp<SkImage>& layerImage) {
    mLayer->setBlend(mBlend);
    mLayer->setForceFilter(forceFilter);
    mLayer->setSize(mWidth, mHeight);
    mLayer->getTexTransform() = textureTransform;
    mLayer->setImage(layerImage);
}

void DeferredLayerUpdater::detachSurfaceTexture() {
    if (mSurfaceTexture.get()) {
        destroyLayer();
        mSurfaceTexture = nullptr;
    }
}

sk_sp<SkImage> DeferredLayerUpdater::ImageSlot::createIfNeeded(AHardwareBuffer* buffer,
                                                               android_dataspace dataspace,
                                                               bool forceCreate,
                                                               GrContext* context) {
    if (!mTextureRelease || !mTextureRelease->getImage().get() || dataspace != mDataspace ||
        forceCreate || mBuffer != buffer) {
        if (buffer != mBuffer) {
            clear();
        }

        if (!buffer) {
            return nullptr;
        }

        if (!mTextureRelease) {
            mTextureRelease = new AutoBackendTextureRelease(context, buffer);
        } else {
            mTextureRelease->newBufferContent(context);
        }

        mDataspace = dataspace;
        mBuffer = buffer;
        mTextureRelease->makeImage(buffer, dataspace, context);
    }
    return mTextureRelease ? mTextureRelease->getImage() : nullptr;
}

void DeferredLayerUpdater::ImageSlot::clear() {
    if (mTextureRelease) {
        // The following unref counteracts the initial mUsageCount of 1, set by default initializer.
        mTextureRelease->unref(true);
        mTextureRelease = nullptr;
    }

    mBuffer = nullptr;
}

} /* namespace uirenderer */
} /* namespace android */
