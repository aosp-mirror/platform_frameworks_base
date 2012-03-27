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

#include <string>
#include <vector>

#include "jni/jni_shader_program.h"
#include "jni/jni_util.h"

#include "native/base/logging.h"
#include "native/core/geometry.h"
#include "native/core/gl_env.h"
#include "native/core/gl_frame.h"
#include "native/core/shader_program.h"
#include "native/core/vertex_frame.h"

using android::filterfw::GLEnv;
using android::filterfw::GLFrame;
using android::filterfw::Point;
using android::filterfw::ProgramVar;
using android::filterfw::Quad;
using android::filterfw::ShaderProgram;
using android::filterfw::VertexFrame;

jboolean Java_android_filterfw_core_ShaderProgram_allocate(JNIEnv* env,
                                                           jobject thiz,
                                                           jobject gl_env,
                                                           jstring vertex_shader,
                                                           jstring fragment_shader) {
  // Get the GLEnv pointer
  GLEnv* gl_env_ptr = ConvertFromJava<GLEnv>(env, gl_env);

  // Create the shader
  if (!fragment_shader || !gl_env_ptr)
    return false;
  else if (!vertex_shader)
    return ToJBool(WrapObjectInJava(new ShaderProgram(
      gl_env_ptr,
      ToCppString(env, fragment_shader)),
      env,
      thiz,
      true));
  else
    return ToJBool(WrapObjectInJava(new ShaderProgram(
      gl_env_ptr,
      ToCppString(env, vertex_shader),
      ToCppString(env, fragment_shader)),
      env,
      thiz,
      true));
}

jboolean Java_android_filterfw_core_ShaderProgram_deallocate(JNIEnv* env, jobject thiz) {
  return ToJBool(DeleteNativeObject<ShaderProgram>(env, thiz));
}

jboolean Java_android_filterfw_core_ShaderProgram_compileAndLink(JNIEnv* env, jobject thiz) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  return program ? ToJBool(program->CompileAndLink()) : JNI_FALSE;
}

jboolean Java_android_filterfw_core_ShaderProgram_setUniformValue(JNIEnv* env,
                                                                  jobject thiz,
                                                                  jstring key,
                                                                  jobject value) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  const Value c_value = ToCValue(env, value);
  const std::string c_key = ToCppString(env, key);
  if (c_value.value) {
    return ToJBool(program && program->SetUniformValue(c_key, c_value));
  } else {
    ALOGE("ShaderProgram: Could not convert java object value passed for key '%s'!", c_key.c_str());
    return JNI_FALSE;
  }
}

jobject Java_android_filterfw_core_ShaderProgram_getUniformValue(JNIEnv* env,
                                                                 jobject thiz,
                                                                 jstring key) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  const std::string c_key = ToCppString(env, key);
  return program ? ToJObject(env, program->GetUniformValue(c_key)) : JNI_NULL;
}

jboolean Java_android_filterfw_core_ShaderProgram_shaderProcess(JNIEnv* env,
                                                                jobject thiz,
                                                                jobjectArray inputs,
                                                                jobject output) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  std::vector<const GLFrame*> input_frames;
  if (program && inputs && output) {
    // Get the input frames
    const int input_count = env->GetArrayLength(inputs);
    for (int i = 0; i < input_count; ++i) {
      jobject input = env->GetObjectArrayElement(inputs, i);
      const GLFrame* input_frame = ConvertFromJava<GLFrame>(env, input);
      if (!input || !input_frame) {
        ALOGE("ShaderProgram: invalid input frame %d!", i);
        return JNI_FALSE;
      }
      input_frames.push_back(input_frame);
    }

    // Get the output frame
    GLFrame* output_frame = ConvertFromJava<GLFrame>(env, output);
    if (!output_frame) {
      ALOGE("ShaderProgram: no output frame found!");
      return JNI_FALSE;
    }

    // Process the frames!
    if (!program->Process(input_frames, output_frame)) {
      ALOGE("ShaderProgram: error processing shader!");
      return JNI_FALSE;
    }

    return JNI_TRUE;
  }
  return JNI_FALSE;
}

jobject Java_android_filterfw_core_ShaderProgram_nativeCreateIdentity(JNIEnv* env,
                                                                      jclass,
                                                                      jobject gl_env) {
  GLEnv* gl_env_ptr = ConvertFromJava<GLEnv>(env, gl_env);
  ShaderProgram* program = gl_env_ptr ? ShaderProgram::CreateIdentity(gl_env_ptr) : NULL;
  return program ? WrapNewObjectInJava(program, env, false) : NULL;
}

