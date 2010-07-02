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
#include <errno.h>
#include <dlfcn.h>

#include <sys/ioctl.h>

#if HAVE_ANDROID_OS
#include <linux/android_pmem.h>
#endif

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include <cutils/log.h>
#include <cutils/atomic.h>
#include <cutils/properties.h>
#include <cutils/memory.h>

#include <utils/SortedVector.h>

#include "hooks.h"
#include "egl_impl.h"
#include "Loader.h"

#define MAKE_CONFIG(_impl, _index)  ((EGLConfig)(((_impl)<<24) | (_index)))
#define setError(_e, _r) setErrorEtc(__FUNCTION__, __LINE__, _e, _r)

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

#define VERSION_MAJOR 1
#define VERSION_MINOR 4
static char const * const gVendorString     = "Android";
static char const * const gVersionString    = "1.4 Android META-EGL";
static char const * const gClientApiString  = "OpenGL ES";
static char const * const gExtensionString  = 
        "EGL_KHR_image "
        "EGL_KHR_image_base "
        "EGL_KHR_image_pixmap "
        "EGL_ANDROID_image_native_buffer "
        "EGL_ANDROID_swap_rectangle "
        "EGL_ANDROID_get_render_buffer "
        ;

// ----------------------------------------------------------------------------

class egl_object_t {
    static SortedVector<egl_object_t*> sObjects;
    static Mutex sLock;

            volatile int32_t  terminated;
    mutable volatile int32_t  count;

public:
    egl_object_t() : terminated(0), count(1) { 
        Mutex::Autolock _l(sLock);
        sObjects.add(this);
    }

    inline bool isAlive() const { return !terminated; }

private:
    bool get() {
        Mutex::Autolock _l(sLock);
        if (egl_object_t::sObjects.indexOf(this) >= 0) {
            android_atomic_inc(&count);
            return true;
        }
        return false;
    }

    bool put() {
        Mutex::Autolock _l(sLock);
        if (android_atomic_dec(&count) == 1) {
            sObjects.remove(this);
            return true;
        }
        return false;
    }    

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

SortedVector<egl_object_t*> egl_object_t::sObjects;
Mutex egl_object_t::sLock;

struct egl_display_t {
    enum { NOT_INITIALIZED, INITIALIZED, TERMINATED };
    
    struct strings_t {
        char const * vendor;
        char const * version;
        char const * clientApi;
        char const * extensions;
    };

    struct DisplayImpl {
        DisplayImpl() : dpy(EGL_NO_DISPLAY), config(0),
                        state(NOT_INITIALIZED), numConfigs(0) { }
        EGLDisplay  dpy;
        EGLConfig*  config;
        EGLint      state;
        EGLint      numConfigs;
        strings_t   queryString;
    };

    uint32_t    magic;
    DisplayImpl disp[IMPL_NUM_IMPLEMENTATIONS];
    EGLint      numTotalConfigs;
    uint32_t    refs;
    Mutex       lock;
    
    egl_display_t() : magic('_dpy'), numTotalConfigs(0) { }
    ~egl_display_t() { magic = 0; }
    inline bool isValid() const { return magic == '_dpy'; }
    inline bool isAlive() const { return isValid(); }
};

struct egl_surface_t : public egl_object_t
{
    typedef egl_object_t::LocalRef<egl_surface_t, EGLSurface> Ref;

    egl_surface_t(EGLDisplay dpy, EGLSurface surface,
            int impl, egl_connection_t const* cnx) 
    : dpy(dpy), surface(surface), impl(impl), cnx(cnx) {
    }
    ~egl_surface_t() {
    }
    EGLDisplay                  dpy;
    EGLSurface                  surface;
    int                         impl;
    egl_connection_t const*     cnx;
};

struct egl_context_t : public egl_object_t
{
    typedef egl_object_t::LocalRef<egl_context_t, EGLContext> Ref;
    
    egl_context_t(EGLDisplay dpy, EGLContext context,
            int impl, egl_connection_t const* cnx, int version) 
    : dpy(dpy), context(context), read(0), draw(0), impl(impl), cnx(cnx),
      version(version)
    {
    }
    EGLDisplay                  dpy;
    EGLContext                  context;
    EGLSurface                  read;
    EGLSurface                  draw;
    int                         impl;
    egl_connection_t const*     cnx;
    int                         version;
};

struct egl_image_t : public egl_object_t
{
    typedef egl_object_t::LocalRef<egl_image_t, EGLImageKHR> Ref;

