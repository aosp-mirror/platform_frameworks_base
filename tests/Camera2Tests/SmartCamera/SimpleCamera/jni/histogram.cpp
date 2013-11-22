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

#include "histogram.h"

#include <string.h>
#include <jni.h>
#include <unistd.h>
#include <android/log.h>

#include "imgprocutil.h"

inline void addPixelToHistogram(unsigned char*& pImg, int* pHist, int numBins) {
    int R = *(pImg++);
    int G = *(pImg++);
    int B = *(pImg++);
    ++pImg;
    int i = getIntensityFast(R, G, B);
    int bin = clamp(0, static_cast<int>(static_cast<float>(i * numBins) / 255.0f), numBins - 1);
    ++pHist[bin];
}

void Java_androidx_media_filterpacks_histogram_GrayHistogramFilter_extractHistogram(
    JNIEnv* env, jclass clazz, jobject imageBuffer, jobject maskBuffer, jobject histogramBuffer )
{
    unsigned char* pImg = static_cast<unsigned char*>(env->GetDirectBufferAddress(imageBuffer));
    int* pHist = static_cast<int*>(env->GetDirectBufferAddress(histogramBuffer));
    int numPixels  = env->GetDirectBufferCapacity(imageBuffer) / 4;  // 4 bytes per pixel
    int numBins    = env->GetDirectBufferCapacity(histogramBuffer);

    unsigned char* pMask = NULL;
    if(maskBuffer != NULL) {
        pMask = static_cast<unsigned char*>(env->GetDirectBufferAddress(maskBuffer));
    }

    for(int i = 0; i < numBins; ++i) pHist[i] = 0;

    if(pMask == NULL) {
        for( ; numPixels > 0; --numPixels) {
            addPixelToHistogram(pImg, pHist, numBins);
        }
    } else {
        for( ; numPixels > 0; --numPixels) {
            if(*pMask == 0){
                pMask += 4;
                pImg  += 4;  // Note that otherwise addPixelToHistogram advances pImg by 4
                continue;
            }
            pMask += 4;
            addPixelToHistogram(pImg, pHist, numBins);
        }
    }
}

void Java_androidx_media_filterpacks_histogram_ChromaHistogramFilter_extractChromaHistogram(
    JNIEnv* env, jclass clazz, jobject imageBuffer, jobject histogramBuffer, jint hBins, jint sBins)
{
    unsigned char* pixelIn = static_cast<unsigned char*>(env->GetDirectBufferAddress(imageBuffer));
    float* histOut = static_cast<float*>(env->GetDirectBufferAddress(histogramBuffer));
    int numPixels  = env->GetDirectBufferCapacity(imageBuffer) / 4;  // 4 bytes per pixel

    for (int i = 0; i < hBins * sBins; ++i) histOut[i] = 0.0f;

    int h, s, v;
    float hScaler = hBins / 256.0f;
    float sScaler = sBins / 256.0f;
    for( ; numPixels > 0; --numPixels) {
      h = *(pixelIn++);
      s = *(pixelIn++);
      v = *(pixelIn++);
      pixelIn++;

      int index = static_cast<int>(s * sScaler) * hBins + static_cast<int>(h * hScaler);
      histOut[index] += 1.0f;
    }
}

void Java_androidx_media_filterpacks_histogram_NewChromaHistogramFilter_extractChromaHistogram(
    JNIEnv* env, jclass clazz, jobject imageBuffer, jobject histogramBuffer,
    jint hueBins, jint saturationBins, jint valueBins,
    jint saturationThreshold, jint valueThreshold) {
    unsigned char* pixelIn = static_cast<unsigned char*>(env->GetDirectBufferAddress(imageBuffer));
    float* histOut = static_cast<float*>(env->GetDirectBufferAddress(histogramBuffer));
    int numPixels  = env->GetDirectBufferCapacity(imageBuffer) / 4;  // 4 bytes per pixel

    // TODO: add check on the size of histOut
    for (int i = 0; i < (hueBins * saturationBins + valueBins); ++i) {
      histOut[i] = 0.0f;
    }

    for( ; numPixels > 0; --numPixels) {
      int h = *(pixelIn++);
      int s = *(pixelIn++);
      int v = *(pixelIn++);

      pixelIn++;
      // If a pixel that is either too dark (less than valueThreshold) or colorless
      // (less than saturationThreshold), if will be put in a 1-D value histogram instead.

      int index;
      if (s > saturationThreshold && v > valueThreshold) {
        int sIndex = s * saturationBins / 256;

        // Shifting hue index by 0.5 such that peaks of red, yellow, green, cyan, blue, pink
        // will be at the center of some bins.
        int hIndex = ((h * hueBins + 128) / 256) % hueBins;
        index = sIndex * hueBins + hIndex;
      } else {
        index =  hueBins * saturationBins + (v * valueBins / 256);
      }
      histOut[index] += 1.0f;
    }
}
