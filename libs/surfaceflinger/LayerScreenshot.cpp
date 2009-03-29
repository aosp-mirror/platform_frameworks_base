/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "SurfaceFlinger"

#include <stdlib.h>
#include <stdint.h>
#include <sys/types.h>

#include <utils/Errors.h>
#include <utils/Log.h>

#include <core/SkBitmap.h>

#include <ui/EGLDisplaySurface.h>

#include "LayerBase.h"
#include "LayerScreenshot.h"
#include "SurfaceFlinger.h"
#include "DisplayHardware/DisplayHardware.h"

namespace android {
// ---------------------------------------------------------------------------

const uint32_t LayerScreenshot::typeInfo = LayerBase::typeInfo | 0x20;
const char* const LayerScreenshot::typeID = "LayerScreenshot";

// ---------------------------------------------------------------------------

LayerScreenshot::LayerScreenshot(SurfaceFlinger* flinger, DisplayID display)
    : LayerBase(flinger, display), mReply(0)
{
}

LayerScreenshot::~LayerScreenshot()
{
}

void LayerScreenshot::onDraw(const Region& clip) const
{
    const DisplayHardware& hw(graphicPlane(0).displayHardware());
    copybit_image_t dst;
    hw.getDisplaySurface(&dst);
    if (dst.base != 0) {
        uint8_t const* src = (uint8_t const*)(intptr_t(dst.base) + dst.offset); 
        const int fbWidth = dst.w;
        const int fbHeight = dst.h;
        const int fbFormat = dst.format;

        int x = mTransformedBounds.left;
        int y = mTransformedBounds.top;
        int w = mTransformedBounds.width();
        int h = mTransformedBounds.height();
        Parcel* const reply = mReply;
        if (reply) {
            const size_t Bpp = bytesPerPixel(fbFormat);
            const size_t size = w * h * Bpp;
            int32_t cfg = SkBitmap::kNo_Config;
            switch (fbFormat) {
                case PIXEL_FORMAT_RGBA_4444: cfg = SkBitmap::kARGB_4444_Config; break;
                case PIXEL_FORMAT_RGBA_8888: cfg = SkBitmap::kARGB_8888_Config; break;
                case PIXEL_FORMAT_RGB_565:   cfg = SkBitmap::kRGB_565_Config; break;
                case PIXEL_FORMAT_A_8:       cfg = SkBitmap::kA8_Config; break;
            }
            reply->writeInt32(0);
            reply->writeInt32(cfg);
            reply->writeInt32(w);
            reply->writeInt32(h);
            reply->writeInt32(w * Bpp);
            void* data = reply->writeInplace(size);
            if (data) {
                uint8_t* d = (uint8_t*)data;
                uint8_t const* s = src + (x + y*fbWidth) * Bpp;
                if (w == fbWidth) {
                    memcpy(d, s, w*h*Bpp);   
                } else {
                    for (int y=0 ; y<h ; y++) {
                        memcpy(d, s, w*Bpp);
                        d += w*Bpp;
                        s += fbWidth*Bpp;
                    }
                }
            }
        }
    }
    mCV.broadcast();
}

void LayerScreenshot::takeScreenshot(Mutex& lock, Parcel* reply)
{
    mReply = reply;
    mCV.wait(lock);
}

// ---------------------------------------------------------------------------

}; // namespace android
