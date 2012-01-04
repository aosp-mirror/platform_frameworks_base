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

#include <cutils/log.h>

#include "gltrace.pb.h"
#include "gltrace_context.h"
#include "gltrace_fixup.h"
#include "gltrace_transport.h"

namespace android {
namespace gltrace {

void GLTrace_eglSwapBuffers(void *dpy, void *draw) {
    GLMessage glmessage;
    GLTraceContext *glContext = getGLTraceContext();

    glmessage.set_context_id(1);
    glmessage.set_function(GLMessage::eglSwapBuffers);

    // read FB0 since that is what is displayed on the screen
    fixup_addFBContents(&glmessage, FB0);
    traceGLMessage(&glmessage);
}

};
};
