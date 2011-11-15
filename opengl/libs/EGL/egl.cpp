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

#include <utils/CallStack.h>
#include <utils/String8.h>

#include "egldefs.h"
#include "egl_impl.h"
#include "egl_tls.h"
#include "glesv2dbg.h"
#include "hooks.h"
#include "Loader.h"

#include "egl_display.h"
#include "egl_object.h"

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

egl_connection_t gEGLImpl[IMPL_NUM_IMPLEMENTATIONS];
gl_hooks_t gHooks[2][IMPL_NUM_IMPLEMENTATIONS];
gl_hooks_t gHooksNoContext;
pthread_key_t gGLWrapperKey = -1;

// ----------------------------------------------------------------------------

#if EGL_TRACE

EGLAPI pthread_key_t gGLTraceKey = -1;

// ----------------------------------------------------------------------------

int gEGLDebugLevel;

static int sEGLTraceLevel;
static int sEGLApplicationTraceLevel;

extern gl_hooks_t gHooksTrace;
extern gl_hooks_t gHooksDebug;

static inline void setGlTraceThreadSpecific(gl_hooks_t const *value) {
    pthread_setspecific(gGLTraceKey, value);
}

gl_hooks_t const* getGLTraceThreadSpecific() {
    return static_cast<gl_hooks_t*>(pthread_getspecific(gGLTraceKey));
}

void initEglTraceLevel() {
    char value[PROPERTY_VALUE_MAX];
    property_get("debug.egl.trace", value, "0");
    int propertyLevel = atoi(value);
    int applicationLevel = sEGLApplicationTraceLevel;
    sEGLTraceLevel = propertyLevel > applicationLevel ? propertyLevel : applicationLevel;

    property_get("debug.egl.debug_proc", value, "");
    long pid = getpid();
    char procPath[128] = {};
    sprintf(procPath, "/proc/%ld/cmdline", pid);
    FILE * file = fopen(procPath, "r");
    if (file)
    {
        char cmdline[256] = {};
        if (fgets(cmdline, sizeof(cmdline) - 1, file))
        {
            if (!strcmp(value, cmdline))
                gEGLDebugLevel = 1;
        }
        fclose(file);
    }

    if (gEGLDebugLevel > 0)
    {
        property_get("debug.egl.debug_port", value, "5039");
        const unsigned short port = (unsigned short)atoi(value);
        property_get("debug.egl.debug_forceUseFile", value, "0");
        const bool forceUseFile = (bool)atoi(value);
        property_get("debug.egl.debug_maxFileSize", value, "8");
        const unsigned int maxFileSize = atoi(value) << 20;
        property_get("debug.egl.debug_filePath", value, "/data/local/tmp/dump.gles2dbg");
        StartDebugServer(port, forceUseFile, maxFileSize, value);
    }
}

void setGLHooksThreadSpecific(gl_hooks_t const *value) {
    if (sEGLTraceLevel > 0) {
        setGlTraceThreadSpecific(value);
        setGlThreadSpecific(&gHooksTrace);
    } else if (gEGLDebugLevel > 0 && value != &gHooksNoContext) {
        setGlTraceThreadSpecific(value);
        setGlThreadSpecific(&gHooksDebug);
    } else {
        setGlThreadSpecific(value);
    }
}

/*
 * Global entry point to allow applications to modify their own trace level.
 * The effective trace level is the max of this level and the value of debug.egl.trace.
 */
extern "C"
void setGLTraceLevel(int level) {
    sEGLApplicationTraceLevel = level;
}

#else

void setGLHooksThreadSpecific(gl_hooks_t const *value) {
    setGlThreadSpecific(value);
}

#endif

/*****************************************************************************/

static int gl_no_context() {
    if (egl_tls_t::logNoContextCall()) {
        LOGE("call to OpenGL ES API with no current context "
             "(logged once per thread)");
        char value[PROPERTY_VALUE_MAX];
        property_get("debug.egl.callstack", value, "0");
        if (atoi(value)) {
            CallStack stack;
            stack.update();
            stack.dump();
        }
    }
    return 0;
}

static void early_egl_init(void) 
{
#if !USE_FAST_TLS_KEY
    pthread_key_create(&gGLWrapperKey, NULL);
#endif
#if EGL_TRACE
    pthread_key_create(&gGLTraceKey, NULL);
    initEglTraceLevel();
#endif
    uint32_t addr = (uint32_t)((void*)gl_no_context);
    android_memset32(
            (uint32_t*)(void*)&gHooksNoContext, 
            addr, 
            sizeof(gHooksNoContext));

    setGLHooksThreadSpecific(&gHooksNoContext);
}

static pthread_once_t once_control = PTHREAD_ONCE_INIT;
static int sEarlyInitState = pthread_once(&once_control, &early_egl_init);

// ----------------------------------------------------------------------------

egl_display_t* validate_display(EGLDisplay dpy) {
    egl_display_t * const dp = get_display(dpy);
    if (!dp)
        return setError(EGL_BAD_DISPLAY, (egl_display_t*)NULL);
    if (!dp->isReady())
        return setError(EGL_NOT_INITIALIZED, (egl_display_t*)NULL);

    return dp;
}

