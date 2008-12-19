/* 
**
** Copyright 2007 The Android Open Source Project
**
** Licensed under the Apache License Version 2.0(the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing software 
** distributed under the License is distributed on an "AS IS" BASIS 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#define LOG_TAG "EGL"

#include <assert.h>
#include <errno.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/mman.h>

#include <cutils/log.h>
#include <cutils/atomic.h>

#include <utils/threads.h>

#include <GLES/egl.h>

#include <pixelflinger/format.h>
#include <pixelflinger/pixelflinger.h>

#include "context.h"
#include "state.h"
#include "texture.h"
#include "matrix.h"

#undef NELEM
#define NELEM(x) (sizeof(x)/sizeof(*(x)))

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

const unsigned int NUM_DISPLAYS = 1;

static pthread_mutex_t gInitMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t gErrorKeyMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_key_t gEGLErrorKey = -1;
#ifndef HAVE_ANDROID_OS
namespace gl {
pthread_key_t gGLKey = -1;
}; // namespace gl
#endif

template<typename T>
static T setError(GLint error, T returnValue) {
    if (ggl_unlikely(gEGLErrorKey == -1)) {
        pthread_mutex_lock(&gErrorKeyMutex);
        if (gEGLErrorKey == -1)
            pthread_key_create(&gEGLErrorKey, NULL);
        pthread_mutex_unlock(&gErrorKeyMutex);
    }
    pthread_setspecific(gEGLErrorKey, (void*)error);
    return returnValue;
}

static GLint getError() {
    if (ggl_unlikely(gEGLErrorKey == -1))
        return EGL_SUCCESS;
    GLint error = (GLint)pthread_getspecific(gEGLErrorKey);
    pthread_setspecific(gEGLErrorKey, (void*)EGL_SUCCESS);
    return error;
}

// ----------------------------------------------------------------------------

struct egl_display_t
{
    egl_display_t() : type(0), initialized(0) { }
    
    static egl_display_t& get_display(EGLDisplay dpy);
    
    static EGLBoolean is_valid(EGLDisplay dpy) {
        return ((uintptr_t(dpy)-1U) >= NUM_DISPLAYS) ? EGL_FALSE : EGL_TRUE;
    }

    NativeDisplayType   type;
    volatile int32_t    initialized;
};

static egl_display_t gDisplays[NUM_DISPLAYS];

egl_display_t& egl_display_t::get_display(EGLDisplay dpy) {
    return gDisplays[uintptr_t(dpy)-1U];
}

struct egl_context_t {
    enum {
        IS_CURRENT      =   0x00010000,
        NEVER_CURRENT   =   0x00020000
    };
    uint32_t            flags;
    EGLDisplay          dpy;
    EGLConfig           config;
    EGLSurface          read;
    EGLSurface          draw;

    static inline egl_context_t* context(EGLContext ctx) {
        ogles_context_t* const gl = static_cast<ogles_context_t*>(ctx);
        return static_cast<egl_context_t*>(gl->rasterizer.base);
    }
};

// ----------------------------------------------------------------------------

struct egl_surface_t
{
    enum {
        PAGE_FLIP = 0x00000001,
        MAGIC     = 0x31415265
    };

    uint32_t            magic;
    EGLDisplay          dpy;
    EGLConfig           config;
    EGLContext          ctx;

                egl_surface_t(EGLDisplay dpy, EGLConfig config, int32_t depthFormat);
    virtual     ~egl_surface_t();
    virtual     bool    isValid() const = 0;
    
    virtual     EGLBoolean  bindDrawSurface(ogles_context_t* gl) = 0;
    virtual     EGLBoolean  bindReadSurface(ogles_context_t* gl) = 0;
    virtual     EGLint      getWidth() const = 0;
    virtual     EGLint      getHeight() const = 0;
    virtual     void*       getBits() const = 0;

    virtual     EGLint      getHorizontalResolution() const;
    virtual     EGLint      getVerticalResolution() const;
    virtual     EGLint      getRefreshRate() const;
    virtual     EGLint      getSwapBehavior() const;
    virtual     EGLBoolean  swapBuffers();
    virtual     EGLBoolean  swapRectangle(EGLint l, EGLint t, EGLint w, EGLint h);
protected:
    GGLSurface              depth;
};

egl_surface_t::egl_surface_t(EGLDisplay dpy,
        EGLConfig config,
        int32_t depthFormat)
    : magic(MAGIC), dpy(dpy), config(config), ctx(0)
{
    depth.version = sizeof(GGLSurface);
    depth.data = 0;
    depth.format = depthFormat;
}
egl_surface_t::~egl_surface_t()
{
    magic = 0;
    free(depth.data);
}
EGLBoolean egl_surface_t::swapBuffers() {
    return EGL_FALSE;
}
EGLBoolean egl_surface_t::swapRectangle(
        EGLint l, EGLint t, EGLint w, EGLint h) {
    return EGL_FALSE;
}
EGLint egl_surface_t::getHorizontalResolution() const {
    return (0 * EGL_DISPLAY_SCALING) * (1.0f / 25.4f);
}
EGLint egl_surface_t::getVerticalResolution() const {
    return (0 * EGL_DISPLAY_SCALING) * (1.0f / 25.4f);
}
EGLint egl_surface_t::getRefreshRate() const {
    return (60 * EGL_DISPLAY_SCALING);
}
EGLint egl_surface_t::getSwapBehavior() const {
    return EGL_BUFFER_PRESERVED;
}

// ----------------------------------------------------------------------------

struct egl_window_surface_t : public egl_surface_t
{
    egl_window_surface_t(
            EGLDisplay dpy, EGLConfig config,
            int32_t depthFormat,
            egl_native_window_t* window);

     ~egl_window_surface_t();

    virtual     bool        isValid() const { return nativeWindow->magic == 0x600913; }    
    virtual     EGLBoolean  swapBuffers();
    virtual     EGLBoolean  swapRectangle(EGLint l, EGLint t, EGLint w, EGLint h);
    virtual     EGLBoolean  bindDrawSurface(ogles_context_t* gl);
    virtual     EGLBoolean  bindReadSurface(ogles_context_t* gl);
    virtual     EGLint      getWidth() const    { return nativeWindow->width;  }
    virtual     EGLint      getHeight() const   { return nativeWindow->height; }
    virtual     void*       getBits() const;
    virtual     EGLint      getHorizontalResolution() const;
    virtual     EGLint      getVerticalResolution() const;
    virtual     EGLint      getRefreshRate() const;
    virtual     EGLint      getSwapBehavior() const;
private:
    egl_native_window_t*    nativeWindow;
};

egl_window_surface_t::egl_window_surface_t(EGLDisplay dpy,
        EGLConfig config,
        int32_t depthFormat,
        egl_native_window_t* window)
    : egl_surface_t(dpy, config, depthFormat), nativeWindow(window)
{
    if (depthFormat) {
        depth.width   = window->width;
        depth.height  = window->height;
        depth.stride  = depth.width; // use the width here
        depth.data    = (GGLubyte*)malloc(depth.stride*depth.height*2);
        if (depth.data == 0) {
            setError(EGL_BAD_ALLOC, EGL_NO_SURFACE);
            return;
        }
    }
    nativeWindow->incRef(nativeWindow);
}
egl_window_surface_t::~egl_window_surface_t() {
    nativeWindow->decRef(nativeWindow);
}

EGLBoolean egl_window_surface_t::swapBuffers()
{
    uint32_t flags = nativeWindow->swapBuffers(nativeWindow);
    if (flags & EGL_NATIVES_FLAG_SIZE_CHANGED) {
        // TODO: we probably should reset the swap rect here
        // if the window size has changed
        //    window->setSwapRectangle(Rect(info.w, info.h));
        if (depth.data) {
            free(depth.data);
            depth.width   = nativeWindow->width;
            depth.height  = nativeWindow->height;
            depth.stride  = nativeWindow->stride;
            depth.data    = (GGLubyte*)malloc(depth.stride*depth.height*2);
            if (depth.data == 0) {
                setError(EGL_BAD_ALLOC, EGL_NO_SURFACE);
                return EGL_FALSE;
            }
        }
    }
    return EGL_TRUE;
}

EGLBoolean egl_window_surface_t::swapRectangle(
        EGLint l, EGLint t, EGLint w, EGLint h)
{
    nativeWindow->setSwapRectangle(nativeWindow, l, t, w, h);
    return EGL_TRUE;
}
EGLBoolean egl_window_surface_t::bindDrawSurface(ogles_context_t* gl)
{
    GGLSurface buffer;
    buffer.version = sizeof(GGLSurface);
    buffer.width   = nativeWindow->width;
    buffer.height  = nativeWindow->height;
    buffer.stride  = nativeWindow->stride;
    buffer.data    = (GGLubyte*)nativeWindow->base + nativeWindow->offset;
    buffer.format  = nativeWindow->format;
    gl->rasterizer.procs.colorBuffer(gl, &buffer);
    if (depth.data != gl->rasterizer.state.buffers.depth.data)
        gl->rasterizer.procs.depthBuffer(gl, &depth);
    return EGL_TRUE;
}
EGLBoolean egl_window_surface_t::bindReadSurface(ogles_context_t* gl)
{
    GGLSurface buffer;
    buffer.version = sizeof(GGLSurface);
    buffer.width   = nativeWindow->width;
    buffer.height  = nativeWindow->height;
    buffer.stride  = nativeWindow->stride;
    buffer.data    = (GGLubyte*)nativeWindow->base + nativeWindow->offset;
    buffer.format  = nativeWindow->format;
    gl->rasterizer.procs.readBuffer(gl, &buffer);
    return EGL_TRUE;
}
void* egl_window_surface_t::getBits() const {
    return (GGLubyte*)nativeWindow->base + nativeWindow->offset;
}
EGLint egl_window_surface_t::getHorizontalResolution() const {
    return (nativeWindow->xdpi * EGL_DISPLAY_SCALING) * (1.0f / 25.4f);
}
EGLint egl_window_surface_t::getVerticalResolution() const {
    return (nativeWindow->ydpi * EGL_DISPLAY_SCALING) * (1.0f / 25.4f);
}
EGLint egl_window_surface_t::getRefreshRate() const {
    return (nativeWindow->fps * EGL_DISPLAY_SCALING);
}
EGLint egl_window_surface_t::getSwapBehavior() const {
    uint32_t flags = nativeWindow->flags;
    if (flags & EGL_NATIVES_FLAG_DESTROY_BACKBUFFER)
        return EGL_BUFFER_DESTROYED;
    return EGL_BUFFER_PRESERVED;
}

// ----------------------------------------------------------------------------

struct egl_pixmap_surface_t : public egl_surface_t
{
    egl_pixmap_surface_t(
            EGLDisplay dpy, EGLConfig config,
            int32_t depthFormat,
            egl_native_pixmap_t const * pixmap);

    virtual ~egl_pixmap_surface_t() { }

    virtual     bool        isValid() const { return nativePixmap.version == sizeof(egl_native_pixmap_t); }    
    virtual     EGLBoolean  bindDrawSurface(ogles_context_t* gl);
    virtual     EGLBoolean  bindReadSurface(ogles_context_t* gl);
    virtual     EGLint      getWidth() const    { return nativePixmap.width;  }
    virtual     EGLint      getHeight() const   { return nativePixmap.height; }
    virtual     void*       getBits() const     { return nativePixmap.data; }
private:
    egl_native_pixmap_t     nativePixmap;
};

egl_pixmap_surface_t::egl_pixmap_surface_t(EGLDisplay dpy,
        EGLConfig config,
        int32_t depthFormat,
        egl_native_pixmap_t const * pixmap)
    : egl_surface_t(dpy, config, depthFormat), nativePixmap(*pixmap)
{
    if (depthFormat) {
        depth.width   = pixmap->width;
        depth.height  = pixmap->height;
        depth.stride  = depth.width; // use the width here
        depth.data    = (GGLubyte*)malloc(depth.stride*depth.height*2);
        if (depth.data == 0) {
            setError(EGL_BAD_ALLOC, EGL_NO_SURFACE);
            return;
        }
    }
}
EGLBoolean egl_pixmap_surface_t::bindDrawSurface(ogles_context_t* gl)
{
    GGLSurface buffer;
    buffer.version = sizeof(GGLSurface);
    buffer.width   = nativePixmap.width;
    buffer.height  = nativePixmap.height;
    buffer.stride  = nativePixmap.stride;
    buffer.data    = nativePixmap.data;
    buffer.format  = nativePixmap.format;
    
    gl->rasterizer.procs.colorBuffer(gl, &buffer);
    if (depth.data != gl->rasterizer.state.buffers.depth.data)
        gl->rasterizer.procs.depthBuffer(gl, &depth);
    return EGL_TRUE;
}
EGLBoolean egl_pixmap_surface_t::bindReadSurface(ogles_context_t* gl)
{
    GGLSurface buffer;
    buffer.version = sizeof(GGLSurface);
    buffer.width   = nativePixmap.width;
    buffer.height  = nativePixmap.height;
    buffer.stride  = nativePixmap.stride;
    buffer.data    = nativePixmap.data;
    buffer.format  = nativePixmap.format;
    gl->rasterizer.procs.readBuffer(gl, &buffer);
    return EGL_TRUE;
}

// ----------------------------------------------------------------------------

struct egl_pbuffer_surface_t : public egl_surface_t
{
    egl_pbuffer_surface_t(
            EGLDisplay dpy, EGLConfig config, int32_t depthFormat,
            int32_t w, int32_t h, int32_t f);

    virtual ~egl_pbuffer_surface_t();

    virtual     bool        isValid() const { return pbuffer.data != 0; }    
    virtual     EGLBoolean  bindDrawSurface(ogles_context_t* gl);
    virtual     EGLBoolean  bindReadSurface(ogles_context_t* gl);
    virtual     EGLint      getWidth() const    { return pbuffer.width;  }
    virtual     EGLint      getHeight() const   { return pbuffer.height; }
    virtual     void*       getBits() const     { return pbuffer.data; }
private:
    GGLSurface  pbuffer;
};

egl_pbuffer_surface_t::egl_pbuffer_surface_t(EGLDisplay dpy,
        EGLConfig config, int32_t depthFormat,
        int32_t w, int32_t h, int32_t f)
    : egl_surface_t(dpy, config, depthFormat)
{
    size_t size = w*h;
    switch (f) {
        case GGL_PIXEL_FORMAT_A_8:          size *= 1; break;
        case GGL_PIXEL_FORMAT_RGB_565:      size *= 2; break;
        case GGL_PIXEL_FORMAT_RGBA_8888:    size *= 4; break;
        default:
            LOGE("incompatible pixel format for pbuffer (format=%d)", f);
            pbuffer.data = 0;
            break;
    }
    pbuffer.version = sizeof(GGLSurface);
    pbuffer.width   = w;
    pbuffer.height  = h;
    pbuffer.stride  = w;
    pbuffer.data    = (GGLubyte*)malloc(size);
    pbuffer.format  = f;
    
    if (depthFormat) {
        depth.width   = pbuffer.width;
        depth.height  = pbuffer.height;
        depth.stride  = depth.width; // use the width here
        depth.data    = (GGLubyte*)malloc(depth.stride*depth.height*2);
        if (depth.data == 0) {
            setError(EGL_BAD_ALLOC, EGL_NO_SURFACE);
            return;
        }
    }
}
egl_pbuffer_surface_t::~egl_pbuffer_surface_t() {
    free(pbuffer.data);
}
EGLBoolean egl_pbuffer_surface_t::bindDrawSurface(ogles_context_t* gl)
{
    gl->rasterizer.procs.colorBuffer(gl, &pbuffer);
    if (depth.data != gl->rasterizer.state.buffers.depth.data)
        gl->rasterizer.procs.depthBuffer(gl, &depth);
    return EGL_TRUE;
}
EGLBoolean egl_pbuffer_surface_t::bindReadSurface(ogles_context_t* gl)
{
    gl->rasterizer.procs.readBuffer(gl, &pbuffer);
    return EGL_TRUE;
}

// ----------------------------------------------------------------------------

struct config_pair_t {
    GLint key;
    GLint value;
};

struct configs_t {
    const config_pair_t* array;
    int                  size;
};

struct config_management_t {
    GLint key;
    bool (*match)(GLint reqValue, GLint confValue);
    static bool atLeast(GLint reqValue, GLint confValue) {
        return (reqValue == EGL_DONT_CARE) || (confValue >= reqValue);
    }
    static bool exact(GLint reqValue, GLint confValue) {
        return (reqValue == EGL_DONT_CARE) || (confValue == reqValue);
    }
    static bool mask(GLint reqValue, GLint confValue) {
        return (confValue & reqValue) == reqValue;
    }
};

// ----------------------------------------------------------------------------

static char const * const gVendorString     = "Google Inc.";
static char const * const gVersionString    = "1.2 Android Driver";
static char const * const gClientApiString  = "OpenGL ES";
static char const * const gExtensionsString =
    "EGL_ANDROID_swap_rectangle"                " "
    "EGL_ANDROID_copy_front_to_back"            " "
    "EGL_ANDROID_get_render_buffer_address"
    ;

// ----------------------------------------------------------------------------

struct extention_map_t {
    const char * const name;
    void (*address)(void);
};

static const extention_map_t gExtentionMap[] = {
    { "eglSwapRectangleANDROID",    (void(*)())&eglSwapRectangleANDROID },
    { "glDrawTexsOES",              (void(*)())&glDrawTexsOES },
    { "glDrawTexiOES",              (void(*)())&glDrawTexiOES },
    { "glDrawTexfOES",              (void(*)())&glDrawTexfOES },
    { "glDrawTexxOES",              (void(*)())&glDrawTexxOES },
    { "glDrawTexsvOES",             (void(*)())&glDrawTexsvOES },
    { "glDrawTexivOES",             (void(*)())&glDrawTexivOES },
    { "glDrawTexfvOES",             (void(*)())&glDrawTexfvOES },
    { "glDrawTexxvOES",             (void(*)())&glDrawTexxvOES },
    { "glQueryMatrixxOES",          (void(*)())&glQueryMatrixxOES },
    { "glClipPlanef",               (void(*)())&glClipPlanef },
    { "glClipPlanex",               (void(*)())&glClipPlanex },
    { "glBindBuffer",               (void(*)())&glBindBuffer },
    { "glBufferData",               (void(*)())&glBufferData },
    { "glBufferSubData",            (void(*)())&glBufferSubData },
    { "glDeleteBuffers",            (void(*)())&glDeleteBuffers },
    { "glGenBuffers",               (void(*)())&glGenBuffers },
};

/* 
 * In the lists below, attributes names MUST be sorted.
 * Additionally, all configs must be sorted according to
 * the EGL specification.
 */

