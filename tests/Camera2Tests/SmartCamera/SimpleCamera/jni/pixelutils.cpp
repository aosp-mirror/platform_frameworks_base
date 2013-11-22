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

#include "pixelutils.h"

#include <stdint.h>

typedef uint32_t uint32;

void JNI_PIXELUTILS_METHOD(nativeCopyPixels)(
    JNIEnv* env, jclass clazz, jobject input, jobject output, jint width, jint height, jint offset,
    jint pixStride, jint rowStride) {
  uint32* pInPix = static_cast<uint32*>(env->GetDirectBufferAddress(input));
  uint32* pOutput = static_cast<uint32*>(env->GetDirectBufferAddress(output));
  uint32* pOutRow = pOutput + offset;
  for (int y = 0; y < height; ++y) {
    uint32* pOutPix = pOutRow;
    for (int x = 0; x < width; ++x) {
      *pOutPix = *(pInPix++);
      pOutPix += pixStride;
    }
    pOutRow += rowStride;
  }
}

