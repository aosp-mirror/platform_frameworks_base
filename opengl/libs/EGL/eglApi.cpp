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

#include <ctype.h>
#include <stdlib.h>
#include <string.h>

#include <hardware/gralloc.h>
#include <system/window.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include <cutils/log.h>
#include <cutils/atomic.h>
#include <cutils/properties.h>
#include <cutils/memory.h>

#include <utils/KeyedVector.h>
#include <utils/SortedVector.h>
#include <utils/String8.h>

#include "egl_impl.h"
#include "egl_tls.h"
#include "glestrace.h"
#include "hooks.h"

#include "egl_display.h"
#include "egl_impl.h"
#include "egl_object.h"
#include "egl_tls.h"
#include "egldefs.h"

using namespace android;

// ----------------------------------------------------------------------------

#define EGL_VERSION_HW_ANDROID  0x3143

struct extention_map_t {
    const char* name;
    __eglMustCastToProperFunctionPointerType address;
};

static const extention_map_t sExtentionMap[] = {
    { "eglLockSurfaceKHR",
            (__eglMustCastToProperFunctionPointerType)&eglLockSurfaceKHR },
    { "eglUnlockSurfaceKHR",
            (__eglMustCastToProperFunctionPointerType)&eglUnlockSurfaceKHR },
    { "eglCreateImageKHR",
            (__eglMustCastToProperFunctionPointerType)&eglCreateImageKHR },
    { "eglDestroyImageKHR",
            (__eglMustCastToProperFunctionPointerType)&eglDestroyImageKHR },
    { "eglGetSystemTimeFrequencyNV",
            (__eglMustCastToProperFunctionPointerType)&eglGetSystemTimeFrequencyNV },
    { "eglGetSystemTimeNV",
            (__eglMustCastToProperFunctionPointerType)&eglGetSystemTimeNV },
};

// accesses protected by sExtensionMapMutex
static DefaultKeyedVector<String8, __eglMustCastToProperFunctionPointerType> sGLExtentionMap;
static int sGLExtentionSlot = 0;
static pthread_mutex_t sExtensionMapMutex = PTHREAD_MUTEX_INITIALIZER;

static void(*findProcAddress(const char* name,
        const extention_map_t* map, size_t n))() {
    for (uint32_t i=0 ; i<n ; i++) {
        if (!strcmp(name, map[i].name)) {
            return map[i].address;
        }
    }
    return NULL;
}

// ----------------------------------------------------------------------------

namespace android {
extern void setGLHooksThreadSpecific(gl_hooks_t const *value);
extern EGLBoolean egl_init_drivers();
extern const __eglMustCastToProperFunctionPointerType gExtensionForwarders[MAX_NUMBER_OF_GL_EXTENSIONS];
extern int gEGLDebugLevel;
extern gl_hooks_t gHooksTrace;
} // namespace android;

// ----------------------------------------------------------------------------

static inline void clearError() { egl_tls_t::clearError(); }
static inline EGLContext getContext() { return egl_tls_t::getContext(); }

// ----------------------------------------------------------------------------

EGLDisplay eglGetDisplay(EGLNativeDisplayType display)
{
    clearError();

    uint32_t index = uint32_t(display);
    if (index >= NUM_DISPLAYS) {
        return setError(EGL_BAD_PARAMETER, EGL_NO_DISPLAY);
    }

    if (egl_init_drivers() == EGL_FALSE) {
        return setError(EGL_BAD_PARAMETER, EGL_NO_DISPLAY);
    }

    EGLDisplay dpy = egl_display_t::getFromNativeDisplay(display);
    return dpy;
}

// ----------------------------------------------------------------------------
// Initialization
// ----------------------------------------------------------------------------

EGLBoolean eglInitialize(EGLDisplay dpy, EGLint *major, EGLint *minor)
{
    clearError();

    egl_display_t * const dp = get_display(dpy);
    if (!dp) return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    EGLBoolean res = dp->initialize(major, minor);

    return res;
}