    egl_image_t(EGLDisplay dpy, EGLContext context)
        : dpy(dpy), context(context)
    {
        memset(images, 0, sizeof(images));
    }
    EGLDisplay dpy;
    EGLConfig context;
    EGLImageKHR images[IMPL_NUM_IMPLEMENTATIONS];
};

typedef egl_surface_t::Ref  SurfaceRef;
typedef egl_context_t::Ref  ContextRef;
typedef egl_image_t::Ref    ImageRef;

struct tls_t
{
    tls_t() : error(EGL_SUCCESS), ctx(0), logCallWithNoContext(EGL_TRUE) { }
    EGLint      error;
    EGLContext  ctx;
    EGLBoolean  logCallWithNoContext;
};


// ----------------------------------------------------------------------------

egl_connection_t gEGLImpl[IMPL_NUM_IMPLEMENTATIONS];
static egl_display_t gDisplay[NUM_DISPLAYS];
static pthread_mutex_t gThreadLocalStorageKeyMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_key_t gEGLThreadLocalStorageKey = -1;

// ----------------------------------------------------------------------------

EGLAPI gl_hooks_t gHooks[2][IMPL_NUM_IMPLEMENTATIONS];
EGLAPI gl_hooks_t gHooksNoContext;
EGLAPI pthread_key_t gGLWrapperKey = -1;

// ----------------------------------------------------------------------------

static __attribute__((noinline))
const char *egl_strerror(EGLint err)
{
    switch (err){
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

static __attribute__((noinline))
void clearTLS() {
    if (gEGLThreadLocalStorageKey != -1) {
        tls_t* tls = (tls_t*)pthread_getspecific(gEGLThreadLocalStorageKey);
        if (tls) {
            delete tls;
            pthread_setspecific(gEGLThreadLocalStorageKey, 0);
        }
    }
}

static tls_t* getTLS()
{
    tls_t* tls = (tls_t*)pthread_getspecific(gEGLThreadLocalStorageKey);
    if (tls == 0) {
        tls = new tls_t;
        pthread_setspecific(gEGLThreadLocalStorageKey, tls);
    }
    return tls;
}

template<typename T>
static __attribute__((noinline))
T setErrorEtc(const char* caller, int line, EGLint error, T returnValue) {
    if (gEGLThreadLocalStorageKey == -1) {
        pthread_mutex_lock(&gThreadLocalStorageKeyMutex);
        if (gEGLThreadLocalStorageKey == -1)
            pthread_key_create(&gEGLThreadLocalStorageKey, NULL);
        pthread_mutex_unlock(&gThreadLocalStorageKeyMutex);
    }
    tls_t* tls = getTLS();
    if (tls->error != error) {
        LOGE("%s:%d error %x (%s)", caller, line, error, egl_strerror(error));
        tls->error = error;
    }
    return returnValue;
}

static __attribute__((noinline))
GLint getError() {
    if (gEGLThreadLocalStorageKey == -1)
        return EGL_SUCCESS;
    tls_t* tls = (tls_t*)pthread_getspecific(gEGLThreadLocalStorageKey);
    if (!tls) return EGL_SUCCESS;
    GLint error = tls->error;
    tls->error = EGL_SUCCESS;
    return error;
}

static __attribute__((noinline))
void setContext(EGLContext ctx) {
    if (gEGLThreadLocalStorageKey == -1) {
        pthread_mutex_lock(&gThreadLocalStorageKeyMutex);
        if (gEGLThreadLocalStorageKey == -1)
            pthread_key_create(&gEGLThreadLocalStorageKey, NULL);
        pthread_mutex_unlock(&gThreadLocalStorageKeyMutex);
    }
    tls_t* tls = getTLS();
    tls->ctx = ctx;
}

static __attribute__((noinline))
EGLContext getContext() {
    if (gEGLThreadLocalStorageKey == -1)
        return EGL_NO_CONTEXT;
    tls_t* tls = (tls_t*)pthread_getspecific(gEGLThreadLocalStorageKey);
    if (!tls) return EGL_NO_CONTEXT;
    return tls->ctx;
}

/*****************************************************************************/

template<typename T>
static __attribute__((noinline))
int binarySearch(
        T const sortedArray[], int first, int last, T key)
{
    while (first <= last) {
        int mid = (first + last) / 2;
        if (key > sortedArray[mid]) { 
            first = mid + 1;
        } else if (key < sortedArray[mid]) { 
            last = mid - 1;
        } else {
            return mid;
        }
    }
    return -1;
}

static EGLint configToUniqueId(egl_display_t const* dp, int i, int index) 
{
    // NOTE: this mapping works only if we have no more than two EGLimpl
    return (i>0 ? dp->disp[0].numConfigs : 0) + index;
}

static void uniqueIdToConfig(egl_display_t const* dp, EGLint configId,
        int& i, int& index) 
{
    // NOTE: this mapping works only if we have no more than two EGLimpl
    size_t numConfigs = dp->disp[0].numConfigs;
    i = configId / numConfigs;
    index = configId % numConfigs;
}

static int cmp_configs(const void* a, const void *b)
{
    EGLConfig c0 = *(EGLConfig const *)a;
    EGLConfig c1 = *(EGLConfig const *)b;
    return c0<c1 ? -1 : (c0>c1 ? 1 : 0);
}

struct extention_map_t {
    const char* name;
    __eglMustCastToProperFunctionPointerType address;
};

static const extention_map_t gExtentionMap[] = {
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
    { "eglGetRenderBufferANDROID", 
            (__eglMustCastToProperFunctionPointerType)&eglGetRenderBufferANDROID }, 
};

static extention_map_t gGLExtentionMap[MAX_NUMBER_OF_GL_EXTENSIONS];

static void(*findProcAddress(const char* name,
        const extention_map_t* map, size_t n))() 
{
    for (uint32_t i=0 ; i<n ; i++) {
        if (!strcmp(name, map[i].name)) {
            return map[i].address;
        }
    }
    return NULL;
}

// ----------------------------------------------------------------------------

static void gl_no_context() {
    tls_t* tls = getTLS();
    if (tls->logCallWithNoContext == EGL_TRUE) {
        tls->logCallWithNoContext = EGL_FALSE;
        LOGE("call to OpenGL ES API with no current context "
             "(logged once per thread)");
    }
}

static void early_egl_init(void) 
{
#if !USE_FAST_TLS_KEY
    pthread_key_create(&gGLWrapperKey, NULL);
#endif
    uint32_t addr = (uint32_t)((void*)gl_no_context);
    android_memset32(
            (uint32_t*)(void*)&gHooksNoContext, 
            addr, 
            sizeof(gHooksNoContext));
    setGlThreadSpecific(&gHooksNoContext);
}

static pthread_once_t once_control = PTHREAD_ONCE_INIT;
static int sEarlyInitState = pthread_once(&once_control, &early_egl_init);


static inline
egl_display_t* get_display(EGLDisplay dpy)
{
    uintptr_t index = uintptr_t(dpy)-1U;
    return (index >= NUM_DISPLAYS) ? NULL : &gDisplay[index];
}

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

static egl_connection_t* validate_display_config(
        EGLDisplay dpy, EGLConfig config,
        egl_display_t const*& dp, int& impl, int& index)
{
    dp = get_display(dpy);
    if (!dp) return setError(EGL_BAD_DISPLAY, (egl_connection_t*)NULL);

    impl = uintptr_t(config)>>24;
    if (uint32_t(impl) >= IMPL_NUM_IMPLEMENTATIONS) {
        return setError(EGL_BAD_CONFIG, (egl_connection_t*)NULL);
    } 
    index = uintptr_t(config) & 0xFFFFFF;
    if (index >= dp->disp[impl].numConfigs) {
        return setError(EGL_BAD_CONFIG, (egl_connection_t*)NULL);
    }
    egl_connection_t* const cnx = &gEGLImpl[impl];
    if (cnx->dso == 0) {
        return setError(EGL_BAD_CONFIG, (egl_connection_t*)NULL);
    }
    return cnx;
}

static EGLBoolean validate_display_context(EGLDisplay dpy, EGLContext ctx)
{
    if ((uintptr_t(dpy)-1U) >= NUM_DISPLAYS)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    if (!get_display(dpy)->isAlive())
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    if (!get_context(ctx)->isAlive())
        return setError(EGL_BAD_CONTEXT, EGL_FALSE);
    return EGL_TRUE;
}

static EGLBoolean validate_display_surface(EGLDisplay dpy, EGLSurface surface)
{
    if ((uintptr_t(dpy)-1U) >= NUM_DISPLAYS)
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    if (!get_display(dpy)->isAlive())
        return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    if (!get_surface(surface)->isAlive())
        return setError(EGL_BAD_SURFACE, EGL_FALSE);
    return EGL_TRUE;
}

EGLImageKHR egl_get_image_for_current_context(EGLImageKHR image)
{
    ImageRef _i(image);
    if (!_i.get()) return EGL_NO_IMAGE_KHR;
    
    EGLContext context = getContext();
    if (context == EGL_NO_CONTEXT || image == EGL_NO_IMAGE_KHR)
        return EGL_NO_IMAGE_KHR;
    
    egl_context_t const * const c = get_context(context);
    if (!c->isAlive())
        return EGL_NO_IMAGE_KHR;

    egl_image_t const * const i = get_image(image);
    return i->images[c->impl];
}

// ----------------------------------------------------------------------------

// this mutex protects:
//    d->disp[]
//    egl_init_drivers_locked()
//
static pthread_mutex_t gInitDriverMutex = PTHREAD_MUTEX_INITIALIZER;

EGLBoolean egl_init_drivers_locked()
{
    if (sEarlyInitState) {
        // initialized by static ctor. should be set here.
        return EGL_FALSE;
    }

    // get our driver loader
    Loader& loader(Loader::getInstance());
    
    // dynamically load all our EGL implementations for all displays
    // and retrieve the corresponding EGLDisplay
    // if that fails, don't use this driver.
    // TODO: currently we only deal with EGL_DEFAULT_DISPLAY
    egl_connection_t* cnx;
    egl_display_t* d = &gDisplay[0];

    cnx = &gEGLImpl[IMPL_SOFTWARE];
    if (cnx->dso == 0) {
        cnx->hooks[GLESv1_INDEX] = &gHooks[GLESv1_INDEX][IMPL_SOFTWARE];
        cnx->hooks[GLESv2_INDEX] = &gHooks[GLESv2_INDEX][IMPL_SOFTWARE];
        cnx->dso = loader.open(EGL_DEFAULT_DISPLAY, 0, cnx);
        if (cnx->dso) {
            EGLDisplay dpy = cnx->egl.eglGetDisplay(EGL_DEFAULT_DISPLAY);
            LOGE_IF(dpy==EGL_NO_DISPLAY, "No EGLDisplay for software EGL!");
            d->disp[IMPL_SOFTWARE].dpy = dpy; 
            if (dpy == EGL_NO_DISPLAY) {
                loader.close(cnx->dso);
                cnx->dso = NULL;
            }
        }
    }

    cnx = &gEGLImpl[IMPL_HARDWARE];
    if (cnx->dso == 0) {
        char value[PROPERTY_VALUE_MAX];
        property_get("debug.egl.hw", value, "1");
        if (atoi(value) != 0) {
            cnx->hooks[GLESv1_INDEX] = &gHooks[GLESv1_INDEX][IMPL_HARDWARE];
            cnx->hooks[GLESv2_INDEX] = &gHooks[GLESv2_INDEX][IMPL_HARDWARE];
            cnx->dso = loader.open(EGL_DEFAULT_DISPLAY, 1, cnx);
            if (cnx->dso) {
                EGLDisplay dpy = cnx->egl.eglGetDisplay(EGL_DEFAULT_DISPLAY);
                LOGE_IF(dpy==EGL_NO_DISPLAY, "No EGLDisplay for hardware EGL!");
                d->disp[IMPL_HARDWARE].dpy = dpy; 
                if (dpy == EGL_NO_DISPLAY) {
                    loader.close(cnx->dso);
                    cnx->dso = NULL;
                }
            }
        } else {
            LOGD("3D hardware acceleration is disabled");
        }
    }

    if (!gEGLImpl[IMPL_SOFTWARE].dso && !gEGLImpl[IMPL_HARDWARE].dso) {
        return EGL_FALSE;
    }

    return EGL_TRUE;
}

EGLBoolean egl_init_drivers()
{
    EGLBoolean res;
    pthread_mutex_lock(&gInitDriverMutex);
    res = egl_init_drivers_locked();
    pthread_mutex_unlock(&gInitDriverMutex);
    return res;
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

using namespace android;

EGLDisplay eglGetDisplay(NativeDisplayType display)
{
    uint32_t index = uint32_t(display);
    if (index >= NUM_DISPLAYS) {
        return setError(EGL_BAD_PARAMETER, EGL_NO_DISPLAY);
    }

    if (egl_init_drivers() == EGL_FALSE) {
        return setError(EGL_BAD_PARAMETER, EGL_NO_DISPLAY);
    }
    
    EGLDisplay dpy = EGLDisplay(uintptr_t(display) + 1LU);
    return dpy;
}

// ----------------------------------------------------------------------------
// Initialization
// ----------------------------------------------------------------------------

EGLBoolean eglInitialize(EGLDisplay dpy, EGLint *major, EGLint *minor)
{
    egl_display_t * const dp = get_display(dpy);
    if (!dp) return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    Mutex::Autolock _l(dp->lock);

    if (dp->refs > 0) {
        if (major != NULL) *major = VERSION_MAJOR;
        if (minor != NULL) *minor = VERSION_MINOR;
        dp->refs++;
        return EGL_TRUE;
    }
    
    setGlThreadSpecific(&gHooksNoContext);
    
    // initialize each EGL and
    // build our own extension string first, based on the extension we know
    // and the extension supported by our client implementation
    for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        cnx->major = -1;
        cnx->minor = -1;
        if (!cnx->dso) 
            continue;

#if defined(ADRENO130)
#warning "Adreno-130 eglInitialize() workaround"
        /*
         * The ADRENO 130 driver returns a different EGLDisplay each time
         * eglGetDisplay() is called, but also makes the EGLDisplay invalid
         * after eglTerminate() has been called, so that eglInitialize() 
         * cannot be called again. Therefore, we need to make sure to call
         * eglGetDisplay() before calling eglInitialize();
         */
        if (i == IMPL_HARDWARE) {
            dp->disp[i].dpy =
                cnx->egl.eglGetDisplay(EGL_DEFAULT_DISPLAY);
        }
#endif


        EGLDisplay idpy = dp->disp[i].dpy;
        if (cnx->egl.eglInitialize(idpy, &cnx->major, &cnx->minor)) {
            //LOGD("initialized %d dpy=%p, ver=%d.%d, cnx=%p",
            //        i, idpy, cnx->major, cnx->minor, cnx);

            // display is now initialized
            dp->disp[i].state = egl_display_t::INITIALIZED;

            // get the query-strings for this display for each implementation
            dp->disp[i].queryString.vendor =
                cnx->egl.eglQueryString(idpy, EGL_VENDOR);
            dp->disp[i].queryString.version =
                cnx->egl.eglQueryString(idpy, EGL_VERSION);
            dp->disp[i].queryString.extensions =
                    cnx->egl.eglQueryString(idpy, EGL_EXTENSIONS);
            dp->disp[i].queryString.clientApi =
                cnx->egl.eglQueryString(idpy, EGL_CLIENT_APIS);

        } else {
            LOGW("%d: eglInitialize(%p) failed (%s)", i, idpy,
                    egl_strerror(cnx->egl.eglGetError()));
        }
    }

    EGLBoolean res = EGL_FALSE;
    for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso && cnx->major>=0 && cnx->minor>=0) {
            EGLint n;
            if (cnx->egl.eglGetConfigs(dp->disp[i].dpy, 0, 0, &n)) {
                dp->disp[i].config = (EGLConfig*)malloc(sizeof(EGLConfig)*n);
                if (dp->disp[i].config) {
                    if (cnx->egl.eglGetConfigs(
                            dp->disp[i].dpy, dp->disp[i].config, n,
                            &dp->disp[i].numConfigs))
                    {
                        // sort the configurations so we can do binary searches
                        qsort(  dp->disp[i].config,
                                dp->disp[i].numConfigs,
                                sizeof(EGLConfig), cmp_configs);

                        dp->numTotalConfigs += n;
                        res = EGL_TRUE;
                    }
                }
            }
        }
    }

    if (res == EGL_TRUE) {
        dp->refs++;
        if (major != NULL) *major = VERSION_MAJOR;
        if (minor != NULL) *minor = VERSION_MINOR;
        return EGL_TRUE;
    }
    return setError(EGL_NOT_INITIALIZED, EGL_FALSE);
}

EGLBoolean eglTerminate(EGLDisplay dpy)
{
    // NOTE: don't unload the drivers b/c some APIs can be called
    // after eglTerminate() has been called. eglTerminate() only
    // terminates an EGLDisplay, not a EGL itself.

    egl_display_t* const dp = get_display(dpy);
    if (!dp) return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    Mutex::Autolock _l(dp->lock);

    if (dp->refs == 0) {
        return setError(EGL_NOT_INITIALIZED, EGL_FALSE);
    }

    // this is specific to Android, display termination is ref-counted.
    if (dp->refs > 1) {
        dp->refs--;
        return EGL_TRUE;
    }

    EGLBoolean res = EGL_FALSE;
    for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso && dp->disp[i].state == egl_display_t::INITIALIZED) {
            if (cnx->egl.eglTerminate(dp->disp[i].dpy) == EGL_FALSE) {
                LOGW("%d: eglTerminate(%p) failed (%s)", i, dp->disp[i].dpy,
                        egl_strerror(cnx->egl.eglGetError()));
            }
            // REVISIT: it's unclear what to do if eglTerminate() fails
            free(dp->disp[i].config);

            dp->disp[i].numConfigs = 0;
            dp->disp[i].config = 0;
            dp->disp[i].state = egl_display_t::TERMINATED;

            res = EGL_TRUE;
        }
    }
    
    // TODO: all egl_object_t should be marked for termination
    
    dp->refs--;
    dp->numTotalConfigs = 0;
    clearTLS();
    return res;
}

