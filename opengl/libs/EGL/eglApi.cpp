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
#include "glesv2dbg.h"
#include "hooks.h"

#include "egl_display.h"
#include "egl_impl.h"
#include "egl_object.h"
#include "egl_tls.h"

using namespace android;

// ----------------------------------------------------------------------------

static char const * const sVendorString     = "Android";
static char const * const sVersionString    = "1.4 Android META-EGL";
static char const * const sClientApiString  = "OpenGL ES";
static char const * const sExtensionString  =
        "EGL_KHR_image "
        "EGL_KHR_image_base "
        "EGL_KHR_image_pixmap "
        "EGL_KHR_gl_texture_2D_image "
        "EGL_KHR_gl_texture_cubemap_image "
        "EGL_KHR_gl_renderbuffer_image "
        "EGL_KHR_fence_sync "
        "EGL_ANDROID_image_native_buffer "
        "EGL_ANDROID_swap_rectangle "
        "EGL_NV_system_time "
        ;

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
    { "eglSetSwapRectangleANDROID",
            (__eglMustCastToProperFunctionPointerType)&eglSetSwapRectangleANDROID },
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

template<typename T>
static __attribute__((noinline))
int binarySearch(T const sortedArray[], int first, int last, T key) {
    while (first <= last) {
        int mid = (first + last) / 2;
        if (sortedArray[mid] < key) {
            first = mid + 1;
        } else if (key < sortedArray[mid]) {
            last = mid - 1;
        } else {
            return mid;
        }
    }
    return -1;
}

// ----------------------------------------------------------------------------

namespace android {
extern void setGLHooksThreadSpecific(gl_hooks_t const *value);
extern EGLBoolean egl_init_drivers();
extern const __eglMustCastToProperFunctionPointerType gExtensionForwarders[MAX_NUMBER_OF_GL_EXTENSIONS];
extern int gEGLDebugLevel;
extern gl_hooks_t gHooksTrace;
extern gl_hooks_t gHooksDebug;
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

    GLint numConfigs = dp->numTotalConfigs;
    if (!configs) {
        *num_config = numConfigs;
        return EGL_TRUE;
    }

    GLint n = 0;
    for (intptr_t i=0 ; i<dp->numTotalConfigs && config_size ; i++) {
        *configs++ = EGLConfig(i);
        config_size--;
        n++;
    }
    
    *num_config = n;
    return EGL_TRUE;
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

    EGLint n;
    EGLBoolean res = EGL_FALSE;
    *num_config = 0;

    
    // It is unfortunate, but we need to remap the EGL_CONFIG_IDs, 
    // to do this, we have to go through the attrib_list array once
    // to figure out both its size and if it contains an EGL_CONFIG_ID
    // key. If so, the full array is copied and patched.
    // NOTE: we assume that there can be only one occurrence
    // of EGL_CONFIG_ID.
    
    EGLint patch_index = -1;
    GLint attr;
    size_t size = 0;
    if (attrib_list) {
        while ((attr=attrib_list[size]) != EGL_NONE) {
            if (attr == EGL_CONFIG_ID)
                patch_index = size;
            size += 2;
        }
    }
    if (patch_index >= 0) {
        size += 2; // we need copy the sentinel as well
        EGLint* new_list = (EGLint*)malloc(size*sizeof(EGLint));
        if (new_list == 0)
            return setError(EGL_BAD_ALLOC, EGL_FALSE);
        memcpy(new_list, attrib_list, size*sizeof(EGLint));

        // patch the requested EGL_CONFIG_ID
        bool found = false;
        EGLConfig ourConfig(0);
        EGLint& configId(new_list[patch_index+1]);
        for (intptr_t i=0 ; i<dp->numTotalConfigs ; i++) {
            if (dp->configs[i].configId == configId) {
                ourConfig = EGLConfig(i);
                configId = dp->configs[i].implConfigId;
                found = true;
                break;
            }
        }

        egl_connection_t* const cnx = &gEGLImpl[dp->configs[intptr_t(ourConfig)].impl];
        if (found && cnx->dso) {
            // and switch to the new list
            attrib_list = const_cast<const EGLint *>(new_list);

            // At this point, the only configuration that can match is
            // dp->configs[i][index], however, we don't know if it would be
            // rejected because of the other attributes, so we do have to call
            // cnx->egl.eglChooseConfig() -- but we don't have to loop
            // through all the EGLimpl[].
            // We also know we can only get a single config back, and we know
            // which one.

            res = cnx->egl.eglChooseConfig(
                    dp->disp[ dp->configs[intptr_t(ourConfig)].impl ].dpy,
                    attrib_list, configs, config_size, &n);
            if (res && n>0) {
                // n has to be 0 or 1, by construction, and we already know
                // which config it will return (since there can be only one).
                if (configs) {
                    configs[0] = ourConfig;
                }
                *num_config = 1;
            }
        }

        free(const_cast<EGLint *>(attrib_list));
        return res;
    }


