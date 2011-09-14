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
#include "gtest/gtest.h"
#include "hooks.h"

namespace android
{
extern FILE * file;
extern unsigned int MAX_FILE_SIZE;
};

// tmpfile fails, so need to manually make a writable file first
static const char * filePath = "/data/local/tmp/dump.gles2dbg";

class ServerFileTest : public ::testing::Test
{
protected:
    ServerFileTest() { }

    virtual ~ServerFileTest() { }

    virtual void SetUp() {
        MAX_FILE_SIZE = 8 << 20;
        ASSERT_EQ((FILE *)NULL, file);
        file = fopen("/data/local/tmp/dump.gles2dbg", "wb+");
        ASSERT_NE((FILE *)NULL, file) << "make sure file is writable: "
        << filePath;
    }

    virtual void TearDown() {
        ASSERT_NE((FILE *)NULL, file);
        fclose(file);
        file = NULL;
    }

    void Read(glesv2debugger::Message & msg) const {
        msg.Clear();
        uint32_t len = 0;
        ASSERT_EQ(sizeof(len), fread(&len, 1, sizeof(len), file));
        ASSERT_GT(len, 0u);
        char * buffer = new char [len];
        ASSERT_EQ(len, fread(buffer, 1, len, file));
        msg.ParseFromArray(buffer, len);
        delete buffer;
    }

    void CheckNoAvailable() {
        const long pos = ftell(file);
        fseek(file, 0, SEEK_END);
        EXPECT_EQ(pos, ftell(file)) << "check no available";
    }
};

TEST_F(ServerFileTest, Send)
{
    glesv2debugger::Message msg, cmd, read;
    msg.set_context_id(1);
    msg.set_function(msg.glFinish);
    msg.set_expect_response(false);
    msg.set_type(msg.BeforeCall);
    rewind(file);
    android::Send(msg, cmd);
    rewind(file);
    Read(read);
    EXPECT_EQ(msg.context_id(), read.context_id());
    EXPECT_EQ(msg.function(), read.function());
    EXPECT_EQ(msg.expect_response(), read.expect_response());
    EXPECT_EQ(msg.type(), read.type());
}

TEST_F(ServerFileTest, CreateDbgContext)
{
    gl_hooks_t hooks;
    struct Constant {
        GLenum pname;
        GLint param;
    };
    static const Constant constants [] = {
        {GL_MAX_VERTEX_ATTRIBS, 16},
        {GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, 32},
        {GL_IMPLEMENTATION_COLOR_READ_FORMAT, GL_RGBA},
        {GL_IMPLEMENTATION_COLOR_READ_TYPE, GL_UNSIGNED_BYTE},
    };
    struct HookMock {
        static void GetIntegerv(GLenum pname, GLint* params) {
            ASSERT_TRUE(params != NULL);
            for (unsigned int i = 0; i < sizeof(constants) / sizeof(*constants); i++)
                if (pname == constants[i].pname) {
                    *params = constants[i].param;
                    return;
                }
            FAIL() << "GetIntegerv unknown pname: " << pname;
        }
        static GLenum GetError() {
            return GL_NO_ERROR;
        }
    };
    hooks.gl.glGetError = HookMock::GetError;
    hooks.gl.glGetIntegerv = HookMock::GetIntegerv;
    DbgContext * const dbg = CreateDbgContext(1, &hooks);
    ASSERT_TRUE(dbg != NULL);
    EXPECT_TRUE(dbg->vertexAttribs != NULL);

    rewind(file);
    glesv2debugger::Message read;
    for (unsigned int i = 0; i < 2; i++) {
        Read(read);
        EXPECT_EQ(reinterpret_cast<int>(dbg), read.context_id());
        EXPECT_FALSE(read.expect_response());
        EXPECT_EQ(read.Response, read.type());
        EXPECT_EQ(read.SETPROP, read.function());
        EXPECT_EQ(read.GLConstant, read.prop());
        GLint expectedConstant = 0;
        HookMock::GetIntegerv(read.arg0(), &expectedConstant);
        EXPECT_EQ(expectedConstant, read.arg1());
    }
    CheckNoAvailable();
    dbgReleaseThread();
}

