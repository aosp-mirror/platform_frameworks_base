/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_ANDROID_NATIVES_H
#define ANDROID_ANDROID_NATIVES_H

#include <sys/types.h>
#include <string.h>

#include <hardware/gralloc.h>

#include <android/native_window.h>

#ifdef __cplusplus
extern "C" {
#endif

/*****************************************************************************/

#define ANDROID_NATIVE_MAKE_CONSTANT(a,b,c,d) \
    (((unsigned)(a)<<24)|((unsigned)(b)<<16)|((unsigned)(c)<<8)|(unsigned)(d))

#define ANDROID_NATIVE_WINDOW_MAGIC \
    ANDROID_NATIVE_MAKE_CONSTANT('_','w','n','d')

#define ANDROID_NATIVE_BUFFER_MAGIC \
    ANDROID_NATIVE_MAKE_CONSTANT('_','b','f','r')

// ---------------------------------------------------------------------------

struct android_native_buffer_t;

typedef struct android_native_rect_t
{
    int32_t left;
    int32_t top;
    int32_t right;
    int32_t bottom;
} android_native_rect_t;

// ---------------------------------------------------------------------------

typedef struct android_native_base_t
{
    /* a magic value defined by the actual EGL native type */
    int magic;
    
    /* the sizeof() of the actual EGL native type */
    int version;

    void* reserved[4];

    /* reference-counting interface */
    void (*incRef)(struct android_native_base_t* base);
    void (*decRef)(struct android_native_base_t* base);
} android_native_base_t;

// ---------------------------------------------------------------------------

/* attributes queriable with query() */
enum {
    NATIVE_WINDOW_WIDTH     = 0,
    NATIVE_WINDOW_HEIGHT,
    NATIVE_WINDOW_FORMAT,
};

/* valid operations for the (*perform)() hook */
enum {
    NATIVE_WINDOW_SET_USAGE  = 0,
    NATIVE_WINDOW_CONNECT,
    NATIVE_WINDOW_DISCONNECT,
    NATIVE_WINDOW_SET_CROP,
    NATIVE_WINDOW_SET_BUFFER_COUNT,
    NATIVE_WINDOW_SET_BUFFERS_GEOMETRY,
    NATIVE_WINDOW_SET_BUFFERS_TRANSFORM,
};

/* parameter for NATIVE_WINDOW_[DIS]CONNECT */
enum {
    NATIVE_WINDOW_API_EGL = 1
};

/* parameter for NATIVE_WINDOW_SET_BUFFERS_TRANSFORM */
enum {
    /* flip source image horizontally */
    NATIVE_WINDOW_TRANSFORM_FLIP_H = HAL_TRANSFORM_FLIP_H ,
    /* flip source image vertically */
    NATIVE_WINDOW_TRANSFORM_FLIP_V = HAL_TRANSFORM_FLIP_V,
    /* rotate source image 90 degrees clock-wise */
    NATIVE_WINDOW_TRANSFORM_ROT_90 = HAL_TRANSFORM_ROT_90,
    /* rotate source image 180 degrees */
    NATIVE_WINDOW_TRANSFORM_ROT_180 = HAL_TRANSFORM_ROT_180,
    /* rotate source image 270 degrees clock-wise */
    NATIVE_WINDOW_TRANSFORM_ROT_270 = HAL_TRANSFORM_ROT_270,
};

struct ANativeWindow 
{
#ifdef __cplusplus
    ANativeWindow()
        : flags(0), minSwapInterval(0), maxSwapInterval(0), xdpi(0), ydpi(0)
    {
        common.magic = ANDROID_NATIVE_WINDOW_MAGIC;
        common.version = sizeof(ANativeWindow);
        memset(common.reserved, 0, sizeof(common.reserved));
    }

    // Implement the methods that sp<ANativeWindow> expects so that it
    // can be used to automatically refcount ANativeWindow's.
    void incStrong(const void* id) const {
        common.incRef(const_cast<android_native_base_t*>(&common));
    }
    void decStrong(const void* id) const {
        common.decRef(const_cast<android_native_base_t*>(&common));
    }
#endif
    
    struct android_native_base_t common;

    /* flags describing some attributes of this surface or its updater */
    const uint32_t flags;
    
    /* min swap interval supported by this updated */
    const int   minSwapInterval;

    /* max swap interval supported by this updated */
    const int   maxSwapInterval;

    /* horizontal and vertical resolution in DPI */
    const float xdpi;
    const float ydpi;

    /* Some storage reserved for the OEM's driver. */
    intptr_t    oem[4];
        

    /*
     * Set the swap interval for this surface.
     * 
     * Returns 0 on success or -errno on error.
     */
    int     (*setSwapInterval)(struct ANativeWindow* window,
                int interval);
    
    /*
     * hook called by EGL to acquire a buffer. After this call, the buffer
     * is not locked, so its content cannot be modified.
     * this call may block if no buffers are available.
     * 
     * Returns 0 on success or -errno on error.
     */
    int     (*dequeueBuffer)(struct ANativeWindow* window,
                struct android_native_buffer_t** buffer);

    /*
     * hook called by EGL to lock a buffer. This MUST be called before modifying
     * the content of a buffer. The buffer must have been acquired with 
     * dequeueBuffer first.
     * 
     * Returns 0 on success or -errno on error.
     */
    int     (*lockBuffer)(struct ANativeWindow* window,
                struct android_native_buffer_t* buffer);
   /*
    * hook called by EGL when modifications to the render buffer are done. 
    * This unlocks and post the buffer.
    * 
    * Buffers MUST be queued in the same order than they were dequeued.
    * 
    * Returns 0 on success or -errno on error.
    */
    int     (*queueBuffer)(struct ANativeWindow* window,
                struct android_native_buffer_t* buffer);

    /*
     * hook used to retrieve information about the native window.
     * 
     * Returns 0 on success or -errno on error.
     */
    int     (*query)(struct ANativeWindow* window,
                int what, int* value);
    
    /*
     * hook used to perform various operations on the surface.
     * (*perform)() is a generic mechanism to add functionality to
     * ANativeWindow while keeping backward binary compatibility.
     * 
     * This hook should not be called directly, instead use the helper functions
     * defined below.
     * 
     *  (*perform)() returns -ENOENT if the 'what' parameter is not supported
     *  by the surface's implementation.
     *
     * The valid operations are:
     *     NATIVE_WINDOW_SET_USAGE
     *     NATIVE_WINDOW_CONNECT
     *     NATIVE_WINDOW_DISCONNECT
     *     NATIVE_WINDOW_SET_CROP
     *     NATIVE_WINDOW_SET_BUFFER_COUNT
     *     NATIVE_WINDOW_SET_BUFFERS_GEOMETRY
     *     NATIVE_WINDOW_SET_BUFFERS_TRANSFORM
     *  
     */
    
    int     (*perform)(struct ANativeWindow* window,
                int operation, ... );
    
    /*
     * hook used to cancel a buffer that has been dequeued.
     * No synchronization is performed between dequeue() and cancel(), so
     * either external synchronization is needed, or these functions must be
     * called from the same thread.
     */
    int     (*cancelBuffer)(struct ANativeWindow* window,
                struct android_native_buffer_t* buffer);


    void* reserved_proc[2];
};

// Backwards compatibility...  please switch to ANativeWindow.
typedef struct ANativeWindow android_native_window_t;

/*
 *  native_window_set_usage(..., usage)
 *  Sets the intended usage flags for the next buffers
 *  acquired with (*lockBuffer)() and on.
 *  By default (if this function is never called), a usage of
 *      GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_TEXTURE
 *  is assumed.
 *  Calling this function will usually cause following buffers to be
 *  reallocated.
 */

static inline int native_window_set_usage(
        ANativeWindow* window, int usage)
{
    return window->perform(window, NATIVE_WINDOW_SET_USAGE, usage);
}

/*
 * native_window_connect(..., NATIVE_WINDOW_API_EGL)
 * Must be called by EGL when the window is made current.
 * Returns -EINVAL if for some reason the window cannot be connected, which
 * can happen if it's connected to some other API.
 */
static inline int native_window_connect(
        ANativeWindow* window, int api)
{
    return window->perform(window, NATIVE_WINDOW_CONNECT, api);
}

/*
 * native_window_disconnect(..., NATIVE_WINDOW_API_EGL)
 * Must be called by EGL when the window is made not current.
 * An error is returned if for instance the window wasn't connected in the
 * first place.
 */
static inline int native_window_disconnect(
        ANativeWindow* window, int api)
{
    return window->perform(window, NATIVE_WINDOW_DISCONNECT, api);
}

/*
 * native_window_set_crop(..., crop)
 * Sets which region of the next queued buffers needs to be considered.
 * A buffer's crop region is scaled to match the surface's size.
 *
 * The specified crop region applies to all buffers queued after it is called.
 *
 * if 'crop' is NULL, subsequently queued buffers won't be cropped.
 *
 * An error is returned if for instance the crop region is invalid,
 * out of the buffer's bound or if the window is invalid.
 */
static inline int native_window_set_crop(
        ANativeWindow* window,
        android_native_rect_t const * crop)
{
    return window->perform(window, NATIVE_WINDOW_SET_CROP, crop);
}

/*
 * native_window_set_buffer_count(..., count)
 * Sets the number of buffers associated with this native window.
 */
static inline int native_window_set_buffer_count(
        ANativeWindow* window,
        size_t bufferCount)
{
    return window->perform(window, NATIVE_WINDOW_SET_BUFFER_COUNT, bufferCount);
}

/*
 * native_window_set_buffers_geometry(..., int w, int h, int format)
 * All buffers dequeued after this call will have the geometry specified.
 * In particular, all buffers will have a fixed-size, independent form the
 * native-window size. They will be appropriately scaled to the window-size
 * upon composition.
 *
 * If all parameters are 0, the normal behavior is restored. That is,
 * dequeued buffers following this call will be sized to the window's size.
 *
 */
static inline int native_window_set_buffers_geometry(
        ANativeWindow* window,
        int w, int h, int format)
{
    return window->perform(window, NATIVE_WINDOW_SET_BUFFERS_GEOMETRY,
            w, h, format);
}

/*
 * native_window_set_buffers_transform(..., int transform)
 * All buffers queued after this call will be displayed transformed according
 * to the transform parameter specified.
 */
static inline int native_window_set_buffers_transform(
        ANativeWindow* window,
        int transform)
{
    return window->perform(window, NATIVE_WINDOW_SET_BUFFERS_TRANSFORM,
            transform);
}

// ---------------------------------------------------------------------------

/* FIXME: this is legacy for pixmaps */
typedef struct egl_native_pixmap_t
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
} egl_native_pixmap_t;