    for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso) {
            if (cnx->egl.eglChooseConfig(
                    dp->disp[i].dpy, attrib_list, configs, config_size, &n)) {
                if (configs) {
                    // now we need to convert these client EGLConfig to our
                    // internal EGLConfig format.
                    // This is done in O(n Log(n)) time.
                    for (int j=0 ; j<n ; j++) {
                        egl_config_t key(i, configs[j]);
                        intptr_t index = binarySearch<egl_config_t>(
                                dp->configs, 0, dp->numTotalConfigs, key);
                        if (index >= 0) {
                            configs[j] = EGLConfig(index);
                        } else {
                            return setError(EGL_BAD_CONFIG, EGL_FALSE);
                        }
                    }
                    configs += n;
                    config_size -= n;
                }
                *num_config += n;
                res = EGL_TRUE;
            }
        }
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
    
    if (attribute == EGL_CONFIG_ID) {
        *value = dp->configs[intptr_t(config)].configId;
        return EGL_TRUE;
    }
    return cnx->egl.eglGetConfigAttrib(
            dp->disp[ dp->configs[intptr_t(config)].impl ].dpy,
            dp->configs[intptr_t(config)].config, attribute, value);
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
        EGLDisplay iDpy = dp->disp[ dp->configs[intptr_t(config)].impl ].dpy;
        EGLConfig iConfig = dp->configs[intptr_t(config)].config;
        EGLint format;

        if (native_window_api_connect(window, NATIVE_WINDOW_API_EGL) != OK) {
            LOGE("EGLNativeWindowType %p already connected to another API",
                    window);
            return setError(EGL_BAD_NATIVE_WINDOW, EGL_NO_SURFACE);
        }

        // set the native window's buffers format to match this config
        if (cnx->egl.eglGetConfigAttrib(iDpy,
                iConfig, EGL_NATIVE_VISUAL_ID, &format)) {
            if (format != 0) {
                int err = native_window_set_buffers_format(window, format);
                if (err != 0) {
                    LOGE("error setting native window pixel format: %s (%d)",
                            strerror(-err), err);
                    native_window_api_disconnect(window, NATIVE_WINDOW_API_EGL);
                    return setError(EGL_BAD_NATIVE_WINDOW, EGL_NO_SURFACE);
                }
            }
        }

        EGLSurface surface = cnx->egl.eglCreateWindowSurface(
                iDpy, iConfig, window, attrib_list);
        if (surface != EGL_NO_SURFACE) {
            egl_surface_t* s = new egl_surface_t(dpy, config, window, surface,
                    dp->configs[intptr_t(config)].impl, cnx);
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
                dp->disp[ dp->configs[intptr_t(config)].impl ].dpy,
                dp->configs[intptr_t(config)].config, pixmap, attrib_list);
        if (surface != EGL_NO_SURFACE) {
            egl_surface_t* s = new egl_surface_t(dpy, config, NULL, surface,
                    dp->configs[intptr_t(config)].impl, cnx);
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
                dp->disp[ dp->configs[intptr_t(config)].impl ].dpy,
                dp->configs[intptr_t(config)].config, attrib_list);
        if (surface != EGL_NO_SURFACE) {
            egl_surface_t* s = new egl_surface_t(dpy, config, NULL, surface,
                    dp->configs[intptr_t(config)].impl, cnx);
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

    SurfaceRef _s(surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t * const s = get_surface(surface);
    EGLBoolean result = s->cnx->egl.eglDestroySurface(
            dp->disp[s->impl].dpy, s->surface);
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

    SurfaceRef _s(surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    EGLBoolean result(EGL_TRUE);
    if (attribute == EGL_CONFIG_ID) {
        // We need to remap EGL_CONFIG_IDs
        *value = dp->configs[intptr_t(s->config)].configId;
    } else {
        result = s->cnx->egl.eglQuerySurface(
                dp->disp[s->impl].dpy, s->surface, attribute, value);
    }

    return result;
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
                dp->disp[ dp->configs[intptr_t(config)].impl ].dpy,
                dp->configs[intptr_t(config)].config,
                share_list, attrib_list);
        if (context != EGL_NO_CONTEXT) {
            // figure out if it's a GLESv1 or GLESv2
            int version = 0;
            if (attrib_list) {
                while (*attrib_list != EGL_NONE) {
                    GLint attr = *attrib_list++;
                    GLint value = *attrib_list++;
                    if (attr == EGL_CONTEXT_CLIENT_VERSION) {
                        if (value == 1) {
                            version = GLESv1_INDEX;
                        } else if (value == 2) {
                            version = GLESv2_INDEX;
                        }
                    }
                };
            }
            egl_context_t* c = new egl_context_t(dpy, context, config,
                    dp->configs[intptr_t(config)].impl, cnx, version);
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

    ContextRef _c(ctx);
    if (!_c.get())
        return setError(EGL_BAD_CONTEXT, EGL_FALSE);
    
    egl_context_t * const c = get_context(ctx);
    EGLBoolean result = c->cnx->egl.eglDestroyContext(
            dp->disp[c->impl].dpy, c->context);
    if (result == EGL_TRUE) {
        _c.terminate();
    }
    return result;
}

static void loseCurrent(egl_context_t * cur_c)
{
    if (cur_c) {
        egl_surface_t * cur_r = get_surface(cur_c->read);
        egl_surface_t * cur_d = get_surface(cur_c->draw);

        // by construction, these are either 0 or valid (possibly terminated)
        // it should be impossible for these to be invalid
        ContextRef _cur_c(cur_c);
        SurfaceRef _cur_r(cur_r);
        SurfaceRef _cur_d(cur_d);

        cur_c->read = NULL;
        cur_c->draw = NULL;

        _cur_c.release();
        _cur_r.release();
        _cur_d.release();
    }
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
    ContextRef _c(ctx);
    SurfaceRef _d(draw);
    SurfaceRef _r(read);

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
        // make sure the EGLContext and EGLSurface passed in are for
        // the same driver
        if (c && d->impl != c->impl)
            return setError(EGL_BAD_MATCH, EGL_FALSE);
        impl_draw = d->surface;
    }

    // retrieve the underlying implementation's read EGLSurface
    if (read != EGL_NO_SURFACE) {
        r = get_surface(read);
        // make sure the EGLContext and EGLSurface passed in are for
        // the same driver
        if (c && r->impl != c->impl)
            return setError(EGL_BAD_MATCH, EGL_FALSE);
        impl_read = r->surface;
    }

    EGLBoolean result;

    if (c) {
        result = c->cnx->egl.eglMakeCurrent(
                dp->disp[c->impl].dpy, impl_draw, impl_read, impl_ctx);
    } else {
        result = cur_c->cnx->egl.eglMakeCurrent(
                dp->disp[cur_c->impl].dpy, impl_draw, impl_read, impl_ctx);
    }

    if (result == EGL_TRUE) {

        loseCurrent(cur_c);

        if (ctx != EGL_NO_CONTEXT) {
            setGLHooksThreadSpecific(c->cnx->hooks[c->version]);
            egl_tls_t::setContext(ctx);
            if (gEGLDebugLevel > 0) {
                CreateDbgContext(c->version, c->cnx->hooks[c->version]);
            }
            _c.acquire();
            _r.acquire();
            _d.acquire();
            c->read = read;
            c->draw = draw;
        } else {
            setGLHooksThreadSpecific(&gHooksNoContext);
            egl_tls_t::setContext(EGL_NO_CONTEXT);
        }
    } else {
        // this will LOGE the error
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

    ContextRef _c(ctx);
    if (!_c.get()) return setError(EGL_BAD_CONTEXT, EGL_FALSE);

    egl_context_t * const c = get_context(ctx);

    EGLBoolean result(EGL_TRUE);
    if (attribute == EGL_CONFIG_ID) {
        *value = dp->configs[intptr_t(c->config)].configId;
    } else {
        // We need to remap EGL_CONFIG_IDs
        result = c->cnx->egl.eglQueryContext(
                dp->disp[c->impl].dpy, c->context, attribute, value);
    }

    return result;
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
    // could be called before eglInitialize(), but we wouldn't have a context
    // then, and this function would return GL_TRUE, which isn't wrong.

    clearError();

    EGLBoolean res = EGL_TRUE;
    EGLContext ctx = getContext();
    if (ctx) {
        egl_context_t const * const c = get_context(ctx);
        if (!c) return setError(EGL_BAD_CONTEXT, EGL_FALSE);
        if (uint32_t(c->impl)>=2)
            return setError(EGL_BAD_CONTEXT, EGL_FALSE);
        egl_connection_t* const cnx = &gEGLImpl[c->impl];
        if (!cnx->dso) 
            return setError(EGL_BAD_CONTEXT, EGL_FALSE);
        res = cnx->egl.eglWaitGL();
    }
    return res;
}

EGLBoolean eglWaitNative(EGLint engine)
{
    // could be called before eglInitialize(), but we wouldn't have a context
    // then, and this function would return GL_TRUE, which isn't wrong.

    clearError();

    EGLBoolean res = EGL_TRUE;
    EGLContext ctx = getContext();
    if (ctx) {
        egl_context_t const * const c = get_context(ctx);
        if (!c) return setError(EGL_BAD_CONTEXT, EGL_FALSE);
        if (uint32_t(c->impl)>=2)
            return setError(EGL_BAD_CONTEXT, EGL_FALSE);
        egl_connection_t* const cnx = &gEGLImpl[c->impl];
        if (!cnx->dso) 
            return setError(EGL_BAD_CONTEXT, EGL_FALSE);
        res = cnx->egl.eglWaitNative(engine);
    }
    return res;
}

EGLint eglGetError(void)
{
    EGLint result = EGL_SUCCESS;
    EGLint err;
    for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
        err = EGL_SUCCESS;
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso)
            err = cnx->egl.eglGetError();
        if (err!=EGL_SUCCESS && result==EGL_SUCCESS)
            result = err;
    }
    err = egl_tls_t::getError();
    if (result == EGL_SUCCESS)
        result = err;
    return result;
}

