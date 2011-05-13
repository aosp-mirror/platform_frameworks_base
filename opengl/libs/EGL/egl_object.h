/* 
 ** Copyright 2007, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License"); 
 ** you may not use this file except in compliance with the License. 
 ** You may obtain a copy of the License at 
 **
 **     http://www.apache.org/licenses/LICENSE-2.0 
 **
 ** Unless required by applicable law or agreed to in writing, software 
 ** distributed under the License is distributed on an "AS IS" BASIS, 
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 ** See the License for the specific language governing permissions and 
 ** limitations under the License.
 */

#ifndef ANDROID_EGL_OBJECT_H
#define ANDROID_EGL_OBJECT_H


#include <ctype.h>
#include <stdint.h>
#include <stdlib.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include <utils/threads.h>

#include <system/window.h>

#include "egl_display.h"

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

struct egl_display_t;

class egl_object_t {
    egl_display_t *display;
    volatile int32_t  terminated;
    mutable volatile int32_t count;

public:
    egl_object_t(egl_display_t* display);

    inline bool isAlive() const { return !terminated; }
    inline int32_t incRef() { return android_atomic_inc(&count); }
    inline int32_t decRef() { return android_atomic_dec(&count); }

private:
    bool get();
    bool put();

public:
    template <typename N, typename T>
    struct LocalRef {
        N* ref;
        LocalRef(T o) : ref(0) {
            N* native = reinterpret_cast<N*>(o);
            if (o && native->get()) {
                ref = native;
            }
        }
        ~LocalRef() {
            if (ref && ref->put()) {
                delete ref;
            }
        }
        inline N* get() {
            return ref;
        }
        void acquire() const {
            if (ref) {
                android_atomic_inc(&ref->count);
            }
        }
        void release() const {
            if (ref) {
                int32_t c = android_atomic_dec(&ref->count);
                // ref->count cannot be 1 prior atomic_dec because we have
                // a reference, and if we have one, it means there was
                // already one before us.
                LOGE_IF(c==1, "refcount is now 0 in release()");
            }
        }
        void terminate() {
            if (ref) {
                ref->terminated = 1;
                release();
            }
        }
    };
};

// ----------------------------------------------------------------------------

struct egl_surface_t: public egl_object_t {
    typedef egl_object_t::LocalRef<egl_surface_t, EGLSurface> Ref;

    egl_surface_t(EGLDisplay dpy, EGLConfig config, EGLNativeWindowType win,
            EGLSurface surface, int impl, egl_connection_t const* cnx) :
        egl_object_t(get_display(dpy)), dpy(dpy), surface(surface),
                config(config), win(win), impl(impl), cnx(cnx) {
    }
    ~egl_surface_t() {
    }
    EGLDisplay dpy;
    EGLSurface surface;
    EGLConfig config;
    sp<ANativeWindow> win;
    int impl;
    egl_connection_t const* cnx;
};

struct egl_context_t: public egl_object_t {
    typedef egl_object_t::LocalRef<egl_context_t, EGLContext> Ref;

    egl_context_t(EGLDisplay dpy, EGLContext context, EGLConfig config,
            int impl, egl_connection_t const* cnx, int version) :
        egl_object_t(get_display(dpy)), dpy(dpy), context(context),
                config(config), read(0), draw(0), impl(impl), cnx(cnx),
                version(version) {
    }
    ~egl_context_t() {
    }
    EGLDisplay dpy;
    EGLContext context;
    EGLConfig config;
    EGLSurface read;
    EGLSurface draw;
    int impl;
    egl_connection_t const* cnx;
    int version;
};

struct egl_image_t: public egl_object_t {
    typedef egl_object_t::LocalRef<egl_image_t, EGLImageKHR> Ref;

    egl_image_t(EGLDisplay dpy, EGLContext context) :
        egl_object_t(get_display(dpy)), dpy(dpy), context(context) {
        memset(images, 0, sizeof(images));
    }
    EGLDisplay dpy;
    EGLContext context;
    EGLImageKHR images[IMPL_NUM_IMPLEMENTATIONS];
};

struct egl_sync_t: public egl_object_t {
    typedef egl_object_t::LocalRef<egl_sync_t, EGLSyncKHR> Ref;

    egl_sync_t(EGLDisplay dpy, EGLContext context, EGLSyncKHR sync) :
        egl_object_t(get_display(dpy)), dpy(dpy), context(context), sync(sync) {
    }
    EGLDisplay dpy;
    EGLContext context;
    EGLSyncKHR sync;
};

// ----------------------------------------------------------------------------

typedef egl_surface_t::Ref  SurfaceRef;
typedef egl_context_t::Ref  ContextRef;
typedef egl_image_t::Ref    ImageRef;
typedef egl_sync_t::Ref     SyncRef;

// ----------------------------------------------------------------------------

template<typename NATIVE, typename EGL>
static inline NATIVE* egl_to_native_cast(EGL arg) {
    return reinterpret_cast<NATIVE*>(arg);
}

static inline
egl_surface_t* get_surface(EGLSurface surface) {
    return egl_to_native_cast<egl_surface_t>(surface);
}

static inline
egl_context_t* get_context(EGLContext context) {
    return egl_to_native_cast<egl_context_t>(context);
}

static inline
egl_image_t* get_image(EGLImageKHR image) {
    return egl_to_native_cast<egl_image_t>(image);
}

static inline
egl_sync_t* get_sync(EGLSyncKHR sync) {
    return egl_to_native_cast<egl_sync_t>(sync);
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

#endif // ANDROID_EGL_OBJECT_H