static config_pair_t const config_base_attribute_list[] = {
        { EGL_STENCIL_SIZE,               0                                 },
        { EGL_CONFIG_CAVEAT,              EGL_SLOW_CONFIG                   },
        { EGL_LEVEL,                      0                                 },
        { EGL_MAX_PBUFFER_HEIGHT,         GGL_MAX_VIEWPORT_DIMS             },
        { EGL_MAX_PBUFFER_PIXELS,         
                GGL_MAX_VIEWPORT_DIMS*GGL_MAX_VIEWPORT_DIMS                 },
        { EGL_MAX_PBUFFER_WIDTH,          GGL_MAX_VIEWPORT_DIMS             },
        { EGL_NATIVE_RENDERABLE,          EGL_TRUE                          },
        { EGL_NATIVE_VISUAL_ID,           0                                 },
        { EGL_NATIVE_VISUAL_TYPE,         GGL_PIXEL_FORMAT_RGB_565          },
        { EGL_SAMPLES,                    0                                 },
        { EGL_SAMPLE_BUFFERS,             0                                 },
        { EGL_TRANSPARENT_TYPE,           EGL_NONE                          },
        { EGL_TRANSPARENT_BLUE_VALUE,     0                                 },
        { EGL_TRANSPARENT_GREEN_VALUE,    0                                 },
        { EGL_TRANSPARENT_RED_VALUE,      0                                 },
        { EGL_BIND_TO_TEXTURE_RGBA,       EGL_FALSE                         },
        { EGL_BIND_TO_TEXTURE_RGB,        EGL_FALSE                         },
        { EGL_MIN_SWAP_INTERVAL,          1                                 },
        { EGL_MAX_SWAP_INTERVAL,          4                                 },
};