// ----------------------------------------------------------------------------
// configuration
// ----------------------------------------------------------------------------

EGLBoolean eglGetConfigs(   EGLDisplay dpy,
                            EGLConfig *configs,
                            EGLint config_size, EGLint *num_config)
{
    egl_display_t const * const dp = get_display(dpy);
    if (!dp) return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    GLint numConfigs = dp->numTotalConfigs;
    if (!configs) {
        *num_config = numConfigs;
        return EGL_TRUE;
    }
    GLint n = 0;
    for (int j=0 ; j<IMPL_NUM_IMPLEMENTATIONS ; j++) {
        for (int i=0 ; i<dp->disp[j].numConfigs && config_size ; i++) {
            *configs++ = MAKE_CONFIG(j, i);
            config_size--;
            n++;
        }
    }    
    
    *num_config = n;
    return EGL_TRUE;
}

EGLBoolean eglChooseConfig( EGLDisplay dpy, const EGLint *attrib_list,
                            EGLConfig *configs, EGLint config_size,
                            EGLint *num_config)
{
    egl_display_t const * const dp = get_display(dpy);
    if (!dp) return setError(EGL_BAD_DISPLAY, EGL_FALSE);

    if (num_config==0) {
        return setError(EGL_BAD_PARAMETER, EGL_FALSE);
    }

    EGLint n;
    EGLBoolean res = EGL_FALSE;
    *num_config = 0;

    
    // It is unfortunate, but we need to remap the EGL_CONFIG_IDs, 
    // to do  this, we have to go through the attrib_list array once
    // to figure out both its size and if it contains an EGL_CONFIG_ID
    // key. If so, the full array is copied and patched.
    // NOTE: we assume that there can be only one occurrence
    // of EGL_CONFIG_ID.
    
    EGLint patch_index = -1;
    GLint attr;
    size_t size = 0;
    while ((attr=attrib_list[size]) != EGL_NONE) {
        if (attr == EGL_CONFIG_ID)
            patch_index = size;
        size += 2;
    }
    if (patch_index >= 0) {
        size += 2; // we need copy the sentinel as well
        EGLint* new_list = (EGLint*)malloc(size*sizeof(EGLint));
        if (new_list == 0)
            return setError(EGL_BAD_ALLOC, EGL_FALSE);
        memcpy(new_list, attrib_list, size*sizeof(EGLint));

        // patch the requested EGL_CONFIG_ID
        int i, index;
        EGLint& configId(new_list[patch_index+1]);
        uniqueIdToConfig(dp, configId, i, index);
        
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso) {
            cnx->egl.eglGetConfigAttrib(
                    dp->disp[i].dpy, dp->disp[i].config[index], 
                    EGL_CONFIG_ID, &configId);

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
                    dp->disp[i].dpy, attrib_list, configs, config_size, &n);
            if (res && n>0) {
                // n has to be 0 or 1, by construction, and we already know
                // which config it will return (since there can be only one).
                if (configs) {
                    configs[0] = MAKE_CONFIG(i, index);
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
                    // internal EGLConfig format. This is done in O(n log n).
                    for (int j=0 ; j<n ; j++) {
                        int index = binarySearch<EGLConfig>(
                                dp->disp[i].config, 0,
                                dp->disp[i].numConfigs-1, configs[j]);
                        if (index >= 0) {
                            if (configs) {
                                configs[j] = MAKE_CONFIG(i, index);
                            }
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
    egl_display_t const* dp = 0;
    int i=0, index=0;
    egl_connection_t* cnx = validate_display_config(dpy, config, dp, i, index);
    if (!cnx) return EGL_FALSE;
    
    if (attribute == EGL_CONFIG_ID) {
        // EGL_CONFIG_IDs must be unique, just use the order of the selected
        // EGLConfig.
        *value = configToUniqueId(dp, i, index);
        return EGL_TRUE;
    }
    return cnx->egl.eglGetConfigAttrib(
            dp->disp[i].dpy, dp->disp[i].config[index], attribute, value);
}

// ----------------------------------------------------------------------------
// surfaces
// ----------------------------------------------------------------------------

EGLSurface eglCreateWindowSurface(  EGLDisplay dpy, EGLConfig config,
                                    NativeWindowType window,
                                    const EGLint *attrib_list)
{
    egl_display_t const* dp = 0;
    int i=0, index=0;
    egl_connection_t* cnx = validate_display_config(dpy, config, dp, i, index);
    if (cnx) {
        EGLSurface surface = cnx->egl.eglCreateWindowSurface(
                dp->disp[i].dpy, dp->disp[i].config[index], window, attrib_list);       
        if (surface != EGL_NO_SURFACE) {
            egl_surface_t* s = new egl_surface_t(dpy, surface, i, cnx);
            return s;
        }
    }
    return EGL_NO_SURFACE;
}

EGLSurface eglCreatePixmapSurface(  EGLDisplay dpy, EGLConfig config,
                                    NativePixmapType pixmap,
                                    const EGLint *attrib_list)
{
    egl_display_t const* dp = 0;
    int i=0, index=0;
    egl_connection_t* cnx = validate_display_config(dpy, config, dp, i, index);
    if (cnx) {
        EGLSurface surface = cnx->egl.eglCreatePixmapSurface(
                dp->disp[i].dpy, dp->disp[i].config[index], pixmap, attrib_list);
        if (surface != EGL_NO_SURFACE) {
            egl_surface_t* s = new egl_surface_t(dpy, surface, i, cnx);
            return s;
        }
    }
    return EGL_NO_SURFACE;
}

EGLSurface eglCreatePbufferSurface( EGLDisplay dpy, EGLConfig config,
                                    const EGLint *attrib_list)
{
    egl_display_t const* dp = 0;
    int i=0, index=0;
    egl_connection_t* cnx = validate_display_config(dpy, config, dp, i, index);
    if (cnx) {
        EGLSurface surface = cnx->egl.eglCreatePbufferSurface(
                dp->disp[i].dpy, dp->disp[i].config[index], attrib_list);
        if (surface != EGL_NO_SURFACE) {
            egl_surface_t* s = new egl_surface_t(dpy, surface, i, cnx);
            return s;
        }
    }
    return EGL_NO_SURFACE;
}
                                    
EGLBoolean eglDestroySurface(EGLDisplay dpy, EGLSurface surface)
{
    SurfaceRef _s(surface);
    if (!_s.get()) return setError(EGL_BAD_SURFACE, EGL_FALSE);

    if (!validate_display_surface(dpy, surface))
        return EGL_FALSE;    
    egl_display_t const * const dp = get_display(dpy);

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
    SurfaceRef _s(surface);
    if (!_s.get()) return setError(EGL_BAD_SURFACE, EGL_FALSE);

    if (!validate_display_surface(dpy, surface))
        return EGL_FALSE;    
    egl_display_t const * const dp = get_display(dpy);
    egl_surface_t const * const s = get_surface(surface);

    return s->cnx->egl.eglQuerySurface(
            dp->disp[s->impl].dpy, s->surface, attribute, value);
}

// ----------------------------------------------------------------------------
// contextes
// ----------------------------------------------------------------------------

EGLContext eglCreateContext(EGLDisplay dpy, EGLConfig config,
                            EGLContext share_list, const EGLint *attrib_list)
{
    egl_display_t const* dp = 0;
    int i=0, index=0;
    egl_connection_t* cnx = validate_display_config(dpy, config, dp, i, index);
    if (cnx) {
        if (share_list != EGL_NO_CONTEXT) {
            egl_context_t* const c = get_context(share_list);
            share_list = c->context;
        }
        EGLContext context = cnx->egl.eglCreateContext(
                dp->disp[i].dpy, dp->disp[i].config[index],
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
            egl_context_t* c = new egl_context_t(dpy, context, i, cnx, version);
            return c;
        }
    }
    return EGL_NO_CONTEXT;
}

EGLBoolean eglDestroyContext(EGLDisplay dpy, EGLContext ctx)
{
    ContextRef _c(ctx);
    if (!_c.get()) return setError(EGL_BAD_CONTEXT, EGL_FALSE);
    
    if (!validate_display_context(dpy, ctx))
        return EGL_FALSE;
    egl_display_t const * const dp = get_display(dpy);
    egl_context_t * const c = get_context(ctx);
    EGLBoolean result = c->cnx->egl.eglDestroyContext(
            dp->disp[c->impl].dpy, c->context);
    if (result == EGL_TRUE) {
        _c.terminate();
    }
    return result;
}

EGLBoolean eglMakeCurrent(  EGLDisplay dpy, EGLSurface draw,
                            EGLSurface read, EGLContext ctx)
{
    // get a reference to the object passed in
    ContextRef _c(ctx);
    SurfaceRef _d(draw);
    SurfaceRef _r(read);

    // validate the display and the context (if not EGL_NO_CONTEXT)
    egl_display_t const * const dp = get_display(dpy);
    if (!dp) return setError(EGL_BAD_DISPLAY, EGL_FALSE);
    if ((ctx != EGL_NO_CONTEXT) && (!validate_display_context(dpy, ctx))) {
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
    egl_surface_t * cur_r = NULL;
    egl_surface_t * cur_d = NULL;
    
    if (ctx != EGL_NO_CONTEXT) {
        c = get_context(ctx);
        cur_r = get_surface(c->read);
        cur_d = get_surface(c->draw);
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
        // by construction, these are either 0 or valid (possibly terminated)
        // it should be impossible for these to be invalid
        ContextRef _cur_c(cur_c);
        SurfaceRef _cur_r(cur_r);
        SurfaceRef _cur_d(cur_d);

        // cur_c has to be valid here (but could be terminated)
        if (ctx != EGL_NO_CONTEXT) {
            setGlThreadSpecific(c->cnx->hooks[c->version]);
            setContext(ctx);
            _c.acquire();
        } else {
            setGlThreadSpecific(&gHooksNoContext);
            setContext(EGL_NO_CONTEXT);
        }
        _cur_c.release();

        _r.acquire();
        _cur_r.release();
        if (c) c->read = read;

        _d.acquire();
        _cur_d.release();
        if (c) c->draw = draw;
    }
    return result;
}


EGLBoolean eglQueryContext( EGLDisplay dpy, EGLContext ctx,
                            EGLint attribute, EGLint *value)
{
    ContextRef _c(ctx);
    if (!_c.get()) return setError(EGL_BAD_CONTEXT, EGL_FALSE);

    if (!validate_display_context(dpy, ctx))
        return EGL_FALSE;    
    
    egl_display_t const * const dp = get_display(dpy);
    egl_context_t * const c = get_context(ctx);

    return c->cnx->egl.eglQueryContext(
            dp->disp[c->impl].dpy, c->context, attribute, value);
}

EGLContext eglGetCurrentContext(void)
{
    // could be called before eglInitialize(), but we wouldn't have a context
    // then, and this function would correctly return EGL_NO_CONTEXT.

    EGLContext ctx = getContext();
    return ctx;
}

EGLSurface eglGetCurrentSurface(EGLint readdraw)
{
    // could be called before eglInitialize(), but we wouldn't have a context
    // then, and this function would correctly return EGL_NO_SURFACE.

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
    for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
        EGLint err = EGL_SUCCESS;
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso)
            err = cnx->egl.eglGetError();
        if (err!=EGL_SUCCESS && result==EGL_SUCCESS)
            result = err;
    }
    if (result == EGL_SUCCESS)
        result = getError();
    return result;
}

__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char *procname)
{
    // eglGetProcAddress() could be the very first function called
    // in which case we must make sure we've initialized ourselves, this
    // happens the first time egl_get_display() is called.

    if (egl_init_drivers() == EGL_FALSE) {
        setError(EGL_BAD_PARAMETER, NULL);
        return  NULL;
    }

    __eglMustCastToProperFunctionPointerType addr;
    addr = findProcAddress(procname, gExtentionMap, NELEM(gExtentionMap));
    if (addr) return addr;

    return NULL; // TODO: finish implementation below

    addr = findProcAddress(procname, gGLExtentionMap, NELEM(gGLExtentionMap));
    if (addr) return addr;
    
    addr = 0;
    int slot = -1;
    for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso) {
            if (cnx->egl.eglGetProcAddress) {
                addr = cnx->egl.eglGetProcAddress(procname);
                if (addr) {
                    if (slot == -1) {
                        slot = 0; // XXX: find free slot
                        if (slot == -1) {
                            addr = 0;
                            break;
                        }
                    }
                    //cnx->hooks->ext.extensions[slot] = addr;
                }
            }
        }
    }
    
    if (slot >= 0) {
        addr = 0; // XXX: address of stub 'slot'
        gGLExtentionMap[slot].name = strdup(procname);
        gGLExtentionMap[slot].address = addr;
    }
    
    return addr;

    
    /*
     *  TODO: For OpenGL ES extensions, we must generate a stub
     *  that looks like
     *      mov     r12, #0xFFFF0FFF
     *      ldr     r12, [r12, #-15]
     *      ldr     r12, [r12, #TLS_SLOT_OPENGL_API*4]
     *      mov     r12, [r12, #api_offset]
     *      ldrne   pc, r12
     *      mov     pc, #unsupported_extension
     * 
     *  and write the address of the extension in *all*
     *  gl_hooks_t::gl_ext_t at offset "api_offset" from gl_hooks_t
     * 
     */
}

