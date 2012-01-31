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

#include <string.h>

#include "egl_cache.h"
#include "egl_display.h"
#include "egl_object.h"
#include "egl_tls.h"
#include "egl_impl.h"
#include "Loader.h"

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

static char const * const sVendorString     = "Android";
static char const * const sVersionString    = "1.4 Android META-EGL";
static char const * const sClientApiString  = "OpenGL ES";

// this is the list of EGL extensions that are exposed to applications
// some of them are mandatory because used by the ANDROID system.
//
// mandatory extensions are required per the CDD and not explicitly
// checked during EGL initialization. the system *assumes* these extensions
// are present. the system may not function properly if some mandatory
// extensions are missing.
//
// NOTE: sExtensionString MUST be have a single space as the last character.
//
static char const * const sExtensionString  =
        "EGL_KHR_image "                        // mandatory
        "EGL_KHR_image_base "                   // mandatory
        "EGL_KHR_image_pixmap "
        "EGL_KHR_gl_texture_2D_image "
        "EGL_KHR_gl_texture_cubemap_image "
        "EGL_KHR_gl_renderbuffer_image "
        "EGL_KHR_fence_sync "
        "EGL_NV_system_time "
        "EGL_ANDROID_image_native_buffer "      // mandatory
        ;

// extensions not exposed to applications but used by the ANDROID system
//      "EGL_ANDROID_recordable "               // mandatory
//      "EGL_ANDROID_blob_cache "               // strongly recommended

extern void initEglTraceLevel();
extern void setGLHooksThreadSpecific(gl_hooks_t const *value);

static int cmp_configs(const void* a, const void *b) {
    const egl_config_t& c0 = *(egl_config_t const *)a;
    const egl_config_t& c1 = *(egl_config_t const *)b;
    return c0<c1 ? -1 : (c1<c0 ? 1 : 0);
}

// ----------------------------------------------------------------------------

egl_display_t egl_display_t::sDisplay[NUM_DISPLAYS];

egl_display_t::egl_display_t() :
    magic('_dpy'), numTotalConfigs(0), configs(0), refs(0) {
}

egl_display_t::~egl_display_t() {
    magic = 0;
    egl_cache_t::get()->terminate();
}

egl_display_t* egl_display_t::get(EGLDisplay dpy) {
    uintptr_t index = uintptr_t(dpy)-1U;
    return (index >= NUM_DISPLAYS) ? NULL : &sDisplay[index];
}

void egl_display_t::addObject(egl_object_t* object) {
    Mutex::Autolock _l(lock);
    objects.add(object);
}

void egl_display_t::removeObject(egl_object_t* object) {
    Mutex::Autolock _l(lock);
    objects.remove(object);
}

bool egl_display_t::getObject(egl_object_t* object) const {
    Mutex::Autolock _l(lock);
    if (objects.indexOf(object) >= 0) {
        if (object->getDisplay() == this) {
            object->incRef();
            return true;
        }
    }
    return false;
}

EGLDisplay egl_display_t::getFromNativeDisplay(EGLNativeDisplayType disp) {
    if (uintptr_t(disp) >= NUM_DISPLAYS)
        return NULL;

    return sDisplay[uintptr_t(disp)].getDisplay(disp);
}

EGLDisplay egl_display_t::getDisplay(EGLNativeDisplayType display) {

    Mutex::Autolock _l(lock);

    // get our driver loader
    Loader& loader(Loader::getInstance());

    for (int i = 0; i < IMPL_NUM_IMPLEMENTATIONS; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso && disp[i].dpy == EGL_NO_DISPLAY) {
            EGLDisplay dpy = cnx->egl.eglGetDisplay(display);
            disp[i].dpy = dpy;
            if (dpy == EGL_NO_DISPLAY) {
                loader.close(cnx->dso);
                cnx->dso = NULL;
            }
        }
    }

    return EGLDisplay(uintptr_t(display) + 1U);
}

