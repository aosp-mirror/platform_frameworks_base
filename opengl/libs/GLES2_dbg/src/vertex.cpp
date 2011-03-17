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
    DbgContext * const dbg = getDbgContextThreadSpecific();
    glesv2debugger::Message msg, cmd;
    msg.set_context_id(reinterpret_cast<int>(dbg));
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
    if (!expectResponse)
        cmd.set_function(glesv2debugger::Message_Function_CONTINUE);
    Send(msg, cmd);
    float t = 0;
    while (true) {
        msg.Clear();
        nsecs_t c0 = systemTime(timeMode);
        switch (cmd.function()) {
        case glesv2debugger::Message_Function_CONTINUE:
            dbg->hooks->gl.glReadPixels(x, y, width, height, format, type, pixels);
            msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
            msg.set_context_id(reinterpret_cast<int>(dbg));
            msg.set_function(glesv2debugger::Message_Function_glReadPixels);
            msg.set_type(glesv2debugger::Message_Type_AfterCall);
            msg.set_expect_response(expectResponse);
            //data = RLEEncode(pixels, GetBytesPerPixel(format, type), width * height, &encodedSize);
            msg.set_data(pixels, width * height * GetBytesPerPixel(format, type));
            //msg.set_data(data, encodedSize);
            //free(data);
            c0 = systemTime(timeMode);
            if (!expectResponse)
                cmd.set_function(glesv2debugger::Message_Function_SKIP);
            t = Send(msg, cmd);
            msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
            msg.set_clock(t);
            // time is total send time in seconds, clock is msg serialization time in seconds
            msg.clear_data();
            msg.set_expect_response(false);
            msg.set_type(glesv2debugger::Message_Type_AfterCall);
            //Send(msg, cmd);
            break;
        case glesv2debugger::Message_Function_SKIP:
            return;
        case glesv2debugger::Message_Function_SETPROP:
            SetProp(cmd);
            Receive(cmd);
            break;
        default:
            assert(0); //GenerateCall(msg, cmd);
            break;
        }
    }
}

void Debug_glDrawArrays(GLenum mode, GLint first, GLsizei count)
{
    DbgContext * const dbg = getDbgContextThreadSpecific();
    glesv2debugger::Message msg, cmd;
    msg.set_context_id(reinterpret_cast<int>(dbg));
    msg.set_type(glesv2debugger::Message_Type_BeforeCall);
    const bool expectResponse = false;
    msg.set_expect_response(expectResponse);
    msg.set_function(glesv2debugger::Message_Function_glDrawArrays);
    msg.set_arg0(mode);
    msg.set_arg1(first);
    msg.set_arg2(count);

    msg.set_arg7(dbg->maxAttrib); // indicate capturing vertex data
    if (dbg->hasNonVBOAttribs) {
        std::string * const data = msg.mutable_data();
        for (unsigned i = 0; i < count; i++)
            dbg->Fetch(i + first, data);
    }

    void * pixels = NULL;
    GLint readFormat = 0, readType = 0;
    int viewport[4] = {};
    if (!expectResponse)
        cmd.set_function(glesv2debugger::Message_Function_CONTINUE);
    Send(msg, cmd);
    while (true) {
        msg.Clear();
        nsecs_t c0 = systemTime(timeMode);
        switch (cmd.function()) {
        case glesv2debugger::Message_Function_CONTINUE:
            dbg->hooks->gl.glDrawArrays(mode, first, count);
            msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
            msg.set_context_id(reinterpret_cast<int>(dbg));
            msg.set_function(glesv2debugger::Message_Function_glDrawArrays);
            msg.set_type(glesv2debugger::Message_Type_AfterCall);
            msg.set_expect_response(expectResponse);
            if (!expectResponse)
                cmd.set_function(glesv2debugger::Message_Function_SKIP);
            Send(msg, cmd);
            if (capture) {
                dbg->hooks->gl.glGetIntegerv(GL_VIEWPORT, viewport);
                dbg->hooks->gl.glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT, &readFormat);
                dbg->hooks->gl.glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE, &readType);
                LOGD("glDrawArrays CAPTURE: x=%d y=%d width=%d height=%d format=0x%.4X type=0x%.4X",
                     viewport[0], viewport[1], viewport[2], viewport[3], readFormat, readType);
                pixels = malloc(viewport[2] * viewport[3] * 4);
                Debug_glReadPixels(viewport[0], viewport[1], viewport[2], viewport[3],
                                   readFormat, readType, pixels);
                free(pixels);
            }
            break;
        case glesv2debugger::Message_Function_SKIP:
            return;
        case glesv2debugger::Message_Function_SETPROP:
            SetProp(cmd);
            Receive(cmd);
            break;
        default:
            assert(0); //GenerateCall(msg, cmd);
            break;
        }
    }
}

