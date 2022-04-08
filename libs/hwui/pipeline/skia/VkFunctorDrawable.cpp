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

#include "VkFunctorDrawable.h"
#include <private/hwui/DrawVkInfo.h>

#include <GrBackendDrawableInfo.h>
#include <SkAndroidFrameworkUtils.h>
#include <SkImage.h>
#include "include/private/SkM44.h"
#include <utils/Color.h>
#include <utils/Trace.h>
#include <utils/TraceUtils.h>
#include <vk/GrVkTypes.h>
#include <thread>
#include "renderthread/RenderThread.h"
#include "renderthread/VulkanManager.h"
#include "thread/ThreadBase.h"
#include "utils/TimeUtils.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

VkFunctorDrawHandler::VkFunctorDrawHandler(sp<WebViewFunctor::Handle> functor_handle,
                                           const SkMatrix& matrix, const SkIRect& clip,
                                           const SkImageInfo& image_info)
        : INHERITED()
        , mFunctorHandle(functor_handle)
        , mMatrix(matrix)
        , mClip(clip)
        , mImageInfo(image_info) {}

VkFunctorDrawHandler::~VkFunctorDrawHandler() {
    if (mDrawn) {
        mFunctorHandle->postDrawVk();
    }
}

void VkFunctorDrawHandler::draw(const GrBackendDrawableInfo& info) {
    ATRACE_CALL();
    if (!renderthread::RenderThread::isCurrent())
        LOG_ALWAYS_FATAL("VkFunctorDrawHandler::draw not called on render thread");

    GrVkDrawableInfo vulkan_info;
    if (!info.getVkDrawableInfo(&vulkan_info)) {
        return;
    }
    renderthread::VulkanManager& vk_manager =
            renderthread::RenderThread::getInstance().vulkanManager();
    mFunctorHandle->initVk(vk_manager.getVkFunctorInitParams());

    SkM44 mat4(mMatrix);
    VkFunctorDrawParams params{
            .width = mImageInfo.width(),
            .height = mImageInfo.height(),
            .color_space_ptr = mImageInfo.colorSpace(),
            .clip_left = mClip.fLeft,
            .clip_top = mClip.fTop,
            .clip_right = mClip.fRight,
            .clip_bottom = mClip.fBottom,
    };
    mat4.getColMajor(&params.transform[0]);
    params.secondary_command_buffer = vulkan_info.fSecondaryCommandBuffer;
    params.color_attachment_index = vulkan_info.fColorAttachmentIndex;
    params.compatible_render_pass = vulkan_info.fCompatibleRenderPass;
    params.format = vulkan_info.fFormat;

    mFunctorHandle->drawVk(params);
    mDrawn = true;

    vulkan_info.fDrawBounds->offset.x = mClip.fLeft;
    vulkan_info.fDrawBounds->offset.y = mClip.fTop;
    vulkan_info.fDrawBounds->extent.width = mClip.fRight - mClip.fLeft;
    vulkan_info.fDrawBounds->extent.height = mClip.fBottom - mClip.fTop;
}

VkFunctorDrawable::~VkFunctorDrawable() {}

void VkFunctorDrawable::onDraw(SkCanvas* canvas) {
    // "canvas" is either SkNWayCanvas created by SkiaPipeline::tryCapture (SKP capture use case) or
    // AlphaFilterCanvas (used by RenderNodeDrawable to apply alpha in certain cases).
    // "VkFunctorDrawable::onDraw" is not invoked for the most common case, when drawing in a GPU
    // canvas.

    if (canvas->getGrContext() == nullptr) {
        // We're dumping a picture, render a light-blue rectangle instead
        SkPaint paint;
        paint.setColor(0xFF81D4FA);
        canvas->drawRect(mBounds, paint);
    } else {
        // Handle the case when "canvas" is AlphaFilterCanvas. Find the wrapped GPU canvas.
        SkCanvas* gpuCanvas = SkAndroidFrameworkUtils::getBaseWrappedCanvas(canvas);
        // Enforce "canvas" must be an AlphaFilterCanvas. For GPU canvas, the call should come from
        // onSnapGpuDrawHandler.
        LOG_ALWAYS_FATAL_IF(gpuCanvas == canvas,
                            "VkFunctorDrawable::onDraw() should not be called with a GPU canvas!");

        // This will invoke onSnapGpuDrawHandler and regular draw flow.
        gpuCanvas->drawDrawable(this);
    }
}

std::unique_ptr<FunctorDrawable::GpuDrawHandler> VkFunctorDrawable::onSnapGpuDrawHandler(
        GrBackendApi backendApi, const SkMatrix& matrix, const SkIRect& clip,
        const SkImageInfo& image_info) {
    if (backendApi != GrBackendApi::kVulkan) {
        return nullptr;
    }
    std::unique_ptr<VkFunctorDrawHandler> draw;
    if (mAnyFunctor.index() == 0) {
        return std::make_unique<VkFunctorDrawHandler>(std::get<0>(mAnyFunctor).handle, matrix, clip,
                                                      image_info);
    } else {
        LOG_ALWAYS_FATAL("Not implemented");
    }
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
