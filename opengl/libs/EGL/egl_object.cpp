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
    display(disp), terminated(0), count(1) {
    display->addObject(this);
}

bool egl_object_t::get() {
    return display->getObject(this);
}

bool egl_object_t::put() {
    return display->removeObject(this);
}

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------
