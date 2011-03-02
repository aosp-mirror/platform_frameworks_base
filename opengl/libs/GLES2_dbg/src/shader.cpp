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

#include "header.h"

void API_ENTRY(glShaderSource)(GLuint shader, GLsizei count, const GLchar** string, const GLint* length)
{
    gl_hooks_t::gl_t const * const _c = &getGLTraceThreadSpecific()->gl;
    GLESv2Debugger::Message msg, cmd;
    msg.set_context_id(0);
    msg.set_has_next_message(true);
    const bool expectResponse = false;
    msg.set_expect_response(expectResponse);
    msg.set_function(GLESv2Debugger::Message_Function_glShaderSource);
    msg.set_arg0(shader);
    msg.set_arg1(count);
    msg.set_arg2((int)string);
    msg.set_arg3((int)length);

    std::string data;
    for (unsigned i = 0; i < count; i++)
        if (!length || length[i] < 0)
            data.append(string[i]);
        else
            data.append(string[i], length[i]);
    msg.set_data(data);

    Send(msg, cmd);
    if (!expectResponse)
        cmd.set_function(GLESv2Debugger::Message_Function_CONTINUE);
    while (true) {
        msg.Clear();
        clock_t c0 = clock();
        switch (cmd.function()) {
        case GLESv2Debugger::Message_Function_CONTINUE:
            _c->glShaderSource(shader, count, string, length);
            msg.set_time((float(clock()) - c0) / CLOCKS_PER_SEC);
            msg.set_context_id(0);
            msg.set_function(GLESv2Debugger::Message_Function_glShaderSource);
            msg.set_has_next_message(false);
            msg.set_expect_response(expectResponse);
            Send(msg, cmd);
            if (!expectResponse)
                cmd.set_function(GLESv2Debugger::Message_Function_SKIP);
            break;
        case GLESv2Debugger::Message_Function_SKIP:
            return;
        default:
            ASSERT(0); //GenerateCall(msg, cmd);
            break;
        }
    }
}
