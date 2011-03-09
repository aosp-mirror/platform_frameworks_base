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

namespace android
{
bool capture; // capture after each glDraw*

void * RLEEncode(const void * pixels, const unsigned bytesPerPixel, const unsigned count, unsigned * encodedSize);
}

void Debug_glReadPixels(GLint x, GLint y, GLsizei width, GLsizei height, GLenum format, GLenum type, GLvoid* pixels)
{
    gl_hooks_t::gl_t const * const _c = &getGLTraceThreadSpecific()->gl;
    glesv2debugger::Message msg, cmd;
    msg.set_context_id(0);
    msg.set_type(glesv2debugger::Message_Type_BeforeCall);
    const bool expectResponse = false;
    msg.set_expect_response(expectResponse);
    msg.set_function(glesv2debugger::Message_Function_glReadPixels);
    msg.set_arg0(x);
    msg.set_arg1(y);
    msg.set_arg2(width);
    msg.set_arg3(height);
    msg.set_arg4(format);
    msg.set_arg5(type);
    msg.set_arg6(reinterpret_cast<int>(pixels));
    //void * data = NULL;
    //unsigned encodedSize = 0;
    Send(msg, cmd);
    float t = 0;
    if (!expectResponse)
        cmd.set_function(glesv2debugger::Message_Function_CONTINUE);
    while (true) {
        msg.Clear();
        nsecs_t c0 = systemTime(timeMode);
        switch (cmd.function()) {
        case glesv2debugger::Message_Function_CONTINUE:
            _c->glReadPixels(x, y, width, height, format, type, pixels);
            msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
            msg.set_context_id(0);
            msg.set_function(glesv2debugger::Message_Function_glReadPixels);
            msg.set_type(glesv2debugger::Message_Type_AfterCall);
            msg.set_expect_response(expectResponse);
            //data = RLEEncode(pixels, GetBytesPerPixel(format, type), width * height, &encodedSize);
            msg.set_data(pixels, width * height * GetBytesPerPixel(format, type));
            //msg.set_data(data, encodedSize);
            //free(data);
            c0 = systemTime(timeMode);
            t = Send(msg, cmd);
            msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
            msg.set_clock(t);
            // time is total send time in seconds, clock is msg serialization time in seconds
            msg.clear_data();
            msg.set_expect_response(false);
            msg.set_type(glesv2debugger::Message_Type_AfterCall);
            Send(msg, cmd);
            if (!expectResponse)
                cmd.set_function(glesv2debugger::Message_Function_SKIP);
            break;
        case glesv2debugger::Message_Function_SKIP:
            return;
        default:
            ASSERT(0); //GenerateCall(msg, cmd);
            break;
        }
    }
}

void Debug_glDrawArrays(GLenum mode, GLint first, GLsizei count)
{
    gl_hooks_t::gl_t const * const _c = &getGLTraceThreadSpecific()->gl;
    glesv2debugger::Message msg, cmd;
    msg.set_context_id(0);
    msg.set_type(glesv2debugger::Message_Type_BeforeCall);
    const bool expectResponse = false;
    msg.set_expect_response(expectResponse);
    msg.set_function(glesv2debugger::Message_Function_glDrawArrays);
    msg.set_arg0(mode);
    msg.set_arg1(first);
    msg.set_arg2(count);
    void * data = NULL;
    int viewport[4] = {};
    Send(msg, cmd);
    if (!expectResponse)
        cmd.set_function(glesv2debugger::Message_Function_CONTINUE);
    while (true) {
        msg.Clear();
        nsecs_t c0 = systemTime(timeMode);
        switch (cmd.function()) {
        case glesv2debugger::Message_Function_CONTINUE:
            _c->glDrawArrays(mode, first, count);
            msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
            msg.set_context_id(0);
            msg.set_function(glesv2debugger::Message_Function_glDrawArrays);
            msg.set_type(glesv2debugger::Message_Type_AfterCall);
            msg.set_expect_response(expectResponse);
            Send(msg, cmd);
            if (capture)
                cmd.set_function(glesv2debugger::Message_Function_CAPTURE);
            else if (!expectResponse)
                cmd.set_function(glesv2debugger::Message_Function_SKIP);
            break;
        case glesv2debugger::Message_Function_SKIP:
            return;
        case glesv2debugger::Message_Function_CAPTURE:
            _c->glGetIntegerv(GL_VIEWPORT, viewport);
            LOGD("glDrawArrays CAPTURE: glGetIntegerv GL_VIEWPORT x=%d y=%d width=%d height=%d",
                 viewport[0], viewport[1], viewport[2], viewport[3]);
            data = malloc(viewport[2] * viewport[3] * 4);
            Debug_glReadPixels(viewport[0], viewport[1], viewport[2], viewport[3],
                               GL_RGBA, GL_UNSIGNED_BYTE, data);
            free(data);
            cmd.set_function(glesv2debugger::Message_Function_SKIP);
            break;
        default:
            ASSERT(0); //GenerateCall(msg, cmd);
            break;
        }
    }
}

void Debug_glDrawElements(GLenum mode, GLsizei count, GLenum type, const GLvoid* indices)
{
    gl_hooks_t::gl_t const * const _c = &getGLTraceThreadSpecific()->gl;
    glesv2debugger::Message msg, cmd;
    msg.set_context_id(0);
    msg.set_type(glesv2debugger::Message_Type_BeforeCall);
    const bool expectResponse = false;
    msg.set_expect_response(expectResponse);
    msg.set_function(glesv2debugger::Message_Function_glDrawElements);
    msg.set_arg0(mode);
    msg.set_arg1(count);
    msg.set_arg2(type);
    msg.set_arg3(reinterpret_cast<int>(indices));
    void * data = NULL;
    int viewport[4] = {};
    Send(msg, cmd);
    if (!expectResponse)
        cmd.set_function(glesv2debugger::Message_Function_CONTINUE);
    while (true) {
        msg.Clear();
        nsecs_t c0 = systemTime(timeMode);
        switch (cmd.function()) {
        case glesv2debugger::Message_Function_CONTINUE:
            _c->glDrawElements(mode, count, type, indices);
            msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
            msg.set_context_id(0);
            msg.set_function(glesv2debugger::Message_Function_glDrawElements);
            msg.set_type(glesv2debugger::Message_Type_AfterCall);
            msg.set_expect_response(expectResponse);
            Send(msg, cmd);
            if (capture)
                cmd.set_function(glesv2debugger::Message_Function_CAPTURE);
            else if (!expectResponse)
                cmd.set_function(glesv2debugger::Message_Function_SKIP);
            break;
        case glesv2debugger::Message_Function_SKIP:
            return;
        case glesv2debugger::Message_Function_CAPTURE:
            _c->glGetIntegerv(GL_VIEWPORT, viewport);
            LOGD("glDrawElements CAPTURE: glGetIntegerv GL_VIEWPORT x=%d y=%d width=%d height=%d",
                 viewport[0], viewport[1], viewport[2], viewport[3]);
            data = malloc(viewport[2] * viewport[3] * 4);
            Debug_glReadPixels(viewport[0], viewport[1], viewport[2], viewport[3],
                               GL_RGBA, GL_UNSIGNED_BYTE, data);
            free(data);
            cmd.set_function(glesv2debugger::Message_Function_SKIP);
            break;
        default:
            ASSERT(0); //GenerateCall(msg, cmd);
            break;
        }
    }
}