EGLBoolean eglTerminate(EGLDisplay dpy)
{
    // NOTE: don't unload the drivers b/c some APIs can be called
    // after eglTerminate() has been called. eglTerminate() only
    // terminates an EGLDisplay, not a EGL itself.

    clearError();

    egl_display_t* const dp = get_display(dpy);
    if (!dp) return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    EGLBoolean res = dp->terminate();
    
    return res;
}

// ----------------------------------------------------------------------------
// configuration
// ----------------------------------------------------------------------------

EGLBoolean eglGetConfigs(   EGLDisplay dpy,
                            EGLConfig *configs,
                            EGLint config_size, EGLint *num_config)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    if (num_config==0) {
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);
    }

    EGLBoolean res = EGL_FALSE;
    *num_config = 0;

    egl_connection_t* const cnx = &gEGLImpl;
    if (cnx->dso) {
        res = cnx->egl.eglGetConfigs(
                dp->disp.dpy, configs, config_size, num_config);
    }

    return res;
}

EGLBoolean eglChooseConfig( EGLDisplay dpy, const EGLint *attrib_list,
                            EGLConfig *configs, EGLint config_size,
                            EGLint *num_config)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    if (num_config==0) {
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);
    }

    EGLBoolean res = EGL_FALSE;
    *num_config = 0;

    egl_connection_t* const cnx = &gEGLImpl;
    if (cnx->dso) {
        res = cnx->egl.eglChooseConfig(
                dp->disp.dpy, attrib_list, configs, config_size, num_config);
    }
    return res;
}

EGLBoolean eglGetConfigAttrib(EGLDisplay dpy, EGLConfig config,
        EGLint attribute, EGLint *value)
{
    clearError();

    egl_display_t const* dp = 0;
    egl_connection_t* cnx = validate_display_config(dpy, config, dp);
    if (!cnx) return EGL_FALSE;
    
    return cnx->egl.eglGetConfigAttrib(
            dp->disp.dpy, config, attribute, value);
}

// ----------------------------------------------------------------------------
// surfaces
// ----------------------------------------------------------------------------

EGLSurface eglCreateWindowSurface(  EGLDisplay dpy, EGLConfig config,
                                    NativeWindowType window,
                                    const EGLint *attrib_list)
{
    clearError();

    egl_display_t const* dp = 0;
    egl_connection_t* cnx = validate_display_config(dpy, config, dp);
    if (cnx) {
        EGLDisplay iDpy = dp->disp.dpy;
        EGLint format;

        if (native_window_api_connect(window, NATIVE_WINDOW_API_EGL) != OK) {
            ALOGE("EGLNativeWindowType %p already connected to another API",
                    window);
            return setError(EGL_BAD_NATIVE_WINDOW, EGL_NO_SURFACE);
        }

        // set the native window's buffers format to match this config
        if (cnx->egl.eglGetConfigAttrib(iDpy,
                config, EGL_NATIVE_VISUAL_ID, &format)) {
            if (format != 0) {
                int err = native_window_set_buffers_format(window, format);
                if (err != 0) {
                    ALOGE("error setting native window pixel format: %s (%d)",
                            strerror(-err), err);
                    native_window_api_disconnect(window, NATIVE_WINDOW_API_EGL);
                    return setError(EGL_BAD_NATIVE_WINDOW, EGL_NO_SURFACE);
                }
            }
        }

        // the EGL spec requires that a new EGLSurface default to swap interval
        // 1, so explicitly set that on the window here.
        ANativeWindow* anw = reinterpret_cast<ANativeWindow*>(window);
        anw->setSwapInterval(anw, 1);

        EGLSurface surface = cnx->egl.eglCreateWindowSurface(
                iDpy, config, window, attrib_list);
        if (surface != EGL_NO_SURFACE) {
            egl_surface_t* s = new egl_surface_t(dpy, config, window, surface, cnx);
            return s;
        }

        // EGLSurface creation failed
        native_window_set_buffers_format(window, 0);
        native_window_api_disconnect(window, NATIVE_WINDOW_API_EGL);
    }
    return EGL_NO_SURFACE;
}