EGLBoolean eglSwapBuffers(EGLDisplay dpy, EGLSurface draw)
{
    SurfaceRef _s(draw);
    if (!_s.get()) return setError(EGL_BAD_SURFACE, EGL_FALSE);

    if (!validate_display_surface(dpy, draw))
        return EGL_FALSE;    
    egl_display_t const * const dp = get_display(dpy);
    egl_surface_t const * const s = get_surface(draw);
    return s->cnx->egl.eglSwapBuffers(dp->disp[s->impl].dpy, s->surface);
}

EGLBoolean eglCopyBuffers(  EGLDisplay dpy, EGLSurface surface,
                            NativePixmapType target)
{
    SurfaceRef _s(surface);
    if (!_s.get()) return setError(EGL_BAD_SURFACE, EGL_FALSE);

    if (!validate_display_surface(dpy, surface))
        return EGL_FALSE;    
    egl_display_t const * const dp = get_display(dpy);
    egl_surface_t const * const s = get_surface(surface);
    return s->cnx->egl.eglCopyBuffers(
            dp->disp[s->impl].dpy, s->surface, target);
}

const char* eglQueryString(EGLDisplay dpy, EGLint name)
{
    egl_display_t const * const dp = get_display(dpy);
    switch (name) {
        case EGL_VENDOR:
            return gVendorString;
        case EGL_VERSION:
            return gVersionString;
        case EGL_EXTENSIONS:
            return gExtensionString;
        case EGL_CLIENT_APIS:
            return gClientApiString;
    }
    return setError(EGL_BAD_PARAMETER, (const char *)0);
}


