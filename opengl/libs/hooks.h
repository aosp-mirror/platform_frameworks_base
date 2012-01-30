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

// maximum number of GL extensions that can be used simultaneously in
// a given process. this limitation exists because we need to have
// a static function for each extension and currently these static functions
// are generated at compile time.
#define MAX_NUMBER_OF_GL_EXTENSIONS 256


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

EGLAPI void setGlThreadSpecific(gl_hooks_t const *value);
EGLAPI gl_hooks_t const* getGlThreadSpecific();

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

#endif /* ANDROID_GLES_CM_HOOKS_H */
