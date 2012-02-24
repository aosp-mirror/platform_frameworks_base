/*
 * Copyright (C) 2005 The Android Open Source Project
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

//

// Pixel formats used across the system.
// These formats might not all be supported by all renderers, for instance
// skia or SurfaceFlinger are not required to support all of these formats
// (either as source or destination)


#ifndef UI_PIXELFORMAT_H
#define UI_PIXELFORMAT_H

#include <stdint.h>
#include <sys/types.h>
#include <utils/Errors.h>
#include <hardware/hardware.h>

namespace android {

enum {
    //
    // these constants need to match those
    // in graphics/PixelFormat.java & pixelflinger/format.h
    //
    PIXEL_FORMAT_UNKNOWN    =   0,
    PIXEL_FORMAT_NONE       =   0,

    // logical pixel formats used by the SurfaceFlinger -----------------------
    PIXEL_FORMAT_CUSTOM         = -4,
        // Custom pixel-format described by a PixelFormatInfo structure

    PIXEL_FORMAT_TRANSLUCENT    = -3,
        // System chooses a format that supports translucency (many alpha bits)

    PIXEL_FORMAT_TRANSPARENT    = -2,
        // System chooses a format that supports transparency
        // (at least 1 alpha bit)

    PIXEL_FORMAT_OPAQUE         = -1,
        // System chooses an opaque format (no alpha bits required)

    // real pixel formats supported for rendering -----------------------------

    PIXEL_FORMAT_RGBA_8888   = HAL_PIXEL_FORMAT_RGBA_8888,  // 4x8-bit RGBA
    PIXEL_FORMAT_RGBX_8888   = HAL_PIXEL_FORMAT_RGBX_8888,  // 4x8-bit RGB0
    PIXEL_FORMAT_RGB_888     = HAL_PIXEL_FORMAT_RGB_888,    // 3x8-bit RGB
    PIXEL_FORMAT_RGB_565     = HAL_PIXEL_FORMAT_RGB_565,    // 16-bit RGB
    PIXEL_FORMAT_BGRA_8888   = HAL_PIXEL_FORMAT_BGRA_8888,  // 4x8-bit BGRA
    PIXEL_FORMAT_RGBA_5551   = HAL_PIXEL_FORMAT_RGBA_5551,  // 16-bit ARGB
    PIXEL_FORMAT_RGBA_4444   = HAL_PIXEL_FORMAT_RGBA_4444,  // 16-bit ARGB
    PIXEL_FORMAT_A_8         = 8,                           // 8-bit A
};

typedef int32_t PixelFormat;

struct PixelFormatInfo {
    enum {
        INDEX_ALPHA   = 0,
        INDEX_RED     = 1,
        INDEX_GREEN   = 2,
        INDEX_BLUE    = 3
    };

    enum { // components
        ALPHA   = 1,
        RGB     = 2,
        RGBA    = 3,
        L       = 4,
        LA      = 5,
        OTHER   = 0xFF
    };

    struct szinfo {
        uint8_t h;
        uint8_t l;
    };

    inline PixelFormatInfo() : version(sizeof(PixelFormatInfo)) { }
    size_t getScanlineSize(unsigned int width) const;
    size_t getSize(size_t ci) const {
        return (ci <= 3) ? (cinfo[ci].h - cinfo[ci].l) : 0;
    }
    size_t      version;
    PixelFormat format;
    size_t      bytesPerPixel;
    size_t      bitsPerPixel;
    union {
        szinfo      cinfo[4];
        struct {
            uint8_t     h_alpha;
            uint8_t     l_alpha;
            uint8_t     h_red;
            uint8_t     l_red;
            uint8_t     h_green;
            uint8_t     l_green;
            uint8_t     h_blue;
            uint8_t     l_blue;
        };
    };
    uint8_t     components;
    uint8_t     reserved0[3];
    uint32_t    reserved1;
};

// Consider caching the results of these functions are they're not
// guaranteed to be fast.
ssize_t     bytesPerPixel(PixelFormat format);
ssize_t     bitsPerPixel(PixelFormat format);
status_t    getPixelFormatInfo(PixelFormat format, PixelFormatInfo* info);

}; // namespace android

#endif // UI_PIXELFORMAT_H
