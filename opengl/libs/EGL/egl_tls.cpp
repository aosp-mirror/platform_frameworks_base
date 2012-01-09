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

#include <stdlib.h>
#include <pthread.h>

#include <cutils/log.h>
#include <cutils/properties.h>

#include <utils/CallStack.h>

#include <EGL/egl.h>

#include "egl_tls.h"


namespace android {

pthread_key_t egl_tls_t::sKey = -1;
pthread_mutex_t egl_tls_t::sLockKey = PTHREAD_MUTEX_INITIALIZER;

egl_tls_t::egl_tls_t()
    : error(EGL_SUCCESS), ctx(0), logCallWithNoContext(EGL_TRUE) {
}

const char *egl_tls_t::egl_strerror(EGLint err) {
    switch (err) {
        case EGL_SUCCESS:               return "EGL_SUCCESS";
        case EGL_NOT_INITIALIZED:       return "EGL_NOT_INITIALIZED";
        case EGL_BAD_ACCESS:            return "EGL_BAD_ACCESS";
        case EGL_BAD_ALLOC:             return "EGL_BAD_ALLOC";
        case EGL_BAD_ATTRIBUTE:         return "EGL_BAD_ATTRIBUTE";
        case EGL_BAD_CONFIG:            return "EGL_BAD_CONFIG";
        case EGL_BAD_CONTEXT:           return "EGL_BAD_CONTEXT";
        case EGL_BAD_CURRENT_SURFACE:   return "EGL_BAD_CURRENT_SURFACE";
        case EGL_BAD_DISPLAY:           return "EGL_BAD_DISPLAY";
        case EGL_BAD_MATCH:             return "EGL_BAD_MATCH";
        case EGL_BAD_NATIVE_PIXMAP:     return "EGL_BAD_NATIVE_PIXMAP";
        case EGL_BAD_NATIVE_WINDOW:     return "EGL_BAD_NATIVE_WINDOW";
        case EGL_BAD_PARAMETER:         return "EGL_BAD_PARAMETER";
        case EGL_BAD_SURFACE:           return "EGL_BAD_SURFACE";
        case EGL_CONTEXT_LOST:          return "EGL_CONTEXT_LOST";
        default: return "UNKNOWN";
    }
}

void egl_tls_t::validateTLSKey()
{
    if (sKey == -1) {
        pthread_mutex_lock(&sLockKey);
        if (sKey == -1)
            pthread_key_create(&sKey, NULL);
        pthread_mutex_unlock(&sLockKey);
    }
}

void egl_tls_t::setErrorEtcImpl(
        const char* caller, int line, EGLint error, bool quiet) {
    validateTLSKey();
    egl_tls_t* tls = getTLS();
    if (tls->error != error) {
        if (!quiet) {
            ALOGE("%s:%d error %x (%s)",
                    caller, line, error, egl_strerror(error));
            char value[PROPERTY_VALUE_MAX];
            property_get("debug.egl.callstack", value, "0");
            if (atoi(value)) {
                CallStack stack;
                stack.update();
                stack.dump();
            }
        }
        tls->error = error;
    }
}

bool egl_tls_t::logNoContextCall() {
    egl_tls_t* tls = getTLS();
    if (tls->logCallWithNoContext == true) {
        tls->logCallWithNoContext = false;
        return true;
    }
    return false;
}

egl_tls_t* egl_tls_t::getTLS() {
    egl_tls_t* tls = (egl_tls_t*)pthread_getspecific(sKey);
    if (tls == 0) {
        tls = new egl_tls_t;
        pthread_setspecific(sKey, tls);
    }
    return tls;
}

void egl_tls_t::clearTLS() {
    if (sKey != -1) {
        egl_tls_t* tls = (egl_tls_t*)pthread_getspecific(sKey);
        if (tls) {
            delete tls;
            pthread_setspecific(sKey, 0);
        }
    }
}

void egl_tls_t::clearError() {
    // This must clear the error from all the underlying EGL implementations as
    // well as the EGL wrapper layer.
    eglGetError();
}

EGLint egl_tls_t::getError() {
    if (sKey == -1)
        return EGL_SUCCESS;
    egl_tls_t* tls = (egl_tls_t*)pthread_getspecific(sKey);
    if (!tls) return EGL_SUCCESS;
    EGLint error = tls->error;
    tls->error = EGL_SUCCESS;
    return error;
}

void egl_tls_t::setContext(EGLContext ctx) {
    validateTLSKey();
    getTLS()->ctx = ctx;
}

EGLContext egl_tls_t::getContext() {
    if (sKey == -1)
        return EGL_NO_CONTEXT;
    egl_tls_t* tls = (egl_tls_t *)pthread_getspecific(sKey);
    if (!tls) return EGL_NO_CONTEXT;
    return tls->ctx;
}


} // namespace android