// These configs can override the base attribute list
// NOTE: when adding a config here, don't forget to update eglCreate*Surface()

static config_pair_t const config_0_attribute_list[] = {
        { EGL_BUFFER_SIZE,     16 },
        { EGL_ALPHA_SIZE,       0 },
        { EGL_BLUE_SIZE,        5 },
        { EGL_GREEN_SIZE,       6 },
        { EGL_RED_SIZE,         5 },
        { EGL_DEPTH_SIZE,       0 },
        { EGL_CONFIG_ID,        0 },
        { EGL_SURFACE_TYPE,     EGL_WINDOW_BIT|EGL_PBUFFER_BIT|EGL_PIXMAP_BIT },
};

static config_pair_t const config_1_attribute_list[] = {
        { EGL_BUFFER_SIZE,     16 },
        { EGL_ALPHA_SIZE,       0 },
        { EGL_BLUE_SIZE,        5 },
        { EGL_GREEN_SIZE,       6 },
        { EGL_RED_SIZE,         5 },
        { EGL_DEPTH_SIZE,      16 },
        { EGL_CONFIG_ID,        1 },
        { EGL_SURFACE_TYPE,     EGL_WINDOW_BIT|EGL_PBUFFER_BIT|EGL_PIXMAP_BIT },
};

static config_pair_t const config_2_attribute_list[] = {
        { EGL_BUFFER_SIZE,     32 },
        { EGL_ALPHA_SIZE,       8 },
        { EGL_BLUE_SIZE,        8 },
        { EGL_GREEN_SIZE,       8 },
        { EGL_RED_SIZE,         8 },
        { EGL_DEPTH_SIZE,       0 },
        { EGL_CONFIG_ID,        2 },
        { EGL_SURFACE_TYPE,     EGL_WINDOW_BIT|EGL_PBUFFER_BIT|EGL_PIXMAP_BIT },
};