EGLSurface eglCreatePixmapSurface(  EGLDisplay dpy, EGLConfig config,
                                    NativePixmapType pixmap,
                                    const EGLint *attrib_list)
{
    clearError();

    egl_display_t const* dp = 0;
    egl_connection_t* cnx = validate_display_config(dpy, config, dp);
    if (cnx) {
        EGLSurface surface = cnx->egl.eglCreatePixmapSurface(
                dp->disp.dpy, config, pixmap, attrib_list);
        if (surface != EGL_NO_SURFACE) {
            egl_surface_t* s = new egl_surface_t(dpy, config, NULL, surface, cnx);
            return s;
        }
    }
    return EGL_NO_SURFACE;
}

EGLSurface eglCreatePbufferSurface( EGLDisplay dpy, EGLConfig config,
                                    const EGLint *attrib_list)
{
    clearError();

    egl_display_t const* dp = 0;
    egl_connection_t* cnx = validate_display_config(dpy, config, dp);
    if (cnx) {
        EGLSurface surface = cnx->egl.eglCreatePbufferSurface(
                dp->disp.dpy, config, attrib_list);
        if (surface != EGL_NO_SURFACE) {
            egl_surface_t* s = new egl_surface_t(dpy, config, NULL, surface, cnx);
            return s;
        }
    }
    return EGL_NO_SURFACE;
}
                                    
EGLBoolean eglDestroySurface(EGLDisplay dpy, EGLSurface surface)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(dp, surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t * const s = get_surface(surface);
    EGLBoolean result = s->cnx->egl.eglDestroySurface(dp->disp.dpy, s->surface);
    if (result == EGL_TRUE) {
        _s.terminate();
    }
    return result;
}

EGLBoolean eglQuerySurface( EGLDisplay dpy, EGLSurface surface,
                            EGLint attribute, EGLint *value)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(dp, surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    return s->cnx->egl.eglQuerySurface(
            dp->disp.dpy, s->surface, attribute, value);
}

void EGLAPI eglBeginFrame(EGLDisplay dpy, EGLSurface surface) {
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) {
        return;
    }

    SurfaceRef _s(dp, surface);
    if (!_s.get()) {
        setError(EGL_BAD_SURFACE, EGL_FALSE);
        return;
    }

    int64_t timestamp = systemTime(SYSTEM_TIME_MONOTONIC);

    egl_surface_t const * const s = get_surface(surface);
    native_window_set_buffers_timestamp(s->win.get(), timestamp);
}

// ----------------------------------------------------------------------------
// Contexts
// ----------------------------------------------------------------------------

EGLContext eglCreateContext(EGLDisplay dpy, EGLConfig config,
                            EGLContext share_list, const EGLint *attrib_list)
{
    clearError();

    egl_display_t const* dp = 0;
    egl_connection_t* cnx = validate_display_config(dpy, config, dp);
    if (cnx) {
        if (share_list != EGL_NO_CONTEXT) {
            egl_context_t* const c = get_context(share_list);
            share_list = c->context;
        }
        EGLContext context = cnx->egl.eglCreateContext(
                dp->disp.dpy, config, share_list, attrib_list);
        if (context != EGL_NO_CONTEXT) {
            // figure out if it's a GLESv1 or GLESv2
            int version = 0;
            if (attrib_list) {
                while (*attrib_list != EGL_NONE) {
                    GLint attr = *attrib_list++;
                    GLint value = *attrib_list++;
                    if (attr == EGL_CONTEXT_CLIENT_VERSION) {
                        if (value == 1) {
                            version = egl_connection_t::GLESv1_INDEX;
                        } else if (value == 2) {
                            version = egl_connection_t::GLESv2_INDEX;
                        }
                    }
                };
            }
            egl_context_t* c = new egl_context_t(dpy, context, config, cnx, version);
#if EGL_TRACE
            if (gEGLDebugLevel > 0)
                GLTrace_eglCreateContext(version, c);
#endif
            return c;
        }
    }
    return EGL_NO_CONTEXT;
}

