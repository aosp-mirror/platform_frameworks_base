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

#include "stats_scorer.h"

#include <jni.h>
#include <math.h>

void Java_androidx_media_filterpacks_numeric_StatsFilter_score(
    JNIEnv* env, jobject thiz, jobject imageBuffer, jfloatArray statsArray)
{
    unsigned char* pImg = static_cast<unsigned char*>(env->GetDirectBufferAddress(imageBuffer));
    int numPixels  = env->GetDirectBufferCapacity(imageBuffer);  // 1 byte per pixel
    float sum = 0.0;
    float sumSquares = 0.0;

    for (int i = 0; i < numPixels; ++i) {
        float val = static_cast<float>(pImg[i]);
        sum += val;
        sumSquares += val * val;
    }
    jfloat result[2];
    result[0] = sum / numPixels;  // mean
    result[1] = sqrt((sumSquares - numPixels * result[0] * result[0]) / (numPixels - 1));  // stdev.
    env->SetFloatArrayRegion(statsArray, 0, 2, result);
}

void Java_androidx_media_filterpacks_numeric_StatsFilter_regionscore(
    JNIEnv* env, jobject thiz, jobject imageBuffer, jint width, jint height,
    jfloat left, jfloat top, jfloat right, jfloat bottom, jfloatArray statsArray)
{
    unsigned char* pImg = static_cast<unsigned char*>(env->GetDirectBufferAddress(imageBuffer));
    int xStart = static_cast<int>(width * left);
    int xEnd = static_cast<int>(width * right);
    int yStart = static_cast<int>(height * top);
    int yEnd = static_cast<int>(height * bottom);
    int numPixels  = (xEnd - xStart) * (yEnd - yStart);
    float sum = 0.0;
    float sumSquares = 0.0;

    for (int y = yStart; y < yEnd; y++) {
      int disp = width * y;
      for (int x = xStart; x < xEnd; ++x) {
        float val = static_cast<float>(*(pImg + disp + x));
        sum += val;
        sumSquares += val * val;
      }
    }
    jfloat result[2];
    result[0] = sum / numPixels;  // mean
    result[1] = (numPixels == 1) ?
        0 : sqrt((sumSquares - numPixels * result[0] * result[0]) / (numPixels - 1));  // stdev.
    env->SetFloatArrayRegion(statsArray, 0, 2, result);
}

