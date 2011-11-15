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
#include <stdint.h>
#include <stdlib.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include <utils/threads.h>

#include "egl_object.h"

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

egl_object_t::egl_object_t(egl_display_t* disp) :
    display(disp), count(1) {
    // NOTE: this does an implicit incRef
    display->addObject(this);
}

egl_object_t::~egl_object_t() {
}

void egl_object_t::terminate() {
    // this marks the object as "terminated"
    display->removeObject(this);
    if (decRef() == 1) {
        // shouldn't happen because this is called from LocalRef
        LOGE("egl_object_t::terminate() removed the last reference!");
    }
}

void egl_object_t::destroy() {
    if (decRef() == 1) {
        delete this;
    }
}

bool egl_object_t::get(egl_display_t const* display, egl_object_t* object) {
    // used by LocalRef, this does an incRef() atomically with
    // checking that the object is valid.
    return display->getObject(object);
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------
