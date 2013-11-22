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

// Stats (mean and stdev) scoring in the native.

#ifndef ANDROID_FILTERFW_JNI_STATS_SCORER_H
#define ANDROID_FILTERFW_JNI_STATS_SCORER_H

#include <jni.h>

#define JNI_FES_FUNCTION(name) Java_androidx_media_filterpacks_numeric_StatsFilter_ ## name

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void JNICALL
JNI_FES_FUNCTION(score)(
    JNIEnv* env, jobject thiz, jobject imageBuffer, jfloatArray statsArray);

JNIEXPORT void JNICALL
JNI_FES_FUNCTION(regionscore)(
   JNIEnv* env, jobject thiz, jobject imageBuffer, jint width, jint height,
   jfloat lefp, jfloat top, jfloat right, jfloat bottom, jfloatArray statsArray);

#ifdef __cplusplus
}
#endif


#endif  // ANDROID_FILTERFW_JNI_STATS_SCORER_H
