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
 * distributed under the License is distributed on an "AS IS" BASIS
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Native function to extract brightness from image (handed down as ByteBuffer).

#include "brightness.h"

#include <math.h>
#include <string.h>
#include <jni.h>
#include <unistd.h>
#include <android/log.h>

jfloat
Java_androidx_media_filterfw_samples_simplecamera_AvgBrightnessFilter_brightnessOperator(
    JNIEnv* env, jclass clazz, jint width, jint height, jobject imageBuffer) {

    if (imageBuffer == 0) {
        return 0.0f;
    }
    float pixelTotals[] = { 0.0f, 0.0f, 0.0f };
    const int numPixels = width * height;
    unsigned char* srcPtr = static_cast<unsigned char*>(env->GetDirectBufferAddress(imageBuffer));
    for (int i = 0; i < numPixels; i++) {
        pixelTotals[0] += *(srcPtr + 4 * i);
        pixelTotals[1] += *(srcPtr + 4 * i + 1);
        pixelTotals[2] += *(srcPtr + 4 * i + 2);
    }
    float avgPixels[] = { 0.0f, 0.0f, 0.0f };

    avgPixels[0] = pixelTotals[0] / numPixels;
    avgPixels[1] = pixelTotals[1] / numPixels;
    avgPixels[2] = pixelTotals[2] / numPixels;
    float returnValue = sqrt(0.241f * avgPixels[0] * avgPixels[0] +
                            0.691f * avgPixels[1] * avgPixels[1] +
                            0.068f * avgPixels[2] * avgPixels[2]);

    return returnValue / 255;
}
