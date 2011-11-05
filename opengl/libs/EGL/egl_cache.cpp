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

#include "egl_cache.h"
#include "egl_display.h"
#include "egl_impl.h"
#include "egldefs.h"

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

#define BC_EXT_STR "EGL_ANDROID_blob_cache"

//
// EGL_ANDROID_blob_cache types and functions
//
typedef khronos_ssize_t EGLsizei;

typedef void (*EGLSetBlobFunc) (const void* key, EGLsizei keySize,
        const void* value, EGLsizei valueSize);

typedef EGLsizei (*EGLGetBlobFunc) (const void* key, EGLsizei keySize,
        void* value, EGLsizei valueSize);

typedef void (EGLAPIENTRYP PFNEGLSETBLOBCACHEFUNCSPROC) (EGLDisplay dpy,
        EGLSetBlobFunc set, EGLGetBlobFunc get);

//
// egl_cache_t definition
//
static void setBlob(const void* key, EGLsizei keySize, const void* value,
        EGLsizei valueSize) {
}

static EGLsizei getBlob(const void* key, EGLsizei keySize, void* value,
        EGLsizei valueSize) {
    return 0;
}

egl_cache_t* egl_cache_t::get() {
    static egl_cache_t theCache;
    return &theCache;
}

void egl_cache_t::initialize(egl_display_t *display) {
    for (int i = 0; i < IMPL_NUM_IMPLEMENTATIONS; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso && cnx->major >= 0 && cnx->minor >= 0) {
            const char* exts = display->disp[i].queryString.extensions;
            size_t bcExtLen = strlen(BC_EXT_STR);
            size_t extsLen = strlen(exts);
            bool equal = !strcmp(BC_EXT_STR, exts);
            bool atStart = !strncmp(BC_EXT_STR " ", exts, bcExtLen+1);
            bool atEnd = (bcExtLen+1) < extsLen &&
                    !strcmp(" " BC_EXT_STR, exts + extsLen - (bcExtLen+1));
            bool inMiddle = strstr(" " BC_EXT_STR " ", exts);
            if (equal || atStart || atEnd || inMiddle) {
                PFNEGLSETBLOBCACHEFUNCSPROC eglSetBlobCacheFuncs;
                eglSetBlobCacheFuncs =
                        reinterpret_cast<PFNEGLSETBLOBCACHEFUNCSPROC>(
                            cnx->egl.eglGetProcAddress("eglSetBlobCacheFuncs"));
                if (eglSetBlobCacheFuncs == NULL) {
                    LOGE("EGL_ANDROID_blob_cache advertised by display %d, "
                            "but unable to get eglSetBlobCacheFuncs", i);
                    continue;
                }

                eglSetBlobCacheFuncs(display->disp[i].dpy, setBlob, getBlob);
                EGLint err = cnx->egl.eglGetError();
                if (err != EGL_SUCCESS) {
                    LOGE("eglSetBlobCacheFuncs resulted in an error: %#x",
                            err);
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------
