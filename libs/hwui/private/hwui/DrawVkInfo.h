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

#ifndef ANDROID_HWUI_DRAW_VK_INFO_H
#define ANDROID_HWUI_DRAW_VK_INFO_H

#include <vulkan/vulkan.h>

namespace android {
namespace uirenderer {

/**
 * Structure used by VulkanRenderer::callDrawVKFunction() to pass and receive data from Vulkan
 * functors.
 */
struct DrawVkInfo {
    // Input: current width/height of destination surface
    int width;
    int height;

    // Input: is the render target an FBO
    bool isLayer;

    // Input: current transform matrix, in OpenGL format
    float transform[16];

    // Input: WebView should do its main compositing draws into this. It cannot do anything that
    // would require stopping the render pass.
    VkCommandBuffer secondaryCommandBuffer;

    // Input: The main color attachment index where secondaryCommandBuffer will eventually be
    // submitted.
    uint32_t colorAttachmentIndex;

    // Input: A render pass which will be compatible to the one which the secondaryCommandBuffer
    // will be submitted into.
    VkRenderPass compatibleRenderPass;

    // Input: Format of the destination surface.
    VkFormat format;

    // Input: Color space
    const SkColorSpace* colorSpaceInfo;

    // Input: current clip rect
    int clipLeft;
    int clipTop;
    int clipRight;
    int clipBottom;

    /**
     * Values used as the "what" parameter of the functor.
     */
    enum Mode {
        // Called once at WebView start
        kModeInit,
        // Called when things need to be re-created
        kModeReInit,
        // Notifies the app that the composite functor will be called soon. This allows WebView to
        // begin work early.
        kModePreComposite,
        // Do the actual composite work
        kModeComposite,
        // This allows WebView to begin using the previously submitted objects in future work.
        kModePostComposite,
        // Invoked every time the UI thread pushes over a frame to the render thread and the owning
        // view has a dirty display list*. This is a signal to sync any data that needs to be
        // shared between the UI thread and the render thread. During this time the UI thread is
        // blocked.
        kModeSync
    };

    /**
     * Values used by Vulkan functors to tell the framework what to do next.
     */
    enum Status {
        // The functor is done
        kStatusDone = 0x0,
    };
};  // struct DrawVkInfo

}  // namespace uirenderer
}  // namespace android

#endif  // ANDROID_HWUI_DRAW_VK_INFO_H