// Note: Similar implementations of these functions also exist in
// gl2.cpp and gl.cpp, and are used by applications that call the
// exported entry points directly.
typedef void (GL_APIENTRYP PFNGLEGLIMAGETARGETTEXTURE2DOESPROC) (GLenum target, GLeglImageOES image);
typedef void (GL_APIENTRYP PFNGLEGLIMAGETARGETRENDERBUFFERSTORAGEOESPROC) (GLenum target, GLeglImageOES image);

static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES_impl = NULL;
static PFNGLEGLIMAGETARGETRENDERBUFFERSTORAGEOESPROC glEGLImageTargetRenderbufferStorageOES_impl = NULL;

static void glEGLImageTargetTexture2DOES_wrapper(GLenum target, GLeglImageOES image)
{
    GLeglImageOES implImage =
        (GLeglImageOES)egl_get_image_for_current_context((EGLImageKHR)image);
    glEGLImageTargetTexture2DOES_impl(target, implImage);
}

static void glEGLImageTargetRenderbufferStorageOES_wrapper(GLenum target, GLeglImageOES image)
{
    GLeglImageOES implImage =
        (GLeglImageOES)egl_get_image_for_current_context((EGLImageKHR)image);
    glEGLImageTargetRenderbufferStorageOES_impl(target, implImage);
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

        LOGE_IF(slot >= MAX_NUMBER_OF_GL_EXTENSIONS,
                "no more slots for eglGetProcAddress(\"%s\")",
                procname);

        if (!addr && (slot < MAX_NUMBER_OF_GL_EXTENSIONS)) {
            bool found = false;
            for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
                egl_connection_t* const cnx = &gEGLImpl[i];
                if (cnx->dso && cnx->egl.eglGetProcAddress) {
                    found = true;
                    // Extensions are independent of the bound context
                    cnx->hooks[GLESv1_INDEX]->ext.extensions[slot] =
                    cnx->hooks[GLESv2_INDEX]->ext.extensions[slot] =
#if EGL_TRACE
                    gHooksDebug.ext.extensions[slot] = gHooksTrace.ext.extensions[slot] =
#endif
                            cnx->egl.eglGetProcAddress(procname);
                }
            }
            if (found) {
                addr = gExtensionForwarders[slot];

                if (!strcmp(procname, "glEGLImageTargetTexture2DOES")) {
                    glEGLImageTargetTexture2DOES_impl = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)addr;
                    addr = (__eglMustCastToProperFunctionPointerType)glEGLImageTargetTexture2DOES_wrapper;
                }
                if (!strcmp(procname, "glEGLImageTargetRenderbufferStorageOES")) {
                    glEGLImageTargetRenderbufferStorageOES_impl = (PFNGLEGLIMAGETARGETRENDERBUFFERSTORAGEOESPROC)addr;
                    addr = (__eglMustCastToProperFunctionPointerType)glEGLImageTargetRenderbufferStorageOES_wrapper;
                }

                sGLExtentionMap.add(name, addr);
                sGLExtentionSlot++;
            }
        }

    pthread_mutex_unlock(&sExtensionMapMutex);
    return addr;
}

