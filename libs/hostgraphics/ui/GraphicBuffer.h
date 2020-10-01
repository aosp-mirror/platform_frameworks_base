/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef ANDROID_GRAPHIC_BUFFER_H
#define ANDROID_GRAPHIC_BUFFER_H

#include <stdint.h>
#include <sys/types.h>

#include <vector>

#include <ui/PixelFormat.h>
#include <ui/Rect.h>

#include <utils/RefBase.h>

namespace android {

class GraphicBuffer : virtual public RefBase {
public:
    GraphicBuffer(uint32_t w, uint32_t h):width(w),height(h) {
        data.resize(w*h);
    }
    uint32_t getWidth() const           { return static_cast<uint32_t>(width); }
    uint32_t getHeight() const          { return static_cast<uint32_t>(height); }
    uint32_t getStride() const          { return static_cast<uint32_t>(width); }
    uint64_t getUsage() const           { return 0; }
    PixelFormat getPixelFormat() const  { return PIXEL_FORMAT_RGBA_8888; }
    //uint32_t getLayerCount() const      { return static_cast<uint32_t>(layerCount); }
    Rect getBounds() const              { return Rect(width, height); }

    status_t lockAsyncYCbCr(uint32_t inUsage, const Rect& rect,
            android_ycbcr *ycbcr, int fenceFd) { return OK; }

    status_t lockAsync(uint32_t inUsage, const Rect& rect, void** vaddr, int fenceFd,
                       int32_t* outBytesPerPixel = nullptr, int32_t* outBytesPerStride = nullptr) {
        *vaddr = data.data();
        return OK;
    }

    status_t unlockAsync(int *fenceFd) { return OK; }

private:
    uint32_t width;
    uint32_t height;
    std::vector<uint32_t> data;
};

}; // namespace android

#endif // ANDROID_GRAPHIC_BUFFER_H