// ----------------------------------------------------------------------------
// EGL 1.1
// ----------------------------------------------------------------------------

EGLBoolean eglSurfaceAttrib(
        EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint value)
{
    SurfaceRef _s(surface);
    if (!_s.get()) return setError(EGL_BAD_SURFACE, EGL_FALSE);

    if (!validate_display_surface(dpy, surface))
        return EGL_FALSE;    
    egl_display_t const * const dp = get_display(dpy);
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
    SurfaceRef _s(surface);
    if (!_s.get()) return setError(EGL_BAD_SURFACE, EGL_FALSE);

    if (!validate_display_surface(dpy, surface))
        return EGL_FALSE;    
    egl_display_t const * const dp = get_display(dpy);
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
    SurfaceRef _s(surface);
    if (!_s.get()) return setError(EGL_BAD_SURFACE, EGL_FALSE);

    if (!validate_display_surface(dpy, surface))
        return EGL_FALSE;    
    egl_display_t const * const dp = get_display(dpy);
    egl_surface_t const * const s = get_surface(surface);
    if (s->cnx->egl.eglReleaseTexImage) {
        return s->cnx->egl.eglReleaseTexImage(
                dp->disp[s->impl].dpy, s->surface, buffer);
    }
    return setError(EGL_BAD_SURFACE, EGL_FALSE);
}

