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
// graphics types into the types the chromium stack expects, and back.

#define LOG_TAG "webviewchromium_plat_support"

#include "draw_gl.h"
#include "draw_sw.h"

#include <cstdlib>
#include <jni.h>
#include <utils/Log.h>
#include "GraphicsJNI.h"
#include "graphic_buffer_impl.h"
#include "SkCanvasStateUtils.h"

#define NELEM(x) ((int) (sizeof(x) / sizeof((x)[0])))

namespace android {
namespace {

class PixelInfo : public AwPixelInfo {
 public:
  explicit PixelInfo(android::Canvas* canvas);
  ~PixelInfo();
};


PixelInfo::PixelInfo(android::Canvas* canvas) {
  memset(this, 0, sizeof(AwPixelInfo));
  version = kAwPixelInfoVersion;
  state = canvas->captureCanvasState();
}

PixelInfo::~PixelInfo() {
  if (state)
    SkCanvasStateUtils::ReleaseCanvasState(state);
}

AwPixelInfo* GetPixels(JNIEnv* env, jobject java_canvas) {
  android::Canvas* nativeCanvas = GraphicsJNI::getNativeCanvas(env, java_canvas);
  if (!nativeCanvas)
    return NULL;

  PixelInfo* pixels = new PixelInfo(nativeCanvas);
  if (!pixels->state) {
      delete pixels;
      pixels = NULL;
  }
  return pixels;
}

void ReleasePixels(AwPixelInfo* pixels) {
  delete static_cast<PixelInfo*>(pixels);
}

jlong GetDrawSWFunctionTable(JNIEnv* env, jclass) {
  static AwDrawSWFunctionTable function_table;
  function_table.version = kAwDrawSWFunctionTableVersion;
  function_table.access_pixels = &GetPixels;
  function_table.release_pixels = &ReleasePixels;
  return reinterpret_cast<intptr_t>(&function_table);
}

jlong GetDrawGLFunctionTable(JNIEnv* env, jclass) {
  static AwDrawGLFunctionTable function_table;
  function_table.version = kAwDrawGLFunctionTableVersion;
  function_table.create_graphic_buffer = &GraphicBufferImpl::Create;
  function_table.release_graphic_buffer = &GraphicBufferImpl::Release;
  function_table.map = &GraphicBufferImpl::MapStatic;
  function_table.unmap = &GraphicBufferImpl::UnmapStatic;
  function_table.get_native_buffer = &GraphicBufferImpl::GetNativeBufferStatic;
  function_table.get_stride = &GraphicBufferImpl::GetStrideStatic;
  return reinterpret_cast<intptr_t>(&function_table);
}

const char kClassName[] = "com/android/webview/chromium/GraphicsUtils";
const JNINativeMethod kJniMethods[] = {
    { "nativeGetDrawSWFunctionTable", "()J",
        reinterpret_cast<void*>(GetDrawSWFunctionTable) },
    { "nativeGetDrawGLFunctionTable", "()J",
        reinterpret_cast<void*>(GetDrawGLFunctionTable) },
};

}  // namespace

void RegisterGraphicsUtils(JNIEnv* env) {
  jclass clazz = env->FindClass(kClassName);
  LOG_ALWAYS_FATAL_IF(!clazz, "Unable to find class '%s'", kClassName);

  int res = env->RegisterNatives(clazz, kJniMethods, NELEM(kJniMethods));
  LOG_ALWAYS_FATAL_IF(res < 0, "register native methods failed: res=%d", res);
}

}  // namespace android
