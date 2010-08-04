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

#ifndef ANDROID_GLES_CM_HOOKS_H
#define ANDROID_GLES_CM_HOOKS_H

#include <ctype.h>
#include <string.h>
#include <errno.h>

#include <pthread.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#if !defined(__arm__)
#define USE_SLOW_BINDING            1
#else
#define USE_SLOW_BINDING            0
#endif
#undef NELEM
#define NELEM(x)                    (sizeof(x)/sizeof(*(x)))
#define MAX_NUMBER_OF_GL_EXTENSIONS 64


#if defined(HAVE_ANDROID_OS) && !USE_SLOW_BINDING && __OPTIMIZE__
#define USE_FAST_TLS_KEY            1
#else
#define USE_FAST_TLS_KEY            0
#endif

#if USE_FAST_TLS_KEY
#   include <bionic_tls.h>  /* special private C library header */
#endif

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

//  EGLDisplay are global, not attached to a given thread
const unsigned int NUM_DISPLAYS = 1;

enum {
    IMPL_HARDWARE = 0,
    IMPL_SOFTWARE,
    IMPL_NUM_IMPLEMENTATIONS
};

enum {
    GLESv1_INDEX = 0,
    GLESv2_INDEX = 1,
};

// ----------------------------------------------------------------------------

// GL / EGL hooks

#undef GL_ENTRY
#undef EGL_ENTRY
#define GL_ENTRY(_r, _api, ...) _r (*_api)(__VA_ARGS__);
#define EGL_ENTRY(_r, _api, ...) _r (*_api)(__VA_ARGS__);

struct egl_t {
    #include "EGL/egl_entries.in"
};

struct gl_hooks_t {
    struct gl_t {
        #include "entries.in"
    } gl;
    struct gl_ext_t {
        __eglMustCastToProperFunctionPointerType extensions[MAX_NUMBER_OF_GL_EXTENSIONS];
    } ext;
};
#undef GL_ENTRY
#undef EGL_ENTRY


// ----------------------------------------------------------------------------

extern gl_hooks_t gHooks[2][IMPL_NUM_IMPLEMENTATIONS];
extern gl_hooks_t gHooksNoContext;
extern pthread_key_t gGLWrapperKey;
extern "C" void gl_unimplemented();

extern char const * const gl_names[];
extern char const * const egl_names[];

// ----------------------------------------------------------------------------

#if USE_FAST_TLS_KEY

// We have a dedicated TLS slot in bionic
static inline gl_hooks_t const * volatile * get_tls_hooks() {
    volatile void *tls_base = __get_tls();
    gl_hooks_t const * volatile * tls_hooks = 
            reinterpret_cast<gl_hooks_t const * volatile *>(tls_base);
    return tls_hooks;
}

static inline void setGlThreadSpecific(gl_hooks_t const *value) {
    gl_hooks_t const * volatile * tls_hooks = get_tls_hooks();
    tls_hooks[TLS_SLOT_OPENGL_API] = value;
}

static gl_hooks_t const* getGlThreadSpecific() {
    gl_hooks_t const * volatile * tls_hooks = get_tls_hooks();
    gl_hooks_t const* hooks = tls_hooks[TLS_SLOT_OPENGL_API];
    if (hooks) return hooks;
    return &gHooksNoContext;
}

#else

static inline void setGlThreadSpecific(gl_hooks_t const *value) {
    pthread_setspecific(gGLWrapperKey, value);
}

static gl_hooks_t const* getGlThreadSpecific() {
    gl_hooks_t const* hooks =  static_cast<gl_hooks_t*>(pthread_getspecific(gGLWrapperKey));
    if (hooks) return hooks;
    return &gHooksNoContext;
}

#endif


// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

#endif /* ANDROID_GLES_CM_HOOKS_H */