static config_pair_t const config_3_attribute_list[] = {
        { EGL_BUFFER_SIZE,     32 },
        { EGL_ALPHA_SIZE,       8 },
        { EGL_BLUE_SIZE,        8 },
        { EGL_GREEN_SIZE,       8 },
        { EGL_RED_SIZE,         8 },
        { EGL_DEPTH_SIZE,      16 },
        { EGL_CONFIG_ID,        3 },
        { EGL_SURFACE_TYPE,     EGL_WINDOW_BIT|EGL_PBUFFER_BIT|EGL_PIXMAP_BIT },
};

static config_pair_t const config_4_attribute_list[] = {
        { EGL_BUFFER_SIZE,      8 },
        { EGL_ALPHA_SIZE,       8 },
        { EGL_BLUE_SIZE,        0 },
        { EGL_GREEN_SIZE,       0 },
        { EGL_RED_SIZE,         0 },
        { EGL_DEPTH_SIZE,       0 },
        { EGL_CONFIG_ID,        4 },
        { EGL_SURFACE_TYPE,     EGL_WINDOW_BIT|EGL_PBUFFER_BIT|EGL_PIXMAP_BIT },
};

static config_pair_t const config_5_attribute_list[] = {
        { EGL_BUFFER_SIZE,      8 },
        { EGL_ALPHA_SIZE,       8 },
        { EGL_BLUE_SIZE,        0 },
        { EGL_GREEN_SIZE,       0 },
        { EGL_RED_SIZE,         0 },
        { EGL_DEPTH_SIZE,      16 },
        { EGL_CONFIG_ID,        5 },
        { EGL_SURFACE_TYPE,     EGL_WINDOW_BIT|EGL_PBUFFER_BIT|EGL_PIXMAP_BIT },
};

static configs_t const gConfigs[] = {
        { config_0_attribute_list, NELEM(config_0_attribute_list) },
        { config_1_attribute_list, NELEM(config_1_attribute_list) },
        { config_2_attribute_list, NELEM(config_2_attribute_list) },
        { config_3_attribute_list, NELEM(config_3_attribute_list) },
        { config_4_attribute_list, NELEM(config_4_attribute_list) },
        { config_5_attribute_list, NELEM(config_5_attribute_list) },
};

static config_management_t const gConfigManagement[] = {
        { EGL_BUFFER_SIZE,                config_management_t::atLeast },
        { EGL_ALPHA_SIZE,                 config_management_t::atLeast },
        { EGL_BLUE_SIZE,                  config_management_t::atLeast },
        { EGL_GREEN_SIZE,                 config_management_t::atLeast },
        { EGL_RED_SIZE,                   config_management_t::atLeast },
        { EGL_DEPTH_SIZE,                 config_management_t::atLeast },
        { EGL_STENCIL_SIZE,               config_management_t::atLeast },
        { EGL_CONFIG_CAVEAT,              config_management_t::exact   },
        { EGL_CONFIG_ID,                  config_management_t::exact   },
        { EGL_LEVEL,                      config_management_t::exact   },
        { EGL_MAX_PBUFFER_HEIGHT,         config_management_t::exact   },
        { EGL_MAX_PBUFFER_PIXELS,         config_management_t::exact   },
        { EGL_MAX_PBUFFER_WIDTH,          config_management_t::exact   },
        { EGL_NATIVE_RENDERABLE,          config_management_t::exact   },
        { EGL_NATIVE_VISUAL_ID,           config_management_t::exact   },
        { EGL_NATIVE_VISUAL_TYPE,         config_management_t::exact   },
        { EGL_SAMPLES,                    config_management_t::exact   },
        { EGL_SAMPLE_BUFFERS,             config_management_t::exact   },
        { EGL_SURFACE_TYPE,               config_management_t::mask    },
        { EGL_TRANSPARENT_TYPE,           config_management_t::exact   },
        { EGL_TRANSPARENT_BLUE_VALUE,     config_management_t::exact   },
        { EGL_TRANSPARENT_GREEN_VALUE,    config_management_t::exact   },
        { EGL_TRANSPARENT_RED_VALUE,      config_management_t::exact   },
        { EGL_BIND_TO_TEXTURE_RGBA,       config_management_t::exact   },
        { EGL_BIND_TO_TEXTURE_RGB,        config_management_t::exact   },
        { EGL_MIN_SWAP_INTERVAL,          config_management_t::exact   },
        { EGL_MAX_SWAP_INTERVAL,          config_management_t::exact   },
};

static config_pair_t const config_defaults[] = {
        { EGL_SURFACE_TYPE,        EGL_WINDOW_BIT },
};

// ----------------------------------------------------------------------------

template<typename T>
static int binarySearch(T const sortedArray[], int first, int last, EGLint key)
{
   while (first <= last) {
       int mid = (first + last) / 2;
       if (key > sortedArray[mid].key) { 
           first = mid + 1;
       } else if (key < sortedArray[mid].key) { 
           last = mid - 1;
       } else {
           return mid;
       }
   }
   return -1;
}