void * glNoop()
{
    return 0;
}

class ServerFileContextTest : public ServerFileTest
{
protected:
    DbgContext* dbg;
    gl_hooks_t hooks;

    ServerFileContextTest() { }

    virtual ~ServerFileContextTest() { }

    virtual void SetUp() {
        ServerFileTest::SetUp();

        dbg = new DbgContext(1, &hooks, 32);
        ASSERT_NE((void *)NULL, dbg);
        for (unsigned int i = 0; i < sizeof(hooks) / sizeof(void *); i++)
            ((void **)&hooks)[i] = reinterpret_cast<void *>(glNoop);
    }

    virtual void TearDown() {
        ServerFileTest::TearDown();
    }
};

TEST_F(ServerFileContextTest, MessageLoop)
{
    static const int arg0 = 45;
    static const float arg7 = -87.2331f;
    static const int arg8 = -3;
    static const int * ret = reinterpret_cast<int *>(870);

    struct Caller : public FunctionCall {
        const int * operator()(gl_hooks_t::gl_t const * const _c, glesv2debugger::Message & msg) {
            msg.set_arg0(arg0);
            msg.set_arg7((int &)arg7);
            msg.set_arg8(arg8);
            return ret;
        }
    } caller;
    const int contextId = reinterpret_cast<int>(dbg);
    glesv2debugger::Message msg, read;

    EXPECT_EQ(ret, MessageLoop(caller, msg, msg.glFinish));

    rewind(file);
    Read(read);
    EXPECT_EQ(contextId, read.context_id());
    EXPECT_EQ(read.glFinish, read.function());
    EXPECT_EQ(false, read.expect_response());
    EXPECT_EQ(read.BeforeCall, read.type());

    Read(read);
    EXPECT_EQ(contextId, read.context_id());
    EXPECT_EQ(read.glFinish, read.function());
    EXPECT_EQ(false, read.expect_response());
    EXPECT_EQ(read.AfterCall, read.type());
    EXPECT_TRUE(read.has_time());
    EXPECT_EQ(arg0, read.arg0());
    const int readArg7 = read.arg7();
    EXPECT_EQ(arg7, (float &)readArg7);
    EXPECT_EQ(arg8, read.arg8());

    const long pos = ftell(file);
    fseek(file, 0, SEEK_END);
    EXPECT_EQ(pos, ftell(file))
    << "should only write the BeforeCall and AfterCall messages";
}

TEST_F(ServerFileContextTest, DisableEnableVertexAttribArray)
{
    Debug_glEnableVertexAttribArray(dbg->MAX_VERTEX_ATTRIBS + 2); // should just ignore invalid index

    glesv2debugger::Message read;
    rewind(file);
    Read(read);
    EXPECT_EQ(read.glEnableVertexAttribArray, read.function());
    EXPECT_EQ(dbg->MAX_VERTEX_ATTRIBS + 2, read.arg0());
    Read(read);

    rewind(file);
    Debug_glDisableVertexAttribArray(dbg->MAX_VERTEX_ATTRIBS + 4); // should just ignore invalid index
    rewind(file);
    Read(read);
    Read(read);

    for (unsigned int i = 0; i < dbg->MAX_VERTEX_ATTRIBS; i += 5) {
        rewind(file);
        Debug_glEnableVertexAttribArray(i);
        EXPECT_TRUE(dbg->vertexAttribs[i].enabled);
        rewind(file);
        Read(read);
        EXPECT_EQ(read.glEnableVertexAttribArray, read.function());
        EXPECT_EQ(i, read.arg0());
        Read(read);

        rewind(file);
        Debug_glDisableVertexAttribArray(i);
        EXPECT_FALSE(dbg->vertexAttribs[i].enabled);
        rewind(file);
        Read(read);
        EXPECT_EQ(read.glDisableVertexAttribArray, read.function());
        EXPECT_EQ(i, read.arg0());
        Read(read);
    }
}
