/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "ImageConsumer.h"
#include <gui/BufferQueue.h>
#include "Properties.h"
#include "SurfaceTexture.h"
#include "renderstate/RenderState.h"
#include "renderthread/EglManager.h"
#include "renderthread/RenderThread.h"
#include "renderthread/VulkanManager.h"
#include "utils/Color.h"
#include <GrAHardwareBufferUtils.h>
#include <GrBackendSurface.h>

// Macro for including the SurfaceTexture name in log messages
#define IMG_LOGE(x, ...) ALOGE("[%s] " x, st.mName.string(), ##__VA_ARGS__)

using namespace android::uirenderer::renderthread;

namespace android {

void ImageConsumer::onFreeBufferLocked(int slotIndex) {
    // This callback may be invoked on any thread.
    mImageSlots[slotIndex].clear();
}

void ImageConsumer::onAcquireBufferLocked(BufferItem* item) {
    // If item->mGraphicBuffer is not null, this buffer has not been acquired
    // before, so any prior SkImage is created with a stale buffer. This resets the stale SkImage.
    if (item->mGraphicBuffer != nullptr) {
        mImageSlots[item->mSlot].clear();
    }
}

void ImageConsumer::onReleaseBufferLocked(int buf) {
    mImageSlots[buf].eglFence() = EGL_NO_SYNC_KHR;
}

/**
 * AutoBackendTextureRelease manages EglImage/VkImage lifetime. It is a ref-counted object
 * that keeps GPU resources alive until the last SKImage object using them is destroyed.
 */
class AutoBackendTextureRelease {
public:
    static void releaseProc(SkImage::ReleaseContext releaseContext);

    AutoBackendTextureRelease(GrContext* context, GraphicBuffer* buffer);

    const GrBackendTexture& getTexture() const { return mBackendTexture; }

    void ref() { mUsageCount++; }

    void unref(bool releaseImage);

    inline sk_sp<SkImage> getImage() { return mImage; }

    void makeImage(sp<GraphicBuffer>& graphicBuffer, android_dataspace dataspace,
                   GrContext* context);

private:
    // The only way to invoke dtor is with unref, when mUsageCount is 0.
    ~AutoBackendTextureRelease() {}

    GrBackendTexture mBackendTexture;
    GrAHardwareBufferUtils::DeleteImageProc mDeleteProc;
    GrAHardwareBufferUtils::DeleteImageCtx mDeleteCtx;

    // Starting with refcount 1, because the first ref is held by SurfaceTexture. Additional refs
    // are held by SkImages.
    int mUsageCount = 1;