EGLBoolean eglDestroyContext(EGLDisplay dpy, EGLContext ctx)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp)
        return EGL_FALSE;

    ContextRef _c(dp, ctx);
    if (!_c.get())
        return setError(EGL_BAD_CONTEXT, EGL_FALSE);
    
    egl_context_t * const c = get_context(ctx);
    EGLBoolean result = c->cnx->egl.eglDestroyContext(dp->disp.dpy, c->context);
    if (result == EGL_TRUE) {
        _c.terminate();
    }
    return result;
}

EGLBoolean eglMakeCurrent(  EGLDisplay dpy, EGLSurface draw,
                            EGLSurface read, EGLContext ctx)
{
    clearError();

    egl_display_t const * const dp = get_display(dpy);
    if (!dp) return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    // If ctx is not EGL_NO_CONTEXT, read is not EGL_NO_SURFACE, or draw is not
    // EGL_NO_SURFACE, then an EGL_NOT_INITIALIZED error is generated if dpy is
    // a valid but uninitialized display.
    if ( (ctx != EGL_NO_CONTEXT) || (read != EGL_NO_SURFACE) ||
         (draw != EGL_NO_SURFACE) ) {
        if (!dp->isReady()) return setError(EGL_NOT_INITIALIZED, EGL_FALSE);
    }

    // get a reference to the object passed in
    ContextRef _c(dp, ctx);
    SurfaceRef _d(dp, draw);
    SurfaceRef _r(dp, read);

    // validate the context (if not EGL_NO_CONTEXT)
    if ((ctx != EGL_NO_CONTEXT) && !_c.get()) {
        // EGL_NO_CONTEXT is valid
        return EGL_FALSE;
    }

    // these are the underlying implementation's object
    EGLContext impl_ctx  = EGL_NO_CONTEXT;
    EGLSurface impl_draw = EGL_NO_SURFACE;
    EGLSurface impl_read = EGL_NO_SURFACE;

    // these are our objects structs passed in
    egl_context_t       * c = NULL;
    egl_surface_t const * d = NULL;
    egl_surface_t const * r = NULL;

    // these are the current objects structs
    egl_context_t * cur_c = get_context(getContext());
    
    if (ctx != EGL_NO_CONTEXT) {
        c = get_context(ctx);
        impl_ctx = c->context;
    } else {
        // no context given, use the implementation of the current context
        if (cur_c == NULL) {
            // no current context
            if (draw != EGL_NO_SURFACE || read != EGL_NO_SURFACE) {
                // calling eglMakeCurrent( ..., !=0, !=0, EGL_NO_CONTEXT);
                return setError(EGL_BAD_MATCH, EGL_FALSE);
            }
            // not an error, there is just no current context.
            return EGL_TRUE;
        }
    }

    // retrieve the underlying implementation's draw EGLSurface
    if (draw != EGL_NO_SURFACE) {
        d = get_surface(draw);
        impl_draw = d->surface;
    }

    // retrieve the underlying implementation's read EGLSurface
    if (read != EGL_NO_SURFACE) {
        r = get_surface(read);
        impl_read = r->surface;
    }


    EGLBoolean result = const_cast<egl_display_t*>(dp)->makeCurrent(c, cur_c,
            draw, read, ctx,
            impl_draw, impl_read, impl_ctx);

    if (result == EGL_TRUE) {
        if (c) {
            setGLHooksThreadSpecific(c->cnx->hooks[c->version]);
            egl_tls_t::setContext(ctx);
#if EGL_TRACE
            if (gEGLDebugLevel > 0)
                GLTrace_eglMakeCurrent(c->version, c->cnx->hooks[c->version], ctx);
#endif
            _c.acquire();
            _r.acquire();
            _d.acquire();
        } else {
            setGLHooksThreadSpecific(&gHooksNoContext);
            egl_tls_t::setContext(EGL_NO_CONTEXT);
        }
    } else {
        // this will ALOGE the error
        result = setError(c->cnx->egl.eglGetError(), EGL_FALSE);
    }
    return result;
}


