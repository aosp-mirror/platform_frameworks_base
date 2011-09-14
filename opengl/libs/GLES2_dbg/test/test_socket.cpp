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

#include <sys/socket.h>
#include <sys/ioctl.h>

#include "header.h"
#include "gtest/gtest.h"
#include "hooks.h"

namespace android
{
extern int serverSock, clientSock;
};

void * glNoop();

class SocketContextTest : public ::testing::Test
{
protected:
    DbgContext* dbg;
    gl_hooks_t hooks;
    int sock;
    char * buffer;
    unsigned int bufferSize;

    SocketContextTest() : sock(-1) {
    }

    virtual ~SocketContextTest() {
    }

    virtual void SetUp() {
        dbg = new DbgContext(1, &hooks, 32);
        ASSERT_TRUE(dbg != NULL);
        for (unsigned int i = 0; i < sizeof(hooks) / sizeof(void *); i++)
            ((void **)&hooks)[i] = (void *)glNoop;

        int socks[2] = {-1, -1};
        ASSERT_EQ(0, socketpair(AF_UNIX, SOCK_STREAM, 0, socks));
        clientSock = socks[0];
        sock = socks[1];

        bufferSize = 128;
        buffer = new char [128];
        ASSERT_NE((char *)NULL, buffer);
    }

    virtual void TearDown() {
        close(sock);
        close(clientSock);
        clientSock = -1;
        delete buffer;
    }

    void Write(glesv2debugger::Message & msg) const {
        msg.set_context_id((int)dbg);
        msg.set_type(msg.Response);
        ASSERT_TRUE(msg.has_context_id());
        ASSERT_TRUE(msg.has_function());
        ASSERT_TRUE(msg.has_type());
        ASSERT_TRUE(msg.has_expect_response());
        static std::string str;
        msg.SerializeToString(&str);
        const uint32_t len = str.length();
        ASSERT_EQ(sizeof(len), send(sock, &len, sizeof(len), 0));
        ASSERT_EQ(str.length(), send(sock, str.data(), str.length(), 0));
    }

    void Read(glesv2debugger::Message & msg) {
        int available = 0;
        ASSERT_EQ(0, ioctl(sock, FIONREAD, &available));
        ASSERT_GT(available, 0);
        uint32_t len = 0;
        ASSERT_EQ(sizeof(len), recv(sock, &len, sizeof(len), 0));
        if (len > bufferSize) {
            bufferSize = len;
            buffer = new char[bufferSize];
            ASSERT_TRUE(buffer != NULL);
        }
        ASSERT_EQ(len, recv(sock, buffer, len, 0));
        msg.Clear();
        msg.ParseFromArray(buffer, len);
        ASSERT_TRUE(msg.has_context_id());
        ASSERT_TRUE(msg.has_function());
        ASSERT_TRUE(msg.has_type());
        ASSERT_TRUE(msg.has_expect_response());
    }

    void CheckNoAvailable() {
        int available = 0;
        ASSERT_EQ(0, ioctl(sock, FIONREAD, &available));
        ASSERT_EQ(available, 0);
    }
};

TEST_F(SocketContextTest, MessageLoopSkip)
{
    static const int arg0 = 45;
    static const float arg7 = -87.2331f;
    static const int arg8 = -3;
    static const int * ret = (int *)870;

    struct Caller : public FunctionCall {
        const int * operator()(gl_hooks_t::gl_t const * const _c, glesv2debugger::Message & msg) {
            msg.set_arg0(arg0);
            msg.set_arg7((int &)arg7);
            msg.set_arg8(arg8);
            return ret;
        }
    } caller;
    glesv2debugger::Message msg, read, cmd;
    dbg->expectResponse.Bit(msg.glFinish, true);

    cmd.set_function(cmd.SKIP);
    cmd.set_expect_response(false);
    Write(cmd);

    EXPECT_NE(ret, MessageLoop(caller, msg, msg.glFinish));

    Read(read);
    EXPECT_EQ(read.glFinish, read.function());
    EXPECT_EQ(read.BeforeCall, read.type());
    EXPECT_NE(arg0, read.arg0());
    EXPECT_NE((int &)arg7, read.arg7());
    EXPECT_NE(arg8, read.arg8());

    CheckNoAvailable();
}

