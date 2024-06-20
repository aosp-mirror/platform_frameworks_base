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

#include "draw_fn.h"

#include <jni.h>
#include <private/hwui/WebViewFunctor.h>
#include <utils/Log.h>

#define NELEM(x) ((int) (sizeof(x) / sizeof((x)[0])))
#define COMPILE_ASSERT(expr, err) \
__unused static const char (err)[(expr) ? 1 : -1] = "";

namespace android {
namespace {

struct SupportData {
  void* const data;
  AwDrawFnFunctorCallbacks callbacks;
};

AwDrawFnOverlaysMode GetOverlaysMode(uirenderer::OverlaysMode overlays_mode) {
  switch (overlays_mode) {
    case uirenderer::OverlaysMode::Disabled:
      return AW_DRAW_FN_OVERLAYS_MODE_DISABLED;
    case uirenderer::OverlaysMode::Enabled:
      return AW_DRAW_FN_OVERLAYS_MODE_ENABLED;
  }
}

void onSync(int functor, void* data,
            const uirenderer::WebViewSyncData& syncData) {
  AwDrawFn_OnSyncParams params = {
      .version = kAwDrawFnVersion,
      .apply_force_dark = syncData.applyForceDark,
  };
  SupportData* support = static_cast<SupportData*>(data);
  support->callbacks.on_sync(functor, support->data, &params);
}

void onContextDestroyed(int functor, void* data) {
  SupportData* support = static_cast<SupportData*>(data);
  support->callbacks.on_context_destroyed(functor, support->data);
}

void onDestroyed(int functor, void* data) {
  SupportData* support = static_cast<SupportData*>(data);
  support->callbacks.on_destroyed(functor, support->data);
  delete support;
}

void removeOverlays(int functor, void* data,
                    AwDrawFn_MergeTransaction merge_transaction) {
  AwDrawFn_RemoveOverlaysParams params = {
      .version = kAwDrawFnVersion,
      .merge_transaction = merge_transaction
  };
  SupportData* support = static_cast<SupportData*>(data);
  if (support->callbacks.remove_overlays)
    support->callbacks.remove_overlays(functor, support->data, &params);
}

void draw_gl(int functor, void* data,
             const uirenderer::DrawGlInfo& draw_gl_params,
             const uirenderer::WebViewOverlayData& overlay_params) {
  float gabcdef[7];
  if (draw_gl_params.color_space_ptr) {
      draw_gl_params.color_space_ptr->transferFn(gabcdef);
  } else {
      // Assume sRGB.
      gabcdef[0] = SkNamedTransferFn::kSRGB.g;
      gabcdef[1] = SkNamedTransferFn::kSRGB.a;
      gabcdef[2] = SkNamedTransferFn::kSRGB.b;
      gabcdef[3] = SkNamedTransferFn::kSRGB.c;
      gabcdef[4] = SkNamedTransferFn::kSRGB.d;
      gabcdef[5] = SkNamedTransferFn::kSRGB.e;
      gabcdef[6] = SkNamedTransferFn::kSRGB.f;
  }
  AwDrawFn_DrawGLParams params = {
      .version = kAwDrawFnVersion,
      .clip_left = draw_gl_params.clipLeft,
      .clip_top = draw_gl_params.clipTop,
      .clip_right = draw_gl_params.clipRight,
      .clip_bottom = draw_gl_params.clipBottom,
      .width = draw_gl_params.width,
      .height = draw_gl_params.height,
      .deprecated_0 = false,
      .transfer_function_g = gabcdef[0],
      .transfer_function_a = gabcdef[1],
      .transfer_function_b = gabcdef[2],
      .transfer_function_c = gabcdef[3],
      .transfer_function_d = gabcdef[4],
      .transfer_function_e = gabcdef[5],
      .transfer_function_f = gabcdef[6],
      .overlays_mode = GetOverlaysMode(overlay_params.overlaysMode),
      .get_surface_control = overlay_params.getSurfaceControl,
      .merge_transaction = overlay_params.mergeTransaction
  };
  COMPILE_ASSERT(NELEM(params.transform) == NELEM(draw_gl_params.transform),
                 mismatched_transform_matrix_sizes);
  for (int i = 0; i < NELEM(params.transform); ++i) {
    params.transform[i] = draw_gl_params.transform[i];
  }
  COMPILE_ASSERT(sizeof(params.color_space_toXYZD50) == sizeof(skcms_Matrix3x3),
                 gamut_transform_size_mismatch);
  if (draw_gl_params.color_space_ptr) {
      draw_gl_params.color_space_ptr->toXYZD50(
              reinterpret_cast<skcms_Matrix3x3*>(&params.color_space_toXYZD50));
  } else {
      // Assume sRGB.
      memcpy(&params.color_space_toXYZD50, &SkNamedGamut::kSRGB,
             sizeof(params.color_space_toXYZD50));
  }

  SupportData* support = static_cast<SupportData*>(data);
  support->callbacks.draw_gl(functor, support->data, &params);
}

void initializeVk(int functor, void* data,
                  const uirenderer::VkFunctorInitParams& init_vk_params) {
  SupportData* support = static_cast<SupportData*>(data);
  VkPhysicalDeviceFeatures2 device_features_2;
  if (init_vk_params.device_features_2)
    device_features_2 = *init_vk_params.device_features_2;

  AwDrawFn_InitVkParams params{
      .version = kAwDrawFnVersion,
      .instance = init_vk_params.instance,
      .physical_device = init_vk_params.physical_device,
      .device = init_vk_params.device,
      .queue = init_vk_params.queue,
      .graphics_queue_index = init_vk_params.graphics_queue_index,
      .api_version = init_vk_params.api_version,
      .enabled_instance_extension_names =
          init_vk_params.enabled_instance_extension_names,
      .enabled_instance_extension_names_length =
          init_vk_params.enabled_instance_extension_names_length,
      .enabled_device_extension_names =
          init_vk_params.enabled_device_extension_names,
      .enabled_device_extension_names_length =
          init_vk_params.enabled_device_extension_names_length,
      .device_features = nullptr,
      .device_features_2 =
          init_vk_params.device_features_2 ? &device_features_2 : nullptr,
  };
  support->callbacks.init_vk(functor, support->data, &params);
}

void drawVk(int functor, void* data,
            const uirenderer::VkFunctorDrawParams& draw_vk_params,
            const uirenderer::WebViewOverlayData& overlay_params) {
  SupportData* support = static_cast<SupportData*>(data);
  float gabcdef[7];
  if (draw_vk_params.color_space_ptr) {
      draw_vk_params.color_space_ptr->transferFn(gabcdef);
  } else {
      // Assume sRGB.
      gabcdef[0] = SkNamedTransferFn::kSRGB.g;
      gabcdef[1] = SkNamedTransferFn::kSRGB.a;
      gabcdef[2] = SkNamedTransferFn::kSRGB.b;
      gabcdef[3] = SkNamedTransferFn::kSRGB.c;
      gabcdef[4] = SkNamedTransferFn::kSRGB.d;
      gabcdef[5] = SkNamedTransferFn::kSRGB.e;
      gabcdef[6] = SkNamedTransferFn::kSRGB.f;
  }
  AwDrawFn_DrawVkParams params{
      .version = kAwDrawFnVersion,
      .width = draw_vk_params.width,
      .height = draw_vk_params.height,
      .deprecated_0 = false,
      .secondary_command_buffer = draw_vk_params.secondary_command_buffer,
      .color_attachment_index = draw_vk_params.color_attachment_index,
      .compatible_render_pass = draw_vk_params.compatible_render_pass,
      .format = draw_vk_params.format,
      .transfer_function_g = gabcdef[0],
      .transfer_function_a = gabcdef[1],
      .transfer_function_b = gabcdef[2],
      .transfer_function_c = gabcdef[3],
      .transfer_function_d = gabcdef[4],
      .transfer_function_e = gabcdef[5],
      .transfer_function_f = gabcdef[6],
      .clip_left = draw_vk_params.clip_left,
      .clip_top = draw_vk_params.clip_top,
      .clip_right = draw_vk_params.clip_right,
      .clip_bottom = draw_vk_params.clip_bottom,
      .overlays_mode = GetOverlaysMode(overlay_params.overlaysMode),
      .get_surface_control = overlay_params.getSurfaceControl,
      .merge_transaction = overlay_params.mergeTransaction
  };
  COMPILE_ASSERT(sizeof(params.color_space_toXYZD50) == sizeof(skcms_Matrix3x3),
                 gamut_transform_size_mismatch);
  if (draw_vk_params.color_space_ptr) {
      draw_vk_params.color_space_ptr->toXYZD50(
              reinterpret_cast<skcms_Matrix3x3*>(&params.color_space_toXYZD50));
  } else {
      // Assume sRGB.
      memcpy(&params.color_space_toXYZD50, &SkNamedGamut::kSRGB,
             sizeof(params.color_space_toXYZD50));
  }
  COMPILE_ASSERT(NELEM(params.transform) == NELEM(draw_vk_params.transform),
                 mismatched_transform_matrix_sizes);
  for (int i = 0; i < NELEM(params.transform); ++i) {
    params.transform[i] = draw_vk_params.transform[i];
  }
  support->callbacks.draw_vk(functor, support->data, &params);
}

void postDrawVk(int functor, void* data) {
  SupportData* support = static_cast<SupportData*>(data);
  AwDrawFn_PostDrawVkParams params{.version = kAwDrawFnVersion};
  support->callbacks.post_draw_vk(functor, support->data, &params);
}

int CreateFunctor_v3(void* data, int version,
                     AwDrawFnFunctorCallbacks* functor_callbacks) {
    static uirenderer::WebViewFunctorCallbacks webview_functor_callbacks = [] {
        uirenderer::WebViewFunctorCallbacks ret = {
                .onSync = &onSync,
                .onContextDestroyed = &onContextDestroyed,
                .onDestroyed = &onDestroyed,
                .removeOverlays = &removeOverlays,
        };
        switch (uirenderer::WebViewFunctor_queryPlatformRenderMode()) {
            case uirenderer::RenderMode::OpenGL_ES:
                ret.gles.draw = &draw_gl;
                break;
            case uirenderer::RenderMode::Vulkan:
                ret.vk.initialize = &initializeVk;
                ret.vk.draw = &drawVk;
                ret.vk.postDraw = &postDrawVk;
                break;
        }
        return ret;
    }();
    SupportData* support = new SupportData{
            .data = data,
    };

    // These callbacks are available on all versions.
    support->callbacks = {
            .on_sync = functor_callbacks->on_sync,
            .on_context_destroyed = functor_callbacks->on_context_destroyed,
            .on_destroyed = functor_callbacks->on_destroyed,
            .draw_gl = functor_callbacks->draw_gl,
            .init_vk = functor_callbacks->init_vk,
            .draw_vk = functor_callbacks->draw_vk,
            .post_draw_vk = functor_callbacks->post_draw_vk,
    };

    if (version >= 3) {
        support->callbacks.remove_overlays = functor_callbacks->remove_overlays;
    }

  int functor = uirenderer::WebViewFunctor_create(
      support, webview_functor_callbacks,
      uirenderer::WebViewFunctor_queryPlatformRenderMode());
  if (functor <= 0) delete support;
  return functor;
}

int CreateFunctor(void* data, AwDrawFnFunctorCallbacks* functor_callbacks) {
  const int kVersionForDeprecatedCreateFunctor = 2;
  return CreateFunctor_v3(data, kVersionForDeprecatedCreateFunctor,
                          functor_callbacks);
}

void ReleaseFunctor(int functor) {
  uirenderer::WebViewFunctor_release(functor);
}

AwDrawFnRenderMode QueryRenderMode(void) {
  switch (uirenderer::WebViewFunctor_queryPlatformRenderMode()) {
    case uirenderer::RenderMode::OpenGL_ES:
      return AW_DRAW_FN_RENDER_MODE_OPENGL_ES;
    case uirenderer::RenderMode::Vulkan:
      return AW_DRAW_FN_RENDER_MODE_VULKAN;
  }
}

void ReportRenderingThreads(int functor, const int32_t* thread_ids, size_t size) {
    uirenderer::WebViewFunctor_reportRenderingThreads(functor, thread_ids, size);
}

jlong GetDrawFnFunctionTable() {
    static AwDrawFnFunctionTable function_table = {
            .version = kAwDrawFnVersion,
            .query_render_mode = &QueryRenderMode,
            .create_functor = &CreateFunctor,
            .release_functor = &ReleaseFunctor,
            .create_functor_v3 = &CreateFunctor_v3,
            .report_rendering_threads = &ReportRenderingThreads,
    };
    return reinterpret_cast<intptr_t>(&function_table);
}

const char kClassName[] = "com/android/webview/chromium/DrawFunctor";
const JNINativeMethod kJniMethods[] = {
    {"nativeGetFunctionTable", "()J",
     reinterpret_cast<void*>(GetDrawFnFunctionTable)},
};

}  // namespace

void RegisterDrawFunctor(JNIEnv* env) {
  jclass clazz = env->FindClass(kClassName);
  LOG_ALWAYS_FATAL_IF(!clazz, "Unable to find class '%s'", kClassName);

  int res = env->RegisterNatives(clazz, kJniMethods, NELEM(kJniMethods));
  LOG_ALWAYS_FATAL_IF(res < 0, "register native methods failed: res=%d", res);
}

}  // namespace android
