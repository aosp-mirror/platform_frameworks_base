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

extern "C" {
#include "liblzf/lzf.h"
}

namespace android {

static pthread_key_t dbgEGLThreadLocalStorageKey = -1;
static pthread_mutex_t gThreadLocalStorageKeyMutex = PTHREAD_MUTEX_INITIALIZER;

DbgContext * getDbgContextThreadSpecific() {
    return (DbgContext*)pthread_getspecific(dbgEGLThreadLocalStorageKey);
}

DbgContext::DbgContext(const unsigned version, const gl_hooks_t * const hooks,
                       const unsigned MAX_VERTEX_ATTRIBS, const GLenum readFormat,
                       const GLenum readType)
        : lzf_buf(NULL), lzf_readIndex(0), lzf_refSize(0), lzf_refBufSize(0)
        , version(version), hooks(hooks)
        , MAX_VERTEX_ATTRIBS(MAX_VERTEX_ATTRIBS)
        , readFormat(readFormat), readType(readType)
        , readBytesPerPixel(GetBytesPerPixel(readFormat, readType))
        , captureSwap(0), captureDraw(0)
        , vertexAttribs(new VertexAttrib[MAX_VERTEX_ATTRIBS])
        , hasNonVBOAttribs(false), indexBuffers(NULL), indexBuffer(NULL)
        , program(0), maxAttrib(0)
{
    lzf_ref[0] = lzf_ref[1] = NULL;
    for (unsigned i = 0; i < MAX_VERTEX_ATTRIBS; i++)
        vertexAttribs[i] = VertexAttrib();
    memset(&expectResponse, 0, sizeof(expectResponse));
}

DbgContext::~DbgContext()
{
    delete vertexAttribs;
    free(lzf_buf);
    free(lzf_ref[0]);
    free(lzf_ref[1]);
}

DbgContext* CreateDbgContext(const unsigned version, const gl_hooks_t * const hooks)
{
    pthread_mutex_lock(&gThreadLocalStorageKeyMutex);
    if (dbgEGLThreadLocalStorageKey == -1)
        pthread_key_create(&dbgEGLThreadLocalStorageKey, NULL);
    pthread_mutex_unlock(&gThreadLocalStorageKeyMutex);

    assert(version < 2);
    assert(GL_NO_ERROR == hooks->gl.glGetError());
    GLint MAX_VERTEX_ATTRIBS = 0;
    hooks->gl.glGetIntegerv(GL_MAX_VERTEX_ATTRIBS, &MAX_VERTEX_ATTRIBS);
    GLint readFormat, readType;
    hooks->gl.glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT, &readFormat);
    hooks->gl.glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE, &readType);
    DbgContext* dbg = new DbgContext(version, hooks, MAX_VERTEX_ATTRIBS, readFormat, readType);

    glesv2debugger::Message msg, cmd;
    msg.set_context_id(reinterpret_cast<int>(dbg));
    msg.set_expect_response(false);
    msg.set_type(msg.Response);
    msg.set_function(msg.SETPROP);
    msg.set_prop(msg.GLConstant);
    msg.set_arg0(GL_MAX_VERTEX_ATTRIBS);
    msg.set_arg1(MAX_VERTEX_ATTRIBS);
    Send(msg, cmd);

    GLint MAX_COMBINED_TEXTURE_IMAGE_UNITS = 0;
    hooks->gl.glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, &MAX_COMBINED_TEXTURE_IMAGE_UNITS);
    msg.set_arg0(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
    msg.set_arg1(MAX_COMBINED_TEXTURE_IMAGE_UNITS);
    Send(msg, cmd);

    pthread_setspecific(dbgEGLThreadLocalStorageKey, dbg);
    return dbg;
}

void dbgReleaseThread() {
    delete getDbgContextThreadSpecific();
}

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

void DbgContext::Fetch(const unsigned index, std::string * const data) const
{
    // VBO data is already on client, just send user pointer data
    for (unsigned i = 0; i < maxAttrib; i++) {
        if (!vertexAttribs[i].enabled)
            continue;
        if (vertexAttribs[i].buffer > 0)
            continue;
        const char * ptr = (const char *)vertexAttribs[i].ptr;
        ptr += index * vertexAttribs[i].stride;
        data->append(ptr, vertexAttribs[i].elemSize);
    }
}

void DbgContext::Compress(const void * in_data, unsigned int in_len,
                          std::string * const outStr)
{
    if (!lzf_buf)
        lzf_buf = (char *)malloc(LZF_CHUNK_SIZE);
    assert(lzf_buf);
    const uint32_t totalDecompSize = in_len;
    outStr->append((const char *)&totalDecompSize, sizeof(totalDecompSize));
    for (unsigned int i = 0; i < in_len; i += LZF_CHUNK_SIZE) {
        uint32_t chunkSize = LZF_CHUNK_SIZE;
        if (i + LZF_CHUNK_SIZE > in_len)
            chunkSize = in_len - i;
        const uint32_t compSize = lzf_compress((const char *)in_data + i, chunkSize,
                                               lzf_buf, LZF_CHUNK_SIZE);
        outStr->append((const char *)&chunkSize, sizeof(chunkSize));
        outStr->append((const char *)&compSize, sizeof(compSize));
        if (compSize > 0)
            outStr->append(lzf_buf, compSize);
        else // compressed chunk bigger than LZF_CHUNK_SIZE (and uncompressed)
            outStr->append((const char *)in_data + i, chunkSize);
    }
}

