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

extern "C"
{
#include "liblzf/lzf.h"
}

namespace android
{

DbgContext::DbgContext(const unsigned version, const gl_hooks_t * const hooks,
                       const unsigned MAX_VERTEX_ATTRIBS)
        : lzf_buf(NULL), lzf_bufSize(0)
        , lzf_readIndex(0), lzf_refSize(0), lzf_refBufSize(0)
        , version(version), hooks(hooks)
        , MAX_VERTEX_ATTRIBS(MAX_VERTEX_ATTRIBS)
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

DbgContext * CreateDbgContext(const unsigned version, const gl_hooks_t * const hooks)
{
    assert(version < 2);
    assert(GL_NO_ERROR == hooks->gl.glGetError());
    GLint MAX_VERTEX_ATTRIBS = 0;
    hooks->gl.glGetIntegerv(GL_MAX_VERTEX_ATTRIBS, &MAX_VERTEX_ATTRIBS);
    return new DbgContext(version, hooks, MAX_VERTEX_ATTRIBS);
}

void DestroyDbgContext(DbgContext * const dbg)
{
    delete dbg;
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

unsigned DbgContext::Compress(const void * in_data, unsigned in_len)
{
    if (lzf_bufSize < in_len * 1.05f) {
        lzf_bufSize = in_len * 1.05f;
        lzf_buf = (char *)realloc(lzf_buf, lzf_bufSize);
    }
    unsigned compressedSize = lzf_compress((const char *)in_data,
                                           in_len, lzf_buf, lzf_bufSize);
    assert (0 < compressedSize);
    return compressedSize;
}

void * DbgContext::GetReadPixelsBuffer(const unsigned size)
{
    if (lzf_refBufSize < size + 8) {
        lzf_refBufSize = size + 8;
        lzf_ref[0] = (unsigned *)realloc(lzf_ref[0], lzf_refBufSize);
        memset(lzf_ref[0], 0, lzf_refBufSize);
        lzf_ref[1] = (unsigned *)realloc(lzf_ref[1], lzf_refBufSize);
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

unsigned DbgContext::CompressReadPixelBuffer()
{
    unsigned * const ref = lzf_ref[lzf_readIndex ^ 1];
    unsigned * const src = lzf_ref[lzf_readIndex];
    for (unsigned i = 0; i < lzf_refSize / sizeof(*ref) + 1; i++)
        ref[i] ^= src[i];
    return Compress(ref, lzf_refSize);
}

void DbgContext::glUseProgram(GLuint program)
{
    while (GLenum error = hooks->gl.glGetError())
        LOGD("DbgContext::glUseProgram: before glGetError() = 0x%.4X", error);

    this->program = program;

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
        LOGD("DbgContext::glUseProgram: after glGetError() = 0x%.4X", error);
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
    assert(index < MAX_VERTEX_ATTRIBS);
    vertexAttribs[index].enabled = true;
    hasNonVBOAttribs = HasNonVBOAttribs(this);
}

void DbgContext::glDisableVertexAttribArray(GLuint index)
{
    assert(index < MAX_VERTEX_ATTRIBS);
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
