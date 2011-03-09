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

static inline void GetFormatAndBytesPerPixel(const GLenum format, unsigned & bytesPerPixel)
{
    switch (format) {
    case GL_ALPHA:
        bytesPerPixel = 1;
        break;
    case GL_LUMINANCE:
        bytesPerPixel = 1;
        break;
    case GL_LUMINANCE_ALPHA:
        bytesPerPixel = 2;
        break;
    case GL_RGB:
        bytesPerPixel = 3;
        break;
    case GL_RGBA:
        bytesPerPixel = 4;
        break;

        // internal formats to avoid conversion
    case GL_UNSIGNED_SHORT_5_6_5:
        bytesPerPixel = 2;
        break;
    case GL_UNSIGNED_SHORT_4_4_4_4:
        bytesPerPixel = 2;
        break;
    case GL_UNSIGNED_SHORT_5_5_5_1:
        bytesPerPixel = 2;
        break;
    default:
        assert(0);
        return;
    }
}

#define USE_RLE 0
#if USE_RLE
template<typename T>
void * RLEEncode(const void * pixels, unsigned count, unsigned * encodedSize)
{
    // first is a byte indicating data size [1,2,4] bytes
    // then an unsigned indicating decompressed size
    // then a byte of header: MSB == 1 indicates run, else literal
    // LSB7 is run or literal length (actual length - 1)

    const T * data = (T *)pixels;
    unsigned bufferSize = sizeof(T) * count / 2 + 8;
    unsigned char * buffer = (unsigned char *)malloc(bufferSize);
    buffer[0] = sizeof(T);
    unsigned bufferWritten = 1; // number of bytes written
    *(unsigned *)(buffer + bufferWritten) = count;
    bufferWritten += sizeof(count);
    while (count) {
        unsigned char run = 1;
        bool repeat = true;
        for (run = 1; run < count; run++)
            if (data[0] != data[run]) {
                repeat = false;
                break;
            } else if (run > 127)
                break;
        if (!repeat) {
            // find literal length
            for (run = 1; run < count; run++)
                if (data[run - 1] == data[run])
                    break;
                else if (run > 127)
                    break;
            unsigned bytesToWrite = 1 + sizeof(T) * run;
            if (bufferWritten + bytesToWrite > bufferSize) {
                bufferSize += sizeof(T) * run + 256;
                buffer = (unsigned char *)realloc(buffer, bufferSize);
            }
            buffer[bufferWritten++] = run - 1;
            for (unsigned i = 0; i < run; i++) {
                *(T *)(buffer + bufferWritten) = *data;
                bufferWritten += sizeof(T);
                data++;
            }
            count -= run;
        } else {
            unsigned bytesToWrite = 1 + sizeof(T);
            if (bufferWritten + bytesToWrite > bufferSize) {
                bufferSize += 256;
                buffer = (unsigned char *)realloc(buffer, bufferSize);
            }
            buffer[bufferWritten++] = (run - 1) | 0x80;
            *(T *)(buffer + bufferWritten) = data[0];
            bufferWritten += sizeof(T);
            data += run;
            count -= run;
        }
    }
    if (encodedSize)
        *encodedSize = bufferWritten;
    return buffer;
}

void * RLEEncode(const void * pixels, const unsigned bytesPerPixel, const unsigned count, unsigned * encodedSize)
{
    switch (bytesPerPixel) {
    case 4:
        return RLEEncode<int>(pixels, count, encodedSize);
    case 2:
        return RLEEncode<short>(pixels, count, encodedSize);
    case 1:
        return RLEEncode<char>(pixels, count, encodedSize);
    default:
        assert(0);
        return NULL;
    }
}
#endif

