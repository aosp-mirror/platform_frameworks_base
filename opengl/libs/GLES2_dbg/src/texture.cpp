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
unsigned GetBytesPerPixel(const GLenum format, const GLenum type)
{
    switch (type) {
    case GL_UNSIGNED_SHORT_5_6_5:
        return 2;
    case GL_UNSIGNED_SHORT_4_4_4_4:
        return 2;
    case GL_UNSIGNED_SHORT_5_5_5_1:
        return 2;
    case GL_UNSIGNED_BYTE:
        break;
    default:
        assert(0);
    }

    switch (format) {
    case GL_ALPHA:
        return 1;
    case GL_LUMINANCE:
        return 1;
        break;
    case GL_LUMINANCE_ALPHA:
        return 2;
    case GL_RGB:
        return 3;
    case GL_RGBA:
        return 4;
    default:
        assert(0);
        return 0;
    }
}
}; // namespace android

void Debug_glTexImage2D(GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLint border, GLenum format, GLenum type, const GLvoid* pixels)
{
    DbgContext * const dbg = getDbgContextThreadSpecific();
    glesv2debugger::Message msg;
    const bool expectResponse = false;
    struct : public FunctionCall {
        GLenum target;
        GLint level;
        GLint internalformat;
        GLsizei width;
        GLsizei height;
        GLint border;
        GLenum format;
        GLenum type;
        const GLvoid* pixels;

        const int * operator()(gl_hooks_t::gl_t const * const _c, glesv2debugger::Message & msg) {
            nsecs_t c0 = systemTime(timeMode);
            _c->glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
            msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
            return 0;
        }
    } caller;
    caller.target = target;
    caller.level = level;
    caller.internalformat = internalformat;
    caller.width = width;
    caller.height = height;
    caller.border = border;
    caller.format = format;
    caller.type = type;
    caller.pixels = pixels;

    msg.set_arg0(target);
    msg.set_arg1(level);
    msg.set_arg2(internalformat);
    msg.set_arg3(width);
    msg.set_arg4(height);
    msg.set_arg5(border);
    msg.set_arg6(format);
    msg.set_arg7(type);
    msg.set_arg8(reinterpret_cast<int>(pixels));

    if (pixels) {
        assert(internalformat == format);
        assert(0 == border);

        unsigned bytesPerPixel = GetBytesPerPixel(format, type);
        assert(0 < bytesPerPixel);
//        LOGD("GLESv2_dbg: glTexImage2D width=%d height=%d level=%d bytesPerPixel=%d",
//             width, height, level, bytesPerPixel);
        unsigned compressedSize = dbg->Compress(pixels, bytesPerPixel * width * height);
        msg.set_data(dbg->lzf_buf, compressedSize);
    }

    int * ret = MessageLoop(caller, msg, expectResponse,
                            glesv2debugger::Message_Function_glTexImage2D);
}

void Debug_glTexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width, GLsizei height, GLenum format, GLenum type, const GLvoid* pixels)
{
    DbgContext * const dbg = getDbgContextThreadSpecific();
    glesv2debugger::Message msg;
    const bool expectResponse = false;
    struct : public FunctionCall {
        GLenum target;
        GLint level;
        GLint xoffset;
        GLint yoffset;
        GLsizei width;
        GLsizei height;
        GLenum format;
        GLenum type;
        const GLvoid* pixels;

        const int * operator()(gl_hooks_t::gl_t const * const _c, glesv2debugger::Message & msg) {
            nsecs_t c0 = systemTime(timeMode);
            _c->glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
            msg.set_time((systemTime(timeMode) - c0) * 1e-6f);
            return 0;
        }
    } caller;
    caller.target = target;
    caller.level = level;
    caller.xoffset = xoffset;
    caller.yoffset = yoffset;
    caller.width = width;
    caller.height = height;
    caller.format = format;
    caller.type = type;
    caller.pixels = pixels;

    msg.set_arg0(target);
    msg.set_arg1(level);
    msg.set_arg2(xoffset);
    msg.set_arg3(yoffset);
    msg.set_arg4(width);
    msg.set_arg5(height);
    msg.set_arg6(format);
    msg.set_arg7(type);
    msg.set_arg8(reinterpret_cast<int>(pixels));

    assert(pixels);
    if (pixels) {
        unsigned bytesPerPixel = GetBytesPerPixel(format, type);
        assert(0 < bytesPerPixel);
//        LOGD("GLESv2_dbg: glTexSubImage2D width=%d height=%d level=%d bytesPerPixel=%d",
//             width, height, level, bytesPerPixel);
        unsigned compressedSize = dbg->Compress(pixels, bytesPerPixel * width * height);
        msg.set_data(dbg->lzf_buf, compressedSize);
    }

    int * ret = MessageLoop(caller, msg, expectResponse,
                            glesv2debugger::Message_Function_glTexSubImage2D);
}
