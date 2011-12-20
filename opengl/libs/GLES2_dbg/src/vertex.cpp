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
}

void Debug_glDrawArrays(GLenum mode, GLint first, GLsizei count)
{
    DbgContext * const dbg = getDbgContextThreadSpecific();
    glesv2debugger::Message msg, cmd;
    msg.set_context_id(reinterpret_cast<int>(dbg));
    msg.set_type(glesv2debugger::Message_Type_BeforeCall);
    bool expectResponse = dbg->expectResponse.Bit(glesv2debugger::Message_Function_glDrawArrays);
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
    int viewport[4] = {};
    cmd.set_function(glesv2debugger::Message_Function_CONTINUE);
    cmd.set_expect_response(expectResponse);
    glesv2debugger::Message_Function oldCmd = cmd.function();
    Send(msg, cmd);
    expectResponse = cmd.expect_response();
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
            if (!expectResponse) {
                cmd.set_function(glesv2debugger::Message_Function_SKIP);
                cmd.set_expect_response(false);
            }
            oldCmd = cmd.function();
            Send(msg, cmd);
            expectResponse = cmd.expect_response();
            // TODO: pack glReadPixels data with vertex data instead of
            //  relying on sperate call for transport, this would allow
            //  auto generated message loop using EXTEND_Debug macro
            if (dbg->captureDraw > 0) {
                dbg->captureDraw--;
                dbg->hooks->gl.glGetIntegerv(GL_VIEWPORT, viewport);
//                ALOGD("glDrawArrays CAPTURE: x=%d y=%d width=%d height=%d format=0x%.4X type=0x%.4X",
//                     viewport[0], viewport[1], viewport[2], viewport[3], readFormat, readType);
                pixels = dbg->GetReadPixelsBuffer(viewport[2] * viewport[3] *
                                                  dbg->readBytesPerPixel);
                Debug_glReadPixels(viewport[0], viewport[1], viewport[2], viewport[3],
                        GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            }
            break;
        case glesv2debugger::Message_Function_SKIP:
            return;
        case glesv2debugger::Message_Function_SETPROP:
            SetProp(dbg, cmd);
            expectResponse = cmd.expect_response();
            if (!expectResponse) // SETPROP is "out of band"
                cmd.set_function(oldCmd);
            else
                Receive(cmd);
            break;
        default:
            GenerateCall(dbg, cmd, msg, NULL);
            msg.set_expect_response(expectResponse);
            if (!expectResponse) {
                cmd.set_function(cmd.SKIP);
                cmd.set_expect_response(expectResponse);
            }
            oldCmd = cmd.function();
            Send(msg, cmd);
            expectResponse = cmd.expect_response();
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
    bool expectResponse = dbg->expectResponse.Bit(glesv2debugger::Message_Function_glDrawElements);
    msg.set_expect_response(expectResponse);
    msg.set_function(glesv2debugger::Message_Function_glDrawElements);
    msg.set_arg0(mode);
    msg.set_arg1(count);
    msg.set_arg2(type);
    msg.set_arg3(reinterpret_cast<int>(indices));

    msg.set_arg7(dbg->maxAttrib); // indicate capturing vertex data
    std::string * const data = msg.mutable_data();
    if (GL_UNSIGNED_BYTE == type) {
        if (dbg->indexBuffer) {
            FetchIndexed(count, (unsigned char *)dbg->indexBuffer->data +
                         (unsigned long)indices, data, dbg);
        } else {
            FetchIndexed(count, (unsigned char *)indices, data, dbg);
        }
    } else if (GL_UNSIGNED_SHORT == type) {
        if (dbg->indexBuffer) {
            FetchIndexed(count, (unsigned short *)((char *)dbg->indexBuffer->data +
                                                   (unsigned long)indices), data, dbg);
        } else {
            FetchIndexed(count, (unsigned short *)indices, data, dbg);
        }
    } else {
        assert(0);
    }

    void * pixels = NULL;
    int viewport[4] = {};
    cmd.set_function(glesv2debugger::Message_Function_CONTINUE);
    cmd.set_expect_response(expectResponse);
    glesv2debugger::Message_Function oldCmd = cmd.function();
    Send(msg, cmd);
    expectResponse = cmd.expect_response();
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
            if (!expectResponse) {
                cmd.set_function(glesv2debugger::Message_Function_SKIP);
                cmd.set_expect_response(false);
            }
            oldCmd = cmd.function();
            Send(msg, cmd);
            expectResponse = cmd.expect_response();
            // TODO: pack glReadPixels data with vertex data instead of
            //  relying on separate call for transport, this would allow
            //  auto generated message loop using EXTEND_Debug macro
            if (dbg->captureDraw > 0) {
                dbg->captureDraw--;
                dbg->hooks->gl.glGetIntegerv(GL_VIEWPORT, viewport);
                pixels = dbg->GetReadPixelsBuffer(viewport[2] * viewport[3] *
                                                  dbg->readBytesPerPixel);
                Debug_glReadPixels(viewport[0], viewport[1], viewport[2], viewport[3],
                        GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            }
            break;
        case glesv2debugger::Message_Function_SKIP:
            return;
        case glesv2debugger::Message_Function_SETPROP:
            SetProp(dbg, cmd);
            expectResponse = cmd.expect_response();
            if (!expectResponse) // SETPROP is "out of band"
                cmd.set_function(oldCmd);
            else
                Receive(cmd);
            break;
        default:
            GenerateCall(dbg, cmd, msg, NULL);
            msg.set_expect_response(expectResponse);
            if (!expectResponse) {
                cmd.set_function(cmd.SKIP);
                cmd.set_expect_response(expectResponse);
            }
            oldCmd = cmd.function();
            Send(msg, cmd);
            expectResponse = cmd.expect_response();
            break;
        }
    }
}