EGLBoolean egl_display_t::initialize(EGLint *major, EGLint *minor) {

    Mutex::Autolock _l(lock);

    if (refs > 0) {
        if (major != NULL)
            *major = VERSION_MAJOR;
        if (minor != NULL)
            *minor = VERSION_MINOR;
        refs++;
        return EGL_TRUE;
    }

#if EGL_TRACE

    // Called both at early_init time and at this time. (Early_init is pre-zygote, so
    // the information from that call may be stale.)
    initEglTraceLevel();

#endif

    setGLHooksThreadSpecific(&gHooksNoContext);

    // initialize each EGL and
    // build our own extension string first, based on the extension we know
    // and the extension supported by our client implementation
    for (int i = 0; i < IMPL_NUM_IMPLEMENTATIONS; i++) {
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
            disp[i].dpy =
            cnx->egl.eglGetDisplay(EGL_DEFAULT_DISPLAY);
        }
#endif

        EGLDisplay idpy = disp[i].dpy;
        if (cnx->egl.eglInitialize(idpy, &cnx->major, &cnx->minor)) {
            //ALOGD("initialized %d dpy=%p, ver=%d.%d, cnx=%p",
            //        i, idpy, cnx->major, cnx->minor, cnx);

            // display is now initialized
            disp[i].state = egl_display_t::INITIALIZED;

            // get the query-strings for this display for each implementation
            disp[i].queryString.vendor = cnx->egl.eglQueryString(idpy,
                    EGL_VENDOR);
            disp[i].queryString.version = cnx->egl.eglQueryString(idpy,
                    EGL_VERSION);
            disp[i].queryString.extensions = cnx->egl.eglQueryString(idpy,
                    EGL_EXTENSIONS);
            disp[i].queryString.clientApi = cnx->egl.eglQueryString(idpy,
                    EGL_CLIENT_APIS);

        } else {
            ALOGW("%d: eglInitialize(%p) failed (%s)", i, idpy,
                    egl_tls_t::egl_strerror(cnx->egl.eglGetError()));
        }
    }

    // the query strings are per-display
    mVendorString.setTo(sVendorString);
    mVersionString.setTo(sVersionString);
    mClientApiString.setTo(sClientApiString);

    // we only add extensions that exist in at least one implementation
    char const* start = sExtensionString;
    char const* end;
    do {
        // find the space separating this extension for the next one
        end = strchr(start, ' ');
        if (end) {
            // length of the extension string
            const size_t len = end - start;
            if (len) {
                // NOTE: we could avoid the copy if we had strnstr.
                const String8 ext(start, len);
                // now go through all implementations and look for this extension
                for (int i = 0; i < IMPL_NUM_IMPLEMENTATIONS; i++) {
                    if (disp[i].queryString.extensions) {
                        // if we find it, add this extension string to our list
                        // (and don't forget the space)
                        const char* match = strstr(disp[i].queryString.extensions, ext.string());
                        if (match && (match[len] == ' ' || match[len] == 0)) {
                            mExtensionString.append(start, len+1);
                        }
                    }
                }
            }
            // process the next extension string, and skip the space.
            start = end + 1;
        }
    } while (end);

    egl_cache_t::get()->initialize(this);

    EGLBoolean res = EGL_FALSE;
    for (int i = 0; i < IMPL_NUM_IMPLEMENTATIONS; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso && cnx->major >= 0 && cnx->minor >= 0) {
            EGLint n;
            if (cnx->egl.eglGetConfigs(disp[i].dpy, 0, 0, &n)) {
                disp[i].config = (EGLConfig*) malloc(sizeof(EGLConfig) * n);
                if (disp[i].config) {
                    if (cnx->egl.eglGetConfigs(disp[i].dpy, disp[i].config, n,
                            &disp[i].numConfigs)) {
                        numTotalConfigs += n;
                        res = EGL_TRUE;
                    }
                }
            }
        }
    }

    if (res == EGL_TRUE) {
        configs = new egl_config_t[numTotalConfigs];
        for (int i = 0, k = 0; i < IMPL_NUM_IMPLEMENTATIONS; i++) {
            egl_connection_t* const cnx = &gEGLImpl[i];
            if (cnx->dso && cnx->major >= 0 && cnx->minor >= 0) {
                for (int j = 0; j < disp[i].numConfigs; j++) {
                    configs[k].impl = i;
                    configs[k].config = disp[i].config[j];
                    configs[k].configId = k + 1; // CONFIG_ID start at 1
                    // store the implementation's CONFIG_ID
                    cnx->egl.eglGetConfigAttrib(disp[i].dpy, disp[i].config[j],
                            EGL_CONFIG_ID, &configs[k].implConfigId);
                    k++;
                }
            }
        }

        // sort our configurations so we can do binary-searches
        qsort(configs, numTotalConfigs, sizeof(egl_config_t), cmp_configs);

        refs++;
        if (major != NULL)
            *major = VERSION_MAJOR;
        if (minor != NULL)
            *minor = VERSION_MINOR;
        return EGL_TRUE;
    }
    return setError(EGL_NOT_INITIALIZED, EGL_FALSE);
}

