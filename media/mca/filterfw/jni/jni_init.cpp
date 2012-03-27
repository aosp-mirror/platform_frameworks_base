/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include "jni/jni_util.h"

#include "native/core/native_frame.h"
#include "native/core/native_program.h"
#include "native/core/gl_env.h"
#include "native/core/gl_frame.h"
#include "native/core/shader_program.h"
#include "native/core/vertex_frame.h"

using namespace android::filterfw;

JavaVM* g_current_java_vm_ = NULL;

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  // Set the current vm pointer
  g_current_java_vm_ = vm;

  // Initialize object pools
  ObjectPool<NativeFrame>::Setup("android/filterfw/core/NativeFrame", "nativeFrameId");
  ObjectPool<NativeProgram>::Setup("android/filterfw/core/NativeProgram", "nativeProgramId");
  ObjectPool<GLFrame>::Setup("android/filterfw/core/GLFrame", "glFrameId");
  ObjectPool<ShaderProgram>::Setup("android/filterfw/core/ShaderProgram", "shaderProgramId");
  ObjectPool<GLEnv>::Setup("android/filterfw/core/GLEnvironment", "glEnvId");
  ObjectPool<VertexFrame>::Setup("android/filterfw/core/VertexFrame", "vertexFrameId");

  return JNI_VERSION_1_4;
}
