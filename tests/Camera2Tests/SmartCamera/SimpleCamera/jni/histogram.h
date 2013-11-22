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

#ifndef ANDROID_FILTERFW_JNI_HISTOGRAM_H
#define ANDROID_FILTERFW_JNI_HISTOGRAM_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void JNICALL
Java_androidx_media_filterpacks_histogram_GrayHistogramFilter_extractHistogram(
    JNIEnv* env, jclass clazz, jobject imageBuffer, jobject maskBuffer, jobject histogramBuffer );

JNIEXPORT void JNICALL
Java_androidx_media_filterpacks_histogram_ChromaHistogramFilter_extractChromaHistogram(
    JNIEnv* env, jclass clazz, jobject imageBuffer, jobject histogramBuffer, jint hBins, jint sBins);

JNIEXPORT void JNICALL
Java_androidx_media_filterpacks_histogram_NewChromaHistogramFilter_extractChromaHistogram(
    JNIEnv* env, jclass clazz, jobject imageBuffer, jobject histogramBuffer,
    jint hueBins, jint saturationBins, jint valueBins,
    jint saturationThreshold, jint valueThreshold);

#ifdef __cplusplus
}
#endif

#endif // ANDROID_FILTERFW_JNI_HISTOGRAM_H