template<typename T>
static inline void FetchIndexed(const unsigned count, const T * indices,
                                std::string * const data, const DbgContext * const ctx)
{
    for (unsigned i = 0; i < count; i++) {
        if (!ctx->indexBuffer)
            data->append((const char *)(indices + i), sizeof(*indices));
        if (ctx->hasNonVBOAttribs)
            ctx->Fetch(indices[i], data);
    }
}

void Debug_glDrawElements(GLenum mode, GLsizei count, GLenum type, const GLvoid* indices)
{
    DbgContext * const dbg = getDbgContextThreadSpecific();
    glesv2debugger::Message msg, cmd;
    msg.set_context_id(reinterpret_cast<int>(dbg));
    msg.set_type(glesv2debugger::Message_Type_BeforeCall);
    const bool expectResponse = false;
    msg.set_expect_response(expectResponse);
    msg.set_function(glesv2debugger::Message_Function_glDrawElements);
    msg.set_arg0(mode);
    msg.set_arg1(count);
    msg.set_arg2(type);
    msg.set_arg3(reinterpret_cast<int>(indices));

    msg.set_arg7(dbg->maxAttrib); // indicate capturing vertex data
    std::string * const data = msg.mutable_data();
    if (GL_UNSIGNED_BYTE == type) {
        if (dbg->indexBuffer)
            FetchIndexed(count, (unsigned char *)dbg->indexBuffer->data + (unsigned long)indices, data, dbg);
        else
            FetchIndexed(count, (unsigned char *)indices, data, dbg);
    } else if (GL_UNSIGNED_SHORT == type) {
        if (dbg->indexBuffer)
            FetchIndexed(count, (unsigned short *)((char *)dbg->indexBuffer->data + (unsigned long)indices), data, dbg);
        else
            FetchIndexed(count, (unsigned short *)indices, data, dbg);
    } else
        assert(0);

    void * pixels = NULL;
    GLint readFormat = 0, readType = 0;
    int viewport[4] = {};
    if (!expectResponse)
        cmd.set_function(glesv2debugger::Message_Function_CONTINUE);
    Send(msg, cmd);
    while (true) {
        msg.Clear();
        nsecs_t c0 = systemTime(timeMode);
        switch (cmd.function()) {
        case glesv2debugger::Message_Function_CONTINUE:
            dbg->hooks->gl.glDrawElements(mode, count, type, indices);
            msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
            msg.set_context_id(reinterpret_cast<int>(dbg));
            msg.set_function(glesv2debugger::Message_Function_glDrawElements);
            msg.set_type(glesv2debugger::Message_Type_AfterCall);
            msg.set_expect_response(expectResponse);
            if (!expectResponse)
                cmd.set_function(glesv2debugger::Message_Function_SKIP);
            Send(msg, cmd);
            if (capture) {
                dbg->hooks->gl.glGetIntegerv(GL_VIEWPORT, viewport);
                dbg->hooks->gl.glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT, &readFormat);
                dbg->hooks->gl.glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE, &readType);
                LOGD("glDrawArrays CAPTURE: x=%d y=%d width=%d height=%d format=0x%.4X type=0x%.4X",
                     viewport[0], viewport[1], viewport[2], viewport[3], readFormat, readType);
                pixels = malloc(viewport[2] * viewport[3] * 4);
                Debug_glReadPixels(viewport[0], viewport[1], viewport[2], viewport[3],
                                   readFormat, readType, pixels);
                free(pixels);
            }
            break;
        case glesv2debugger::Message_Function_SKIP:
            return;
        case glesv2debugger::Message_Function_SETPROP:
            SetProp(cmd);
            Receive(cmd);
            break;
        default:
            assert(0); //GenerateCall(msg, cmd);
            break;
        }
    }
}