EGLBoolean eglSwapBuffers(EGLDisplay dpy, EGLSurface draw)
{
    EGLBoolean Debug_eglSwapBuffers(EGLDisplay dpy, EGLSurface draw);
    if (gEGLDebugLevel > 0)
        Debug_eglSwapBuffers(dpy, draw);

    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(draw);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(draw);
    return s->cnx->egl.eglSwapBuffers(dp->disp[s->impl].dpy, s->surface);
}

EGLBoolean eglCopyBuffers(  EGLDisplay dpy, EGLSurface surface,
                            NativePixmapType target)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    return s->cnx->egl.eglCopyBuffers(
            dp->disp[s->impl].dpy, s->surface, target);
}

const char* eglQueryString(EGLDisplay dpy, EGLint name)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return (const char *) NULL;

    switch (name) {
        case EGL_VENDOR:
            return sVendorString;
        case EGL_VERSION:
            return sVersionString;
        case EGL_EXTENSIONS:
            return sExtensionString;
        case EGL_CLIENT_APIS:
            return sClientApiString;
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

    SurfaceRef _s(surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    if (s->cnx->egl.eglSurfaceAttrib) {
        return s->cnx->egl.eglSurfaceAttrib(
                dp->disp[s->impl].dpy, s->surface, attribute, value);
    }
    return setError(EGL_BAD_SURFACE, EGL_FALSE);
}

EGLBoolean eglBindTexImage(
        EGLDisplay dpy, EGLSurface surface, EGLint buffer)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    if (s->cnx->egl.eglBindTexImage) {
        return s->cnx->egl.eglBindTexImage(
                dp->disp[s->impl].dpy, s->surface, buffer);
    }
    return setError(EGL_BAD_SURFACE, EGL_FALSE);
}