EGLBoolean eglQueryContext( EGLDisplay dpy, EGLContext ctx,
                            EGLint attribute, EGLint *value)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    ContextRef _c(dp, ctx);
    if (!_c.get()) return setError(EGL_BAD_CONTEXT, EGL_FALSE);

    egl_context_t * const c = get_context(ctx);
    return c->cnx->egl.eglQueryContext(
            dp->disp.dpy, c->context, attribute, value);

}

EGLContext eglGetCurrentContext(void)
{
    // could be called before eglInitialize(), but we wouldn't have a context
    // then, and this function would correctly return EGL_NO_CONTEXT.

    clearError();

    EGLContext ctx = getContext();
    return ctx;
}

EGLSurface eglGetCurrentSurface(EGLint readdraw)
{
    // could be called before eglInitialize(), but we wouldn't have a context
    // then, and this function would correctly return EGL_NO_SURFACE.

    clearError();

    EGLContext ctx = getContext();
    if (ctx) {
        egl_context_t const * const c = get_context(ctx);
        if (!c) return setError(EGL_BAD_CONTEXT, EGL_NO_SURFACE);
        switch (readdraw) {
            case EGL_READ: return c->read;
            case EGL_DRAW: return c->draw;            
            default: return setError(EGL_BAD_PARAMETER, EGL_NO_SURFACE);
        }
    }
    return EGL_NO_SURFACE;
}

EGLDisplay eglGetCurrentDisplay(void)
{
    // could be called before eglInitialize(), but we wouldn't have a context
    // then, and this function would correctly return EGL_NO_DISPLAY.

    clearError();

    EGLContext ctx = getContext();
    if (ctx) {
        egl_context_t const * const c = get_context(ctx);
        if (!c) return setError(EGL_BAD_CONTEXT, EGL_NO_SURFACE);
        return c->dpy;
    }
    return EGL_NO_DISPLAY;
}

EGLBoolean eglWaitGL(void)
{
    clearError();

    egl_connection_t* const cnx = &gEGLImpl;
    if (!cnx->dso)
        return setError(EGL_BAD_CONTEXT, EGL_FALSE);

    return cnx->egl.eglWaitGL();
}

EGLBoolean eglWaitNative(EGLint engine)
{
    clearError();

    egl_connection_t* const cnx = &gEGLImpl;
    if (!cnx->dso)
        return setError(EGL_BAD_CONTEXT, EGL_FALSE);

    return cnx->egl.eglWaitNative(engine);
}

EGLint eglGetError(void)
{
    EGLint err = EGL_SUCCESS;
    egl_connection_t* const cnx = &gEGLImpl;
    if (cnx->dso) {
        err = cnx->egl.eglGetError();
    }
    if (err == EGL_SUCCESS) {
        err = egl_tls_t::getError();
    }
    return err;
}

__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char *procname)
{
    // eglGetProcAddress() could be the very first function called
    // in which case we must make sure we've initialized ourselves, this
    // happens the first time egl_get_display() is called.

    clearError();

    if (egl_init_drivers() == EGL_FALSE) {
        setError(EGL_BAD_PARAMETER, NULL);
        return  NULL;
    }

    // The EGL_ANDROID_blob_cache extension should not be exposed to
    // applications.  It is used internally by the Android EGL layer.
    if (!strcmp(procname, "eglSetBlobCacheFuncsANDROID")) {
        return NULL;
    }

    __eglMustCastToProperFunctionPointerType addr;
    addr = findProcAddress(procname, sExtentionMap, NELEM(sExtentionMap));
    if (addr) return addr;


    // this protects accesses to sGLExtentionMap and sGLExtentionSlot
    pthread_mutex_lock(&sExtensionMapMutex);

        /*
         * Since eglGetProcAddress() is not associated to anything, it needs
         * to return a function pointer that "works" regardless of what
         * the current context is.
         *
         * For this reason, we return a "forwarder", a small stub that takes
         * care of calling the function associated with the context
         * currently bound.
         *
         * We first look for extensions we've already resolved, if we're seeing
         * this extension for the first time, we go through all our
         * implementations and call eglGetProcAddress() and record the
         * result in the appropriate implementation hooks and return the
         * address of the forwarder corresponding to that hook set.
         *
         */

        const String8 name(procname);
        addr = sGLExtentionMap.valueFor(name);
        const int slot = sGLExtentionSlot;

        ALOGE_IF(slot >= MAX_NUMBER_OF_GL_EXTENSIONS,
                "no more slots for eglGetProcAddress(\"%s\")",
                procname);

#if EGL_TRACE
        gl_hooks_t *debugHooks = GLTrace_getGLHooks();
#endif

        if (!addr && (slot < MAX_NUMBER_OF_GL_EXTENSIONS)) {
            bool found = false;

            egl_connection_t* const cnx = &gEGLImpl;
            if (cnx->dso && cnx->egl.eglGetProcAddress) {
                found = true;
                // Extensions are independent of the bound context
                cnx->hooks[egl_connection_t::GLESv1_INDEX]->ext.extensions[slot] =
                cnx->hooks[egl_connection_t::GLESv2_INDEX]->ext.extensions[slot] =
#if EGL_TRACE
                debugHooks->ext.extensions[slot] =
                gHooksTrace.ext.extensions[slot] =
#endif
                        cnx->egl.eglGetProcAddress(procname);
            }

            if (found) {
                addr = gExtensionForwarders[slot];
                sGLExtentionMap.add(name, addr);
                sGLExtentionSlot++;
            }
        }

    pthread_mutex_unlock(&sExtensionMapMutex);
    return addr;
}