static int isAttributeMatching(int i, EGLint attr, EGLint val)
{
    // look for the attribute in all of our configs
    config_pair_t const* configFound = gConfigs[i].array; 
    int index = binarySearch<config_pair_t>(
            gConfigs[i].array,
            0, gConfigs[i].size-1,
            attr);
    if (index < 0) {
        configFound = config_base_attribute_list; 
        index = binarySearch<config_pair_t>(
                config_base_attribute_list,
                0, NELEM(config_base_attribute_list)-1,
                attr);
    }
    if (index >= 0) {
        // attribute found, check if this config could match
        int cfgMgtIndex = binarySearch<config_management_t>(
                gConfigManagement,
                0, NELEM(gConfigManagement)-1,
                attr);
        if (index >= 0) {
            bool match = gConfigManagement[cfgMgtIndex].match(
                    val, configFound[index].value);
            if (match) {
                // this config matches
                return 1;
            }
        } else {
            // attribute not found. this should NEVER happen.
        }
    } else {
        // error, this attribute doesn't exist
    }
    return 0;
}

static int makeCurrent(ogles_context_t* gl)
{
    ogles_context_t* current = (ogles_context_t*)getGlThreadSpecific();
    if (gl) {
        egl_context_t* c = egl_context_t::context(gl);
        if (c->flags & egl_context_t::IS_CURRENT) {
            if (current != gl) {
                // it is an error to set a context current, if it's already
                // current to another thread
                return -1;
            }
        } else {
            if (current) {
                // mark the current context as not current, and flush
                glFlush();
                egl_context_t::context(current)->flags &= ~egl_context_t::IS_CURRENT;
            }
        }
        if (!(c->flags & egl_context_t::IS_CURRENT)) {
            // The context is not current, make it current!
            setGlThreadSpecific(gl);
            c->flags |= egl_context_t::IS_CURRENT;
        }
    } else {
        if (current) {
            // mark the current context as not current, and flush
            glFlush();
            egl_context_t::context(current)->flags &= ~egl_context_t::IS_CURRENT;
        }
        // this thread has no context attached to it
        setGlThreadSpecific(0);
    }
    return 0;
}

static EGLBoolean getConfigAttrib(EGLDisplay dpy, EGLConfig config,
        EGLint attribute, EGLint *value)
{
    size_t numConfigs =  NELEM(gConfigs);
    int index = (int)config;
    if (uint32_t(index) >= numConfigs)
        return setError(EGL_BAD_CONFIG, EGL_FALSE);

    int attrIndex;
    attrIndex = binarySearch<config_pair_t>(
            gConfigs[index].array,
            0, gConfigs[index].size-1,
            attribute);
    if (attrIndex>=0) {
        *value = gConfigs[index].array[attrIndex].value;
        return EGL_TRUE;
    }

    attrIndex = binarySearch<config_pair_t>(
            config_base_attribute_list,
            0, NELEM(config_base_attribute_list)-1,
            attribute);
    if (attrIndex>=0) {
        *value = config_base_attribute_list[attrIndex].value;
        return EGL_TRUE;
    }
    return setError(EGL_BAD_ATTRIBUTE, EGL_FALSE);
}

static EGLSurface createWindowSurface(EGLDisplay dpy, EGLConfig config,
        NativeWindowType window, const EGLint *attrib_list)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_NO_SURFACE);
    if (window == 0)
        return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);

    EGLint surfaceType;
    if (getConfigAttrib(dpy, config, EGL_SURFACE_TYPE, &surfaceType) == EGL_FALSE)
        return EGL_FALSE;

    if (!(surfaceType & EGL_WINDOW_BIT))
        return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);

    EGLint configID;
    if (getConfigAttrib(dpy, config, EGL_CONFIG_ID, &configID) == EGL_FALSE)
        return EGL_FALSE;

    int32_t depthFormat;
    int32_t pixelFormat;
    switch(configID) {
    case 0: 
        pixelFormat = GGL_PIXEL_FORMAT_RGB_565; 
        depthFormat = 0;
        break;
    case 1:
        pixelFormat = GGL_PIXEL_FORMAT_RGB_565; 
        depthFormat = GGL_PIXEL_FORMAT_Z_16;
        break;
    case 2:
        pixelFormat = GGL_PIXEL_FORMAT_RGBA_8888; 
        depthFormat = 0;
        break;
    case 3:
        pixelFormat = GGL_PIXEL_FORMAT_RGBA_8888; 
        depthFormat = GGL_PIXEL_FORMAT_Z_16;
        break;
    case 4:
        pixelFormat = GGL_PIXEL_FORMAT_A_8; 
        depthFormat = 0;
        break;
    case 5:
        pixelFormat = GGL_PIXEL_FORMAT_A_8; 
        depthFormat = GGL_PIXEL_FORMAT_Z_16;
        break;
    default:
        return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);
    }

    // FIXME: we don't have access to the pixelFormat here just yet.
    // (it's possible that the surface is not fully initialized)
    // maybe this should be done after the page-flip
    //if (EGLint(info.format) != pixelFormat)
    //    return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);

    egl_surface_t* surface =
        new egl_window_surface_t(dpy, config, depthFormat,
                static_cast<egl_native_window_t*>(window));

    if (!surface->isValid()) {
        // there was a problem in the ctor, the error
        // flag has been set.
        delete surface;
        surface = 0;
    }
    return surface;
}

static EGLSurface createPixmapSurface(EGLDisplay dpy, EGLConfig config,
        NativePixmapType pixmap, const EGLint *attrib_list)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_NO_SURFACE);
    if (pixmap == 0)
        return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);

    EGLint surfaceType;
    if (getConfigAttrib(dpy, config, EGL_SURFACE_TYPE, &surfaceType) == EGL_FALSE)
        return EGL_FALSE;

    if (!(surfaceType & EGL_PIXMAP_BIT))
        return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);

    EGLint configID;
    if (getConfigAttrib(dpy, config, EGL_CONFIG_ID, &configID) == EGL_FALSE)
        return EGL_FALSE;

    int32_t depthFormat;
    int32_t pixelFormat;
    switch(configID) {
    case 0: 
        pixelFormat = GGL_PIXEL_FORMAT_RGB_565; 
        depthFormat = 0;
        break;
    case 1:
        pixelFormat = GGL_PIXEL_FORMAT_RGB_565; 
        depthFormat = GGL_PIXEL_FORMAT_Z_16;
        break;
    case 2:
        pixelFormat = GGL_PIXEL_FORMAT_RGBA_8888; 
        depthFormat = 0;
        break;
    case 3:
        pixelFormat = GGL_PIXEL_FORMAT_RGBA_8888; 
        depthFormat = GGL_PIXEL_FORMAT_Z_16;
        break;
    case 4:
        pixelFormat = GGL_PIXEL_FORMAT_A_8; 
        depthFormat = 0;
        break;
    case 5:
        pixelFormat = GGL_PIXEL_FORMAT_A_8; 
        depthFormat = GGL_PIXEL_FORMAT_Z_16;
        break;
    default:
        return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);
    }

    if (pixmap->format != pixelFormat)
        return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);

    egl_surface_t* surface =
        new egl_pixmap_surface_t(dpy, config, depthFormat,
                static_cast<egl_native_pixmap_t*>(pixmap));

    if (!surface->isValid()) {
        // there was a problem in the ctor, the error
        // flag has been set.
        delete surface;
        surface = 0;
    }
    return surface;
}

