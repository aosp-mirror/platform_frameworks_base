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

// Native function to extract histogram from image (handed down as ByteBuffer).

#ifndef ANDROID_FILTERFW_JNI_SOBELOPERATOR_H
#define ANDROID_FILTERFW_JNI_SOBELOPERATOR_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jboolean JNICALL
Java_androidx_media_filterpacks_image_SobelFilter_sobelOperator(
    JNIEnv* env, jclass clazz, jint width, jint height,
    jobject imageBuffer, jobject magBuffer, jobject dirBuffer);

#ifdef __cplusplus
}
#endif

#endif // ANDROID_FILTERFW_JNI_SOBELOPERATOR_H
