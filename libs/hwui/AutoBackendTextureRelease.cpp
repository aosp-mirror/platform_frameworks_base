/*
 * Copyright 2019 The Android Open Source Project
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

#include "AutoBackendTextureRelease.h"

#include <SkImage.h>
#include <include/gpu/MutableTextureState.h>
#include <include/gpu/ganesh/GrBackendSurface.h>
#include <include/gpu/ganesh/GrDirectContext.h>
#include <include/gpu/ganesh/SkImageGanesh.h>
#include <include/gpu/vk/VulkanMutableTextureState.h>

#include "renderthread/RenderThread.h"
#include "utils/Color.h"
#include "utils/PaintUtils.h"

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {

AutoBackendTextureRelease::AutoBackendTextureRelease(GrDirectContext* context,
                                                     AHardwareBuffer* buffer) {
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);
    bool createProtectedImage = 0 != (desc.usage & AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT);

    GrBackendFormat backendFormat;
    GrBackendApi backend = context->backend();
    if (backend == GrBackendApi::kOpenGL) {
        backendFormat =
                GrAHardwareBufferUtils::GetGLBackendFormat(context, desc.format, false);
        mBackendTexture =
                GrAHardwareBufferUtils::MakeGLBackendTexture(context,
                                                             buffer,
                                                             desc.width,
                                                             desc.height,
                                                             &mDeleteProc,
                                                             &mUpdateProc,
                                                             &mImageCtx,
                                                             createProtectedImage,
                                                             backendFormat,
                                                             false);
    } else if (backend == GrBackendApi::kVulkan) {
        backendFormat =
                GrAHardwareBufferUtils::GetVulkanBackendFormat(context,
                                                               buffer,
                                                               desc.format,
                                                               false);
        mBackendTexture =
                GrAHardwareBufferUtils::MakeVulkanBackendTexture(context,
                                                                 buffer,
                                                                 desc.width,
                                                                 desc.height,
                                                                 &mDeleteProc,
                                                                 &mUpdateProc,
                                                                 &mImageCtx,
                                                                 createProtectedImage,
                                                                 backendFormat,
                                                                 false);
    } else {
        LOG_ALWAYS_FATAL("Unexpected backend %d", backend);
    }
    LOG_ALWAYS_FATAL_IF(!backendFormat.isValid(),
                        __FILE__ " Invalid GrBackendFormat. GrBackendApi==%" PRIu32
                                 ", AHardwareBuffer_Format==%" PRIu32 ".",
                        static_cast<int>(context->backend()), desc.format);
    LOG_ALWAYS_FATAL_IF(!mBackendTexture.isValid(),
                        __FILE__ " Invalid GrBackendTexture. Width==%" PRIu32 ", height==%" PRIu32
                                 ", protected==%d",
                        desc.width, desc.height, createProtectedImage);
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
            mDeleteProc(mImageCtx);
            mBackendTexture = {};
        }
        delete this;
    }
}

// releaseProc is invoked by SkImage, when texture is no longer in use.
// "releaseContext" contains an "AutoBackendTextureRelease*".
static void releaseProc(SkImages::ReleaseContext releaseContext) {
    AutoBackendTextureRelease* textureRelease =
            reinterpret_cast<AutoBackendTextureRelease*>(releaseContext);
    textureRelease->unref(false);
}

void AutoBackendTextureRelease::makeImage(AHardwareBuffer* buffer,
                                          android_dataspace dataspace,
                                          GrDirectContext* context) {
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);
    SkColorType colorType = GrAHardwareBufferUtils::GetSkColorTypeFromBufferFormat(desc.format);
    // The following ref will be counteracted by Skia calling releaseProc, either during
    // BorrowTextureFrom if there is a failure, or later when SkImage is discarded. It must
    // be called before BorrowTextureFrom, otherwise Skia may remove HWUI's ref on failure.
    ref();
    mImage = SkImages::BorrowTextureFrom(
            context, mBackendTexture, kTopLeft_GrSurfaceOrigin, colorType, kPremul_SkAlphaType,
            uirenderer::DataSpaceToColorSpace(dataspace), releaseProc, this);
}

void AutoBackendTextureRelease::newBufferContent(GrDirectContext* context) {
    if (mBackendTexture.isValid()) {
        mUpdateProc(mImageCtx, context);
    }
}

void AutoBackendTextureRelease::releaseQueueOwnership(GrDirectContext* context) {
    if (!context) {
        return;
    }

    LOG_ALWAYS_FATAL_IF(Properties::getRenderPipelineType() != RenderPipelineType::SkiaVulkan);
    if (mBackendTexture.isValid()) {
        // Passing in VK_IMAGE_LAYOUT_UNDEFINED means we keep the old layout.
        skgpu::MutableTextureState newState = skgpu::MutableTextureStates::MakeVulkan(
                                                                  VK_IMAGE_LAYOUT_UNDEFINED,
                                                                  VK_QUEUE_FAMILY_FOREIGN_EXT);

        // The unref for this ref happens in the releaseProc passed into setBackendTextureState. The
        // releaseProc callback will be made when the work to set the new state has finished on the
        // gpu.
        ref();
        // Note that we don't have an explicit call to set the backend texture back onto the
        // graphics queue when we use the VkImage again. Internally, Skia will notice that the image
        // is not on the graphics queue and will do the transition automatically.
        context->setBackendTextureState(mBackendTexture, newState, nullptr, releaseProc, this);
    }
}

} /* namespace uirenderer */
} /* namespace android */
