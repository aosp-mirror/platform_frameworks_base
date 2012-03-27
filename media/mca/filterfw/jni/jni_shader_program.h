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

#ifndef ANDROID_FILTERFW_JNI_SHADER_PROGRAM_H
#define ANDROID_FILTERFW_JNI_SHADER_PROGRAM_H

#include <jni.h>

#include "native/core/value.h"

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_allocate(JNIEnv* env,
                                                  jobject thiz,
                                                  jobject gl_env,
                                                  jstring vertex_shader,
                                                  jstring fragment_shader);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_deallocate(JNIEnv* env, jobject thiz);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_compileAndLink(JNIEnv* env, jobject thiz);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_setUniformValue(JNIEnv* env,
                                                         jobject thiz,
                                                         jstring key,
                                                         jobject value);

JNIEXPORT jobject JNICALL
Java_android_filterfw_core_ShaderProgram_getUniformValue(JNIEnv* env,
                                                         jobject thiz,
                                                         jstring key);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_shaderProcess(JNIEnv* env,
                                                       jobject thiz,
                                                       jobjectArray inputs,
                                                       jobject output);

JNIEXPORT jobject JNICALL
Java_android_filterfw_core_ShaderProgram_nativeCreateIdentity(JNIEnv* env,
                                                              jclass clazz,
                                                              jobject gl_env);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_setSourceRegion(JNIEnv* env,
                                                         jobject thiz,
                                                         jfloat x0,
                                                         jfloat y0,
                                                         jfloat x1,
                                                         jfloat y1,
                                                         jfloat x2,
                                                         jfloat y2,
                                                         jfloat x3,
                                                         jfloat y3);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_setTargetRegion(JNIEnv* env,
                                                         jobject thiz,
                                                         jfloat x0,
                                                         jfloat y0,
                                                         jfloat x1,
                                                         jfloat y1,
                                                         jfloat x2,
                                                         jfloat y2,
                                                         jfloat x3,
                                                         jfloat y3);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_setShaderClearsOutput(JNIEnv* env,
                                                               jobject thiz,
                                                               jboolean clears);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_setShaderClearColor(JNIEnv* env,
                                                             jobject thiz,
                                                             jfloat r,
                                                             jfloat g,
                                                             jfloat b);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_setShaderBlendEnabled(JNIEnv* env,
                                                               jobject thiz,
                                                               jboolean enable);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_setShaderBlendFunc(JNIEnv* env,
                                                               jobject thiz,
                                                               jint sfactor,
                                                               jint dfactor);
JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_setShaderDrawMode(JNIEnv* env,
                                                           jobject thiz,
                                                           jint draw_mode);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_setShaderTileCounts(JNIEnv* env,
                                                             jobject thiz,
                                                             jint x_count,
                                                             jint y_count);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_setShaderVertexCount(JNIEnv* env,
                                                              jobject thiz,
                                                              jint vertex_count);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_beginShaderDrawing(JNIEnv* env, jobject thiz);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_setShaderAttributeValues(JNIEnv* env,
                                                                  jobject thiz,
                                                                  jstring attr_name,
                                                                  jfloatArray values,
                                                                  jint component_count);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_ShaderProgram_setShaderAttributeVertexFrame(JNIEnv* env,
                                                                       jobject thiz,
                                                                       jstring attr_name,
                                                                       jobject vertex_frame,
                                                                       jint type,
                                                                       jint component_count,
                                                                       jint stride,
                                                                       jint offset,
                                                                       jboolean normalize);

#ifdef __cplusplus
}
#endif

#endif // ANDROID_FILTERFW_JNI_SHADER_PROGRAM_H