EGLBoolean eglSwapBuffers(EGLDisplay dpy, EGLSurface draw)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(dp, draw);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

#if EGL_TRACE
    if (gEGLDebugLevel > 0)
        GLTrace_eglSwapBuffers(dpy, draw);
#endif

    egl_surface_t const * const s = get_surface(draw);
    return s->cnx->egl.eglSwapBuffers(dp->disp.dpy, s->surface);
}

EGLBoolean eglCopyBuffers(  EGLDisplay dpy, EGLSurface surface,
                            NativePixmapType target)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(dp, surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    return s->cnx->egl.eglCopyBuffers(dp->disp.dpy, s->surface, target);
}

const char* eglQueryString(EGLDisplay dpy, EGLint name)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return (const char *) NULL;

    switch (name) {
        case EGL_VENDOR:
            return dp->getVendorString();
        case EGL_VERSION:
            return dp->getVersionString();
        case EGL_EXTENSIONS:
            return dp->getExtensionString();
        case EGL_CLIENT_APIS:
            return dp->getClientApiString();
        case EGL_VERSION_HW_ANDROID:
            return dp->disp.queryString.version;
    }
    return setError(EGL_BAD_PARAMETER, (const char *)0);
}


// ----------------------------------------------------------------------------
// EGL 1.1
// ----------------------------------------------------------------------------

EGLBoolean eglSurfaceAttrib(
        EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint value)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(dp, surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    if (s->cnx->egl.eglSurfaceAttrib) {
        return s->cnx->egl.eglSurfaceAttrib(
                dp->disp.dpy, s->surface, attribute, value);
    }
    return setError(EGL_BAD_SURFACE, EGL_FALSE);
}

EGLBoolean eglBindTexImage(
        EGLDisplay dpy, EGLSurface surface, EGLint buffer)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(dp, surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    if (s->cnx->egl.eglBindTexImage) {
        return s->cnx->egl.eglBindTexImage(
                dp->disp.dpy, s->surface, buffer);
    }
    return setError(EGL_BAD_SURFACE, EGL_FALSE);
}

EGLBoolean eglReleaseTexImage(
        EGLDisplay dpy, EGLSurface surface, EGLint buffer)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(dp, surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    if (s->cnx->egl.eglReleaseTexImage) {
        return s->cnx->egl.eglReleaseTexImage(
                dp->disp.dpy, s->surface, buffer);
    }
    return setError(EGL_BAD_SURFACE, EGL_FALSE);
}

EGLBoolean eglSwapInterval(EGLDisplay dpy, EGLint interval)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    EGLBoolean res = EGL_TRUE;
    egl_connection_t* const cnx = &gEGLImpl;
    if (cnx->dso && cnx->egl.eglSwapInterval) {
        res = cnx->egl.eglSwapInterval(dp->disp.dpy, interval);
    }

    return res;
}