/*****************************************************************************/

#ifdef __cplusplus
}
#endif


/*****************************************************************************/

#ifdef __cplusplus

#include <utils/RefBase.h>

namespace android {

/*
 * This helper class turns an EGL android_native_xxx type into a C++
 * reference-counted object; with proper type conversions.
 */
template <typename NATIVE_TYPE, typename TYPE, typename REF>
class EGLNativeBase : public NATIVE_TYPE, public REF
{
public:
    // Disambiguate between the incStrong in REF and NATIVE_TYPE
    void incStrong(const void* id) const {
        REF::incStrong(id);
    }
    void decStrong(const void* id) const {
        REF::decStrong(id);
    }

protected:
    typedef EGLNativeBase<NATIVE_TYPE, TYPE, REF> BASE;
    EGLNativeBase() : NATIVE_TYPE(), REF() {
        NATIVE_TYPE::common.incRef = incRef;
        NATIVE_TYPE::common.decRef = decRef;
    }
    static inline TYPE* getSelf(NATIVE_TYPE* self) {
        return static_cast<TYPE*>(self);
    }
    static inline TYPE const* getSelf(NATIVE_TYPE const* self) {
        return static_cast<TYPE const *>(self);
    }
    static inline TYPE* getSelf(android_native_base_t* base) {
        return getSelf(reinterpret_cast<NATIVE_TYPE*>(base));
    }
    static inline TYPE const * getSelf(android_native_base_t const* base) {
        return getSelf(reinterpret_cast<NATIVE_TYPE const*>(base));
    }
    static void incRef(android_native_base_t* base) {
        EGLNativeBase* self = getSelf(base);
        self->incStrong(self);
    }
    static void decRef(android_native_base_t* base) {
        EGLNativeBase* self = getSelf(base);
        self->decStrong(self);
    }
};

} // namespace android
#endif // __cplusplus

/*****************************************************************************/

#endif /* ANDROID_ANDROID_NATIVES_H */