static EGLSurface createPbufferSurface(EGLDisplay dpy, EGLConfig config,
        const EGLint *attrib_list)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_NO_SURFACE);

    EGLint surfaceType;
    if (getConfigAttrib(dpy, config, EGL_SURFACE_TYPE, &surfaceType) == EGL_FALSE)
        return EGL_FALSE;
    
    if (!(surfaceType & EGL_PBUFFER_BIT))
        return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);
        
    EGLint configID;
    if (getConfigAttrib(dpy, config, EGL_CONFIG_ID, &configID) == EGL_FALSE)
        return EGL_FALSE;

    int32_t depthFormat;
    int32_t pixelFormat;
    switch(configID) {
    case 0: 
        pixelFormat = GGL_PIXEL_FORMAT_RGB_565; 
        depthFormat = 0;
        break;
    case 1:
        pixelFormat = GGL_PIXEL_FORMAT_RGB_565; 
        depthFormat = GGL_PIXEL_FORMAT_Z_16;
        break;
    case 2:
        pixelFormat = GGL_PIXEL_FORMAT_RGBA_8888; 
        depthFormat = 0;
        break;
    case 3:
        pixelFormat = GGL_PIXEL_FORMAT_RGBA_8888; 
        depthFormat = GGL_PIXEL_FORMAT_Z_16;
        break;
    case 4:
        pixelFormat = GGL_PIXEL_FORMAT_A_8; 
        depthFormat = 0;
        break;
    case 5:
        pixelFormat = GGL_PIXEL_FORMAT_A_8; 
        depthFormat = GGL_PIXEL_FORMAT_Z_16;
        break;
    default:
        return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);
    }

    int32_t w = 0;
    int32_t h = 0;
    while (attrib_list[0]) {
        if (attrib_list[0] == EGL_WIDTH)  w = attrib_list[1];
        if (attrib_list[0] == EGL_HEIGHT) h = attrib_list[1];
        attrib_list+=2;
    }

    egl_surface_t* surface =
        new egl_pbuffer_surface_t(dpy, config, depthFormat, w, h, pixelFormat);

    if (!surface->isValid()) {
        // there was a problem in the ctor, the error
        // flag has been set.
        delete surface;
        surface = 0;
    }
    return surface;
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

using namespace android;

// ----------------------------------------------------------------------------
// Initialization
// ----------------------------------------------------------------------------

EGLDisplay eglGetDisplay(NativeDisplayType display)
{
#ifndef HAVE_ANDROID_OS
    // this just needs to be done once
    if (gGLKey == -1) {
        pthread_mutex_lock(&gInitMutex);
        if (gGLKey == -1)
            pthread_key_create(&gGLKey, NULL);
        pthread_mutex_unlock(&gInitMutex);
    }
#endif
    if (display == EGL_DEFAULT_DISPLAY) {
        EGLDisplay dpy = (EGLDisplay)1;
        egl_display_t& d = egl_display_t::get_display(dpy);
        d.type = display;
        return dpy;
    }    
    return EGL_NO_DISPLAY;
}

EGLBoolean eglInitialize(EGLDisplay dpy, EGLint *major, EGLint *minor)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    
    EGLBoolean res = EGL_TRUE;
    egl_display_t& d = egl_display_t::get_display(dpy);
    
    if (android_atomic_inc(&d.initialized) == 0) {
        // initialize stuff here if needed
        //pthread_mutex_lock(&gInitMutex);
        //pthread_mutex_unlock(&gInitMutex);
    }

    if (res == EGL_TRUE) {
        if (major != NULL) *major = 1;
        if (minor != NULL) *minor = 2;
    }
    return res;
}

EGLBoolean eglTerminate(EGLDisplay dpy)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    EGLBoolean res = EGL_TRUE;
    egl_display_t& d = egl_display_t::get_display(dpy);
    if (android_atomic_dec(&d.initialized) == 1) {
        // TODO: destroy all resources (surfaces, contexts, etc...)
        //pthread_mutex_lock(&gInitMutex);
        //pthread_mutex_unlock(&gInitMutex);
    }
    return res;
}

// ----------------------------------------------------------------------------
// configuration
// ----------------------------------------------------------------------------

EGLBoolean eglGetConfigs(   EGLDisplay dpy,
                            EGLConfig *configs,
                            EGLint config_size, EGLint *num_config)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    GLint numConfigs = NELEM(gConfigs);
    if (!configs) {
        *num_config = numConfigs;
        return EGL_TRUE;
    }
    GLint i;
    for (i=0 ; i<numConfigs && i<config_size ; i++) {
        *configs++ = (EGLConfig)i;
    }
    *num_config = i;
    return EGL_TRUE;
}

EGLBoolean eglChooseConfig( EGLDisplay dpy, const EGLint *attrib_list,
                            EGLConfig *configs, EGLint config_size,
                            EGLint *num_config)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    if (ggl_unlikely(configs==0 || attrib_list==0)) {
        *num_config = 0;
        return EGL_TRUE;
    }
    
    int numAttributes = 0;
    int numConfigs =  NELEM(gConfigs);
    uint32_t possibleMatch = (1<<numConfigs)-1;
    while(possibleMatch && *attrib_list != EGL_NONE) {
        numAttributes++;
        EGLint attr = *attrib_list++;
        EGLint val  = *attrib_list++;
        for (int i=0 ; i<numConfigs ; i++) {
            if (!(possibleMatch & (1<<i)))
                continue;
            if (isAttributeMatching(i, attr, val) == 0) {
                possibleMatch &= ~(1<<i);
            }
        }
    }

    // now, handle the attributes which have a useful default value
    for (size_t j=0 ; j<NELEM(config_defaults) ; j++) {
        // see if this attribute was specified, if not apply its
        // default value
        if (binarySearch<config_pair_t>(
                (config_pair_t const*)attrib_list,
                0, numAttributes,
                config_defaults[j].key) < 0)
        {
            for (int i=0 ; i<numConfigs ; i++) {
                if (!(possibleMatch & (1<<i)))
                    continue;
                if (isAttributeMatching(i,
                        config_defaults[j].key,
                        config_defaults[j].value) == 0)
                {
                    possibleMatch &= ~(1<<i);
                }
            }
        }
    }

    // return the configurations found
    int n=0;
    if (possibleMatch) {
        for (int i=0 ; config_size && i<numConfigs ; i++) {
            if (possibleMatch & (1<<i)) {
               *configs++ = (EGLConfig)i;
                config_size--;
                n++;
            }
        }
    }
    *num_config = n;
     return EGL_TRUE;
}

EGLBoolean eglGetConfigAttrib(EGLDisplay dpy, EGLConfig config,
        EGLint attribute, EGLint *value)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    return getConfigAttrib(dpy, config, attribute, value);
}

