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

#include "frametovalues.h"

#include <string.h>
#include <jni.h>
#include <unistd.h>
#include <android/log.h>

#include "imgprocutil.h"

jboolean Java_androidx_media_filterpacks_image_ToGrayValuesFilter_toGrayValues(
    JNIEnv* env, jclass clazz, jobject imageBuffer, jobject grayBuffer )
{
    unsigned char* pixelPtr = static_cast<unsigned char*>(env->GetDirectBufferAddress(imageBuffer));
    unsigned char* grayPtr = static_cast<unsigned char*>(env->GetDirectBufferAddress(grayBuffer));

    if (pixelPtr == 0 || grayPtr == 0) {
      return JNI_FALSE;
    }

    int numPixels  = env->GetDirectBufferCapacity(imageBuffer) / 4;

    // TODO: the current implementation is focused on the correctness not performance.
    // If performance becomes an issue, it is better to increment pixelPtr directly.
    int disp = 0;
    for(int idx = 0; idx < numPixels; idx++, disp+=4) {
      int R = *(pixelPtr + disp);
      int G = *(pixelPtr + disp + 1);
      int B = *(pixelPtr + disp + 2);
      int gray = getIntensityFast(R, G, B);
      *(grayPtr+idx) = static_cast<unsigned char>(gray);
    }

    return JNI_TRUE;
}

jboolean Java_androidx_media_filterpacks_image_ToRgbValuesFilter_toRgbValues(
    JNIEnv* env, jclass clazz, jobject imageBuffer, jobject rgbBuffer )
{
    unsigned char* pixelPtr = static_cast<unsigned char*>(env->GetDirectBufferAddress(imageBuffer));
    unsigned char* rgbPtr = static_cast<unsigned char*>(env->GetDirectBufferAddress(rgbBuffer));

    if (pixelPtr == 0 || rgbPtr == 0) {
      return JNI_FALSE;
    }

    int numPixels  = env->GetDirectBufferCapacity(imageBuffer) / 4;

    // TODO: this code could be revised to improve the performance as the TODO above.
    int pixelDisp = 0;
    int rgbDisp = 0;
    for(int idx = 0; idx < numPixels; idx++, pixelDisp += 4, rgbDisp += 3) {
      for (int c = 0; c < 3; ++c) {
        *(rgbPtr + rgbDisp + c) = *(pixelPtr + pixelDisp + c);
      }
    }
    return JNI_TRUE;
}

