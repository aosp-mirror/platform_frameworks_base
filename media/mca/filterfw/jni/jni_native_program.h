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

#ifndef ANDROID_FILTERFW_JNI_NATIVE_PROGRAM_H
#define ANDROID_FILTERFW_JNI_NATIVE_PROGRAM_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_allocate(JNIEnv* env, jobject thiz);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_deallocate(JNIEnv* env, jobject thiz);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_nativeInit(JNIEnv* env, jobject thiz);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_openNativeLibrary(JNIEnv* env,
                                                           jobject thiz,
                                                           jstring lib_name);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_bindInitFunction(JNIEnv* env,
                                                          jobject thiz,
                                                          jstring func_name);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_bindSetValueFunction(JNIEnv* env,
                                                              jobject thiz,
                                                              jstring func_name);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_bindGetValueFunction(JNIEnv* env,
                                                              jobject thiz,
                                                              jstring func_name);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_bindProcessFunction(JNIEnv* env,
                                                             jobject thiz,
                                                             jstring func_name);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_bindResetFunction(JNIEnv* env,
                                                           jobject thiz,
                                                           jstring func_name);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_bindTeardownFunction(JNIEnv* env,
                                                              jobject thiz,
                                                              jstring func_name);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_callNativeInit(JNIEnv* env, jobject thiz);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_callNativeSetValue(JNIEnv* env,
                                                            jobject thiz,
                                                            jstring key,
                                                            jstring value);

JNIEXPORT jstring JNICALL
Java_android_filterfw_core_NativeProgram_callNativeGetValue(JNIEnv* env,
                                                            jobject thiz,
                                                            jstring key);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_callNativeProcess(JNIEnv* env,
                                                           jobject thiz,
                                                           jobjectArray inputs,
                                                           jobject output);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_callNativeReset(JNIEnv* env, jobject thiz);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeProgram_callNativeTeardown(JNIEnv* env, jobject thiz);

#ifdef __cplusplus
}
#endif

#endif // ANDROID_FILTERFW_JNI_NATIVE_PROGRAM_H