EGLBoolean eglSwapInterval(EGLDisplay dpy, EGLint interval)
{
    egl_display_t * const dp = get_display(dpy);
    if (!dp) return setError(EGL_BAD_DISPLAY, EGL_FALSE);

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
    for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso) {
            if (cnx->egl.eglReleaseThread) {
                cnx->egl.eglReleaseThread();
            }
        }
    }
    clearTLS();    
    return EGL_TRUE;
}

EGLSurface eglCreatePbufferFromClientBuffer(
          EGLDisplay dpy, EGLenum buftype, EGLClientBuffer buffer,
          EGLConfig config, const EGLint *attrib_list)
{
    egl_display_t const* dp = 0;
    int i=0, index=0;
    egl_connection_t* cnx = validate_display_config(dpy, config, dp, i, index);
    if (!cnx) return EGL_FALSE;
    if (cnx->egl.eglCreatePbufferFromClientBuffer) {
        return cnx->egl.eglCreatePbufferFromClientBuffer(
                dp->disp[i].dpy, buftype, buffer, 
                dp->disp[i].config[index], attrib_list);
    }
    return setError(EGL_BAD_CONFIG, EGL_NO_SURFACE);
}

// ----------------------------------------------------------------------------
// EGL_EGLEXT_VERSION 3
// ----------------------------------------------------------------------------

