/*
 ** Copyright 2011, The Android Open Source Project
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

#ifndef ANDROID_EGL_TLS_H
#define ANDROID_EGL_TLS_H

#include <pthread.h>

#include <EGL/egl.h>

#include "egldefs.h"
#include "hooks.h"

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

class DbgContext;

class egl_tls_t {
    static pthread_key_t sKey;
    static pthread_mutex_t sLockKey;

    EGLint      error;
    EGLContext  ctx;
    EGLBoolean  logCallWithNoContext;

    egl_tls_t();
    static void validateTLSKey();
    static void setErrorEtcImpl(
            const char* caller, int line, EGLint error, bool quiet);

public:
    static egl_tls_t* getTLS();
    static void clearTLS();
    static void clearError();
    static EGLint getError();
    static void setContext(EGLContext ctx);
    static EGLContext getContext();
    static bool logNoContextCall();
    static const char *egl_strerror(EGLint err);

    template<typename T>
    static T setErrorEtc(const char* caller,
            int line, EGLint error, T returnValue, bool quiet = false) {
        setErrorEtcImpl(caller, line, error, quiet);
        return returnValue;
    }
};

#define setError(_e, _r)        \
    egl_tls_t::setErrorEtc(__FUNCTION__, __LINE__, _e, _r)

#define setErrorQuiet(_e, _r)   \
    egl_tls_t::setErrorEtc(__FUNCTION__, __LINE__, _e, _r, true)

// ----------------------------------------------------------------------------

#if EGL_TRACE

extern gl_hooks_t const* getGLTraceThreadSpecific();

#endif

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

#endif // ANDROID_EGL_TLS_H
