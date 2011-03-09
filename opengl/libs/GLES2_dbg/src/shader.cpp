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

void Debug_glShaderSource(GLuint shader, GLsizei count, const GLchar** string, const GLint* length)
{
    glesv2debugger::Message msg;
    const bool expectResponse = false;
    struct : public FunctionCall {
        GLuint shader;
        GLsizei count;
        const GLchar** string;
        const GLint* length;

        const int * operator()(gl_hooks_t::gl_t const * const _c, glesv2debugger::Message & msg) {
            nsecs_t c0 = systemTime(timeMode);
            _c->glShaderSource(shader, count, string, length);
            msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
            return 0;
        }
    } caller;
    caller.shader = shader;
    caller.count = count;
    caller.string = string;
    caller.length = length;

    msg.set_arg0(shader);
    msg.set_arg1(count);
    msg.set_arg2(reinterpret_cast<int>(string));
    msg.set_arg3(reinterpret_cast<int>(length));

    std::string data;
    for (unsigned i = 0; i < count; i++)
        if (!length || length[i] < 0)
            data.append(string[i]);
        else
            data.append(string[i], length[i]);
    msg.set_data(data);
    
    int * ret = MessageLoop(caller, msg, expectResponse,
                            glesv2debugger::Message_Function_glShaderSource);
}