EGLBoolean eglLockSurfaceKHR(EGLDisplay dpy, EGLSurface surface,
        const EGLint *attrib_list)
{
    SurfaceRef _s(surface);
    if (!_s.get()) return setError(EGL_BAD_SURFACE, EGL_FALSE);

    if (!validate_display_surface(dpy, surface))
        return EGL_FALSE;

    egl_display_t const * const dp = get_display(dpy);
    egl_surface_t const * const s = get_surface(surface);

    if (s->cnx->egl.eglLockSurfaceKHR) {
        return s->cnx->egl.eglLockSurfaceKHR(
                dp->disp[s->impl].dpy, s->surface, attrib_list);
    }
    return setError(EGL_BAD_DISPLAY, EGL_FALSE);
}

EGLBoolean eglUnlockSurfaceKHR(EGLDisplay dpy, EGLSurface surface)
{
    SurfaceRef _s(surface);
    if (!_s.get()) return setError(EGL_BAD_SURFACE, EGL_FALSE);

    if (!validate_display_surface(dpy, surface))
        return EGL_FALSE;

    egl_display_t const * const dp = get_display(dpy);
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
    if (ctx != EGL_NO_CONTEXT) {
        ContextRef _c(ctx);
        if (!_c.get()) return setError(EGL_BAD_CONTEXT, EGL_NO_IMAGE_KHR);
        if (!validate_display_context(dpy, ctx))
            return EGL_NO_IMAGE_KHR;
        egl_display_t const * const dp = get_display(dpy);
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
        egl_display_t const * const dp = get_display(dpy);
        if (dp == 0) {
            return setError(EGL_BAD_DISPLAY, EGL_NO_IMAGE_KHR);
        }

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
    egl_display_t const * const dp = get_display(dpy);
     if (dp == 0) {
         return setError(EGL_BAD_DISPLAY, EGL_FALSE);
     }

     ImageRef _i(img);
     if (!_i.get()) return setError(EGL_BAD_PARAMETER, EGL_FALSE);

     egl_image_t* image = get_image(img);
     bool success = false;
     for (int i=0 ; i<IMPL_NUM_IMPLEMENTATIONS ; i++) {
         egl_connection_t* const cnx = &gEGLImpl[i];
         if (image->images[i] != EGL_NO_IMAGE_KHR) {
             if (cnx->dso) {
                 if (cnx->egl.eglCreateImageKHR) {
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
// ANDROID extensions
// ----------------------------------------------------------------------------

EGLBoolean eglSetSwapRectangleANDROID(EGLDisplay dpy, EGLSurface draw,
        EGLint left, EGLint top, EGLint width, EGLint height)
{
    SurfaceRef _s(draw);
    if (!_s.get()) return setError(EGL_BAD_SURFACE, EGL_FALSE);

    if (!validate_display_surface(dpy, draw))
        return EGL_FALSE;    
    egl_display_t const * const dp = get_display(dpy);
    egl_surface_t const * const s = get_surface(draw);
    if (s->cnx->egl.eglSetSwapRectangleANDROID) {
        return s->cnx->egl.eglSetSwapRectangleANDROID(
                dp->disp[s->impl].dpy, s->surface, left, top, width, height);
    }
    return setError(EGL_BAD_DISPLAY, NULL);
}

EGLClientBuffer eglGetRenderBufferANDROID(EGLDisplay dpy, EGLSurface draw)
{
    SurfaceRef _s(draw);
    if (!_s.get()) return setError(EGL_BAD_SURFACE, (EGLClientBuffer*)0);

    if (!validate_display_surface(dpy, draw))
        return 0;    
    egl_display_t const * const dp = get_display(dpy);
    egl_surface_t const * const s = get_surface(draw);
    if (s->cnx->egl.eglGetRenderBufferANDROID) {
        return s->cnx->egl.eglGetRenderBufferANDROID(
                dp->disp[s->impl].dpy, s->surface);
    }
    return setError(EGL_BAD_DISPLAY, (EGLClientBuffer*)0);
}