void API_ENTRY(glTexImage2D)(GLenum target, GLint level, GLint internalformat, GLsizei width,
                             GLsizei height, GLint border, GLenum format, GLenum type, const GLvoid * pixels)
{
//    LOGD("\n*\n* GLESv2_dbg: %s \n*", "glTexImage2D");
    gl_hooks_t::gl_t const * const _c = &getGLTraceThreadSpecific()->gl;

    GLESv2Debugger::Message msg, cmd;
    msg.set_context_id(0);
    msg.set_has_next_message(true);
    const bool expectResponse = false;
    msg.set_expect_response(expectResponse);
    msg.set_function(GLESv2Debugger::Message_Function_glTexImage2D);

    msg.set_arg0(target);
    msg.set_arg1(level);
    msg.set_arg2(internalformat);
    msg.set_arg3(width);
    msg.set_arg4(height);
    msg.set_arg5(border);
    msg.set_arg6(format);
    msg.set_arg7(type);

    if (pixels) {
        assert(internalformat == format);
        assert(0 == border);

        GLenum newFormat = internalformat;
        switch (type) {
        case GL_UNSIGNED_BYTE:
            break;
        case GL_UNSIGNED_SHORT_5_6_5:
        case GL_UNSIGNED_SHORT_4_4_4_4:
        case GL_UNSIGNED_SHORT_5_5_5_1:
            newFormat = type;
            break;
        default:
            LOGD("GLESv2_dbg: glTexImage2D type=0x%.4X", type);
            assert(0);
        }
        unsigned bytesPerPixel = 0;
        GetFormatAndBytesPerPixel(newFormat, bytesPerPixel);
        assert(0 < bytesPerPixel);

//        LOGD("GLESv2_dbg: glTexImage2D width=%d height=%d level=%d bytesPerPixel=%d",
//             width, height, level, bytesPerPixel);
#if USE_RLE
        unsigned encodedSize = 0;
        void * data = RLEEncode(pixels, bytesPerPixel, width * height, &encodedSize);
        msg.set_data(data, encodedSize);
        free(data);
        if (encodedSize > bytesPerPixel * width * height)
            LOGD("GLESv2_dbg: glTexImage2D sending data encodedSize=%d size=%d", encodedSize, bytesPerPixel * width * height);
#else
        msg.set_data(pixels, bytesPerPixel * width * height);
#endif
    }
    assert(msg.has_arg3());
    Send(msg, cmd);
    if (!expectResponse)
        cmd.set_function(GLESv2Debugger::Message_Function_CONTINUE);

    while (true) {
        msg.Clear();
        clock_t c0 = clock();
        switch (cmd.function()) {
        case GLESv2Debugger::Message_Function_CONTINUE:
            _c->glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
            msg.set_time((float(clock()) - c0) / CLOCKS_PER_SEC);
            msg.set_context_id(0);
            msg.set_function(GLESv2Debugger::Message_Function_glTexImage2D);
            msg.set_has_next_message(false);
            msg.set_expect_response(expectResponse);
            assert(!msg.has_arg3());
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

void API_ENTRY(glTexSubImage2D)(GLenum target, GLint level, GLint xoffset, GLint yoffset,
                                    GLsizei width, GLsizei height, GLenum format, GLenum type, const GLvoid* pixels)
{
//    LOGD("\n*\n* GLESv2_dbg: %s \n*", "glTexSubImage2D");
    gl_hooks_t::gl_t const * const _c = &getGLTraceThreadSpecific()->gl;

    GLESv2Debugger::Message msg, cmd;
    msg.set_context_id(0);
    msg.set_has_next_message(true);
    const bool expectResponse = false;
    msg.set_expect_response(expectResponse);
    msg.set_function(GLESv2Debugger::Message_Function_glTexSubImage2D);

    msg.set_arg0(target);
    msg.set_arg1(level);
    msg.set_arg2(xoffset);
    msg.set_arg3(yoffset);
    msg.set_arg4(width);
    msg.set_arg5(height);
    msg.set_arg6(format);
    msg.set_arg7(type);

    assert(pixels);
    if (pixels) {
        GLenum newFormat = format;
        switch (type) {
        case GL_UNSIGNED_BYTE:
            break;
        case GL_UNSIGNED_SHORT_5_6_5:
        case GL_UNSIGNED_SHORT_4_4_4_4:
        case GL_UNSIGNED_SHORT_5_5_5_1:
            newFormat = type;
            break;
        default:
            assert(0);
        }
        unsigned bytesPerPixel = 0;
        GetFormatAndBytesPerPixel(newFormat, bytesPerPixel);
        assert(0 < bytesPerPixel);

//        LOGD("GLESv2_dbg: glTexSubImage2D width=%d height=%d level=%d bytesPerPixel=%d",
//             width, height, level, bytesPerPixel);

#if USE_RLE
        unsigned encodedSize = 0;
        void * data = RLEEncode(pixels, bytesPerPixel, width * height, &encodedSize);
        msg.set_data(data, encodedSize);
        free(data);
        if (encodedSize > bytesPerPixel * width * height)
            LOGD("GLESv2_dbg: glTexImage2D sending data encodedSize=%d size=%d", encodedSize, bytesPerPixel * width * height);
#else
        msg.set_data(pixels, bytesPerPixel * width * height);
#endif
    }

    Send(msg, cmd);
    if (!expectResponse)
        cmd.set_function(GLESv2Debugger::Message_Function_CONTINUE);

    while (true) {
        msg.Clear();
        clock_t c0 = clock();
        switch (cmd.function()) {
        case GLESv2Debugger::Message_Function_CONTINUE:
            _c->glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
            msg.set_time((float(clock()) - c0) / CLOCKS_PER_SEC);
            msg.set_function(GLESv2Debugger::Message_Function_glTexImage2D);
            msg.set_context_id(0);
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
