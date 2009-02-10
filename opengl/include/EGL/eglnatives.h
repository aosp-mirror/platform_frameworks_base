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

#ifndef ANDROID_EGLNATIVES_H
#define ANDROID_EGLNATIVES_H

#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

/*****************************************************************************/

/* flags returned from swapBuffer */
#define EGL_NATIVES_FLAG_SIZE_CHANGED       0x00000001

/* surface flags */
#define EGL_NATIVES_FLAG_DESTROY_BACKBUFFER 0x00000001

enum native_pixel_format_t
{
    NATIVE_PIXEL_FORMAT_RGBA_8888   = 1,
    NATIVE_PIXEL_FORMAT_RGB_565     = 4,
    NATIVE_PIXEL_FORMAT_BGRA_8888   = 5,
    NATIVE_PIXEL_FORMAT_RGBA_5551   = 6,
    NATIVE_PIXEL_FORMAT_RGBA_4444   = 7,
    NATIVE_PIXEL_FORMAT_YCbCr_422_SP= 0x10,
    NATIVE_PIXEL_FORMAT_YCbCr_420_SP= 0x11,
};

enum native_memory_type_t
{
    NATIVE_MEMORY_TYPE_PMEM         = 0,
    NATIVE_MEMORY_TYPE_GPU          = 1,
    NATIVE_MEMORY_TYPE_FB           = 2,
    NATIVE_MEMORY_TYPE_HEAP         = 128
};


struct egl_native_window_t
{
    /*
     * magic must be set to 0x600913
     */
    uint32_t    magic;
    
    /*
     * must be sizeof(egl_native_window_t)
     */
    uint32_t    version;

    /*
     * ident is reserved for the Android platform
     */
    uint32_t    ident;
    
    /*
     * width, height and stride of the window in pixels
     * Any of these value can be nul in which case GL commands are
     * accepted and processed as usual, but not rendering occurs.
     */
    int         width;      // w=h=0 is legal
    int         height;
    int         stride;

    /*
     * format of the native window (see ui/PixelFormat.h)
     */
    int         format;
    
    /*
     * Offset of the bits in the VRAM
     */
    intptr_t    offset;
    
    /*
     * flags describing some attributes of this surface
     * EGL_NATIVES_FLAG_DESTROY_BACKBUFFER: backbuffer not preserved after 
     * eglSwapBuffers
     */
    uint32_t    flags;
    
    /*
     * horizontal and vertical resolution in DPI
     */
    float       xdpi;
    float       ydpi;
    
    /*
     * refresh rate in frames per second (Hz)
     */
    float       fps;
    
    
    /*
     *  Base memory virtual address of the surface in the CPU side
     */
    intptr_t    base;
    
    /*
     *  Heap the offset above is based from
     */
    int         fd;
    
    /*
     *  Memory type the surface resides into
     */
    uint8_t     memory_type;
    
    /*
     * Reserved for future use. MUST BE ZERO.
     */
    uint8_t     reserved_pad[3];
    int         reserved[8];
    
    /*
     * Vertical stride (only relevant with planar formats) 
     */
    
    int         vstride;

    /*
     * Hook called by EGL to hold a reference on this structure
     */
    void        (*incRef)(struct egl_native_window_t* window);

    /*
     * Hook called by EGL to release a reference on this structure
     */
    void        (*decRef)(struct egl_native_window_t* window);

    /*
     * Hook called by EGL to perform a page flip. This function
     * may update the size attributes above, in which case it returns
     * the EGL_NATIVES_FLAG_SIZE_CHANGED bit set.
     */
    uint32_t    (*swapBuffers)(struct egl_native_window_t* window);
    
    /*
     * Reserved for future use. MUST BE ZERO.
     */
    void        (*reserved_proc_0)(void);

    /*
     * Reserved for future use. MUST BE ZERO.
     */
    void        (*reserved_proc_1)(void);
    
    /*
     * Reserved for future use. MUST BE ZERO.
     */
    void        (*reserved_proc_2)(void);

    
    /*
     * Hook called by EGL when the native surface is associated to EGL
     * (eglCreateWindowSurface). Can be NULL.
     */
    void        (*connect)(struct egl_native_window_t* window);

    /*
     * Hook called by EGL when eglDestroySurface is called.  Can be NULL.
     */
    void        (*disconnect)(struct egl_native_window_t* window);
    
    /*
     * Reserved for future use. MUST BE ZERO.
     */
    void        (*reserved_proc[11])(void);
    
    /*
     *  Some storage reserved for the oem driver.
     */
    intptr_t    oem[4];
};


struct egl_native_pixmap_t
{
    int32_t     version;    /* must be 32 */
    int32_t     width;
    int32_t     height;
    int32_t     stride;
    uint8_t*    data;
    uint8_t     format;
    uint8_t     rfu[3];
    union {
        uint32_t    compressedFormat;
        int32_t     vstride;
    };
    int32_t     reserved;
};

/*****************************************************************************/

/* 
 * This a convenience function to create a NativeWindowType surface
 * that maps to the whole screen
 * This function is actually implemented in libui.so
 */

struct egl_native_window_t* android_createDisplaySurface();

/*****************************************************************************/


/*
 * OEM's egl's library (libhgl.so) must imlement these hooks to allocate
 * the GPU memory they need  
 */


typedef struct
{
    // for internal use
    void*   user;
    // virtual address of this area
    void*   base;
    // size of this area in bytes
    size_t  size;
    // physical address of this area
    void*   phys;
    // offset in this area available to the GPU
    size_t  offset;
    // fd of this area
    int     fd;
} gpu_area_t;

typedef struct
{
    // area where GPU registers are mapped
    gpu_area_t regs;
    // number of extra areas (currently limited to 2)
    int32_t count;
    // extra GPU areas (currently limited to 2)
    gpu_area_t gpu[2];
} request_gpu_t;


typedef request_gpu_t* (*OEM_EGL_acquire_gpu_t)(void* user);
typedef int (*OEM_EGL_release_gpu_t)(void* user, request_gpu_t* handle);
typedef void (*register_gpu_t)
        (void* user, OEM_EGL_acquire_gpu_t, OEM_EGL_release_gpu_t);

void oem_register_gpu(
        void* user,
        OEM_EGL_acquire_gpu_t acquire,
        OEM_EGL_release_gpu_t release);


/*****************************************************************************/

#ifdef __cplusplus
}
#endif

#endif /* ANDROID_EGLNATIVES_H */
