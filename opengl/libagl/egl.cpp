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

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include <pixelflinger/format.h>
#include <pixelflinger/pixelflinger.h>

#include <private/ui/android_natives_priv.h>
#include <private/ui/sw_gralloc_handle.h>

#include <hardware/copybit.h>

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
                bool    isValid() const;
    virtual     bool    initCheck() const = 0;

    virtual     EGLBoolean  bindDrawSurface(ogles_context_t* gl) = 0;
    virtual     EGLBoolean  bindReadSurface(ogles_context_t* gl) = 0;
    virtual     EGLBoolean  connect() { return EGL_TRUE; }
    virtual     void        disconnect() {}
    virtual     EGLint      getWidth() const = 0;
    virtual     EGLint      getHeight() const = 0;

    virtual     EGLint      getHorizontalResolution() const;
    virtual     EGLint      getVerticalResolution() const;
    virtual     EGLint      getRefreshRate() const;
    virtual     EGLint      getSwapBehavior() const;
    virtual     EGLBoolean  swapBuffers();
    virtual     EGLBoolean  setSwapRectangle(EGLint l, EGLint t, EGLint w, EGLint h);
    virtual     EGLClientBuffer getRenderBuffer() const;
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
bool egl_surface_t::isValid() const {
    LOGE_IF(magic != MAGIC, "invalid EGLSurface (%p)", this);
    return magic == MAGIC; 
}