TEST_F(SocketContextTest, MessageLoopContinue)
{
    static const int arg0 = GL_FRAGMENT_SHADER;
    static const int ret = -342;
    struct Caller : public FunctionCall {
        const int * operator()(gl_hooks_t::gl_t const * const _c, glesv2debugger::Message & msg) {
            msg.set_ret(ret);
            return (int *)ret;
        }
    } caller;
    glesv2debugger::Message msg, read, cmd;
    dbg->expectResponse.Bit(msg.glCreateShader, true);

    cmd.set_function(cmd.CONTINUE);
    cmd.set_expect_response(false); // MessageLoop should automatically skip after continue
    Write(cmd);

    msg.set_arg0(arg0);
    EXPECT_EQ((int *)ret, MessageLoop(caller, msg, msg.glCreateShader));

    Read(read);
    EXPECT_EQ(read.glCreateShader, read.function());
    EXPECT_EQ(read.BeforeCall, read.type());
    EXPECT_EQ(arg0, read.arg0());

    Read(read);
    EXPECT_EQ(read.glCreateShader, read.function());
    EXPECT_EQ(read.AfterCall, read.type());
    EXPECT_EQ(ret, read.ret());

    CheckNoAvailable();
}

TEST_F(SocketContextTest, MessageLoopGenerateCall)
{
    static const int ret = -342;
    static unsigned int createShader, createProgram;
    createShader = 0;
    createProgram = 0;
    struct Caller : public FunctionCall {
        const int * operator()(gl_hooks_t::gl_t const * const _c, glesv2debugger::Message & msg) {
            const int r = (int)_c->glCreateProgram();
            msg.set_ret(r);
            return (int *)r;
        }
        static GLuint CreateShader(const GLenum type) {
            createShader++;
            return type;
        }
        static GLuint CreateProgram() {
            createProgram++;
            return ret;
        }
    } caller;
    glesv2debugger::Message msg, read, cmd;
    hooks.gl.glCreateShader = caller.CreateShader;
    hooks.gl.glCreateProgram = caller.CreateProgram;
    dbg->expectResponse.Bit(msg.glCreateProgram, true);

    cmd.set_function(cmd.glCreateShader);
    cmd.set_arg0(GL_FRAGMENT_SHADER);
    cmd.set_expect_response(true);
    Write(cmd);

    cmd.Clear();
    cmd.set_function(cmd.CONTINUE);
    cmd.set_expect_response(true);
    Write(cmd);

    cmd.set_function(cmd.glCreateShader);
    cmd.set_arg0(GL_VERTEX_SHADER);
    cmd.set_expect_response(false); // MessageLoop should automatically skip afterwards
    Write(cmd);

    EXPECT_EQ((int *)ret, MessageLoop(caller, msg, msg.glCreateProgram));

    Read(read);
    EXPECT_EQ(read.glCreateProgram, read.function());
    EXPECT_EQ(read.BeforeCall, read.type());

    Read(read);
    EXPECT_EQ(read.glCreateShader, read.function());
    EXPECT_EQ(read.AfterGeneratedCall, read.type());
    EXPECT_EQ(GL_FRAGMENT_SHADER, read.ret());

    Read(read);
    EXPECT_EQ(read.glCreateProgram, read.function());
    EXPECT_EQ(read.AfterCall, read.type());
    EXPECT_EQ(ret, read.ret());

    Read(read);
    EXPECT_EQ(read.glCreateShader, read.function());
    EXPECT_EQ(read.AfterGeneratedCall, read.type());
    EXPECT_EQ(GL_VERTEX_SHADER, read.ret());

    EXPECT_EQ(2, createShader);
    EXPECT_EQ(1, createProgram);

    CheckNoAvailable();
}