EGLBoolean eglReleaseTexImage(
        EGLDisplay dpy, EGLSurface surface, EGLint buffer)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    if (s->cnx->egl.eglReleaseTexImage) {
        return s->cnx->egl.eglReleaseTexImage(
                dp->disp[s->impl].dpy, s->surface, buffer);
    }
    return setError(EGL_BAD_SURFACE, EGL_FALSE);
}

EGLBoolean eglSwapInterval(EGLDisplay dpy, EGLint interval)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    EGLBoolean res = EGL_TRUE;
    for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso) {
            if (cnx->egl.eglSwapInterval) {
                if (cnx->egl.eglSwapInterval(
                        dp->disp[i].dpy, interval) == EGL_FALSE) {
                    res = EGL_FALSE;
                }
            }
        }
    }
    return res;
}


// ----------------------------------------------------------------------------
// EGL 1.2
// ----------------------------------------------------------------------------

EGLBoolean eglWaitClient(void)
{
    clearError();

    // could be called before eglInitialize(), but we wouldn't have a context
    // then, and this function would return GL_TRUE, which isn't wrong.
    EGLBoolean res = EGL_TRUE;
    EGLContext ctx = getContext();
    if (ctx) {
        egl_context_t const * const c = get_context(ctx);
        if (!c) return setError(EGL_BAD_CONTEXT, EGL_FALSE);
        if (uint32_t(c->impl)>=2)
            return setError(EGL_BAD_CONTEXT, EGL_FALSE);
        egl_connection_t* const cnx = &gEGLImpl[c->impl];
        if (!cnx->dso) 
            return setError(EGL_BAD_CONTEXT, EGL_FALSE);
        if (cnx->egl.eglWaitClient) {
            res = cnx->egl.eglWaitClient();
        } else {
            res = cnx->egl.eglWaitGL();
        }
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
    for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso) {
            if (cnx->egl.eglBindAPI) {
                if (cnx->egl.eglBindAPI(api) == EGL_FALSE) {
                    res = EGL_FALSE;
                }
            }
        }
    }
    return res;
}

