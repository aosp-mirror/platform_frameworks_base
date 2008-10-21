/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef ANDROID_BLIT_HARDWARE_H
#define ANDROID_BLIT_HARDWARE_H

#include <stdint.h>
#include <sys/types.h>

#if __cplusplus
extern "C" {
#endif

/******************************************************************************/

/* supported pixel-formats. these must be compatible with
 * graphics/PixelFormat.java, ui/PixelFormat.h, pixelflinger/format.h
 */

enum
{
    COPYBIT_RGBA_8888    = 1,
    COPYBIT_RGB_565      = 4,
    COPYBIT_RGBA_5551    = 6,
    COPYBIT_RGBA_4444    = 7,
    COPYBIT_YCbCr_422_SP = 0x10,
    COPYBIT_YCbCr_420_SP = 0x11
};

/* name for copybit_set_parameter */
enum 
{
    /* rotation of the source image in degrees (0 to 359) */
    COPYBIT_ROTATION_DEG    = 1,
    /* plane alpha value */
    COPYBIT_PLANE_ALPHA     = 2,
    /* enable or disable dithering */
    COPYBIT_DITHER          = 3,
    /* transformation applied (this is a superset of COPYBIT_ROTATION_DEG) */
    COPYBIT_TRANSFORM       = 4,
};

/* values for copybit_set_parameter(COPYBIT_TRANSFORM) */
enum {
    /* flip source image horizontally */
    COPYBIT_TRANSFORM_FLIP_H    = 0x01,
    /* flip source image vertically */
    COPYBIT_TRANSFORM_FLIP_V    = 0x02,
    /* rotate source image 90 degres */
    COPYBIT_TRANSFORM_ROT_90    = 0x04,
    /* rotate source image 180 degres */
    COPYBIT_TRANSFORM_ROT_180   = 0x03,
    /* rotate source image 270 degres */
    COPYBIT_TRANSFORM_ROT_270   = 0x07,
};

/* enable/disable value copybit_set_parameter */
enum {
    COPYBIT_DISABLE = 0,
    COPYBIT_ENABLE  = 1
};

/* use get() to query static informations about the hardware */
enum {
    /* Maximum amount of minification supported by the hardware*/
    COPYBIT_MINIFICATION_LIMIT  = 1,
    /* Maximum amount of magnification supported by the hardware */
    COPYBIT_MAGNIFICATION_LIMIT = 2,
    /* Number of fractional bits support by the scaling engine */
    COPYBIT_SCALING_FRAC_BITS   = 3,
    /* Supported rotation step in degres. */
    COPYBIT_ROTATION_STEP_DEG   = 4,
};

struct copybit_image_t {
    uint32_t    w;
    uint32_t    h;
    int32_t     format;
    uint32_t    offset;
    void*       base;
    int         fd;
};


struct copybit_rect_t {
    int l;
    int t;
    int r;
    int b;
};

struct copybit_region_t {
    int (*next)(copybit_region_t const*, copybit_rect_t* rect); 
};

struct copybit_t
{
    int (*set_parameter)(struct copybit_t* handle, int name, int value);

    int (*get)(struct copybit_t* handle, int name);
    
    int (*blit)(
            struct copybit_t* handle, 
            struct copybit_image_t const* dst, 
            struct copybit_image_t const* src,
            struct copybit_region_t const* region);

    int (*stretch)(
            struct copybit_t* handle, 
            struct copybit_image_t const* dst, 
            struct copybit_image_t const* src, 
            struct copybit_rect_t const* dst_rect,
            struct copybit_rect_t const* src_rect,
            struct copybit_region_t const* region);
};

/******************************************************************************/

struct copybit_t* copybit_init();

int copybit_term(struct copybit_t* handle);


/******************************************************************************/

#if __cplusplus
} // extern "C"
#endif

#endif // ANDROID_BLIT_HARDWARE_H
