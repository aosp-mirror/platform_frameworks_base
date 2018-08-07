/*
 * Copyright (C) 2012 The Android Open Source Project
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
// GL Functor data types into the types the chromium stack expects, and back.

#define LOG_TAG "webviewchromium_plat_support"

#include "draw_gl.h"

#include <Properties.h>
#include <errno.h>
#include <jni.h>
#include <private/hwui/DrawGlInfo.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/time.h>
#include <utils/Functor.h>
#include <utils/Log.h>

#define NELEM(x) ((int) (sizeof(x) / sizeof((x)[0])))
#define COMPILE_ASSERT(expr, err) \
__unused static const char (err)[(expr) ? 1 : -1] = "";

namespace android {
namespace {

AwDrawGLFunction* g_aw_drawgl_function = NULL;

class DrawGLFunctor : public Functor {
 public:
  explicit DrawGLFunctor(jlong view_context) : view_context_(view_context) {}
  virtual ~DrawGLFunctor() {}

  // Functor
  virtual status_t operator ()(int what, void* data) {
    using uirenderer::DrawGlInfo;
    if (!g_aw_drawgl_function) {
      ALOGE("Cannot draw: no DrawGL Function installed");
      return DrawGlInfo::kStatusDone;
    }

    AwDrawGLInfo aw_info;
    // TODO(boliu): Remove property check once OpenGL fallback is removed.
    auto render_pipeline_type =
        android::uirenderer::Properties::getRenderPipelineType();
    aw_info.version = (render_pipeline_type ==
                       android::uirenderer::RenderPipelineType::OpenGL)
                          ? 2
                          : kAwDrawGLInfoVersion;
    switch (what) {
      case DrawGlInfo::kModeDraw: {
        aw_info.mode = AwDrawGLInfo::kModeDraw;
        DrawGlInfo* gl_info = reinterpret_cast<DrawGlInfo*>(data);

        // Map across the input values.
        aw_info.clip_left = gl_info->clipLeft;
        aw_info.clip_top = gl_info->clipTop;
        aw_info.clip_right = gl_info->clipRight;
        aw_info.clip_bottom = gl_info->clipBottom;
        aw_info.width = gl_info->width;
        aw_info.height = gl_info->height;
        aw_info.is_layer = gl_info->isLayer;
        COMPILE_ASSERT(NELEM(aw_info.transform) == NELEM(gl_info->transform),
                       mismatched_transform_matrix_sizes);
        for (int i = 0; i < NELEM(aw_info.transform); ++i) {
          aw_info.transform[i] = gl_info->transform[i];
        }
        break;
      }
      case DrawGlInfo::kModeProcess:
        aw_info.mode = AwDrawGLInfo::kModeProcess;
        break;
      case DrawGlInfo::kModeProcessNoContext:
        aw_info.mode = AwDrawGLInfo::kModeProcessNoContext;
        break;
      case DrawGlInfo::kModeSync:
        aw_info.mode = AwDrawGLInfo::kModeSync;
        break;
      default:
        ALOGE("Unexpected DrawGLInfo type %d", what);
        return DrawGlInfo::kStatusDone;
    }

    // Invoke the DrawGL method.
    g_aw_drawgl_function(view_context_, &aw_info, NULL);

    return DrawGlInfo::kStatusDone;
  }

 private:
  intptr_t view_context_;
};

// Raise the file handle soft limit to the hard limit since gralloc buffers
// uses file handles.
void RaiseFileNumberLimit() {
  static bool have_raised_limit = false;
  if (have_raised_limit)
    return;

  have_raised_limit = true;
  struct rlimit limit_struct;
  limit_struct.rlim_cur = 0;
  limit_struct.rlim_max = 0;
  if (getrlimit(RLIMIT_NOFILE, &limit_struct) == 0) {
    limit_struct.rlim_cur = limit_struct.rlim_max;
    if (setrlimit(RLIMIT_NOFILE, &limit_struct) != 0) {
      ALOGE("setrlimit failed: %s", strerror(errno));
    }
  } else {
    ALOGE("getrlimit failed: %s", strerror(errno));
  }
}

jlong CreateGLFunctor(JNIEnv*, jclass, jlong view_context) {
  RaiseFileNumberLimit();
  return reinterpret_cast<jlong>(new DrawGLFunctor(view_context));
}

void DestroyGLFunctor(JNIEnv*, jclass, jlong functor) {
  delete reinterpret_cast<DrawGLFunctor*>(functor);
}

void SetChromiumAwDrawGLFunction(JNIEnv*, jclass, jlong draw_function) {
  g_aw_drawgl_function = reinterpret_cast<AwDrawGLFunction*>(draw_function);
}

const char kClassName[] = "com/android/webview/chromium/DrawGLFunctor";
const JNINativeMethod kJniMethods[] = {
    { "nativeCreateGLFunctor", "(J)J",
        reinterpret_cast<void*>(CreateGLFunctor) },
    { "nativeDestroyGLFunctor", "(J)V",
        reinterpret_cast<void*>(DestroyGLFunctor) },
    { "nativeSetChromiumAwDrawGLFunction", "(J)V",
        reinterpret_cast<void*>(SetChromiumAwDrawGLFunction) },
};

}  // namespace

void RegisterDrawGLFunctor(JNIEnv* env) {
  jclass clazz = env->FindClass(kClassName);
  LOG_ALWAYS_FATAL_IF(!clazz, "Unable to find class '%s'", kClassName);

  int res = env->RegisterNatives(clazz, kJniMethods, NELEM(kJniMethods));
  LOG_ALWAYS_FATAL_IF(res < 0, "register native methods failed: res=%d", res);
}

}  // namespace android
