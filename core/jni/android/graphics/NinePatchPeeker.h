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

#ifndef _ANDROID_GRAPHICS_NINE_PATCH_PEEKER_H_
#define _ANDROID_GRAPHICS_NINE_PATCH_PEEKER_H_

#include "SkPngChunkReader.h"
#include <androidfw/ResourceTypes.h>

class SkImageDecoder;

using namespace android;

class NinePatchPeeker : public SkPngChunkReader {
public:
    NinePatchPeeker()
            : mPatch(NULL)
            , mPatchSize(0)
            , mHasInsets(false)
            , mOutlineRadius(0)
            , mOutlineAlpha(0) {
        memset(mOpticalInsets, 0, 4 * sizeof(int32_t));
        memset(mOutlineInsets, 0, 4 * sizeof(int32_t));
    }

    ~NinePatchPeeker() {
        free(mPatch);
    }

    bool readChunk(const char tag[], const void* data, size_t length) override;

    Res_png_9patch* mPatch;
    size_t mPatchSize;
    bool mHasInsets;
    int32_t mOpticalInsets[4];
    int32_t mOutlineInsets[4];
    float mOutlineRadius;
    uint8_t mOutlineAlpha;
};

#endif  // _ANDROID_GRAPHICS_NINE_PATCH_PEEKER_H_
