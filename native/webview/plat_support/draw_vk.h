// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
//
//******************************************************************************
// This is a copy of the coresponding android_webview/public/browser header.
// Any changes to the interface should be made there.
//
// The purpose of having the copy is twofold:
//  - it removes the need to have Chromium sources present in the tree in order
//    to build the plat_support library,
//  - it captures API that the corresponding Android release supports.
//******************************************************************************

#ifndef ANDROID_WEBVIEW_PUBLIC_BROWSER_DRAW_VK_H_
#define ANDROID_WEBVIEW_PUBLIC_BROWSER_DRAW_VK_H_

#include <vulkan/vulkan.h>

#ifdef __cplusplus
extern "C" {
#endif

static const int kAwDrawVKInfoVersion = 1;

// Holds the information required to trigger initialization of the Vulkan
// functor.
struct InitParams {
  // All params are input
  VkInstance instance;
  VkPhysicalDevice physical_device;
  VkDevice device;
  VkQueue queue;
  uint32_t graphics_queue_index;
  uint32_t instance_version;
  const char* const* enabled_extension_names;
  // Only one of device_features and device_features_2 should be non-null.
  // If both are null then no features are enabled.
  VkPhysicalDeviceFeatures* device_features;
  VkPhysicalDeviceFeatures2* device_features_2;
};

// Holds the information required to trigger an Vulkan composite operation.
struct CompositeParams {
  // Input: current width/height of destination surface.
  int width;
  int height;

  // Input: is the render target a FBO
  bool is_layer;

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

  // Input: Color space transfer params
  float G;
  float A;
  float B;
  float C;
  float D;
  float E;
  float F;

  // Input: Color space transformation from linear RGB to D50-adapted XYZ
  float matrix[9];

  // Input: current clip rect
  int clip_left;
  int clip_top;
  int clip_right;
  int clip_bottom;
};

// Holds the information for the post-submission callback of main composite
// draw.
struct PostCompositeParams {
  // Input: Fence for the composite command buffer to signal it has finished its
  // work on the GPU.
  int fd;
};

// Holds the information required to trigger an Vulkan operation.
struct AwDrawVKInfo {
  int version;  // The AwDrawVKInfo this struct was built with.

  // Input: tells the draw function what action to perform.
  enum Mode {
    kModeInit = 0,
    kModeReInit = 1,
    kModePreComposite = 2,
    kModeComposite = 3,
    kModePostComposite = 4,
    kModeSync = 5,
  } mode;

  // Input: The parameters for the functor being called
  union ParamUnion {
    struct InitParams init_params;
    struct CompositeParams composite_params;
    struct PostCompositeParams post_composite_params;
  } info;
};

typedef void(AwDrawVKFunction)(long view_context, AwDrawVKInfo* draw_info);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // ANDROID_WEBVIEW_PUBLIC_BROWSER_DRAW_VK_H_