TEST_F(SocketContextTest, MessageLoopSetProp)
{
    static const int ret = -342;
    static unsigned int createShader, createProgram;
    createShader = 0;
    createProgram = 0;
    struct Caller : public FunctionCall {
        const int * operator()(gl_hooks_t::gl_t const * const _c, glesv2debugger::Message & msg) {
            const int r = (int)_c->glCreateProgram();
            msg.set_ret(r);
            return (int *)r;
        }
        static GLuint CreateShader(const GLenum type) {
            createShader++;
            return type;
        }
        static GLuint CreateProgram() {
            createProgram++;
            return ret;
        }
    } caller;
    glesv2debugger::Message msg, read, cmd;
    hooks.gl.glCreateShader = caller.CreateShader;
    hooks.gl.glCreateProgram = caller.CreateProgram;
    dbg->expectResponse.Bit(msg.glCreateProgram, false);

    cmd.set_function(cmd.SETPROP);
    cmd.set_prop(cmd.ExpectResponse);
    cmd.set_arg0(cmd.glCreateProgram);
    cmd.set_arg1(true);
    cmd.set_expect_response(true);
    Write(cmd);

    cmd.Clear();
    cmd.set_function(cmd.glCreateShader);
    cmd.set_arg0(GL_FRAGMENT_SHADER);
    cmd.set_expect_response(true);
    Write(cmd);

    cmd.set_function(cmd.SETPROP);
    cmd.set_prop(cmd.CaptureDraw);
    cmd.set_arg0(819);
    cmd.set_expect_response(true);
    Write(cmd);

    cmd.Clear();
    cmd.set_function(cmd.CONTINUE);
    cmd.set_expect_response(true);
    Write(cmd);

    cmd.set_function(cmd.glCreateShader);
    cmd.set_arg0(GL_VERTEX_SHADER);
    cmd.set_expect_response(false); // MessageLoop should automatically skip afterwards
    Write(cmd);

    EXPECT_EQ((int *)ret, MessageLoop(caller, msg, msg.glCreateProgram));

    EXPECT_TRUE(dbg->expectResponse.Bit(msg.glCreateProgram));
    EXPECT_EQ(819, dbg->captureDraw);

    Read(read);
    EXPECT_EQ(read.glCreateProgram, read.function());
    EXPECT_EQ(read.BeforeCall, read.type());

    Read(read);
    EXPECT_EQ(read.glCreateShader, read.function());
    EXPECT_EQ(read.AfterGeneratedCall, read.type());
    EXPECT_EQ(GL_FRAGMENT_SHADER, read.ret());

    Read(read);
    EXPECT_EQ(read.glCreateProgram, read.function());
    EXPECT_EQ(read.AfterCall, read.type());
    EXPECT_EQ(ret, read.ret());

    Read(read);
    EXPECT_EQ(read.glCreateShader, read.function());
    EXPECT_EQ(read.AfterGeneratedCall, read.type());
    EXPECT_EQ(GL_VERTEX_SHADER, read.ret());

    EXPECT_EQ(2, createShader);
    EXPECT_EQ(1, createProgram);

    CheckNoAvailable();
}

TEST_F(SocketContextTest, TexImage2D)
{
    static const GLenum _target = GL_TEXTURE_2D;
    static const GLint _level = 1, _internalformat = GL_RGBA;
    static const GLsizei _width = 2, _height = 2;
    static const GLint _border = 333;
    static const GLenum _format = GL_RGB, _type = GL_UNSIGNED_SHORT_5_6_5;
    static const short _pixels [_width * _height] = {11, 22, 33, 44};
    static unsigned int texImage2D;
    texImage2D = 0;

    struct Caller {
        static void TexImage2D(GLenum target, GLint level, GLint internalformat,
                               GLsizei width, GLsizei height, GLint border,
                               GLenum format, GLenum type, const GLvoid* pixels) {
            EXPECT_EQ(_target, target);
            EXPECT_EQ(_level, level);
            EXPECT_EQ(_internalformat, internalformat);
            EXPECT_EQ(_width, width);
            EXPECT_EQ(_height, height);
            EXPECT_EQ(_border, border);
            EXPECT_EQ(_format, format);
            EXPECT_EQ(_type, type);
            EXPECT_EQ(0, memcmp(_pixels, pixels, sizeof(_pixels)));
            texImage2D++;
        }
    } caller;
    glesv2debugger::Message msg, read, cmd;
    hooks.gl.glTexImage2D = caller.TexImage2D;
    dbg->expectResponse.Bit(msg.glTexImage2D, false);

    Debug_glTexImage2D(_target, _level, _internalformat, _width, _height, _border,
                       _format, _type, _pixels);
    EXPECT_EQ(1, texImage2D);

    Read(read);
    EXPECT_EQ(read.glTexImage2D, read.function());
    EXPECT_EQ(read.BeforeCall, read.type());
    EXPECT_EQ(_target, read.arg0());
    EXPECT_EQ(_level, read.arg1());
    EXPECT_EQ(_internalformat, read.arg2());
    EXPECT_EQ(_width, read.arg3());
    EXPECT_EQ(_height, read.arg4());
    EXPECT_EQ(_border, read.arg5());
    EXPECT_EQ(_format, read.arg6());
    EXPECT_EQ(_type, read.arg7());

    EXPECT_TRUE(read.has_data());
    uint32_t dataLen = 0;
    const unsigned char * data = dbg->Decompress(read.data().data(),
                                 read.data().length(), &dataLen);
    EXPECT_EQ(sizeof(_pixels), dataLen);
    if (sizeof(_pixels) == dataLen)
        EXPECT_EQ(0, memcmp(_pixels, data, sizeof(_pixels)));

    Read(read);
    EXPECT_EQ(read.glTexImage2D, read.function());
    EXPECT_EQ(read.AfterCall, read.type());

    CheckNoAvailable();
}