// ----------------------------------------------------------------------------
// surfaces
// ----------------------------------------------------------------------------

EGLSurface eglCreateWindowSurface(  EGLDisplay dpy, EGLConfig config,
                                    NativeWindowType window,
                                    const EGLint *attrib_list)
{
    return createWindowSurface(dpy, config, window, attrib_list);
}
    
EGLSurface eglCreatePixmapSurface(  EGLDisplay dpy, EGLConfig config,
                                    NativePixmapType pixmap,
                                    const EGLint *attrib_list)
{
    return createPixmapSurface(dpy, config, pixmap, attrib_list);
}

EGLSurface eglCreatePbufferSurface( EGLDisplay dpy, EGLConfig config,
                                    const EGLint *attrib_list)
{
    return createPbufferSurface(dpy, config, attrib_list);
}
                                    
EGLBoolean eglDestroySurface(EGLDisplay dpy, EGLSurface eglSurface)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    if (eglSurface != EGL_NO_SURFACE) {
        egl_surface_t* surface( static_cast<egl_surface_t*>(eglSurface) );
        if (surface->magic != egl_surface_t::MAGIC)
            return setError(EGL_BAD_SURFACE, EGL_FALSE);
        if (surface->dpy != dpy)
            return setError(EGL_BAD_DISPLAY, EGL_FALSE);
        delete surface;
    }
    return EGL_TRUE;
}

EGLBoolean eglQuerySurface( EGLDisplay dpy, EGLSurface eglSurface,
                            EGLint attribute, EGLint *value)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    egl_surface_t* surface = static_cast<egl_surface_t*>(eglSurface);
    if (surface->dpy != dpy)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    EGLBoolean ret = EGL_TRUE;
    switch (attribute) {
        case EGL_CONFIG_ID:
            ret = getConfigAttrib(dpy, surface->config, EGL_CONFIG_ID, value);
            break;
        case EGL_WIDTH:
            *value = surface->getWidth();
            break;
        case EGL_HEIGHT:
            *value = surface->getHeight();
            break;
        case EGL_LARGEST_PBUFFER:
            // not modified for a window or pixmap surface
            break;
        case EGL_TEXTURE_FORMAT:
            *value = EGL_NO_TEXTURE;
            break;
        case EGL_TEXTURE_TARGET:
            *value = EGL_NO_TEXTURE;
            break;
        case EGL_MIPMAP_TEXTURE:
            *value = EGL_FALSE;
            break;
        case EGL_MIPMAP_LEVEL:
            *value = 0;
            break;
        case EGL_RENDER_BUFFER:
            // TODO: return the real RENDER_BUFFER here
            *value = EGL_BACK_BUFFER;
            break;
        case EGL_HORIZONTAL_RESOLUTION:
            // pixel/mm * EGL_DISPLAY_SCALING
            *value = surface->getHorizontalResolution();
            break;
        case EGL_VERTICAL_RESOLUTION:
            // pixel/mm * EGL_DISPLAY_SCALING
            *value = surface->getVerticalResolution();
            break;
        case EGL_PIXEL_ASPECT_RATIO: {
            // w/h * EGL_DISPLAY_SCALING
            int wr = surface->getHorizontalResolution();
            int hr = surface->getVerticalResolution();
            *value = (wr * EGL_DISPLAY_SCALING) / hr;
        } break;
        case EGL_SWAP_BEHAVIOR:
            *value = surface->getSwapBehavior(); 
            break;
        default:
            ret = setError(EGL_BAD_ATTRIBUTE, EGL_FALSE);
    }
    return ret;
}

EGLContext eglCreateContext(EGLDisplay dpy, EGLConfig config,
                            EGLContext share_list, const EGLint *attrib_list)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_NO_SURFACE);

    ogles_context_t* gl = ogles_init(sizeof(egl_context_t));
    if (!gl) return setError(EGL_BAD_ALLOC, EGL_NO_CONTEXT);

    egl_context_t* c = static_cast<egl_context_t*>(gl->rasterizer.base);
    c->flags = egl_context_t::NEVER_CURRENT;
    c->dpy = dpy;
    c->config = config;
    c->read = 0;
    c->draw = 0;
    return (EGLContext)gl;
}

EGLBoolean eglDestroyContext(EGLDisplay dpy, EGLContext ctx)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    egl_context_t* c = egl_context_t::context(ctx);
    if (c->flags & egl_context_t::IS_CURRENT)
        setGlThreadSpecific(0);
    ogles_uninit((ogles_context_t*)ctx);
    return EGL_TRUE;
}

EGLBoolean eglMakeCurrent(  EGLDisplay dpy, EGLSurface draw,
                            EGLSurface read, EGLContext ctx)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    if (draw) {
        egl_surface_t* s = (egl_surface_t*)draw;
        if (s->dpy != dpy)
            return setError(EGL_BAD_DISPLAY, EGL_FALSE);
        // TODO: check that draw and read are compatible with the context
    }

    EGLContext current_ctx = EGL_NO_CONTEXT;
    
    if ((read == EGL_NO_SURFACE && draw == EGL_NO_SURFACE) && (ctx != EGL_NO_CONTEXT))
        return setError(EGL_BAD_MATCH, EGL_FALSE);

    if ((read != EGL_NO_SURFACE || draw != EGL_NO_SURFACE) && (ctx == EGL_NO_CONTEXT))
        return setError(EGL_BAD_MATCH, EGL_FALSE);

    if (ctx == EGL_NO_CONTEXT) {
        // if we're detaching, we need the current context
        current_ctx = (EGLContext)getGlThreadSpecific();
    } else {
        egl_context_t* c = egl_context_t::context(ctx);
        egl_surface_t* d = (egl_surface_t*)draw;
        egl_surface_t* r = (egl_surface_t*)read;
        if ((d && d->ctx && d->ctx != ctx) ||
            (r && r->ctx && r->ctx != ctx)) {
            // once of the surface is bound to a context in another thread
            return setError(EGL_BAD_ACCESS, EGL_FALSE);
        }
    }

    ogles_context_t* gl = (ogles_context_t*)ctx;
    if (makeCurrent(gl) == 0) {
        if (ctx) {
            egl_context_t* c = egl_context_t::context(ctx);
            egl_surface_t* d = (egl_surface_t*)draw;
            egl_surface_t* r = (egl_surface_t*)read;
            c->read = read;
            c->draw = draw;
            if (c->flags & egl_context_t::NEVER_CURRENT) {
                c->flags &= ~egl_context_t::NEVER_CURRENT;
                GLint w = 0;
                GLint h = 0;
                if (draw) {
                    w = d->getWidth();
                    h = d->getHeight();
                }
                ogles_surfaceport(gl, 0, 0);
                ogles_viewport(gl, 0, 0, w, h);
                ogles_scissor(gl, 0, 0, w, h);
            }
            if (d) {
                d->ctx = ctx;
                d->bindDrawSurface(gl);
            }
            if (r) {
                r->ctx = ctx;
                r->bindReadSurface(gl);
            }
        } else {
            // if surfaces were bound to the context bound to this thread
            // mark then as unbound.
            if (current_ctx) {
                egl_context_t* c = egl_context_t::context(current_ctx);
                egl_surface_t* d = (egl_surface_t*)c->draw;
                egl_surface_t* r = (egl_surface_t*)c->read;
                if (d) d->ctx = EGL_NO_CONTEXT;
                if (r) r->ctx = EGL_NO_CONTEXT;
            }
        }
        return EGL_TRUE;
    }
    return setError(EGL_BAD_ACCESS, EGL_FALSE);
}