    // mImage is the SkImage created from mBackendTexture.
    sk_sp<SkImage> mImage;
};

AutoBackendTextureRelease::AutoBackendTextureRelease(GrContext* context, GraphicBuffer* buffer) {
    bool createProtectedImage =
        0 != (buffer->getUsage() & GraphicBuffer::USAGE_PROTECTED);
    GrBackendFormat backendFormat = GrAHardwareBufferUtils::GetBackendFormat(
        context,
        reinterpret_cast<AHardwareBuffer*>(buffer),
        buffer->getPixelFormat(),
        false);
    mBackendTexture = GrAHardwareBufferUtils::MakeBackendTexture(
        context,
        reinterpret_cast<AHardwareBuffer*>(buffer),
        buffer->getWidth(),
        buffer->getHeight(),
        &mDeleteProc,
        &mDeleteCtx,
        createProtectedImage,
        backendFormat,
        false);
}

void AutoBackendTextureRelease::unref(bool releaseImage) {
    if (!RenderThread::isCurrent()) {
        // EGLImage needs to be destroyed on RenderThread to prevent memory leak.
        // ~SkImage dtor for both pipelines needs to be invoked on RenderThread, because it is not
        // thread safe.
        RenderThread::getInstance().queue().post([this, releaseImage]() { unref(releaseImage); });
        return;
    }

    if (releaseImage) {
        mImage.reset();
    }

    mUsageCount--;
    if (mUsageCount <= 0) {
        if (mBackendTexture.isValid()) {
            mDeleteProc(mDeleteCtx);
            mBackendTexture = {};
        }
        delete this;
    }
}

void AutoBackendTextureRelease::releaseProc(SkImage::ReleaseContext releaseContext) {
    AutoBackendTextureRelease* textureRelease =
        reinterpret_cast<AutoBackendTextureRelease*>(releaseContext);
    textureRelease->unref(false);
}

void AutoBackendTextureRelease::makeImage(sp<GraphicBuffer>& graphicBuffer,
                                          android_dataspace dataspace, GrContext* context) {
    SkColorType colorType = GrAHardwareBufferUtils::GetSkColorTypeFromBufferFormat(
        graphicBuffer->getPixelFormat());
    mImage = SkImage::MakeFromTexture(context,
        mBackendTexture,
        kTopLeft_GrSurfaceOrigin,
        colorType,
        kPremul_SkAlphaType,
        uirenderer::DataSpaceToColorSpace(dataspace),
        releaseProc,
        this);
    if (mImage.get()) {
        // The following ref will be counteracted by releaseProc, when SkImage is discarded.
        ref();
    }
}

void ImageConsumer::ImageSlot::createIfNeeded(sp<GraphicBuffer> graphicBuffer,
                                              android_dataspace dataspace, bool forceCreate,
                                              GrContext* context) {
    if (!mTextureRelease || !mTextureRelease->getImage().get() || dataspace != mDataspace
            || forceCreate) {
        if (!graphicBuffer.get()) {
            clear();
            return;
        }

        if (!mTextureRelease) {
            mTextureRelease = new AutoBackendTextureRelease(context, graphicBuffer.get());
        }

        mDataspace = dataspace;
        mTextureRelease->makeImage(graphicBuffer, dataspace, context);
    }
}

void ImageConsumer::ImageSlot::clear() {
    if (mTextureRelease) {
        // The following unref counteracts the initial mUsageCount of 1, set by default initializer.
        mTextureRelease->unref(true);
        mTextureRelease = nullptr;
    }
}

sk_sp<SkImage> ImageConsumer::ImageSlot::getImage() {
    return mTextureRelease ? mTextureRelease->getImage() : nullptr;
}

sk_sp<SkImage> ImageConsumer::dequeueImage(bool* queueEmpty, SurfaceTexture& st,
                                           uirenderer::RenderState& renderState) {
    BufferItem item;
    status_t err;
    err = st.acquireBufferLocked(&item, 0);
    if (err != OK) {
        if (err != BufferQueue::NO_BUFFER_AVAILABLE) {
            IMG_LOGE("Error acquiring buffer: %s (%d)", strerror(err), err);
        } else {
            int slot = st.mCurrentTexture;
            if (slot != BufferItem::INVALID_BUFFER_SLOT) {
                *queueEmpty = true;
                mImageSlots[slot].createIfNeeded(st.mSlots[slot].mGraphicBuffer,
                        st.mCurrentDataSpace, false, renderState.getRenderThread().getGrContext());
                return mImageSlots[slot].getImage();
            }
        }
        return nullptr;
    }

    int slot = item.mSlot;
    if (item.mFence->isValid()) {
        // Wait on the producer fence for the buffer to be ready.
        if (uirenderer::Properties::getRenderPipelineType() ==
            uirenderer::RenderPipelineType::SkiaGL) {
            err = renderState.getRenderThread().eglManager().fenceWait(item.mFence);
        } else {
            err = renderState.getRenderThread().vulkanManager().fenceWait(item.mFence);
        }
        if (err != OK) {
            st.releaseBufferLocked(slot, st.mSlots[slot].mGraphicBuffer, EGL_NO_DISPLAY,
                                   EGL_NO_SYNC_KHR);
            return nullptr;
        }
    }

    // Release old buffer.
    if (st.mCurrentTexture != BufferItem::INVALID_BUFFER_SLOT) {
        // If needed, set the released slot's fence to guard against a producer accessing the
        // buffer before the outstanding accesses have completed.
        sp<Fence> releaseFence;
        EGLDisplay display = EGL_NO_DISPLAY;
        if (uirenderer::Properties::getRenderPipelineType() ==
            uirenderer::RenderPipelineType::SkiaGL) {
            auto& eglManager = renderState.getRenderThread().eglManager();
            display = eglManager.eglDisplay();
            err = eglManager.createReleaseFence(st.mUseFenceSync, &mImageSlots[slot].eglFence(),
                                                releaseFence);
        } else {
            err = renderState.getRenderThread().vulkanManager().createReleaseFence(releaseFence);
        }
        if (OK != err) {
            st.releaseBufferLocked(slot, st.mSlots[slot].mGraphicBuffer, EGL_NO_DISPLAY,
                                   EGL_NO_SYNC_KHR);
            return nullptr;
        }

        if (releaseFence.get()) {
            status_t err = st.addReleaseFenceLocked(
                    st.mCurrentTexture, st.mSlots[st.mCurrentTexture].mGraphicBuffer, releaseFence);
            if (err != OK) {
                IMG_LOGE("dequeueImage: error adding release fence: %s (%d)", strerror(-err), err);
                st.releaseBufferLocked(slot, st.mSlots[slot].mGraphicBuffer, EGL_NO_DISPLAY,
                                       EGL_NO_SYNC_KHR);
                return nullptr;
            }
        }

        // Finally release the old buffer.
        status_t status = st.releaseBufferLocked(
                st.mCurrentTexture, st.mSlots[st.mCurrentTexture].mGraphicBuffer, display,
                mImageSlots[st.mCurrentTexture].eglFence());
        if (status < NO_ERROR) {
            IMG_LOGE("dequeueImage: failed to release buffer: %s (%d)", strerror(-status), status);
            err = status;
            // Keep going, with error raised.
        }
    }

    // Update the state.
    st.mCurrentTexture = slot;
    st.mCurrentCrop = item.mCrop;
    st.mCurrentTransform = item.mTransform;
    st.mCurrentScalingMode = item.mScalingMode;
    st.mCurrentTimestamp = item.mTimestamp;
    st.mCurrentDataSpace = item.mDataSpace;
    st.mCurrentFence = item.mFence;
    st.mCurrentFenceTime = item.mFenceTime;
    st.mCurrentFrameNumber = item.mFrameNumber;
    st.computeCurrentTransformMatrixLocked();

    *queueEmpty = false;
    mImageSlots[slot].createIfNeeded(st.mSlots[slot].mGraphicBuffer, item.mDataSpace, true,
        renderState.getRenderThread().getGrContext());
    return mImageSlots[slot].getImage();
}

} /* namespace android */
