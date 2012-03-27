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

#ifndef ANDROID_FILTERFW_JNI_NATIVE_FRAME_H
#define ANDROID_FILTERFW_JNI_NATIVE_FRAME_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeFrame_nativeAllocate(JNIEnv* env, jobject thiz, jint size);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeFrame_nativeDeallocate(JNIEnv* env, jobject thiz);

JNIEXPORT jint JNICALL
Java_android_filterfw_core_NativeFrame_nativeIntSize(JNIEnv* env, jclass clazz);

JNIEXPORT jint JNICALL
Java_android_filterfw_core_NativeFrame_nativeFloatSize(JNIEnv* env, jclass clazz);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeFrame_setNativeInts(JNIEnv* env, jobject thiz, jintArray ints);

JNIEXPORT jintArray JNICALL
Java_android_filterfw_core_NativeFrame_getNativeInts(JNIEnv* env, jobject thiz, jint size);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeFrame_setNativeFloats(JNIEnv* env, jobject thiz, jfloatArray ints);

JNIEXPORT jfloatArray JNICALL
Java_android_filterfw_core_NativeFrame_getNativeFloats(JNIEnv* env, jobject thiz, jint size);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeFrame_setNativeData(JNIEnv* env,
                                                     jobject thiz,
                                                     jbyteArray data,
                                                     jint offset,
                                                     jint length);

JNIEXPORT jbyteArray JNICALL
Java_android_filterfw_core_NativeFrame_getNativeData(JNIEnv* env, jobject thiz, jint size);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeFrame_getNativeBuffer(JNIEnv* env, jobject thiz, jobject buffer);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeFrame_setNativeBitmap(JNIEnv* env,
                                                       jobject thiz,
                                                       jobject bitmap,
                                                       jint size,
                                                       jint bytes_per_sample);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeFrame_getNativeBitmap(JNIEnv* env,
                                                       jobject thiz,
                                                       jobject bitmap,
                                                       jint size,
                                                       jint bytes_per_sample);

JNIEXPORT jint JNICALL
Java_android_filterfw_core_NativeFrame_getNativeCapacity(JNIEnv* env, jobject thiz);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeFrame_nativeCopyFromNative(JNIEnv* env,
                                                            jobject thiz,
                                                            jobject frame);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeFrame_nativeCopyFromGL(JNIEnv* env,
                                                        jobject thiz,
                                                        jobject frame);

#ifdef __cplusplus
}
#endif

#endif // ANDROID_FILTERFW_JNI_NATIVE_FRAME_H
