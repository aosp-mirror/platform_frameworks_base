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

namespace
{

// The fixture for testing class Foo.
class DbgContextTest : public ::testing::Test
{
protected:
    android::DbgContext dbg;
    gl_hooks_t hooks;

    DbgContextTest()
            : dbg(1, &hooks, 32) {
        // You can do set-up work for each test here.
        hooks.gl.glGetError = GetError;
    }

    static GLenum GetError() {
        return GL_NO_ERROR;
    }

    virtual ~DbgContextTest() {
        // You can do clean-up work that doesn't throw exceptions here.
    }

    // If the constructor and destructor are not enough for setting up
    // and cleaning up each test, you can define the following methods:

    virtual void SetUp() {
        // Code here will be called immediately after the constructor (right
        // before each test).
    }

    virtual void TearDown() {
        // Code here will be called immediately after each test (right
        // before the destructor).
    }
};

TEST_F(DbgContextTest, GetReadPixelBuffer)
{
    const unsigned int bufferSize = 512;
    // test that it's allocating two buffers and swapping them
    void * const buffer0 = dbg.GetReadPixelsBuffer(bufferSize);
    ASSERT_NE((void *)NULL, buffer0);
    for (unsigned int i = 0; i < bufferSize / sizeof(unsigned int); i++) {
        EXPECT_EQ(0, ((unsigned int *)buffer0)[i])
        << "GetReadPixelsBuffer should allocate and zero";
        ((unsigned int *)buffer0)[i] = i * 13;
    }

    void * const buffer1 = dbg.GetReadPixelsBuffer(bufferSize);
    ASSERT_NE((void *)NULL, buffer1);
    EXPECT_NE(buffer0, buffer1);
    for (unsigned int i = 0; i < bufferSize / sizeof(unsigned int); i++) {
        EXPECT_EQ(0, ((unsigned int *)buffer1)[i])
        << "GetReadPixelsBuffer should allocate and zero";
        ((unsigned int *)buffer1)[i] = i * 17;
    }

    void * const buffer2 = dbg.GetReadPixelsBuffer(bufferSize);
    EXPECT_EQ(buffer2, buffer0);
    for (unsigned int i = 0; i < bufferSize / sizeof(unsigned int); i++)
        EXPECT_EQ(i * 13, ((unsigned int *)buffer2)[i])
        << "GetReadPixelsBuffer should swap buffers";

    void * const buffer3 = dbg.GetReadPixelsBuffer(bufferSize);
    EXPECT_EQ(buffer3, buffer1);
    for (unsigned int i = 0; i < bufferSize / sizeof(unsigned int); i++)
        EXPECT_EQ(i * 17, ((unsigned int *)buffer3)[i])
        << "GetReadPixelsBuffer should swap buffers";

    void * const buffer4 = dbg.GetReadPixelsBuffer(bufferSize);
    EXPECT_NE(buffer3, buffer4);
    EXPECT_EQ(buffer0, buffer2);
    EXPECT_EQ(buffer1, buffer3);
    EXPECT_EQ(buffer2, buffer4);

    // it reallocs as necessary; 0 size may result in NULL
    for (unsigned int i = 0; i < 42; i++) {
        void * const buffer = dbg.GetReadPixelsBuffer(((i & 7)) << 20);
        EXPECT_NE((void *)NULL, buffer)
        << "should be able to get a variety of reasonable sizes";
        EXPECT_TRUE(dbg.IsReadPixelBuffer(buffer));
    }
}

TEST_F(DbgContextTest, CompressReadPixelBuffer)
{
    const unsigned int bufferSize = dbg.LZF_CHUNK_SIZE * 4 + 33;
    std::string out;
    unsigned char * buffer = (unsigned char *)dbg.GetReadPixelsBuffer(bufferSize);
    for (unsigned int i = 0; i < bufferSize; i++)
        buffer[i] = i * 13;
    dbg.CompressReadPixelBuffer(&out);
    uint32_t decompSize = 0;
    ASSERT_LT(12, out.length()); // at least written chunk header
    ASSERT_EQ(bufferSize, *(uint32_t *)out.data())
    << "total decompressed size should be as requested in GetReadPixelsBuffer";
    for (unsigned int i = 4; i < out.length();) {
        const uint32_t outSize = *(uint32_t *)(out.data() + i);
        i += 4;
        const uint32_t inSize = *(uint32_t *)(out.data() + i);
        i += 4;
        if (inSize == 0)
            i += outSize; // chunk not compressed
        else
            i += inSize; // skip the actual compressed chunk
        decompSize += outSize;
    }
    ASSERT_EQ(bufferSize, decompSize);
    decompSize = 0;

    unsigned char * decomp = dbg.Decompress(out.data(), out.length(), &decompSize);
    ASSERT_EQ(decompSize, bufferSize);
    for (unsigned int i = 0; i < bufferSize; i++)
        EXPECT_EQ((unsigned char)(i * 13), decomp[i]) << "xor with 0 ref is identity";
    free(decomp);

    buffer = (unsigned char *)dbg.GetReadPixelsBuffer(bufferSize);
    for (unsigned int i = 0; i < bufferSize; i++)
        buffer[i] = i * 13;
    out.clear();
    dbg.CompressReadPixelBuffer(&out);
    decompSize = 0;
    decomp = dbg.Decompress(out.data(), out.length(), &decompSize);
    ASSERT_EQ(decompSize, bufferSize);
    for (unsigned int i = 0; i < bufferSize; i++)
        EXPECT_EQ(0, decomp[i]) << "xor with same ref is 0";
    free(decomp);

    buffer = (unsigned char *)dbg.GetReadPixelsBuffer(bufferSize);
    for (unsigned int i = 0; i < bufferSize; i++)
        buffer[i] = i * 19;
    out.clear();
    dbg.CompressReadPixelBuffer(&out);
    decompSize = 0;
    decomp = dbg.Decompress(out.data(), out.length(), &decompSize);
    ASSERT_EQ(decompSize, bufferSize);
    for (unsigned int i = 0; i < bufferSize; i++)
        EXPECT_EQ((unsigned char)(i * 13) ^ (unsigned char)(i * 19), decomp[i])
        << "xor ref";
    free(decomp);
}

TEST_F(DbgContextTest, UseProgram)
{
    static const GLuint _program = 74568;
    static const struct Attribute {
        const char * name;
        GLint location;
        GLint size;
        GLenum type;
    } _attributes [] = {
        {"aaa", 2, 2, GL_FLOAT_VEC2},
        {"bb", 6, 2, GL_FLOAT_MAT2},
        {"c", 1, 1, GL_FLOAT},
    };
    static const unsigned int _attributeCount = sizeof(_attributes) / sizeof(*_attributes);
    struct GL {
        static void GetProgramiv(GLuint program, GLenum pname, GLint* params) {
            EXPECT_EQ(_program, program);
            ASSERT_NE((GLint *)NULL, params);
            switch (pname) {
            case GL_ACTIVE_ATTRIBUTES:
                *params = _attributeCount;
                return;
            case GL_ACTIVE_ATTRIBUTE_MAX_LENGTH:
                *params = 4; // includes NULL terminator
                return;
            default:
                ADD_FAILURE() << "not handled pname: " << pname;
            }
        }

        static GLint GetAttribLocation(GLuint program, const GLchar* name) {
            EXPECT_EQ(_program, program);
            for (unsigned int i = 0; i < _attributeCount; i++)
                if (!strcmp(name, _attributes[i].name))
                    return _attributes[i].location;
            ADD_FAILURE() << "unknown attribute name: " << name;
            return -1;
        }

        static void GetActiveAttrib(GLuint program, GLuint index, GLsizei bufsize,
                                    GLsizei* length, GLint* size, GLenum* type, GLchar* name) {
            EXPECT_EQ(_program, program);
            ASSERT_LT(index, _attributeCount);
            const Attribute & att = _attributes[index];
            ASSERT_GE(bufsize, strlen(att.name) + 1);
            ASSERT_NE((GLint *)NULL, size);
            ASSERT_NE((GLenum *)NULL, type);
            ASSERT_NE((GLchar *)NULL, name);
            strcpy(name, att.name);
            if (length)
                *length = strlen(name) + 1;
            *size = att.size;
            *type = att.type;
        }
    };
    hooks.gl.glGetProgramiv = GL::GetProgramiv;
    hooks.gl.glGetAttribLocation = GL::GetAttribLocation;
    hooks.gl.glGetActiveAttrib = GL::GetActiveAttrib;
    dbg.glUseProgram(_program);
    EXPECT_EQ(10, dbg.maxAttrib);
    dbg.glUseProgram(0);
    EXPECT_EQ(0, dbg.maxAttrib);
}
}  // namespace

int main(int argc, char **argv)
{
    ::testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
}