EGLBoolean egl_display_t::terminate() {

    Mutex::Autolock _l(lock);

    if (refs == 0) {
        return setError(EGL_NOT_INITIALIZED, EGL_FALSE);
    }

    // this is specific to Android, display termination is ref-counted.
    if (refs > 1) {
        refs--;
        return EGL_TRUE;
    }

    EGLBoolean res = EGL_FALSE;
    for (int i = 0; i < IMPL_NUM_IMPLEMENTATIONS; i++) {
        egl_connection_t* const cnx = &gEGLImpl[i];
        if (cnx->dso && disp[i].state == egl_display_t::INITIALIZED) {
            if (cnx->egl.eglTerminate(disp[i].dpy) == EGL_FALSE) {
                ALOGW("%d: eglTerminate(%p) failed (%s)", i, disp[i].dpy,
                        egl_tls_t::egl_strerror(cnx->egl.eglGetError()));
            }
            // REVISIT: it's unclear what to do if eglTerminate() fails
            free(disp[i].config);

            disp[i].numConfigs = 0;
            disp[i].config = 0;
            disp[i].state = egl_display_t::TERMINATED;

            res = EGL_TRUE;
        }
    }

    // Mark all objects remaining in the list as terminated, unless
    // there are no reference to them, it which case, we're free to
    // delete them.
    size_t count = objects.size();
    ALOGW_IF(count, "eglTerminate() called w/ %d objects remaining", count);
    for (size_t i=0 ; i<count ; i++) {
        egl_object_t* o = objects.itemAt(i);
        o->destroy();
    }

    // this marks all object handles are "terminated"
    objects.clear();

    refs--;
    numTotalConfigs = 0;
    delete[] configs;
    return res;
}

void egl_display_t::loseCurrent(egl_context_t * cur_c)
{
    if (cur_c) {
        egl_surface_t * cur_r = get_surface(cur_c->read);
        egl_surface_t * cur_d = get_surface(cur_c->draw);

        // by construction, these are either 0 or valid (possibly terminated)
        // it should be impossible for these to be invalid
        ContextRef _cur_c(cur_c);
        SurfaceRef _cur_r(cur_r);
        SurfaceRef _cur_d(cur_d);

        cur_c->onLooseCurrent();

        _cur_c.release();
        _cur_r.release();
        _cur_d.release();
    }
}

EGLBoolean egl_display_t::makeCurrent(egl_context_t* c, egl_context_t* cur_c,
        EGLSurface draw, EGLSurface read, EGLContext ctx,
        EGLSurface impl_draw, EGLSurface impl_read, EGLContext impl_ctx)
{
    Mutex::Autolock _l(lock);
    EGLBoolean result;
    if (c) {
        result = c->cnx->egl.eglMakeCurrent(
                disp[c->impl].dpy, impl_draw, impl_read, impl_ctx);
    } else {
        result = cur_c->cnx->egl.eglMakeCurrent(
                disp[cur_c->impl].dpy, impl_draw, impl_read, impl_ctx);
    }
    if (result == EGL_TRUE) {
        loseCurrent(cur_c);
        if (c) {
            c->onMakeCurrent(draw, read);
        }
    }
    return result;
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------