unsigned char * DbgContext::Decompress(const void * in, const unsigned int inLen,
                                       unsigned int * const outLen)
{
    assert(inLen > 4 * 3);
    if (inLen < 4 * 3)
        return NULL;
    *outLen = *(uint32_t *)in;
    unsigned char * const out = (unsigned char *)malloc(*outLen);
    unsigned int outPos = 0;
    const unsigned char * const end = (const unsigned char *)in + inLen;
    for (const unsigned char * inData = (const unsigned char *)in + 4; inData < end; ) {
        const uint32_t chunkOut = *(uint32_t *)inData;
        inData += 4;
        const uint32_t chunkIn = *(uint32_t *)inData;
        inData += 4;
        if (chunkIn > 0) {
            assert(inData + chunkIn <= end);
            assert(outPos + chunkOut <= *outLen);
            outPos += lzf_decompress(inData, chunkIn, out + outPos, chunkOut);
            inData += chunkIn;
        } else {
            assert(inData + chunkOut <= end);
            assert(outPos + chunkOut <= *outLen);
            memcpy(out + outPos, inData, chunkOut);
            inData += chunkOut;
            outPos += chunkOut;
        }
    }
    return out;
}

void * DbgContext::GetReadPixelsBuffer(const unsigned size)
{
    if (lzf_refBufSize < size + 8) {
        lzf_refBufSize = size + 8;
        lzf_ref[0] = (unsigned *)realloc(lzf_ref[0], lzf_refBufSize);
        assert(lzf_ref[0]);
        memset(lzf_ref[0], 0, lzf_refBufSize);
        lzf_ref[1] = (unsigned *)realloc(lzf_ref[1], lzf_refBufSize);
        assert(lzf_ref[1]);
        memset(lzf_ref[1], 0, lzf_refBufSize);
    }
    if (lzf_refSize != size) // need to clear unused ref to maintain consistency
    { // since ref and src are swapped each time
        memset((char *)lzf_ref[0] + lzf_refSize, 0, lzf_refBufSize - lzf_refSize);
        memset((char *)lzf_ref[1] + lzf_refSize, 0, lzf_refBufSize - lzf_refSize);
    }
    lzf_refSize = size;
    lzf_readIndex ^= 1;
    return lzf_ref[lzf_readIndex];
}

void DbgContext::CompressReadPixelBuffer(std::string * const outStr)
{
    assert(lzf_ref[0] && lzf_ref[1]);
    unsigned * const ref = lzf_ref[lzf_readIndex ^ 1];
    unsigned * const src = lzf_ref[lzf_readIndex];
    for (unsigned i = 0; i < lzf_refSize / sizeof(*ref) + 1; i++)
        ref[i] ^= src[i];
    Compress(ref, lzf_refSize, outStr);
}

char * DbgContext::GetBuffer()
{
    if (!lzf_buf)
        lzf_buf = (char *)malloc(LZF_CHUNK_SIZE);
    assert(lzf_buf);
    return lzf_buf;
}

unsigned int DbgContext::GetBufferSize()
{
    if (!lzf_buf)
        lzf_buf = (char *)malloc(LZF_CHUNK_SIZE);
    assert(lzf_buf);
    if (lzf_buf)
        return LZF_CHUNK_SIZE;
    else
        return 0;
}

void DbgContext::glUseProgram(GLuint program)
{
    while (GLenum error = hooks->gl.glGetError())
        LOGD("DbgContext::glUseProgram(%u): before glGetError() = 0x%.4X",
             program, error);
    this->program = program;
    maxAttrib = 0;
    if (program == 0)
        return;
    GLint activeAttributes = 0;
    hooks->gl.glGetProgramiv(program, GL_ACTIVE_ATTRIBUTES, &activeAttributes);
    maxAttrib = 0;
    GLint maxNameLen = -1;
    hooks->gl.glGetProgramiv(program, GL_ACTIVE_ATTRIBUTE_MAX_LENGTH, &maxNameLen);
    char * name = new char [maxNameLen + 1];
    name[maxNameLen] = 0;
    // find total number of attribute slots used
    for (unsigned i = 0; i < activeAttributes; i++) {
        GLint size = -1;
        GLenum type = -1;
        hooks->gl.glGetActiveAttrib(program, i, maxNameLen + 1, NULL, &size, &type, name);
        GLint slot = hooks->gl.glGetAttribLocation(program, name);
        assert(slot >= 0);
        switch (type) {
        case GL_FLOAT:
        case GL_FLOAT_VEC2:
        case GL_FLOAT_VEC3:
        case GL_FLOAT_VEC4:
            slot += size;
            break;
        case GL_FLOAT_MAT2:
            slot += size * 2;
            break;
        case GL_FLOAT_MAT3:
            slot += size * 3;
            break;
        case GL_FLOAT_MAT4:
            slot += size * 4;
            break;
        default:
            assert(0);
        }
        if (slot > maxAttrib)
            maxAttrib = slot;
    }
    delete name;
    while (GLenum error = hooks->gl.glGetError())
        LOGD("DbgContext::glUseProgram(%u): after glGetError() = 0x%.4X",
             program, error);
}

