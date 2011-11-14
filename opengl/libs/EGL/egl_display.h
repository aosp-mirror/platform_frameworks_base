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

#ifndef ANDROID_EGL_DISPLAY_H
#define ANDROID_EGL_DISPLAY_H


#include <ctype.h>
#include <stdint.h>
#include <stdlib.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include <utils/SortedVector.h>
#include <utils/threads.h>

#include "egldefs.h"
#include "hooks.h"

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

class egl_object_t;
class egl_connection_t;

// ----------------------------------------------------------------------------

struct egl_config_t {
    egl_config_t() {}
    egl_config_t(int impl, EGLConfig config)
        : impl(impl), config(config), configId(0), implConfigId(0) { }
    int         impl;           // the implementation this config is for
    EGLConfig   config;         // the implementation's EGLConfig
    EGLint      configId;       // our CONFIG_ID
    EGLint      implConfigId;   // the implementation's CONFIG_ID
    inline bool operator < (const egl_config_t& rhs) const {
        if (impl < rhs.impl) return true;
        if (impl > rhs.impl) return false;
        return config < rhs.config;
    }
};

// ----------------------------------------------------------------------------

class EGLAPI egl_display_t { // marked as EGLAPI for testing purposes
    static egl_display_t sDisplay[NUM_DISPLAYS];
    EGLDisplay getDisplay(EGLNativeDisplayType display);

public:
    enum {
        NOT_INITIALIZED = 0,
        INITIALIZED     = 1,
        TERMINATED      = 2
    };

    egl_display_t();
    ~egl_display_t();

    EGLBoolean initialize(EGLint *major, EGLint *minor);
    EGLBoolean terminate();

    // add object to this display's list
    void addObject(egl_object_t* object);
    // remove object from this display's list
    void removeObject(egl_object_t* object);
    // add reference to this object. returns true if this is a valid object.
    bool getObject(egl_object_t* object) const;


    static egl_display_t* get(EGLDisplay dpy);
    static EGLDisplay getFromNativeDisplay(EGLNativeDisplayType disp);

    inline bool isReady() const { return (refs > 0); }
    inline bool isValid() const { return magic == '_dpy'; }
    inline bool isAlive() const { return isValid(); }

    inline uint32_t getRefsCount() const { return refs; }

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

private:
    uint32_t        magic;

public:
    DisplayImpl     disp[IMPL_NUM_IMPLEMENTATIONS];
    EGLint          numTotalConfigs;
    egl_config_t*   configs;

private:
            uint32_t                    refs;
    mutable Mutex                       lock;
            SortedVector<egl_object_t*> objects;
};

// ----------------------------------------------------------------------------

inline egl_display_t* get_display(EGLDisplay dpy) {
    return egl_display_t::get(dpy);
}

// ----------------------------------------------------------------------------

egl_display_t* validate_display(EGLDisplay dpy);
egl_connection_t* validate_display_config(EGLDisplay dpy,
        EGLConfig config, egl_display_t const*& dp);
EGLBoolean validate_display_context(EGLDisplay dpy, EGLContext ctx);
EGLBoolean validate_display_surface(EGLDisplay dpy, EGLSurface surface);

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

#endif // ANDROID_EGL_DISPLAY_H