EGLenum eglQueryAPI(void)
{
    clearError();

    if (egl_init_drivers() == EGL_FALSE) {
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);
    }

    for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso) {
            if (cnx->egl.eglQueryAPI) {
                // the first one we find is okay, because they all
                // should be the same
                return cnx->egl.eglQueryAPI();
            }
        }
    }
    // or, it can only be OpenGL ES
    return EGL_OPENGL_ES_API;
}

EGLBoolean eglReleaseThread(void)
{
    clearError();

    // If there is context bound to the thread, release it
    loseCurrent(get_context(getContext()));

    for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso) {
            if (cnx->egl.eglReleaseThread) {
                cnx->egl.eglReleaseThread();
            }
        }
    }
    egl_tls_t::clearTLS();
    dbgReleaseThread();
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
                dp->disp[ dp->configs[intptr_t(config)].impl ].dpy,
                buftype, buffer,
                dp->configs[intptr_t(config)].config, attrib_list);
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

    SurfaceRef _s(surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    if (s->cnx->egl.eglLockSurfaceKHR) {
        return s->cnx->egl.eglLockSurfaceKHR(
                dp->disp[s->impl].dpy, s->surface, attrib_list);
    }
    return setError(EGL_BAD_DISPLAY, EGL_FALSE);
}

EGLBoolean eglUnlockSurfaceKHR(EGLDisplay dpy, EGLSurface surface)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(surface);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(surface);
    if (s->cnx->egl.eglUnlockSurfaceKHR) {
        return s->cnx->egl.eglUnlockSurfaceKHR(
                dp->disp[s->impl].dpy, s->surface);
    }
    return setError(EGL_BAD_DISPLAY, EGL_FALSE);
}

