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
#include <utils/Timers.h>

#include "gltrace.pb.h"
#include "gltrace_context.h"
#include "gltrace_fixup.h"
#include "gltrace_transport.h"

namespace android {
namespace gltrace {

void GLTrace_eglCreateContext(int version, int contextId) {
    GLMessage glmessage;
    GLTraceContext *glContext = getGLTraceContext();

    glmessage.set_context_id(contextId);
    glmessage.set_function(GLMessage::eglCreateContext);

    // copy argument version
    GLMessage_DataType *arg_version = glmessage.add_args();
    arg_version->set_isarray(false);
    arg_version->set_type(GLMessage::DataType::INT);
    arg_version->add_intvalue(version);

    // copy argument context
    GLMessage_DataType *arg_context = glmessage.add_args();
    arg_context->set_isarray(false);
    arg_context->set_type(GLMessage::DataType::INT);
    arg_context->add_intvalue(contextId);

    // set start time and duration
    glmessage.set_start_time(systemTime());
    glmessage.set_duration(0);

    glContext->traceGLMessage(&glmessage);
}

void GLTrace_eglMakeCurrent(int contextId) {
    GLMessage glmessage;
    GLTraceContext *glContext = getGLTraceContext();

    glmessage.set_context_id(contextId);
    glmessage.set_function(GLMessage::eglMakeCurrent);

    // copy argument context
    GLMessage_DataType *arg_context = glmessage.add_args();
    arg_context->set_isarray(false);
    arg_context->set_type(GLMessage::DataType::INT);
    arg_context->add_intvalue(contextId);

    // set start time and duration
    glmessage.set_start_time(systemTime());
    glmessage.set_duration(0);

    glContext->traceGLMessage(&glmessage);
}

void GLTrace_eglSwapBuffers(void *dpy, void *draw) {
    GLMessage glmessage;
    GLTraceContext *glContext = getGLTraceContext();

    glmessage.set_context_id(glContext->getId());
    glmessage.set_function(GLMessage::eglSwapBuffers);

    if (glContext->getGlobalTraceState()->shouldCollectFbOnEglSwap()) {
        // read FB0 since that is what is displayed on the screen
        fixup_addFBContents(glContext, &glmessage, FB0);
    }

    // set start time and duration
    glmessage.set_start_time(systemTime());
    glmessage.set_duration(0);

    glContext->traceGLMessage(&glmessage);
}

};
};