static bool HasNonVBOAttribs(const DbgContext * const ctx)
{
    bool need = false;
    for (unsigned i = 0; !need && i < ctx->maxAttrib; i++)
        if (ctx->vertexAttribs[i].enabled && ctx->vertexAttribs[i].buffer == 0)
            need = true;
    return need;
}

void DbgContext::glVertexAttribPointer(GLuint indx, GLint size, GLenum type,
                                       GLboolean normalized, GLsizei stride, const GLvoid* ptr)
{
    assert(GL_NO_ERROR == hooks->gl.glGetError());
    assert(indx < MAX_VERTEX_ATTRIBS);
    vertexAttribs[indx].size = size;
    vertexAttribs[indx].type = type;
    vertexAttribs[indx].normalized = normalized;
    switch (type) {
    case GL_FLOAT:
        vertexAttribs[indx].elemSize = sizeof(GLfloat) * size;
        break;
    case GL_INT:
    case GL_UNSIGNED_INT:
        vertexAttribs[indx].elemSize = sizeof(GLint) * size;
        break;
    case GL_SHORT:
    case GL_UNSIGNED_SHORT:
        vertexAttribs[indx].elemSize = sizeof(GLshort) * size;
        break;
    case GL_BYTE:
    case GL_UNSIGNED_BYTE:
        vertexAttribs[indx].elemSize = sizeof(GLbyte) * size;
        break;
    default:
        assert(0);
    }
    if (0 == stride)
        stride = vertexAttribs[indx].elemSize;
    vertexAttribs[indx].stride = stride;
    vertexAttribs[indx].ptr = ptr;
    hooks->gl.glGetVertexAttribiv(indx, GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING,
                                  (GLint *)&vertexAttribs[indx].buffer);
    hasNonVBOAttribs = HasNonVBOAttribs(this);
}

void DbgContext::glEnableVertexAttribArray(GLuint index)
{
    if (index >= MAX_VERTEX_ATTRIBS)
        return;
    vertexAttribs[index].enabled = true;
    hasNonVBOAttribs = HasNonVBOAttribs(this);
}

void DbgContext::glDisableVertexAttribArray(GLuint index)
{
    if (index >= MAX_VERTEX_ATTRIBS)
        return;
    vertexAttribs[index].enabled = false;
    hasNonVBOAttribs = HasNonVBOAttribs(this);
}

void DbgContext::glBindBuffer(GLenum target, GLuint buffer)
{
    if (GL_ELEMENT_ARRAY_BUFFER != target)
        return;
    if (0 == buffer) {
        indexBuffer = NULL;
        return;
    }
    VBO * b = indexBuffers;
    indexBuffer = NULL;
    while (b) {
        if (b->name == buffer) {
            assert(GL_ELEMENT_ARRAY_BUFFER == b->target);
            indexBuffer = b;
            break;
        }
        b = b->next;
    }
    if (!indexBuffer)
        indexBuffer = indexBuffers = new VBO(buffer, target, indexBuffers);
}

void DbgContext::glBufferData(GLenum target, GLsizeiptr size, const GLvoid* data, GLenum usage)
{
    if (GL_ELEMENT_ARRAY_BUFFER != target)
        return;
    assert(indexBuffer);
    assert(size >= 0);
    indexBuffer->size = size;
    indexBuffer->data = realloc(indexBuffer->data, size);
    memcpy(indexBuffer->data, data, size);
}

void DbgContext::glBufferSubData(GLenum target, GLintptr offset, GLsizeiptr size, const GLvoid* data)
{
    if (GL_ELEMENT_ARRAY_BUFFER != target)
        return;
    assert(indexBuffer);
    assert(size >= 0);
    assert(offset >= 0);
    assert(offset + size <= indexBuffer->size);
    memcpy((char *)indexBuffer->data + offset, data, size);
}

void DbgContext::glDeleteBuffers(GLsizei n, const GLuint *buffers)
{
    for (unsigned i = 0; i < n; i++) {
        for (unsigned j = 0; j < MAX_VERTEX_ATTRIBS; j++)
            if (buffers[i] == vertexAttribs[j].buffer) {
                vertexAttribs[j].buffer = 0;
                vertexAttribs[j].enabled = false;
            }
        VBO * b = indexBuffers, * previous = NULL;
        while (b) {
            if (b->name == buffers[i]) {
                assert(GL_ELEMENT_ARRAY_BUFFER == b->target);
                if (indexBuffer == b)
                    indexBuffer = NULL;
                if (previous)
                    previous->next = b->next;
                else
                    indexBuffers = b->next;
                free(b->data);
                delete b;
                break;
            }
            previous = b;
            b = b->next;
        }
    }
    hasNonVBOAttribs = HasNonVBOAttribs(this);
}

}; // namespace android
