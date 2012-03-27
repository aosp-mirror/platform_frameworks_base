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

#ifndef ANDROID_FILTERFW_JNI_VERTEX_FRAME_H
#define ANDROID_FILTERFW_JNI_VERTEX_FRAME_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_VertexFrame_nativeAllocate(JNIEnv* env, jobject thiz, jint size);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_VertexFrame_nativeDeallocate(JNIEnv* env, jobject thiz);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_VertexFrame_setNativeInts(JNIEnv* env, jobject thiz, jintArray ints);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_VertexFrame_setNativeFloats(JNIEnv* env,
                                                       jobject thiz,
                                                       jfloatArray floats);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_VertexFrame_setNativeData(JNIEnv* env,
                                                     jobject thiz,
                                                     jbyteArray data,
                                                     jint offset,
                                                     jint length);

JNIEXPORT jint JNICALL
Java_android_filterfw_core_VertexFrame_getNativeVboId(JNIEnv* env, jobject thiz);

#ifdef __cplusplus
}
#endif

#endif // ANDROID_FILTERFW_JNI_VERTEX_FRAME_H