EGLBoolean egl_surface_t::swapBuffers() {
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
EGLBoolean egl_surface_t::setSwapRectangle(
        EGLint l, EGLint t, EGLint w, EGLint h)
{
    return EGL_FALSE;
}
EGLClientBuffer egl_surface_t::getRenderBuffer() const {
    return 0;
}

// ----------------------------------------------------------------------------

struct egl_window_surface_v2_t : public egl_surface_t
{
    egl_window_surface_v2_t(
            EGLDisplay dpy, EGLConfig config,
            int32_t depthFormat,
            android_native_window_t* window);

    ~egl_window_surface_v2_t();

    virtual     bool        initCheck() const { return true; } // TODO: report failure if ctor fails
    virtual     EGLBoolean  swapBuffers();
    virtual     EGLBoolean  bindDrawSurface(ogles_context_t* gl);
    virtual     EGLBoolean  bindReadSurface(ogles_context_t* gl);
    virtual     EGLBoolean  connect();
    virtual     void        disconnect();
    virtual     EGLint      getWidth() const    { return width;  }
    virtual     EGLint      getHeight() const   { return height; }
    virtual     EGLint      getHorizontalResolution() const;
    virtual     EGLint      getVerticalResolution() const;
    virtual     EGLint      getRefreshRate() const;
    virtual     EGLint      getSwapBehavior() const;
    virtual     EGLBoolean  setSwapRectangle(EGLint l, EGLint t, EGLint w, EGLint h);
    virtual     EGLClientBuffer  getRenderBuffer() const;
    
private:
    status_t lock(android_native_buffer_t* buf, int usage, void** vaddr);
    status_t unlock(android_native_buffer_t* buf);
    android_native_window_t*   nativeWindow;
    android_native_buffer_t*   buffer;
    android_native_buffer_t*   previousBuffer;
    gralloc_module_t const*    module;
    copybit_device_t*          blitengine;
    int width;
    int height;
    void* bits;
    GGLFormat const* pixelFormatTable;
    
    struct Rect {
        inline Rect() { };
        inline Rect(int32_t w, int32_t h)
            : left(0), top(0), right(w), bottom(h) { }
        inline Rect(int32_t l, int32_t t, int32_t r, int32_t b)
            : left(l), top(t), right(r), bottom(b) { }
        Rect& andSelf(const Rect& r) {
            left   = max(left, r.left);
            top    = max(top, r.top);
            right  = min(right, r.right);
            bottom = min(bottom, r.bottom);
            return *this;
        }
        bool isEmpty() const {
            return (left>=right || top>=bottom);
        }
        void dump(char const* what) {
            LOGD("%s { %5d, %5d, w=%5d, h=%5d }", 
                    what, left, top, right-left, bottom-top);
        }
        
        int32_t left;
        int32_t top;
        int32_t right;
        int32_t bottom;
    };

    struct Region {
        inline Region() : count(0) { }
        typedef Rect const* const_iterator;
        const_iterator begin() const { return storage; }
        const_iterator end() const { return storage+count; }
        static Region subtract(const Rect& lhs, const Rect& rhs) {
            Region reg;
            Rect* storage = reg.storage;
            if (!lhs.isEmpty()) {
                if (lhs.top < rhs.top) { // top rect
                    storage->left   = lhs.left;
                    storage->top    = lhs.top;
                    storage->right  = lhs.right;
                    storage->bottom = rhs.top;
                    storage++;
                }
                const int32_t top = max(lhs.top, rhs.top);
                const int32_t bot = min(lhs.bottom, rhs.bottom);
                if (top < bot) {
                    if (lhs.left < rhs.left) { // left-side rect
                        storage->left   = lhs.left;
                        storage->top    = top;
                        storage->right  = rhs.left;
                        storage->bottom = bot;
                        storage++;
                    }
                    if (lhs.right > rhs.right) { // right-side rect
                        storage->left   = rhs.right;
                        storage->top    = top;
                        storage->right  = lhs.right;
                        storage->bottom = bot;
                        storage++;
                    }
                }
                if (lhs.bottom > rhs.bottom) { // bottom rect
                    storage->left   = lhs.left;
                    storage->top    = rhs.bottom;
                    storage->right  = lhs.right;
                    storage->bottom = lhs.bottom;
                    storage++;
                }
                reg.count = storage - reg.storage;
            }
            return reg;
        }
        bool isEmpty() const {
            return count<=0;
        }
    private:
        Rect storage[4];
        ssize_t count;
    };
    
    struct region_iterator : public copybit_region_t {
        region_iterator(const Region& region)
            : b(region.begin()), e(region.end()) {
            this->next = iterate;
        }
    private:
        static int iterate(copybit_region_t const * self, copybit_rect_t* rect) {
            region_iterator const* me = static_cast<region_iterator const*>(self);
            if (me->b != me->e) {
                *reinterpret_cast<Rect*>(rect) = *me->b++;
                return 1;
            }
            return 0;
        }
        mutable Region::const_iterator b;
        Region::const_iterator const e;
    };

    void copyBlt(
            android_native_buffer_t* dst, void* dst_vaddr,
            android_native_buffer_t* src, void const* src_vaddr,
            const Region& clip);

    Rect dirtyRegion;
    Rect oldDirtyRegion;
};

egl_window_surface_v2_t::egl_window_surface_v2_t(EGLDisplay dpy,
        EGLConfig config,
        int32_t depthFormat,
        android_native_window_t* window)
    : egl_surface_t(dpy, config, depthFormat), 
    nativeWindow(window), buffer(0), previousBuffer(0), module(0),
    blitengine(0), bits(NULL)
{
    hw_module_t const* pModule;
    hw_get_module(GRALLOC_HARDWARE_MODULE_ID, &pModule);
    module = reinterpret_cast<gralloc_module_t const*>(pModule);

    if (hw_get_module(COPYBIT_HARDWARE_MODULE_ID, &pModule) == 0) {
        copybit_open(pModule, &blitengine);
    }

    pixelFormatTable = gglGetPixelFormatTable();
    
    // keep a reference on the window
    nativeWindow->common.incRef(&nativeWindow->common);
    nativeWindow->query(nativeWindow, NATIVE_WINDOW_WIDTH, &width);
    nativeWindow->query(nativeWindow, NATIVE_WINDOW_HEIGHT, &height);
}

egl_window_surface_v2_t::~egl_window_surface_v2_t() {
    if (buffer) {
        buffer->common.decRef(&buffer->common);
    }
    if (previousBuffer) {
        previousBuffer->common.decRef(&previousBuffer->common); 
    }
    nativeWindow->common.decRef(&nativeWindow->common);
    if (blitengine) {
        copybit_close(blitengine);
    }
}

EGLBoolean egl_window_surface_v2_t::connect() 
{
    // we're intending to do software rendering
    native_window_set_usage(nativeWindow, 
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN);

    // dequeue a buffer
    if (nativeWindow->dequeueBuffer(nativeWindow, &buffer) != NO_ERROR) {
        return setError(EGL_BAD_ALLOC, EGL_FALSE);
    }

    // allocate a corresponding depth-buffer
    width = buffer->width;
    height = buffer->height;
    if (depth.format) {
        depth.width   = width;
        depth.height  = height;
        depth.stride  = depth.width; // use the width here
        depth.data    = (GGLubyte*)malloc(depth.stride*depth.height*2);
        if (depth.data == 0) {
            return setError(EGL_BAD_ALLOC, EGL_FALSE);
        }
    }

    // keep a reference on the buffer
    buffer->common.incRef(&buffer->common);

    // Lock the buffer
    nativeWindow->lockBuffer(nativeWindow, buffer);
    // pin the buffer down
    if (lock(buffer, GRALLOC_USAGE_SW_READ_OFTEN | 
            GRALLOC_USAGE_SW_WRITE_OFTEN, &bits) != NO_ERROR) {
        LOGE("connect() failed to lock buffer %p (%ux%u)",
                buffer, buffer->width, buffer->height);
        return setError(EGL_BAD_ACCESS, EGL_FALSE);
        // FIXME: we should make sure we're not accessing the buffer anymore
    }
    return EGL_TRUE;
}

void egl_window_surface_v2_t::disconnect() 
{
    if (buffer && bits) {
        bits = NULL;
        unlock(buffer);
    }
    // enqueue the last frame
    nativeWindow->queueBuffer(nativeWindow, buffer);
    if (buffer) {
        buffer->common.decRef(&buffer->common);
        buffer = 0;
    }
    if (previousBuffer) {
        previousBuffer->common.decRef(&previousBuffer->common); 
        previousBuffer = 0;
    }
}

status_t egl_window_surface_v2_t::lock(
        android_native_buffer_t* buf, int usage, void** vaddr)
{
    int err;
    if (sw_gralloc_handle_t::validate(buf->handle) < 0) {
        err = module->lock(module, buf->handle,
                usage, 0, 0, buf->width, buf->height, vaddr);
    } else {
        sw_gralloc_handle_t const* hnd =
                reinterpret_cast<sw_gralloc_handle_t const*>(buf->handle);
        *vaddr = (void*)hnd->base;
        err = NO_ERROR;
    }
    return err;
}

status_t egl_window_surface_v2_t::unlock(android_native_buffer_t* buf)
{
    if (!buf) return BAD_VALUE;
    int err = NO_ERROR;
    if (sw_gralloc_handle_t::validate(buf->handle) < 0) {
        err = module->unlock(module, buf->handle);
    }
    return err;
}

void egl_window_surface_v2_t::copyBlt(
        android_native_buffer_t* dst, void* dst_vaddr,
        android_native_buffer_t* src, void const* src_vaddr,
        const Region& clip)
{
    // FIXME: use copybit if possible
    // NOTE: dst and src must be the same format
    
    status_t err = NO_ERROR;
    copybit_device_t* const copybit = blitengine;
    if (copybit)  {
        copybit_image_t simg;
        simg.w = src->stride;
        simg.h = src->height;
        simg.format = src->format;
        simg.handle = const_cast<native_handle_t*>(src->handle);

        copybit_image_t dimg;
        dimg.w = dst->stride;
        dimg.h = dst->height;
        dimg.format = dst->format;
        dimg.handle = const_cast<native_handle_t*>(dst->handle);
        
        copybit->set_parameter(copybit, COPYBIT_TRANSFORM, 0);
        copybit->set_parameter(copybit, COPYBIT_PLANE_ALPHA, 255);
        copybit->set_parameter(copybit, COPYBIT_DITHER, COPYBIT_DISABLE);
        region_iterator it(clip);
        err = copybit->blit(copybit, &dimg, &simg, &it);
        if (err != NO_ERROR) {
            LOGE("copybit failed (%s)", strerror(err));
        }
    }

    if (!copybit || err) {
        Region::const_iterator cur = clip.begin();
        Region::const_iterator end = clip.end();
        
        const size_t bpp = pixelFormatTable[src->format].size;
        const size_t dbpr = dst->stride * bpp;
        const size_t sbpr = src->stride * bpp;

        uint8_t const * const src_bits = (uint8_t const *)src_vaddr;
        uint8_t       * const dst_bits = (uint8_t       *)dst_vaddr;

        while (cur != end) {
            const Rect& r(*cur++);
            ssize_t w = r.right - r.left;
            ssize_t h = r.bottom - r.top;
            if (w <= 0 || h<=0) continue;
            size_t size = w * bpp;
            uint8_t const * s = src_bits + (r.left + src->stride * r.top) * bpp;
            uint8_t       * d = dst_bits + (r.left + dst->stride * r.top) * bpp;
            if (dbpr==sbpr && size==sbpr) {
                size *= h;
                h = 1;
            }
            do {
                memcpy(d, s, size);
                d += dbpr;
                s += sbpr;
            } while (--h > 0);
        }
    }
}

EGLBoolean egl_window_surface_v2_t::swapBuffers()
{
    if (!buffer) {
        return setError(EGL_BAD_ACCESS, EGL_FALSE);
    }
    
    /*
     * Handle eglSetSwapRectangleANDROID()
     * We copyback from the front buffer 
     */
    if (!dirtyRegion.isEmpty()) {
        dirtyRegion.andSelf(Rect(buffer->width, buffer->height));
        if (previousBuffer) {
            const Region copyBack(Region::subtract(oldDirtyRegion, dirtyRegion));
            if (!copyBack.isEmpty()) {
                void* prevBits;
                if (lock(previousBuffer, 
                        GRALLOC_USAGE_SW_READ_OFTEN, &prevBits) == NO_ERROR) {
                    // copy from previousBuffer to buffer
                    copyBlt(buffer, bits, previousBuffer, prevBits, copyBack);
                    unlock(previousBuffer);
                }
            }
        }
        oldDirtyRegion = dirtyRegion;
    }

    if (previousBuffer) {
        previousBuffer->common.decRef(&previousBuffer->common); 
        previousBuffer = 0;
    }
    
    unlock(buffer);
    previousBuffer = buffer;
    nativeWindow->queueBuffer(nativeWindow, buffer);
    buffer = 0;

    // dequeue a new buffer
    nativeWindow->dequeueBuffer(nativeWindow, &buffer);
    
    // TODO: lockBuffer should rather be executed when the very first
    // direct rendering occurs.
    nativeWindow->lockBuffer(nativeWindow, buffer);
    
    // reallocate the depth-buffer if needed
    if ((width != buffer->width) || (height != buffer->height)) {
        // TODO: we probably should reset the swap rect here
        // if the window size has changed
        width = buffer->width;
        height = buffer->height;
        if (depth.data) {
            free(depth.data);
            depth.width   = width;
            depth.height  = height;
            depth.stride  = buffer->stride;
            depth.data    = (GGLubyte*)malloc(depth.stride*depth.height*2);
            if (depth.data == 0) {
                setError(EGL_BAD_ALLOC, EGL_FALSE);
                return EGL_FALSE;
            }
        }
    }
    
    // keep a reference on the buffer
    buffer->common.incRef(&buffer->common);

    // finally pin the buffer down
    if (lock(buffer, GRALLOC_USAGE_SW_READ_OFTEN | 
            GRALLOC_USAGE_SW_WRITE_OFTEN, &bits) != NO_ERROR) {
        LOGE("eglSwapBuffers() failed to lock buffer %p (%ux%u)",
                buffer, buffer->width, buffer->height);
        return setError(EGL_BAD_ACCESS, EGL_FALSE);
        // FIXME: we should make sure we're not accessing the buffer anymore
    }

    return EGL_TRUE;
}

EGLBoolean egl_window_surface_v2_t::setSwapRectangle(
        EGLint l, EGLint t, EGLint w, EGLint h)
{
    dirtyRegion = Rect(l, t, l+w, t+h);
    return EGL_TRUE;
}

EGLClientBuffer egl_window_surface_v2_t::getRenderBuffer() const
{
    return buffer;
}

#ifdef LIBAGL_USE_GRALLOC_COPYBITS

static bool supportedCopybitsDestinationFormat(int format) {
    // Hardware supported
    switch (format) {
    case HAL_PIXEL_FORMAT_RGB_565:
    case HAL_PIXEL_FORMAT_RGBA_8888:
    case HAL_PIXEL_FORMAT_RGBX_8888:
    case HAL_PIXEL_FORMAT_RGBA_4444:
    case HAL_PIXEL_FORMAT_RGBA_5551:
    case HAL_PIXEL_FORMAT_BGRA_8888:
        return true;
    }
    return false;
}
#endif

EGLBoolean egl_window_surface_v2_t::bindDrawSurface(ogles_context_t* gl)
{
    GGLSurface buffer;
    buffer.version = sizeof(GGLSurface);
    buffer.width   = this->buffer->width;
    buffer.height  = this->buffer->height;
    buffer.stride  = this->buffer->stride;
    buffer.data    = (GGLubyte*)bits;
    buffer.format  = this->buffer->format;
    gl->rasterizer.procs.colorBuffer(gl, &buffer);
    if (depth.data != gl->rasterizer.state.buffers.depth.data)
        gl->rasterizer.procs.depthBuffer(gl, &depth);

#ifdef LIBAGL_USE_GRALLOC_COPYBITS
    gl->copybits.drawSurfaceBuffer = 0;
    if (gl->copybits.blitEngine != NULL) {
        if (supportedCopybitsDestinationFormat(buffer.format)) {
            buffer_handle_t handle = this->buffer->handle;
            if (handle != NULL) {
                gl->copybits.drawSurfaceBuffer = this->buffer;
            }
        }
    }
#endif // LIBAGL_USE_GRALLOC_COPYBITS

    return EGL_TRUE;
}
EGLBoolean egl_window_surface_v2_t::bindReadSurface(ogles_context_t* gl)
{
    GGLSurface buffer;
    buffer.version = sizeof(GGLSurface);
    buffer.width   = this->buffer->width;
    buffer.height  = this->buffer->height;
    buffer.stride  = this->buffer->stride;
    buffer.data    = (GGLubyte*)bits; // FIXME: hopefully is is LOCKED!!!
    buffer.format  = this->buffer->format;
    gl->rasterizer.procs.readBuffer(gl, &buffer);
    return EGL_TRUE;
}
EGLint egl_window_surface_v2_t::getHorizontalResolution() const {
    return (nativeWindow->xdpi * EGL_DISPLAY_SCALING) * (1.0f / 25.4f);
}
EGLint egl_window_surface_v2_t::getVerticalResolution() const {
    return (nativeWindow->ydpi * EGL_DISPLAY_SCALING) * (1.0f / 25.4f);
}
EGLint egl_window_surface_v2_t::getRefreshRate() const {
    return (60 * EGL_DISPLAY_SCALING); // FIXME
}
EGLint egl_window_surface_v2_t::getSwapBehavior() const 
{
    /*
     * EGL_BUFFER_PRESERVED means that eglSwapBuffers() completely preserves
     * the content of the swapped buffer.
     * 
     * EGL_BUFFER_DESTROYED means that the content of the buffer is lost.
     * 
     * However when ANDROID_swap_retcangle is supported, EGL_BUFFER_DESTROYED
     * only applies to the area specified by eglSetSwapRectangleANDROID(), that
     * is, everything outside of this area is preserved.
     * 
     * This implementation of EGL assumes the later case.
     * 
     */

    return EGL_BUFFER_DESTROYED;
}

// ----------------------------------------------------------------------------

struct egl_pixmap_surface_t : public egl_surface_t
{
    egl_pixmap_surface_t(
            EGLDisplay dpy, EGLConfig config,
            int32_t depthFormat,
            egl_native_pixmap_t const * pixmap);

    virtual ~egl_pixmap_surface_t() { }

    virtual     bool        initCheck() const { return !depth.format || depth.data!=0; } 
    virtual     EGLBoolean  bindDrawSurface(ogles_context_t* gl);
    virtual     EGLBoolean  bindReadSurface(ogles_context_t* gl);
    virtual     EGLint      getWidth() const    { return nativePixmap.width;  }
    virtual     EGLint      getHeight() const   { return nativePixmap.height; }
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

    virtual     bool        initCheck() const   { return pbuffer.data != 0; }
    virtual     EGLBoolean  bindDrawSurface(ogles_context_t* gl);
    virtual     EGLBoolean  bindReadSurface(ogles_context_t* gl);
    virtual     EGLint      getWidth() const    { return pbuffer.width;  }
    virtual     EGLint      getHeight() const   { return pbuffer.height; }
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
        case GGL_PIXEL_FORMAT_RGBX_8888:    size *= 4; break;
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

#define VERSION_MAJOR 1
#define VERSION_MINOR 2
static char const * const gVendorString     = "Google Inc.";
static char const * const gVersionString    = "1.2 Android Driver 1.1.0";
static char const * const gClientApiString  = "OpenGL ES";
static char const * const gExtensionsString =
        "EGL_KHR_image_base "
        // "KHR_image_pixmap "
        "EGL_ANDROID_image_native_buffer "
        "EGL_ANDROID_swap_rectangle "
        "EGL_ANDROID_get_render_buffer "
        ;

// ----------------------------------------------------------------------------

struct extention_map_t {
    const char * const name;
    __eglMustCastToProperFunctionPointerType address;
};

static const extention_map_t gExtentionMap[] = {
    { "glDrawTexsOES",
            (__eglMustCastToProperFunctionPointerType)&glDrawTexsOES },
    { "glDrawTexiOES",
            (__eglMustCastToProperFunctionPointerType)&glDrawTexiOES },
    { "glDrawTexfOES",
            (__eglMustCastToProperFunctionPointerType)&glDrawTexfOES },
    { "glDrawTexxOES",
            (__eglMustCastToProperFunctionPointerType)&glDrawTexxOES },
    { "glDrawTexsvOES",
            (__eglMustCastToProperFunctionPointerType)&glDrawTexsvOES },
    { "glDrawTexivOES",
            (__eglMustCastToProperFunctionPointerType)&glDrawTexivOES },
    { "glDrawTexfvOES",
            (__eglMustCastToProperFunctionPointerType)&glDrawTexfvOES },
    { "glDrawTexxvOES",
            (__eglMustCastToProperFunctionPointerType)&glDrawTexxvOES },
    { "glQueryMatrixxOES",
            (__eglMustCastToProperFunctionPointerType)&glQueryMatrixxOES },
    { "glEGLImageTargetTexture2DOES",
            (__eglMustCastToProperFunctionPointerType)&glEGLImageTargetTexture2DOES },
    { "glEGLImageTargetRenderbufferStorageOES",
            (__eglMustCastToProperFunctionPointerType)&glEGLImageTargetRenderbufferStorageOES },
    { "glClipPlanef",
            (__eglMustCastToProperFunctionPointerType)&glClipPlanef },
    { "glClipPlanex",
            (__eglMustCastToProperFunctionPointerType)&glClipPlanex },
    { "glBindBuffer",
            (__eglMustCastToProperFunctionPointerType)&glBindBuffer },
    { "glBufferData",
            (__eglMustCastToProperFunctionPointerType)&glBufferData },
    { "glBufferSubData",
            (__eglMustCastToProperFunctionPointerType)&glBufferSubData },
    { "glDeleteBuffers",
            (__eglMustCastToProperFunctionPointerType)&glDeleteBuffers },
    { "glGenBuffers",
            (__eglMustCastToProperFunctionPointerType)&glGenBuffers },
    { "eglCreateImageKHR",  
            (__eglMustCastToProperFunctionPointerType)&eglCreateImageKHR }, 
    { "eglDestroyImageKHR", 
            (__eglMustCastToProperFunctionPointerType)&eglDestroyImageKHR }, 
    { "eglSetSwapRectangleANDROID", 
            (__eglMustCastToProperFunctionPointerType)&eglSetSwapRectangleANDROID }, 
    { "eglGetRenderBufferANDROID", 
            (__eglMustCastToProperFunctionPointerType)&eglGetRenderBufferANDROID }, 
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
        { EGL_MAX_SWAP_INTERVAL,          1                                 },
        { EGL_LUMINANCE_SIZE,             0                                 },
        { EGL_ALPHA_MASK_SIZE,            0                                 },
        { EGL_COLOR_BUFFER_TYPE,          EGL_RGB_BUFFER                    },
        { EGL_RENDERABLE_TYPE,            EGL_OPENGL_ES_BIT                 },
        { EGL_CONFORMANT,                 0                                 }
};

// These configs can override the base attribute list
// NOTE: when adding a config here, don't forget to update eglCreate*Surface()

// 565 configs
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

// RGB 888 configs
static config_pair_t const config_2_attribute_list[] = {
        { EGL_BUFFER_SIZE,     32 },
        { EGL_ALPHA_SIZE,       0 },
        { EGL_BLUE_SIZE,        8 },
        { EGL_GREEN_SIZE,       8 },
        { EGL_RED_SIZE,         8 },
        { EGL_DEPTH_SIZE,       0 },
        { EGL_CONFIG_ID,        6 },
        { EGL_SURFACE_TYPE,     EGL_WINDOW_BIT|EGL_PBUFFER_BIT|EGL_PIXMAP_BIT },
};

static config_pair_t const config_3_attribute_list[] = {
        { EGL_BUFFER_SIZE,     32 },
        { EGL_ALPHA_SIZE,       0 },
        { EGL_BLUE_SIZE,        8 },
        { EGL_GREEN_SIZE,       8 },
        { EGL_RED_SIZE,         8 },
        { EGL_DEPTH_SIZE,      16 },
        { EGL_CONFIG_ID,        7 },
        { EGL_SURFACE_TYPE,     EGL_WINDOW_BIT|EGL_PBUFFER_BIT|EGL_PIXMAP_BIT },
};

// 8888 configs
static config_pair_t const config_4_attribute_list[] = {
        { EGL_BUFFER_SIZE,     32 },
        { EGL_ALPHA_SIZE,       8 },
        { EGL_BLUE_SIZE,        8 },
        { EGL_GREEN_SIZE,       8 },
        { EGL_RED_SIZE,         8 },
        { EGL_DEPTH_SIZE,       0 },
        { EGL_CONFIG_ID,        2 },
        { EGL_SURFACE_TYPE,     EGL_WINDOW_BIT|EGL_PBUFFER_BIT|EGL_PIXMAP_BIT },
};

static config_pair_t const config_5_attribute_list[] = {
        { EGL_BUFFER_SIZE,     32 },
        { EGL_ALPHA_SIZE,       8 },
        { EGL_BLUE_SIZE,        8 },
        { EGL_GREEN_SIZE,       8 },
        { EGL_RED_SIZE,         8 },
        { EGL_DEPTH_SIZE,      16 },
        { EGL_CONFIG_ID,        3 },
        { EGL_SURFACE_TYPE,     EGL_WINDOW_BIT|EGL_PBUFFER_BIT|EGL_PIXMAP_BIT },
};

// A8 configs
static config_pair_t const config_6_attribute_list[] = {
        { EGL_BUFFER_SIZE,      8 },
        { EGL_ALPHA_SIZE,       8 },
        { EGL_BLUE_SIZE,        0 },
        { EGL_GREEN_SIZE,       0 },
        { EGL_RED_SIZE,         0 },
        { EGL_DEPTH_SIZE,       0 },
        { EGL_CONFIG_ID,        4 },
        { EGL_SURFACE_TYPE,     EGL_WINDOW_BIT|EGL_PBUFFER_BIT|EGL_PIXMAP_BIT },
};

static config_pair_t const config_7_attribute_list[] = {
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
        { config_6_attribute_list, NELEM(config_6_attribute_list) },
        { config_7_attribute_list, NELEM(config_7_attribute_list) },
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
        { EGL_LUMINANCE_SIZE,             config_management_t::atLeast },
        { EGL_ALPHA_MASK_SIZE,            config_management_t::atLeast },
        { EGL_COLOR_BUFFER_TYPE,          config_management_t::exact   },
        { EGL_RENDERABLE_TYPE,            config_management_t::mask    },
        { EGL_CONFORMANT,                 config_management_t::mask    }
};


static config_pair_t const config_defaults[] = {
    // attributes that are not specified are simply ignored, if a particular
    // one needs not be ignored, it must be specified here, eg:
    // { EGL_SURFACE_TYPE, EGL_WINDOW_BIT },
};

// ----------------------------------------------------------------------------

static status_t getConfigFormatInfo(EGLint configID,
        int32_t& pixelFormat, int32_t& depthFormat)
{
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
        pixelFormat = GGL_PIXEL_FORMAT_RGBX_8888;
        depthFormat = 0;
        break;
    case 3:
        pixelFormat = GGL_PIXEL_FORMAT_RGBX_8888;
        depthFormat = GGL_PIXEL_FORMAT_Z_16;
        break;
    case 4:
        pixelFormat = GGL_PIXEL_FORMAT_RGBA_8888;
        depthFormat = 0;
        break;
    case 5:
        pixelFormat = GGL_PIXEL_FORMAT_RGBA_8888;
        depthFormat = GGL_PIXEL_FORMAT_Z_16;
        break;
    case 6:
        pixelFormat = GGL_PIXEL_FORMAT_A_8;
        depthFormat = 0;
        break;
    case 7:
        pixelFormat = GGL_PIXEL_FORMAT_A_8;
        depthFormat = GGL_PIXEL_FORMAT_Z_16;
        break;
    default:
        return NAME_NOT_FOUND;
    }
    return NO_ERROR;
}

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
        if (cfgMgtIndex >= 0) {
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

    if (static_cast<android_native_window_t*>(window)->common.magic !=
            ANDROID_NATIVE_WINDOW_MAGIC) {
        return setError(EGL_BAD_NATIVE_WINDOW, EGL_NO_SURFACE);
    }
        
    EGLint configID;
    if (getConfigAttrib(dpy, config, EGL_CONFIG_ID, &configID) == EGL_FALSE)
        return EGL_FALSE;

    int32_t depthFormat;
    int32_t pixelFormat;
    if (getConfigFormatInfo(configID, pixelFormat, depthFormat) != NO_ERROR) {
        return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);
    }

    // FIXME: we don't have access to the pixelFormat here just yet.
    // (it's possible that the surface is not fully initialized)
    // maybe this should be done after the page-flip
    //if (EGLint(info.format) != pixelFormat)
    //    return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);

    egl_surface_t* surface;
    surface = new egl_window_surface_v2_t(dpy, config, depthFormat,
            static_cast<android_native_window_t*>(window));

    if (!surface->initCheck()) {
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

    if (static_cast<egl_native_pixmap_t*>(pixmap)->version != 
            sizeof(egl_native_pixmap_t)) {
        return setError(EGL_BAD_NATIVE_PIXMAP, EGL_NO_SURFACE);
    }
    
    EGLint configID;
    if (getConfigAttrib(dpy, config, EGL_CONFIG_ID, &configID) == EGL_FALSE)
        return EGL_FALSE;

    int32_t depthFormat;
    int32_t pixelFormat;
    if (getConfigFormatInfo(configID, pixelFormat, depthFormat) != NO_ERROR) {
        return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);
    }

    if (pixmap->format != pixelFormat)
        return setError(EGL_BAD_MATCH, EGL_NO_SURFACE);

    egl_surface_t* surface =
        new egl_pixmap_surface_t(dpy, config, depthFormat,
                static_cast<egl_native_pixmap_t*>(pixmap));

    if (!surface->initCheck()) {
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
    if (getConfigFormatInfo(configID, pixelFormat, depthFormat) != NO_ERROR) {
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

    if (!surface->initCheck()) {
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
        if (major != NULL) *major = VERSION_MAJOR;
        if (minor != NULL) *minor = VERSION_MINOR;
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
    
    if (ggl_unlikely(num_config==0)) {
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);
    }

    if (ggl_unlikely(attrib_list==0)) {
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
        for (int i=0 ; possibleMatch && i<numConfigs ; i++) {
            if (!(possibleMatch & (1<<i)))
                continue;
            if (isAttributeMatching(i, attr, val) == 0) {
                possibleMatch &= ~(1<<i);
            }
        }
    }

    // now, handle the attributes which have a useful default value
    for (size_t j=0 ; possibleMatch && j<NELEM(config_defaults) ; j++) {
        // see if this attribute was specified, if not, apply its
        // default value
        if (binarySearch<config_pair_t>(
                (config_pair_t const*)attrib_list,
                0, numAttributes-1,
                config_defaults[j].key) < 0)
        {
            for (int i=0 ; possibleMatch && i<numConfigs ; i++) {
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
        if (configs) {
            for (int i=0 ; config_size && i<numConfigs ; i++) {
                if (possibleMatch & (1<<i)) {
                    *configs++ = (EGLConfig)i;
                    config_size--;
                    n++;
                }
            }
        } else {
            for (int i=0 ; i<numConfigs ; i++) {
                if (possibleMatch & (1<<i)) {
                    n++;
                }
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
        if (!surface->isValid())
            return setError(EGL_BAD_SURFACE, EGL_FALSE);
        if (surface->dpy != dpy)
            return setError(EGL_BAD_DISPLAY, EGL_FALSE);
        if (surface->ctx) {
            // FIXME: this surface is current check what the spec says
            surface->disconnect();
            surface->ctx = 0;
        }
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
    if (!surface->isValid())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);
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
        if (!s->isValid())
            return setError(EGL_BAD_SURFACE, EGL_FALSE);
        if (s->dpy != dpy)
            return setError(EGL_BAD_DISPLAY, EGL_FALSE);
        // TODO: check that draw is compatible with the context
    }
    if (read && read!=draw) {
        egl_surface_t* s = (egl_surface_t*)read;
        if (!s->isValid())
            return setError(EGL_BAD_SURFACE, EGL_FALSE);
        if (s->dpy != dpy)
            return setError(EGL_BAD_DISPLAY, EGL_FALSE);
        // TODO: check that read is compatible with the context
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
            // one of the surface is bound to a context in another thread
            return setError(EGL_BAD_ACCESS, EGL_FALSE);
        }
    }

    ogles_context_t* gl = (ogles_context_t*)ctx;
    if (makeCurrent(gl) == 0) {
        if (ctx) {
            egl_context_t* c = egl_context_t::context(ctx);
            egl_surface_t* d = (egl_surface_t*)draw;
            egl_surface_t* r = (egl_surface_t*)read;
            
            if (c->draw) {
                egl_surface_t* s = reinterpret_cast<egl_surface_t*>(c->draw);
                s->disconnect();
            }
            if (c->read) {
                // FIXME: unlock/disconnect the read surface too 
            }
            
            c->draw = draw;
            c->read = read;

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
                if (d->connect() == EGL_FALSE) {
                    return EGL_FALSE;
                }
                d->ctx = ctx;
                d->bindDrawSurface(gl);
            }
            if (r) {
                // FIXME: lock/connect the read surface too 
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
                if (d) {
                    c->draw = 0;
                    d->ctx = EGL_NO_CONTEXT;
                    d->disconnect();
                }
                if (r) {
                    c->read = 0;
                    r->ctx = EGL_NO_CONTEXT;
                    // FIXME: unlock/disconnect the read surface too 
                }
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
    if (!d->isValid())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);
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
// EGL_EGLEXT_VERSION 3
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

EGLBoolean eglLockSurfaceKHR(EGLDisplay dpy, EGLSurface surface,
        const EGLint *attrib_list)
{
    EGLBoolean result = EGL_FALSE;
    return result;
}

EGLBoolean eglUnlockSurfaceKHR(EGLDisplay dpy, EGLSurface surface)
{
    EGLBoolean result = EGL_FALSE;
    return result;
}

EGLImageKHR eglCreateImageKHR(EGLDisplay dpy, EGLContext ctx, EGLenum target,
        EGLClientBuffer buffer, const EGLint *attrib_list)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE) {
        return setError(EGL_BAD_DISPLAY, EGL_NO_IMAGE_KHR);
    }
    if (ctx != EGL_NO_CONTEXT) {
        return setError(EGL_BAD_CONTEXT, EGL_NO_IMAGE_KHR);
    }
    if (target != EGL_NATIVE_BUFFER_ANDROID) {
        return setError(EGL_BAD_PARAMETER, EGL_NO_IMAGE_KHR);
    }

    android_native_buffer_t* native_buffer = (android_native_buffer_t*)buffer;

    if (native_buffer->common.magic != ANDROID_NATIVE_BUFFER_MAGIC)
        return setError(EGL_BAD_PARAMETER, EGL_NO_IMAGE_KHR);

    if (native_buffer->common.version != sizeof(android_native_buffer_t))
        return setError(EGL_BAD_PARAMETER, EGL_NO_IMAGE_KHR);

    switch (native_buffer->format) {
        case HAL_PIXEL_FORMAT_RGBA_8888:
        case HAL_PIXEL_FORMAT_RGBX_8888:
        case HAL_PIXEL_FORMAT_RGB_888:
        case HAL_PIXEL_FORMAT_RGB_565:
        case HAL_PIXEL_FORMAT_BGRA_8888:
        case HAL_PIXEL_FORMAT_RGBA_5551:
        case HAL_PIXEL_FORMAT_RGBA_4444:
            break;
        default:
            return setError(EGL_BAD_PARAMETER, EGL_NO_IMAGE_KHR);
    }

    native_buffer->common.incRef(&native_buffer->common);
    return (EGLImageKHR)native_buffer;
}

EGLBoolean eglDestroyImageKHR(EGLDisplay dpy, EGLImageKHR img)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE) {
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    }

    android_native_buffer_t* native_buffer = (android_native_buffer_t*)img;

    if (native_buffer->common.magic != ANDROID_NATIVE_BUFFER_MAGIC)
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);

    if (native_buffer->common.version != sizeof(android_native_buffer_t))
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);

    native_buffer->common.decRef(&native_buffer->common);

    return EGL_TRUE;
}

// ----------------------------------------------------------------------------
// ANDROID extensions
// ----------------------------------------------------------------------------

EGLBoolean eglSetSwapRectangleANDROID(EGLDisplay dpy, EGLSurface draw,
        EGLint left, EGLint top, EGLint width, EGLint height)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    egl_surface_t* d = static_cast<egl_surface_t*>(draw);
    if (!d->isValid())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);
    if (d->dpy != dpy)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    // post the surface
    d->setSwapRectangle(left, top, width, height);

    return EGL_TRUE;
}

EGLClientBuffer eglGetRenderBufferANDROID(EGLDisplay dpy, EGLSurface draw)
{
    if (egl_display_t::is_valid(dpy) == EGL_FALSE)
        return setError(EGL_BAD_DISPLAY, (EGLClientBuffer)0);

    egl_surface_t* d = static_cast<egl_surface_t*>(draw);
    if (!d->isValid())
        return setError(EGL_BAD_SURFACE, (EGLClientBuffer)0);
    if (d->dpy != dpy)
        return setError(EGL_BAD_DISPLAY, (EGLClientBuffer)0);

    // post the surface
    return d->getRenderBuffer();
}