jboolean Java_android_filterfw_core_ShaderProgram_setSourceRegion(JNIEnv* env,
                                                                  jobject thiz,
                                                                  jfloat x0,
                                                                  jfloat y0,
                                                                  jfloat x1,
                                                                  jfloat y1,
                                                                  jfloat x2,
                                                                  jfloat y2,
                                                                  jfloat x3,
                                                                  jfloat y3) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  if (program) {
    program->SetSourceRegion(Quad(Point(x0, y0), Point(x1, y1), Point(x2, y2), Point(x3, y3)));
    return JNI_TRUE;
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_ShaderProgram_setTargetRegion(JNIEnv* env,
                                                                  jobject thiz,
                                                                  jfloat x0,
                                                                  jfloat y0,
                                                                  jfloat x1,
                                                                  jfloat y1,
                                                                  jfloat x2,
                                                                  jfloat y2,
                                                                  jfloat x3,
                                                                  jfloat y3) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  if (program) {
    program->SetTargetRegion(Quad(Point(x0, y0), Point(x1, y1), Point(x2, y2), Point(x3, y3)));
    return JNI_TRUE;
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_ShaderProgram_setShaderClearsOutput(JNIEnv* env,
                                                                        jobject thiz,
                                                                        jboolean clears) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  if (program) {
    program->SetClearsOutput(ToCppBool(clears));
    return JNI_TRUE;
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_ShaderProgram_setShaderBlendEnabled(JNIEnv* env,
                                                                        jobject thiz,
                                                                        jboolean enable) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  if (program) {
    program->SetBlendEnabled(ToCppBool(enable));
    return JNI_TRUE;
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_ShaderProgram_setShaderBlendFunc(JNIEnv* env,
                                                                     jobject thiz,
                                                                     jint sfactor,
                                                                     jint dfactor) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  if (program) {
    program->SetBlendFunc(sfactor, dfactor);
    return JNI_TRUE;
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_ShaderProgram_setShaderClearColor(JNIEnv* env,
                                                                      jobject thiz,
                                                                      jfloat r,
                                                                      jfloat g,
                                                                      jfloat b) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  if (program) {
    program->SetClearColor(r, g, b, 1.0f);
    return JNI_TRUE;
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_ShaderProgram_setShaderDrawMode(JNIEnv* env,
                                                                    jobject thiz,
                                                                    jint draw_mode) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  if (program) {
    program->SetDrawMode(draw_mode);
    return JNI_TRUE;
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_ShaderProgram_setShaderTileCounts(JNIEnv* env,
                                                                      jobject thiz,
                                                                      jint x_count,
                                                                      jint y_count) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  if (program) {
    program->SetTileCounts(x_count, y_count);
    return JNI_TRUE;
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_ShaderProgram_setShaderVertexCount(JNIEnv* env,
                                                                       jobject thiz,
                                                                       jint vertex_count) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  if (program) {
    program->SetVertexCount(vertex_count);
    return JNI_TRUE;
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_ShaderProgram_beginShaderDrawing(JNIEnv* env, jobject thiz) {
    ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
    return ToJBool(program && program->BeginDraw());
}

jboolean Java_android_filterfw_core_ShaderProgram_setShaderAttributeValues(
    JNIEnv* env,
    jobject thiz,
    jstring attr_name,
    jfloatArray values,
    jint component_count) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  if (program) {
    // Get the floats to set
    jfloat* float_ptr = env->GetFloatArrayElements(values, NULL);
    const int length = env->GetArrayLength(values);

    // Get the program variable to set
    const std::string attr_string = ToCppString(env, attr_name);
    ProgramVar program_var = program->GetAttribute(attr_string);

    // Set the variable
    if (float_ptr && ShaderProgram::IsVarValid(program_var)) {
      const bool success = program->SetAttributeValues(program_var,
                                                       reinterpret_cast<float*>(float_ptr),
                                                       length,
                                                       component_count);
      env->ReleaseFloatArrayElements(values, float_ptr, JNI_ABORT);
      return ToJBool(success);
    }
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_ShaderProgram_setShaderAttributeVertexFrame(
    JNIEnv* env,
    jobject thiz,
    jstring attr_name,
    jobject vertex_frame,
    jint type,
    jint component_count,
    jint stride,
    jint offset,
    jboolean normalize) {
  ShaderProgram* program = ConvertFromJava<ShaderProgram>(env, thiz);
  if (program) {
    // Get the vertex frame
    VertexFrame* v_frame = ConvertFromJava<VertexFrame>(env, vertex_frame);

    // Get the program variable to set
    const std::string attr_string = ToCppString(env, attr_name);
    ProgramVar program_var = program->GetAttribute(attr_string);

    // Set the variable
    if (v_frame && ShaderProgram::IsVarValid(program_var)) {
      const bool success = program->SetAttributeValues(program_var,
                                                       v_frame,
                                                       type,
                                                       component_count,
                                                       stride,
                                                       offset,
                                                       ToCppBool(normalize));
      return ToJBool(success);
    }
  }
  return JNI_FALSE;
}