egl_connection_t* validate_display_config(EGLDisplay dpy, EGLConfig config,
        egl_display_t const*& dp) {
    dp = validate_display(dpy);
    if (!dp)
        return (egl_connection_t*) NULL;

    if (intptr_t(config) >= dp->numTotalConfigs) {
        return setError(EGL_BAD_CONFIG, (egl_connection_t*)NULL);
    }
    egl_connection_t* const cnx = &gEGLImpl[dp->configs[intptr_t(config)].impl];
    if (cnx->dso == 0) {
        return setError(EGL_BAD_CONFIG, (egl_connection_t*)NULL);
    }
    return cnx;
}

// ----------------------------------------------------------------------------

EGLImageKHR egl_get_image_for_current_context(EGLImageKHR image)
{
    EGLContext context = egl_tls_t::getContext();
    if (context == EGL_NO_CONTEXT || image == EGL_NO_IMAGE_KHR)
        return EGL_NO_IMAGE_KHR;

    egl_context_t const * const c = get_context(context);
    if (c == NULL) // this should never happen, by construction
        return EGL_NO_IMAGE_KHR;

    egl_display_t* display = egl_display_t::get(c->dpy);
    if (display == NULL) // this should never happen, by construction
        return EGL_NO_IMAGE_KHR;

    ImageRef _i(display, image);
    if (!_i.get())
        return EGL_NO_IMAGE_KHR;

    // here we don't validate the context because if it's been marked for
    // termination, this call should still succeed since it's internal to
    // EGL.

    egl_image_t const * const i = get_image(image);
    return i->images[c->impl];
}

// ----------------------------------------------------------------------------

// this mutex protects:
//    d->disp[]
//    egl_init_drivers_locked()
//
static EGLBoolean egl_init_drivers_locked() {
    if (sEarlyInitState) {
        // initialized by static ctor. should be set here.
        return EGL_FALSE;
    }

    // get our driver loader
    Loader& loader(Loader::getInstance());

    // dynamically load all our EGL implementations
    egl_connection_t* cnx;

    cnx = &gEGLImpl[IMPL_SOFTWARE];
    if (cnx->dso == 0) {
        cnx->hooks[GLESv1_INDEX] = &gHooks[GLESv1_INDEX][IMPL_SOFTWARE];
        cnx->hooks[GLESv2_INDEX] = &gHooks[GLESv2_INDEX][IMPL_SOFTWARE];
        cnx->dso = loader.open(EGL_DEFAULT_DISPLAY, 0, cnx);
    }

    cnx = &gEGLImpl[IMPL_HARDWARE];
    if (cnx->dso == 0) {
        char value[PROPERTY_VALUE_MAX];
        property_get("debug.egl.hw", value, "1");
        if (atoi(value) != 0) {
            cnx->hooks[GLESv1_INDEX] = &gHooks[GLESv1_INDEX][IMPL_HARDWARE];
            cnx->hooks[GLESv2_INDEX] = &gHooks[GLESv2_INDEX][IMPL_HARDWARE];
            cnx->dso = loader.open(EGL_DEFAULT_DISPLAY, 1, cnx);
        } else {
            LOGD("3D hardware acceleration is disabled");
        }
    }

    if (!gEGLImpl[IMPL_SOFTWARE].dso && !gEGLImpl[IMPL_HARDWARE].dso) {
        return EGL_FALSE;
    }

    return EGL_TRUE;
}

static pthread_mutex_t sInitDriverMutex = PTHREAD_MUTEX_INITIALIZER;

EGLBoolean egl_init_drivers() {
    EGLBoolean res;
    pthread_mutex_lock(&sInitDriverMutex);
    res = egl_init_drivers_locked();
    pthread_mutex_unlock(&sInitDriverMutex);
    return res;
}

void gl_unimplemented() {
    LOGE("called unimplemented OpenGL ES API");
}

// ----------------------------------------------------------------------------

#if USE_FAST_TLS_KEY

// We have a dedicated TLS slot in bionic
static inline gl_hooks_t const * volatile * get_tls_hooks() {
    volatile void *tls_base = __get_tls();
    gl_hooks_t const * volatile * tls_hooks =
            reinterpret_cast<gl_hooks_t const * volatile *>(tls_base);
    return tls_hooks;
}

void setGlThreadSpecific(gl_hooks_t const *value) {
    gl_hooks_t const * volatile * tls_hooks = get_tls_hooks();
    tls_hooks[TLS_SLOT_OPENGL_API] = value;
}

gl_hooks_t const* getGlThreadSpecific() {
    gl_hooks_t const * volatile * tls_hooks = get_tls_hooks();
    gl_hooks_t const* hooks = tls_hooks[TLS_SLOT_OPENGL_API];
    if (hooks) return hooks;
    return &gHooksNoContext;
}

#else

void setGlThreadSpecific(gl_hooks_t const *value) {
    pthread_setspecific(gGLWrapperKey, value);
}

gl_hooks_t const* getGlThreadSpecific() {
    gl_hooks_t const* hooks =  static_cast<gl_hooks_t*>(pthread_getspecific(gGLWrapperKey));
    if (hooks) return hooks;
    return &gHooksNoContext;
}

#endif

// ----------------------------------------------------------------------------
// GL / EGL hooks
// ----------------------------------------------------------------------------

#undef GL_ENTRY
#undef EGL_ENTRY
#define GL_ENTRY(_r, _api, ...) #_api,
#define EGL_ENTRY(_r, _api, ...) #_api,

char const * const gl_names[] = {
    #include "entries.in"
    NULL
};

char const * const egl_names[] = {
    #include "egl_entries.in"
    NULL
};

#undef GL_ENTRY
#undef EGL_ENTRY


// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