// ----------------------------------------------------------------------------
// EGL 1.2
// ----------------------------------------------------------------------------

EGLBoolean eglWaitClient(void)
{
    clearError();

    egl_connection_t* const cnx = &gEGLImpl;
    if (!cnx->dso)
        return setError(EGL_BAD_CONTEXT, EGL_FALSE);

    EGLBoolean res;
    if (cnx->egl.eglWaitClient) {
        res = cnx->egl.eglWaitClient();
    } else {
        res = cnx->egl.eglWaitGL();
    }
    return res;
}

EGLBoolean eglBindAPI(EGLenum api)
{
    clearError();

    if (egl_init_drivers() == EGL_FALSE) {
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);
    }

    // bind this API on all EGLs
    EGLBoolean res = EGL_TRUE;
    egl_connection_t* const cnx = &gEGLImpl;
    if (cnx->dso && cnx->egl.eglBindAPI) {
        res = cnx->egl.eglBindAPI(api);
    }
    return res;
}

EGLenum eglQueryAPI(void)
{
    clearError();

    if (egl_init_drivers() == EGL_FALSE) {
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);
    }

    egl_connection_t* const cnx = &gEGLImpl;
    if (cnx->dso && cnx->egl.eglQueryAPI) {
        return cnx->egl.eglQueryAPI();
    }

    // or, it can only be OpenGL ES
    return EGL_OPENGL_ES_API;
}

EGLBoolean eglReleaseThread(void)
{
    clearError();

    // If there is context bound to the thread, release it
    egl_display_t::loseCurrent(get_context(getContext()));

    egl_connection_t* const cnx = &gEGLImpl;
    if (cnx->dso && cnx->egl.eglReleaseThread) {
        cnx->egl.eglReleaseThread();
    }

    egl_tls_t::clearTLS();
#if EGL_TRACE
    if (gEGLDebugLevel > 0)
        GLTrace_eglReleaseThread();
#endif
    return EGL_TRUE;
}

EGLSurface eglCreatePbufferFromClientBuffer(
          EGLDisplay dpy, EGLenum buftype, EGLClientBuffer buffer,
          EGLConfig config, const EGLint *attrib_list)
{
    clearError();

    egl_display_t const* dp = 0;
    egl_connection_t* cnx = validate_display_config(dpy, config, dp);
    if (!cnx) return EGL_FALSE;
    if (cnx->egl.eglCreatePbufferFromClientBuffer) {
        return cnx->egl.eglCreatePbufferFromClientBuffer(
                dp->disp.dpy, buftype, buffer, config, attrib_list);
    }
    return setError(EGL_BAD_CONFIG, EGL_NO_SURFACE);
}

// ----------------------------------------------------------------------------
// EGL_EGLEXT_VERSION 3
// ----------------------------------------------------------------------------

EGLBoolean eglLockSurfaceKHR(EGLDisplay dpy, EGLSurface surface,
        const EGLint *attrib_list)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(dp, surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    if (s->cnx->egl.eglLockSurfaceKHR) {
        return s->cnx->egl.eglLockSurfaceKHR(
                dp->disp.dpy, s->surface, attrib_list);
    }
    return setError(EGL_BAD_DISPLAY, EGL_FALSE);
}

EGLBoolean eglUnlockSurfaceKHR(EGLDisplay dpy, EGLSurface surface)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(dp, surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    if (s->cnx->egl.eglUnlockSurfaceKHR) {
        return s->cnx->egl.eglUnlockSurfaceKHR(dp->disp.dpy, s->surface);
    }
    return setError(EGL_BAD_DISPLAY, EGL_FALSE);
}

