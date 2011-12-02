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

#include <stdlib.h>
#include <cutils/log.h>
#include <cutils/properties.h>

#include "hooks.h"
#include "glestrace.h"

#include "gltrace_context.h"
#include "gltrace_egl.h"
#include "gltrace_hooks.h"
#include "gltrace_transport.h"

namespace android {

void GLTrace_eglMakeCurrent(const unsigned version, gl_hooks_t *hooks) {
    gltrace::initContext(version, hooks);
}

void GLTrace_eglReleaseThread() {
    gltrace::releaseContext();
}

void GLTrace_eglCreateContext(int version, EGLContext c) {
    // TODO
}

void GLTrace_start() {
    char value[PROPERTY_VALUE_MAX];

    property_get("debug.egl.debug_port", value, "5039");
    const unsigned short port = (unsigned short)atoi(value);

    gltrace::startServer(port);
}

void GLTrace_stop() {
    gltrace::stopServer();
}

gl_hooks_t *GLTrace_getGLHooks() {
    return gltrace::getGLHooks();
}

void GLTrace_eglSwapBuffers(void *dpy, void *draw) {
    gltrace::GLTrace_eglSwapBuffers(dpy, draw);
}

}
