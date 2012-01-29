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

#ifndef ANDROID_EGLDEFS_H
#define ANDROID_EGLDEFS_H

#include "hooks.h"

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

#define VERSION_MAJOR 1
#define VERSION_MINOR 4

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

struct egl_connection_t
{
    inline egl_connection_t() : dso(0) { }
    void *              dso;
    gl_hooks_t *        hooks[2];
    EGLint              major;
    EGLint              minor;
    egl_t               egl;
};

// ----------------------------------------------------------------------------

extern gl_hooks_t gHooks[2][IMPL_NUM_IMPLEMENTATIONS];
extern gl_hooks_t gHooksNoContext;
extern pthread_key_t gGLWrapperKey;
extern "C" void gl_unimplemented();
extern "C" void gl_noop();

extern char const * const gl_names[];
extern char const * const egl_names[];

extern egl_connection_t gEGLImpl[IMPL_NUM_IMPLEMENTATIONS];

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

#endif /* ANDROID_EGLDEFS_H */
