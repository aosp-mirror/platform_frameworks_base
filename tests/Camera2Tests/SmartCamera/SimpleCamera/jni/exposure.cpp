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

// Native function to extract exposure from image (handed down as ByteBuffer).

#include "exposure.h"

#include <math.h>
#include <string.h>
#include <jni.h>
#include <unistd.h>
#include <android/log.h>


jfloat
Java_androidx_media_filterfw_samples_simplecamera_ExposureFilter_overExposureOperator(
    JNIEnv* env, jclass clazz, jint width, jint height, jobject imageBuffer) {
    if (imageBuffer == 0) {
        return 0.0f;
    }
    const int numPixels = width * height;
    unsigned char* srcPtr = static_cast<unsigned char*>(env->GetDirectBufferAddress(imageBuffer));
    int output = 0;
    float tempLuminance = 0.0f;

    for (int i = 0; i < numPixels; i++) {
        tempLuminance = (0.2126f * *(srcPtr + 4 * i) +
                        0.7152f * *(srcPtr + 4 * i + 1) +
                        0.0722f * *(srcPtr + 4 * i + 2));
        if (tempLuminance + 5 >= 255) {
            output++;
        }
    }
    return (static_cast<float>(output)) / numPixels;
}

jfloat
Java_androidx_media_filterfw_samples_simplecamera_ExposureFilter_underExposureOperator(
    JNIEnv* env, jclass clazz, jint width, jint height, jobject imageBuffer) {
    if (imageBuffer == 0) {
        return 0.0f;
    }
    const int numPixels = width * height;
    unsigned char* srcPtr = static_cast<unsigned char*>(env->GetDirectBufferAddress(imageBuffer));
    int output = 0;
    float tempLuminance = 0.0f;

    for (int i = 0; i < numPixels; i++) {
        tempLuminance = (0.2126f * *(srcPtr + 4 * i) +
                        0.7152f * *(srcPtr + 4 * i + 1) +
                        0.0722f * *(srcPtr + 4 * i + 2));
        if (tempLuminance - 5 <= 0) {
            output++;
        }
    }
    return (static_cast<float>(output)) / numPixels;
}
