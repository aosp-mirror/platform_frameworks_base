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

void draw_gl(int functor, void* data,
             const uirenderer::DrawGlInfo& draw_gl_params) {
  AwDrawFn_DrawGLParams params = {
      .version = kAwDrawFnVersion,
      .clip_left = draw_gl_params.clipLeft,
      .clip_top = draw_gl_params.clipTop,
      .clip_right = draw_gl_params.clipRight,
      .clip_bottom = draw_gl_params.clipBottom,
      .width = draw_gl_params.width,
      .height = draw_gl_params.height,
      .is_layer = draw_gl_params.isLayer,
  };
  COMPILE_ASSERT(NELEM(params.transform) == NELEM(draw_gl_params.transform),
                 mismatched_transform_matrix_sizes);
  for (int i = 0; i < NELEM(params.transform); ++i) {
    params.transform[i] = draw_gl_params.transform[i];
  }
  SupportData* support = static_cast<SupportData*>(data);
  support->callbacks.draw_gl(functor, support->data, &params);
}

int CreateFunctor(void* data, AwDrawFnFunctorCallbacks* functor_callbacks) {
  static bool callbacks_initialized = false;
  static uirenderer::WebViewFunctorCallbacks webview_functor_callbacks = {
      .onSync = &onSync,
      .onContextDestroyed = &onContextDestroyed,
      .onDestroyed = &onDestroyed,
  };
  if (!callbacks_initialized) {
    // Under uirenderer::RenderMode::Vulkan, whether gles or vk union should
    // be populated should match whether the vk-gl interop is used.
    webview_functor_callbacks.gles.draw = &draw_gl;
    callbacks_initialized = true;
  }
  SupportData* support = new SupportData{
      .data = data,
      .callbacks = *functor_callbacks,
  };
  int functor = uirenderer::WebViewFunctor_create(
      support, webview_functor_callbacks,
      uirenderer::WebViewFunctor_queryPlatformRenderMode());
  if (functor <= 0) delete support;
  return functor;
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

jlong GetDrawFnFunctionTable() {
  static AwDrawFnFunctionTable function_table = {
    .version = kAwDrawFnVersion,
    .query_render_mode = &QueryRenderMode,
    .create_functor = &CreateFunctor,
    .release_functor = &ReleaseFunctor,
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