EGLContext eglGetCurrentContext(void)
{
    // eglGetCurrentContext returns the current EGL rendering context,
    // as specified by eglMakeCurrent. If no context is current,
    // EGL_NO_CONTEXT is returned.
    return (EGLContext)getGlThreadSpecific();
}

EGLSurface eglGetCurrentSurface(EGLint readdraw)
{
    // eglGetCurrentSurface returns the read or draw surface attached
    // to the current EGL rendering context, as specified by eglMakeCurrent.
    // If no context is current, EGL_NO_SURFACE is returned.
    EGLContext ctx = (EGLContext)getGlThreadSpecific();
    if (ctx == EGL_NO_CONTEXT) return EGL_NO_SURFACE;
    egl_context_t* c = egl_context_t::context(ctx);
    if (readdraw == EGL_READ) {
        return c->read;
    } else if (readdraw == EGL_DRAW) {
        return c->draw;
    }
    return setError(EGL_BAD_ATTRIBUTE, EGL_NO_SURFACE);
}

EGLDisplay eglGetCurrentDisplay(void)
{
    // eglGetCurrentDisplay returns the current EGL display connection
    // for the current EGL rendering context, as specified by eglMakeCurrent.
    // If no context is current, EGL_NO_DISPLAY is returned.
    EGLContext ctx = (EGLContext)getGlThreadSpecific();
    if (ctx == EGL_NO_CONTEXT) return EGL_NO_DISPLAY;
    egl_context_t* c = egl_context_t::context(ctx);
    return c->dpy;
}

EGLBoolean eglQueryContext( EGLDisplay dpy, EGLContext ctx,
                            EGLint attribute, EGLint *value)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    egl_context_t* c = egl_context_t::context(ctx);
    switch (attribute) {
        case EGL_CONFIG_ID:
            // Returns the ID of the EGL frame buffer configuration with
            // respect to which the context was created
            return getConfigAttrib(dpy, c->config, EGL_CONFIG_ID, value);
    }
    return setError(EGL_BAD_ATTRIBUTE, EGL_FALSE);
}

EGLBoolean eglWaitGL(void)
{
    return EGL_TRUE;
}

EGLBoolean eglWaitNative(EGLint engine)
{
    return EGL_TRUE;
}

EGLBoolean eglSwapBuffers(EGLDisplay dpy, EGLSurface draw)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    
    egl_surface_t* d = static_cast<egl_surface_t*>(draw);
    if (d->dpy != dpy)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    // post the surface
    d->swapBuffers();

    // if it's bound to a context, update the buffer
    if (d->ctx != EGL_NO_CONTEXT) {
        d->bindDrawSurface((ogles_context_t*)d->ctx);
        // if this surface is also the read surface of the context
        // it is bound to, make sure to update the read buffer as well.
        // The EGL spec is a little unclear about this.
        egl_context_t* c = egl_context_t::context(d->ctx);
        if (c->read == draw) {
            d->bindReadSurface((ogles_context_t*)d->ctx);
        }
    }

    return EGL_TRUE;
}

EGLBoolean eglCopyBuffers(  EGLDisplay dpy, EGLSurface surface,
                            NativePixmapType target)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    // TODO: eglCopyBuffers()
    return EGL_FALSE;
}

EGLint eglGetError(void)
{
    return getError();
}

const char* eglQueryString(EGLDisplay dpy, EGLint name)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, (const char*)0);

    switch (name) {
        case EGL_VENDOR:
            return gVendorString;
        case EGL_VERSION:
            return gVersionString;
        case EGL_EXTENSIONS:
            return gExtensionsString;
        case EGL_CLIENT_APIS:
            return gClientApiString;
    }
    return setError(EGL_BAD_PARAMETER, (const char *)0);
}

// ----------------------------------------------------------------------------
// EGL 1.1
// ----------------------------------------------------------------------------

EGLBoolean eglSurfaceAttrib(
        EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint value)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    // TODO: eglSurfaceAttrib()
    return setError(EGL_BAD_PARAMETER, EGL_FALSE);
}

EGLBoolean eglBindTexImage(
        EGLDisplay dpy, EGLSurface surface, EGLint buffer)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    // TODO: eglBindTexImage()
    return setError(EGL_BAD_PARAMETER, EGL_FALSE);
}

EGLBoolean eglReleaseTexImage(
        EGLDisplay dpy, EGLSurface surface, EGLint buffer)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    // TODO: eglReleaseTexImage()
    return setError(EGL_BAD_PARAMETER, EGL_FALSE);
}

EGLBoolean eglSwapInterval(EGLDisplay dpy, EGLint interval)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    // TODO: eglSwapInterval()
    return setError(EGL_BAD_PARAMETER, EGL_FALSE);
}

// ----------------------------------------------------------------------------
// EGL 1.2
// ----------------------------------------------------------------------------

EGLBoolean eglBindAPI(EGLenum api)
{
    if (api != EGL_OPENGL_ES_API)
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);
    return EGL_TRUE;
}

EGLenum eglQueryAPI(void)
{
    return EGL_OPENGL_ES_API;
}

EGLBoolean eglWaitClient(void)
{
    glFinish();
    return EGL_TRUE;
}

EGLBoolean eglReleaseThread(void)
{
    // TODO: eglReleaseThread()
    return EGL_TRUE;
}

EGLSurface eglCreatePbufferFromClientBuffer(
          EGLDisplay dpy, EGLenum buftype, EGLClientBuffer buffer,
          EGLConfig config, const EGLint *attrib_list)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_NO_SURFACE);
    // TODO: eglCreatePbufferFromClientBuffer()
    return setError(EGL_BAD_PARAMETER, EGL_NO_SURFACE);
}

// ----------------------------------------------------------------------------
// Android extensions
// ----------------------------------------------------------------------------

void (*eglGetProcAddress (const char *procname))()
{
    extention_map_t const * const map = gExtentionMap;
    for (uint32_t i=0 ; i<NELEM(gExtentionMap) ; i++) {
        if (!strcmp(procname, map[i].name)) {
            return map[i].address;
        }
    }
    return NULL;
}

EGLBoolean eglSwapRectangleANDROID(
        EGLDisplay dpy, EGLSurface draw,
        EGLint l, EGLint t, EGLint w, EGLint h)
{    
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    egl_surface_t* surface = (egl_surface_t*)draw;
    if (surface->dpy != dpy)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    return surface->swapRectangle(l, t, w, h);
}