EGLImageKHR eglCreateImageKHR(EGLDisplay dpy, EGLContext ctx, EGLenum target,
        EGLClientBuffer buffer, const EGLint *attrib_list)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_NO_IMAGE_KHR;

    ContextRef _c(dp, ctx);
    egl_context_t * const c = _c.get();

    EGLImageKHR result = EGL_NO_IMAGE_KHR;
    egl_connection_t* const cnx = &gEGLImpl;
    if (cnx->dso && cnx->egl.eglCreateImageKHR) {
        result = cnx->egl.eglCreateImageKHR(
                dp->disp.dpy,
                c ? c->context : EGL_NO_CONTEXT,
                target, buffer, attrib_list);
    }
    return result;
}

EGLBoolean eglDestroyImageKHR(EGLDisplay dpy, EGLImageKHR img)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    egl_connection_t* const cnx = &gEGLImpl;
    if (cnx->dso && cnx->egl.eglDestroyImageKHR) {
        cnx->egl.eglDestroyImageKHR(dp->disp.dpy, img);
    }
    return EGL_TRUE;
}

// ----------------------------------------------------------------------------
// EGL_EGLEXT_VERSION 5
// ----------------------------------------------------------------------------


EGLSyncKHR eglCreateSyncKHR(EGLDisplay dpy, EGLenum type, const EGLint *attrib_list)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_NO_SYNC_KHR;

    EGLSyncKHR result = EGL_NO_SYNC_KHR;
    egl_connection_t* const cnx = &gEGLImpl;
    if (cnx->dso && cnx->egl.eglCreateSyncKHR) {
        result = cnx->egl.eglCreateSyncKHR(dp->disp.dpy, type, attrib_list);
    }
    return result;
}

EGLBoolean eglDestroySyncKHR(EGLDisplay dpy, EGLSyncKHR sync)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    EGLBoolean result = EGL_FALSE;
    egl_connection_t* const cnx = &gEGLImpl;
    if (cnx->dso && cnx->egl.eglDestroySyncKHR) {
        result = cnx->egl.eglDestroySyncKHR(dp->disp.dpy, sync);
    }
    return result;
}

EGLint eglClientWaitSyncKHR(EGLDisplay dpy, EGLSyncKHR sync,
        EGLint flags, EGLTimeKHR timeout)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    EGLBoolean result = EGL_FALSE;
    egl_connection_t* const cnx = &gEGLImpl;
    if (cnx->dso && cnx->egl.eglClientWaitSyncKHR) {
        result = cnx->egl.eglClientWaitSyncKHR(
                dp->disp.dpy, sync, flags, timeout);
    }
    return result;
}

EGLBoolean eglGetSyncAttribKHR(EGLDisplay dpy, EGLSyncKHR sync,
        EGLint attribute, EGLint *value)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    EGLBoolean result = EGL_FALSE;
    egl_connection_t* const cnx = &gEGLImpl;
    if (cnx->dso && cnx->egl.eglGetSyncAttribKHR) {
        result = cnx->egl.eglGetSyncAttribKHR(
                dp->disp.dpy, sync, attribute, value);
    }
    return result;
}

// ----------------------------------------------------------------------------
// ANDROID extensions
// ----------------------------------------------------------------------------

/* ANDROID extensions entry-point go here */

// ----------------------------------------------------------------------------
// NVIDIA extensions
// ----------------------------------------------------------------------------
EGLuint64NV eglGetSystemTimeFrequencyNV()
{
    clearError();

    if (egl_init_drivers() == EGL_FALSE) {
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);
    }

    EGLuint64NV ret = 0;
    egl_connection_t* const cnx = &gEGLImpl;

    if (cnx->dso && cnx->egl.eglGetSystemTimeFrequencyNV) {
        return cnx->egl.eglGetSystemTimeFrequencyNV();
    }

    return setErrorQuiet(EGL_BAD_DISPLAY, 0);
}

EGLuint64NV eglGetSystemTimeNV()
{
    clearError();

    if (egl_init_drivers() == EGL_FALSE) {
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);
    }

    EGLuint64NV ret = 0;
    egl_connection_t* const cnx = &gEGLImpl;

    if (cnx->dso && cnx->egl.eglGetSystemTimeNV) {
        return cnx->egl.eglGetSystemTimeNV();
    }

    return setErrorQuiet(EGL_BAD_DISPLAY, 0);
}
