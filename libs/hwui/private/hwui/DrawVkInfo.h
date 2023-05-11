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

#include <SkColorSpace.h>
#include <vulkan/vulkan.h>

namespace android {
namespace uirenderer {

struct VkFunctorInitParams {
  VkInstance instance;
  VkPhysicalDevice physical_device;
  VkDevice device;
  VkQueue queue;
  uint32_t graphics_queue_index;
  uint32_t api_version;
  const char* const* enabled_instance_extension_names;
  uint32_t enabled_instance_extension_names_length;
  const char* const* enabled_device_extension_names;
  uint32_t enabled_device_extension_names_length;
  const VkPhysicalDeviceFeatures2* device_features_2;
};

struct VkFunctorDrawParams {
  // Input: current width/height of destination surface.
  int width;
  int height;

  // Input: current transform matrix
  float transform[16];

  // Input WebView should do its main compositing draws into this. It cannot do
  // anything that would require stopping the render pass.
  VkCommandBuffer secondary_command_buffer;

  // Input: The main color attachment index where secondary_command_buffer will
  // eventually be submitted.
  uint32_t color_attachment_index;

  // Input: A render pass which will be compatible to the one which the
  // secondary_command_buffer will be submitted into.
  VkRenderPass compatible_render_pass;

  // Input: Format of the destination surface.
  VkFormat format;

  // Input: Color space.
  const SkColorSpace* color_space_ptr;

  // Input: current clip rect
  int clip_left;
  int clip_top;
  int clip_right;
  int clip_bottom;

  // Input: Whether destination surface is offscreen surface.
  bool is_layer;

  // The current HDR/SDR ratio that we are rendering to. The transform to SDR will already
  // be baked into the color_space_ptr, so this is just to indicate the amount of extended
  // range is available if desired
  float currentHdrSdrRatio;

  // Whether or not dithering is globally enabled
  bool shouldDither;
};

}  // namespace uirenderer
}  // namespace android

#endif  // ANDROID_HWUI_DRAW_VK_INFO_H