EGLImageKHR eglCreateImageKHR(EGLDisplay dpy, EGLContext ctx, EGLenum target,
        EGLClientBuffer buffer, const EGLint *attrib_list)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_NO_IMAGE_KHR;

    if (ctx != EGL_NO_CONTEXT) {
        ContextRef _c(ctx);
        if (!_c.get())
            return setError(EGL_BAD_CONTEXT, EGL_NO_IMAGE_KHR);
        egl_context_t * const c = get_context(ctx);
        // since we have an EGLContext, we know which implementation to use
        EGLImageKHR image = c->cnx->egl.eglCreateImageKHR(
                dp->disp[c->impl].dpy, c->context, target, buffer, attrib_list);
        if (image == EGL_NO_IMAGE_KHR)
            return image;
            
        egl_image_t* result = new egl_image_t(dpy, ctx);
        result->images[c->impl] = image;
        return (EGLImageKHR)result;
    } else {
        // EGL_NO_CONTEXT is a valid parameter

        /* Since we don't have a way to know which implementation to call,
         * we're calling all of them. If at least one of the implementation
         * succeeded, this is a success.
         */

        EGLint currentError = eglGetError();

        EGLImageKHR implImages[IMPL_NUM_IMPLEMENTATIONS];
        bool success = false;
        for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
            egl_connection_t* const cnx = &gEGLImpl[i];
            implImages[i] = EGL_NO_IMAGE_KHR;
            if (cnx->dso) {
                if (cnx->egl.eglCreateImageKHR) {
                    implImages[i] = cnx->egl.eglCreateImageKHR(
                            dp->disp[i].dpy, ctx, target, buffer, attrib_list);
                    if (implImages[i] != EGL_NO_IMAGE_KHR) {
                        success = true;
                    }
                }
            }
        }

        if (!success) {
            // failure, if there was an error when we entered this function,
            // the error flag must not be updated.
            // Otherwise, the error is whatever happened in the implementation
            // that faulted.
            if (currentError != EGL_SUCCESS) {
                setError(currentError, EGL_NO_IMAGE_KHR);
            }
            return EGL_NO_IMAGE_KHR;
        } else {
            // In case of success, we need to clear all error flags
            // (especially those caused by the implementation that didn't
            // succeed). TODO: we could avoid this if we knew this was
            // a "full" success (all implementation succeeded).
            eglGetError();
        }

        egl_image_t* result = new egl_image_t(dpy, ctx);
        memcpy(result->images, implImages, sizeof(implImages));
        return (EGLImageKHR)result;
    }
}

EGLBoolean eglDestroyImageKHR(EGLDisplay dpy, EGLImageKHR img)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    ImageRef _i(img);
    if (!_i.get()) return setError(EGL_BAD_PARAMETER, EGL_FALSE);

    egl_image_t* image = get_image(img);
    bool success = false;
    for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (image->images[i] != EGL_NO_IMAGE_KHR) {
            if (cnx->dso) {
                if (cnx->egl.eglDestroyImageKHR) {
                    if (cnx->egl.eglDestroyImageKHR(
                            dp->disp[i].dpy, image->images[i])) {
                        success = true;
                    }
                }
            }
        }
    }
    if (!success)
        return EGL_FALSE;

    _i.terminate();

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

    EGLContext ctx = eglGetCurrentContext();
    ContextRef _c(ctx);
    if (!_c.get())
        return setError(EGL_BAD_CONTEXT, EGL_NO_SYNC_KHR);

    egl_context_t * const c = get_context(ctx);
    EGLSyncKHR result = EGL_NO_SYNC_KHR;
    if (c->cnx->egl.eglCreateSyncKHR) {
        EGLSyncKHR sync = c->cnx->egl.eglCreateSyncKHR(
                dp->disp[c->impl].dpy, type, attrib_list);
        if (sync == EGL_NO_SYNC_KHR)
            return sync;
        result = (egl_sync_t*)new egl_sync_t(dpy, ctx, sync);
    }
    return (EGLSyncKHR)result;
}

