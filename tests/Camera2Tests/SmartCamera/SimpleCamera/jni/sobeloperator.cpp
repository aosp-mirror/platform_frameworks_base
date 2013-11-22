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

#include "sobeloperator.h"

#include <math.h>
#include <string.h>
#include <jni.h>
#include <unistd.h>
#include <android/log.h>

#include "imgprocutil.h"

/*
 * Perform 1d convolution on 3 channel image either horizontally or vertically.
 * Parameters:
 *  inputHead: pointer to input image
 *  length: the length of image in the chosen axis.
 *  fragments: number of lines of the image in the chosen axis.
 *  step: the 1d pixel distance between adjacent pixels in the chosen axis.
 *  shift: the 1d pixel distance between adjacent lines in the chosen axis.
 *  filter: pointer to 1d filter
 *  halfSize: the length of filter is supposed to be (2 * halfSize + 1)
 *  outputHead: pointer to output image
 */

void computeGradient(unsigned char* dataPtr, int width, int height, short* gxPtr, short* gyPtr) {
  for (int i = 0; i < height; i++) {
    for (int j = 0; j < width; j++) {
      const int left = (j > 0)? -4 : 0;
      const int right = (j < width - 1) ? 4 : 0;
      const int curr = (i * width + j) * 4;
      const int above = (i > 0) ? curr - 4 * width : curr;
      const int below = (i < height - 1) ? curr + 4 * width : curr;
      const int offset = (i * width + j) * 3;
      for (int c = 0; c < 3; c++) {
        *(gxPtr + offset + c) =
            (*(dataPtr + curr + c + right) - *(dataPtr + curr + c + left)) * 2 +
            *(dataPtr + above + c + right) - *(dataPtr + above + c + left) +
            *(dataPtr + below + c + right) - *(dataPtr + below + c + left);
        *(gyPtr + offset + c) =
            (*(dataPtr + c + below) - *(dataPtr + c + above)) * 2 +
            *(dataPtr + left + c + below) - *(dataPtr + left + c + above) +
            *(dataPtr + right + c + below) - *(dataPtr + right + c + above);
      }
    }
  }
}

jboolean Java_androidx_media_filterpacks_image_SobelFilter_sobelOperator(
    JNIEnv* env, jclass clazz, jint width, jint height, jobject imageBuffer,
    jobject magBuffer, jobject dirBuffer) {

  if (imageBuffer == 0) {
    return JNI_FALSE;
  }
  unsigned char* srcPtr = static_cast<unsigned char*>(env->GetDirectBufferAddress(imageBuffer));
  unsigned char* magPtr = (magBuffer == 0) ?
      0 : static_cast<unsigned char*>(env->GetDirectBufferAddress(magBuffer));
  unsigned char* dirPtr = (dirBuffer == 0) ?
      0 : static_cast<unsigned char*>(env->GetDirectBufferAddress(dirBuffer));

  int numPixels = width * height;
  // TODO: avoid creating and deleting these buffers within this native function.
  short* gxPtr = new short[3 * numPixels];
  short* gyPtr = new short[3 * numPixels];
  computeGradient(srcPtr, width, height, gxPtr, gyPtr);

  unsigned char* mag = magPtr;
  unsigned char* dir = dirPtr;
  for (int i = 0; i < numPixels; ++i) {
    for (int c = 0; c < 3; c++) {
      int gx = static_cast<int>(*(gxPtr + 3 * i + c) / 8 + 127.5);
      int gy = static_cast<int>(*(gyPtr + 3 * i + c) / 8 + 127.5);

      // emulate arithmetic in GPU.
      gx = 2 * gx - 255;
      gy = 2 * gy - 255;
      if (magPtr != 0) {
        double value = sqrt(gx * gx + gy * gy);
        *(magPtr + 4 * i + c) = static_cast<unsigned char>(value);
      }
      if (dirPtr != 0) {
        *(dirPtr + 4 * i + c) = static_cast<unsigned char>(
            (atan(static_cast<double>(gy)/static_cast<double>(gx)) + 3.14) / 6.28);
      }
    }
    //setting alpha change to 1.0 (255)
    if (magPtr != 0) {
      *(magPtr + 4 * i + 3) = 255;
    }
    if (dirPtr != 0) {
      *(dirPtr + 4 * i + 3) = 255;
    }
  }

  delete[] gxPtr;
  delete[] gyPtr;

  return JNI_TRUE;
}
