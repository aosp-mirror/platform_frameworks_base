/*
 * Copyright 2011, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "hooks.h"
#include "gltrace_api.h"
#include "gltrace_hooks.h"

namespace android {
namespace gltrace {

// Hook up all the GLTrace functions
#define GL_ENTRY(_r, _api, ...) GLTrace_ ## _api,
EGLAPI gl_hooks_t gHooksDebug = {
    {
        #include "entries.in"
    },
    {
        {0}
    }
};
#undef GL_ENTRY

gl_hooks_t *getGLHooks() {
    return &gHooksDebug;
}

};
};
