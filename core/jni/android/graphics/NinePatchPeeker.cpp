/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include "NinePatchPeeker.h"

#include <SkBitmap.h>
#include <cutils/compiler.h>

using namespace android;

bool NinePatchPeeker::readChunk(const char tag[], const void* data, size_t length) {
    if (!strcmp("npTc", tag) && length >= sizeof(Res_png_9patch)) {
        Res_png_9patch* patch = (Res_png_9patch*) data;
        size_t patchSize = patch->serializedSize();
        if (length != patchSize) {
            return false;
        }
        // You have to copy the data because it is owned by the png reader
        Res_png_9patch* patchNew = (Res_png_9patch*) malloc(patchSize);
        memcpy(patchNew, patch, patchSize);
        Res_png_9patch::deserialize(patchNew);
        patchNew->fileToDevice();
        free(mPatch);
        mPatch = patchNew;
        mPatchSize = patchSize;
    } else if (!strcmp("npLb", tag) && length == sizeof(int32_t) * 4) {
        mHasInsets = true;
        memcpy(&mOpticalInsets, data, sizeof(int32_t) * 4);
    } else if (!strcmp("npOl", tag) && length == 24) { // 4 int32_ts, 1 float, 1 int32_t sized byte
        mHasInsets = true;
        memcpy(&mOutlineInsets, data, sizeof(int32_t) * 4);
        mOutlineRadius = ((const float*)data)[4];
        mOutlineAlpha = ((const int32_t*)data)[5] & 0xff;
    }
    return true;    // keep on decoding
}

static void scaleDivRange(int32_t* divs, int count, float scale, int maxValue) {
    for (int i = 0; i < count; i++) {
        divs[i] = int32_t(divs[i] * scale + 0.5f);
        if (i > 0 && divs[i] == divs[i - 1]) {
            divs[i]++; // avoid collisions
        }
    }

    if (CC_UNLIKELY(divs[count - 1] > maxValue)) {
        // if the collision avoidance above put some divs outside the bounds of the bitmap,
        // slide outer stretchable divs inward to stay within bounds
        int highestAvailable = maxValue;
        for (int i = count - 1; i >= 0; i--) {
            divs[i] = highestAvailable;
            if (i > 0 && divs[i] <= divs[i-1]) {
                // keep shifting
                highestAvailable = divs[i] - 1;
            } else {
                break;
            }
        }
    }
}

void NinePatchPeeker::scale(float scaleX, float scaleY, int scaledWidth, int scaledHeight) {
    if (!mPatch) {
        return;
    }

    // The max value for the divRange is one pixel less than the actual max to ensure that the size
    // of the last div is not zero. A div of size 0 is considered invalid input and will not render.
    if (!SkScalarNearlyEqual(scaleX, 1.0f)) {
        mPatch->paddingLeft   = int(mPatch->paddingLeft   * scaleX + 0.5f);
        mPatch->paddingRight  = int(mPatch->paddingRight  * scaleX + 0.5f);
        scaleDivRange(mPatch->getXDivs(), mPatch->numXDivs, scaleX, scaledWidth - 1);
    }

    if (!SkScalarNearlyEqual(scaleY, 1.0f)) {
        mPatch->paddingTop    = int(mPatch->paddingTop    * scaleY + 0.5f);
        mPatch->paddingBottom = int(mPatch->paddingBottom * scaleY + 0.5f);
        scaleDivRange(mPatch->getYDivs(), mPatch->numYDivs, scaleY, scaledHeight - 1);
    }
}