EGLBoolean eglDestroySyncKHR(EGLDisplay dpy, EGLSyncKHR sync)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SyncRef _s(sync);
    if (!_s.get()) return setError(EGL_BAD_PARAMETER, EGL_FALSE);
    egl_sync_t* syncObject = get_sync(sync);

    EGLContext ctx = syncObject->context;
    ContextRef _c(ctx);
    if (!_c.get())
        return setError(EGL_BAD_CONTEXT, EGL_FALSE);

    EGLBoolean result = EGL_FALSE;
    egl_context_t * const c = get_context(ctx);
    if (c->cnx->egl.eglDestroySyncKHR) {
        result = c->cnx->egl.eglDestroySyncKHR(
                dp->disp[c->impl].dpy, syncObject->sync);
        if (result)
            _s.terminate();
    }
    return result;
}

EGLint eglClientWaitSyncKHR(EGLDisplay dpy, EGLSyncKHR sync, EGLint flags, EGLTimeKHR timeout)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SyncRef _s(sync);
    if (!_s.get()) return setError(EGL_BAD_PARAMETER, EGL_FALSE);
    egl_sync_t* syncObject = get_sync(sync);

    EGLContext ctx = syncObject->context;
    ContextRef _c(ctx);
    if (!_c.get())
        return setError(EGL_BAD_CONTEXT, EGL_FALSE);

    egl_context_t * const c = get_context(ctx);
    if (c->cnx->egl.eglClientWaitSyncKHR) {
        return c->cnx->egl.eglClientWaitSyncKHR(
                dp->disp[c->impl].dpy, syncObject->sync, flags, timeout);
    }

    return EGL_FALSE;
}

EGLBoolean eglGetSyncAttribKHR(EGLDisplay dpy, EGLSyncKHR sync, EGLint attribute, EGLint *value)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SyncRef _s(sync);
    if (!_s.get())
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);

    egl_sync_t* syncObject = get_sync(sync);
    EGLContext ctx = syncObject->context;
    ContextRef _c(ctx);
    if (!_c.get())
        return setError(EGL_BAD_CONTEXT, EGL_FALSE);

    egl_context_t * const c = get_context(ctx);
    if (c->cnx->egl.eglGetSyncAttribKHR) {
        return c->cnx->egl.eglGetSyncAttribKHR(
                dp->disp[c->impl].dpy, syncObject->sync, attribute, value);
    }

    return EGL_FALSE;
}

// ----------------------------------------------------------------------------
// ANDROID extensions
// ----------------------------------------------------------------------------

EGLBoolean eglSetSwapRectangleANDROID(EGLDisplay dpy, EGLSurface draw,
        EGLint left, EGLint top, EGLint width, EGLint height)
{
    clearError();

    egl_display_t const * const dp = validate_display(dpy);
    if (!dp) return EGL_FALSE;

    SurfaceRef _s(draw);
    if (!_s.get())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);

    egl_surface_t const * const s = get_surface(draw);
    if (s->cnx->egl.eglSetSwapRectangleANDROID) {
        return s->cnx->egl.eglSetSwapRectangleANDROID(
                dp->disp[s->impl].dpy, s->surface, left, top, width, height);
    }
    return setError(EGL_BAD_DISPLAY, NULL);
}

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
    egl_connection_t* const cnx = &gEGLImpl[IMPL_HARDWARE];

    if (cnx->dso) {
        if (cnx->egl.eglGetSystemTimeFrequencyNV) {
            return cnx->egl.eglGetSystemTimeFrequencyNV();
        }
    }

    return setError(EGL_BAD_DISPLAY, 0);;
}

EGLuint64NV eglGetSystemTimeNV()
{
    clearError();

    if (egl_init_drivers() == EGL_FALSE) {
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);
    }

    EGLuint64NV ret = 0;
    egl_connection_t* const cnx = &gEGLImpl[IMPL_HARDWARE];

    if (cnx->dso) {
        if (cnx->egl.eglGetSystemTimeNV) {
            return cnx->egl.eglGetSystemTimeNV();
        }
    }

    return setError(EGL_BAD_DISPLAY, 0);;
}
