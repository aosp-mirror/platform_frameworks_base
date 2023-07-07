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
#include "android/hdr_metadata.h"
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

    for (auto& [index, slot] : mImageSlots) {
        slot.clear(mRenderState.getRenderThread().getGrContext());
    }
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

status_t DeferredLayerUpdater::createReleaseFence(bool useFenceSync, EGLSyncKHR* eglFence,
                                                  EGLDisplay* display, int* releaseFence,
                                                  void* handle) {
    *display = EGL_NO_DISPLAY;
    DeferredLayerUpdater* dlu = (DeferredLayerUpdater*)handle;
    RenderState& renderState = dlu->mRenderState;
    status_t err;
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL) {
        EglManager& eglManager = renderState.getRenderThread().eglManager();
        *display = eglManager.eglDisplay();
        err = eglManager.createReleaseFence(useFenceSync, eglFence, releaseFence);
    } else {
        int previousSlot = dlu->mCurrentSlot;
        if (previousSlot != -1) {
            dlu->mImageSlots[previousSlot].releaseQueueOwnership(
                    renderState.getRenderThread().getGrContext());
        }
        err = renderState.getRenderThread().vulkanManager().createReleaseFence(
                releaseFence, renderState.getRenderThread().getGrContext());
    }
    return err;
}

status_t DeferredLayerUpdater::fenceWait(int fence, void* handle) {
    // Wait on the producer fence for the buffer to be ready.
    status_t err;
    DeferredLayerUpdater* dlu = (DeferredLayerUpdater*)handle;
    RenderState& renderState = dlu->mRenderState;
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL) {
        err = renderState.getRenderThread().eglManager().fenceWait(fence);
    } else {
        err = renderState.getRenderThread().vulkanManager().fenceWait(
                fence, renderState.getRenderThread().getGrContext());
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
            AHdrMetadataType hdrMetadataType;
            android_cta861_3_metadata cta861_3;
            android_smpte2086_metadata smpte2086;
            int slot;
            bool newContent = false;
            ARect currentCrop;
            uint32_t outTransform;
            // Note: ASurfaceTexture_dequeueBuffer discards all but the last frame. This
            // is necessary if the SurfaceTexture queue is in synchronous mode, and we
            // cannot tell which mode it is in.
            AHardwareBuffer* hardwareBuffer = ASurfaceTexture_dequeueBuffer(
                    mSurfaceTexture.get(), &slot, &dataspace, &hdrMetadataType, &cta861_3,
                    &smpte2086, transformMatrix, &outTransform, &newContent, createReleaseFence,
                    fenceWait, this, &currentCrop);

            if (hardwareBuffer) {
                mCurrentSlot = slot;
                sk_sp<SkImage> layerImage = mImageSlots[slot].createIfNeeded(
                        hardwareBuffer, dataspace, newContent,
                        mRenderState.getRenderThread().getGrContext());
                AHardwareBuffer_Desc bufferDesc;
                AHardwareBuffer_describe(hardwareBuffer, &bufferDesc);
                // unref to match the ref added by ASurfaceTexture_dequeueBuffer. eglCreateImageKHR
                // (invoked by createIfNeeded) will add a ref to the AHardwareBuffer.
                AHardwareBuffer_release(hardwareBuffer);
                if (layerImage.get()) {
                    // force filtration if buffer size != layer size
                    bool forceFilter =
                            mWidth != layerImage->width() || mHeight != layerImage->height();
                    SkRect currentCropRect =
                            SkRect::MakeLTRB(currentCrop.left, currentCrop.top, currentCrop.right,
                                             currentCrop.bottom);

                    float maxLuminanceNits = -1.f;
                    if (hdrMetadataType & HDR10_SMPTE2086) {
                        maxLuminanceNits = std::max(smpte2086.maxLuminance, maxLuminanceNits);
                    }

                    if (hdrMetadataType & HDR10_CTA861_3) {
                        maxLuminanceNits =
                                std::max(cta861_3.maxContentLightLevel, maxLuminanceNits);
                    }
                    mLayer->setBufferFormat(bufferDesc.format);
                    updateLayer(forceFilter, layerImage, outTransform, currentCropRect,
                                maxLuminanceNits);
                }
            }
        }

        if (mTransform) {
            mLayer->getTransform() = *mTransform;
            setTransform(nullptr);
        }
    }
}

void DeferredLayerUpdater::updateLayer(bool forceFilter, const sk_sp<SkImage>& layerImage,
                                       const uint32_t transform, SkRect currentCrop,
                                       float maxLuminanceNits) {
    mLayer->setBlend(mBlend);
    mLayer->setForceFilter(forceFilter);
    mLayer->setSize(mWidth, mHeight);
    mLayer->setCurrentCropRect(currentCrop);
    mLayer->setWindowTransform(transform);
    mLayer->setImage(layerImage);
    mLayer->setMaxLuminanceNits(maxLuminanceNits);
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
                                                               GrDirectContext* context) {
    if (!mTextureRelease || !mTextureRelease->getImage().get() || dataspace != mDataspace ||
        forceCreate || mBuffer != buffer) {
        if (buffer != mBuffer) {
            clear(context);
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

void DeferredLayerUpdater::ImageSlot::clear(GrDirectContext* context) {
    if (mTextureRelease) {
        if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaVulkan) {
            this->releaseQueueOwnership(context);
        }
        // The following unref counteracts the initial mUsageCount of 1, set by default initializer.
        mTextureRelease->unref(true);
        mTextureRelease = nullptr;
    }

    mBuffer = nullptr;
}

void DeferredLayerUpdater::ImageSlot::releaseQueueOwnership(GrDirectContext* context) {
    LOG_ALWAYS_FATAL_IF(Properties::getRenderPipelineType() != RenderPipelineType::SkiaVulkan);
    if (mTextureRelease) {
        mTextureRelease->releaseQueueOwnership(context);
    }
}

} /* namespace uirenderer */
} /* namespace android */
