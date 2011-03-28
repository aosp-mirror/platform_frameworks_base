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

#include <EGL/egl.h>

#include "glesv2dbg.h"

namespace android
{
struct tls_t {
    tls_t() : error(EGL_SUCCESS), ctx(0), logCallWithNoContext(EGL_TRUE), dbg(0) { }
    ~tls_t() {
        if (dbg)
            DestroyDbgContext(dbg);
    }

    EGLint      error;
    EGLContext  ctx;
    EGLBoolean  logCallWithNoContext;
    DbgContext* dbg;
};
}

#endif
