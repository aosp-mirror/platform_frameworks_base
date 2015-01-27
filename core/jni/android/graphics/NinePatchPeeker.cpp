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

#include "SkBitmap.h"

using namespace android;

bool NinePatchPeeker::peek(const char tag[], const void* data, size_t length) {
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

        // now update our host to force index or 32bit config
        // 'cause we don't want 565 predithered, since as a 9patch, we know
        // we will be stretched, and therefore we want to dither afterwards.
        SkImageDecoder::PrefConfigTable table;
        table.fPrefFor_8Index_NoAlpha_src   = SkBitmap::kIndex8_Config;
        table.fPrefFor_8Index_YesAlpha_src  = SkBitmap::kIndex8_Config;
        table.fPrefFor_8Gray_src            = SkBitmap::kARGB_8888_Config;
        table.fPrefFor_8bpc_NoAlpha_src     = SkBitmap::kARGB_8888_Config;
        table.fPrefFor_8bpc_YesAlpha_src    = SkBitmap::kARGB_8888_Config;

        mHost->setPrefConfigTable(table);
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
