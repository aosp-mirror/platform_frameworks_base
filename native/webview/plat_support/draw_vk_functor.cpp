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

// Provides a webviewchromium glue layer adapter from the internal Android
// Vulkan Functor data types into the types the chromium stack expects, and
// back.

#define LOG_TAG "webviewchromium_plat_support"

#include "draw_fn.h"
#include "draw_vk.h"

#include <jni.h>
#include <private/hwui/DrawVkInfo.h>
#include <utils/Functor.h>
#include <utils/Log.h>

#include "functor_utils.h"

#define NELEM(x) ((int) (sizeof(x) / sizeof((x)[0])))

namespace android {
namespace {

AwDrawVKFunction* g_aw_drawvk_function = NULL;

class DrawVKFunctor : public Functor {
 public:
  explicit DrawVKFunctor(jlong view_context) : view_context_(view_context) {}
  ~DrawVKFunctor() override {}

  // Functor
  status_t operator ()(int what, void* data) override {
    using uirenderer::DrawVkInfo;
    if (!g_aw_drawvk_function) {
      ALOGE("Cannot draw: no DrawVK Function installed");
      return DrawVkInfo::kStatusDone;
    }

    AwDrawVKInfo aw_info;
    aw_info.version = kAwDrawVKInfoVersion;
    switch (what) {
      case DrawVkInfo::kModeComposite: {
        aw_info.mode = AwDrawVKInfo::kModeComposite;
        DrawVkInfo* vk_info = reinterpret_cast<DrawVkInfo*>(data);

        // Map across the input values.
        CompositeParams& params = aw_info.info.composite_params;
        params.width = vk_info->width;
        params.height = vk_info->height;
        params.is_layer = vk_info->isLayer;
        for (size_t i = 0; i < 16; i++) {
            params.transform[i] = vk_info->transform[i];
        }
        params.secondary_command_buffer = vk_info->secondaryCommandBuffer;
        params.color_attachment_index = vk_info->colorAttachmentIndex;
        params.compatible_render_pass = vk_info->compatibleRenderPass;
        params.format = vk_info->format;
        params.G = vk_info->G;
        params.A = vk_info->A;
        params.B = vk_info->B;
        params.C = vk_info->C;
        params.D = vk_info->D;
        params.E = vk_info->E;
        params.F = vk_info->F;
        for (size_t i = 0; i < 9; i++) {
            params.matrix[i] = vk_info->matrix[i];
        }
        params.clip_left = vk_info->clipLeft;
        params.clip_top = vk_info->clipTop;
        params.clip_right = vk_info->clipRight;
        params.clip_bottom = vk_info->clipBottom;

        break;
      }
      case DrawVkInfo::kModePostComposite:
        break;
      case DrawVkInfo::kModeSync:
        aw_info.mode = AwDrawVKInfo::kModeSync;
        break;
      default:
        ALOGE("Unexpected DrawVKInfo type %d", what);
        return DrawVkInfo::kStatusDone;
    }

    // Invoke the DrawVK method.
    g_aw_drawvk_function(view_context_, &aw_info);

    return DrawVkInfo::kStatusDone;
  }

 private:
  intptr_t view_context_;
};

jlong CreateVKFunctor(JNIEnv*, jclass, jlong view_context) {
  RaiseFileNumberLimit();
  return reinterpret_cast<jlong>(new DrawVKFunctor(view_context));
}

void DestroyVKFunctor(JNIEnv*, jclass, jlong functor) {
  delete reinterpret_cast<DrawVKFunctor*>(functor);
}

void SetChromiumAwDrawVKFunction(JNIEnv*, jclass, jlong draw_function) {
  g_aw_drawvk_function = reinterpret_cast<AwDrawVKFunction*>(draw_function);
}

const char kClassName[] = "com/android/webview/chromium/DrawVKFunctor";
const JNINativeMethod kJniMethods[] = {
    { "nativeCreateVKFunctor", "(J)J",
        reinterpret_cast<void*>(CreateVKFunctor) },
    { "nativeDestroyVKFunctor", "(J)V",
        reinterpret_cast<void*>(DestroyVKFunctor) },
    { "nativeSetChromiumAwDrawVKFunction", "(J)V",
        reinterpret_cast<void*>(SetChromiumAwDrawVKFunction) },
};

}  // namespace

void RegisterDrawVKFunctor(JNIEnv* env) {
  jclass clazz = env->FindClass(kClassName);
  LOG_ALWAYS_FATAL_IF(!clazz, "Unable to find class '%s'", kClassName);

  int res = env->RegisterNatives(clazz, kJniMethods, NELEM(kJniMethods));
  LOG_ALWAYS_FATAL_IF(res < 0, "register native methods failed: res=%d", res);
}

}  // namespace android