TEST_F(SocketContextTest, CopyTexImage2D)
{
    static const GLenum _target = GL_TEXTURE_2D;
    static const GLint _level = 1, _internalformat = GL_RGBA;
    static const GLint _x = 9, _y = 99;
    static const GLsizei _width = 2, _height = 3;
    static const GLint _border = 333;
    static const int _pixels [_width * _height] = {11, 22, 33, 44, 55, 66};
    static unsigned int copyTexImage2D, readPixels;
    copyTexImage2D = 0, readPixels = 0;

    struct Caller {
        static void CopyTexImage2D(GLenum target, GLint level, GLenum internalformat,
                                   GLint x, GLint y, GLsizei width, GLsizei height, GLint border) {
            EXPECT_EQ(_target, target);
            EXPECT_EQ(_level, level);
            EXPECT_EQ(_internalformat, internalformat);
            EXPECT_EQ(_x, x);
            EXPECT_EQ(_y, y);
            EXPECT_EQ(_width, width);
            EXPECT_EQ(_height, height);
            EXPECT_EQ(_border, border);
            copyTexImage2D++;
        }
        static void ReadPixels(GLint x, GLint y, GLsizei width, GLsizei height,
                               GLenum format, GLenum type, GLvoid* pixels) {
            EXPECT_EQ(_x, x);
            EXPECT_EQ(_y, y);
            EXPECT_EQ(_width, width);
            EXPECT_EQ(_height, height);
            EXPECT_EQ(GL_RGBA, format);
            EXPECT_EQ(GL_UNSIGNED_BYTE, type);
            ASSERT_TRUE(pixels != NULL);
            memcpy(pixels, _pixels, sizeof(_pixels));
            readPixels++;
        }
    } caller;
    glesv2debugger::Message msg, read, cmd;
    hooks.gl.glCopyTexImage2D = caller.CopyTexImage2D;
    hooks.gl.glReadPixels = caller.ReadPixels;
    dbg->expectResponse.Bit(msg.glCopyTexImage2D, false);

    Debug_glCopyTexImage2D(_target, _level, _internalformat, _x, _y, _width, _height,
                           _border);
    ASSERT_EQ(1, copyTexImage2D);
    ASSERT_EQ(1, readPixels);

    Read(read);
    EXPECT_EQ(read.glCopyTexImage2D, read.function());
    EXPECT_EQ(read.BeforeCall, read.type());
    EXPECT_EQ(_target, read.arg0());
    EXPECT_EQ(_level, read.arg1());
    EXPECT_EQ(_internalformat, read.arg2());
    EXPECT_EQ(_x, read.arg3());
    EXPECT_EQ(_y, read.arg4());
    EXPECT_EQ(_width, read.arg5());
    EXPECT_EQ(_height, read.arg6());
    EXPECT_EQ(_border, read.arg7());

    EXPECT_TRUE(read.has_data());
    EXPECT_EQ(read.ReferencedImage, read.data_type());
    EXPECT_EQ(GL_RGBA, read.pixel_format());
    EXPECT_EQ(GL_UNSIGNED_BYTE, read.pixel_type());
    uint32_t dataLen = 0;
    unsigned char * const data = dbg->Decompress(read.data().data(),
                                 read.data().length(), &dataLen);
    ASSERT_EQ(sizeof(_pixels), dataLen);
    for (unsigned i = 0; i < sizeof(_pixels) / sizeof(*_pixels); i++)
        EXPECT_EQ(_pixels[i], ((const int *)data)[i]) << "xor with 0 ref is identity";
    free(data);

    Read(read);
    EXPECT_EQ(read.glCopyTexImage2D, read.function());
    EXPECT_EQ(read.AfterCall, read.type());

    CheckNoAvailable();
}
