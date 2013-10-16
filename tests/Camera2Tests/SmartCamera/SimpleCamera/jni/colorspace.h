/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_FILTERFW_JNI_COLORSPACE_H
#define ANDROID_FILTERFW_JNI_COLORSPACE_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

#define JNI_COLORSPACE_METHOD(METHOD_NAME) \
  Java_androidx_media_filterfw_ColorSpace_ ## METHOD_NAME

JNIEXPORT void JNICALL
JNI_COLORSPACE_METHOD(nativeYuv420pToRgba8888)(
    JNIEnv* env, jclass clazz, jobject input, jobject output, jint width, jint height);

JNIEXPORT void JNICALL
JNI_COLORSPACE_METHOD(nativeArgb8888ToRgba8888)(
    JNIEnv* env, jclass clazz, jobject input, jobject output, jint width, jint height);

JNIEXPORT void JNICALL
JNI_COLORSPACE_METHOD(nativeRgba8888ToHsva8888)(
    JNIEnv* env, jclass clazz, jobject input, jobject output, jint width, jint height);

JNIEXPORT void JNICALL
JNI_COLORSPACE_METHOD(nativeRgba8888ToYcbcra8888)(
    JNIEnv* env, jclass clazz, jobject input, jobject output, jint width, jint height);


#ifdef __cplusplus
}
#endif

#endif // ANDROID_FILTERFW_JNI_COLORSPACE_H
