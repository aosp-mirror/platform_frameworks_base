/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// TODO: Remove this file. This has been temporarily copied from Skia (where this class is
// deprecated). The NullGlesDriver should be constructing a GrGLInterface that calls *its*
// GL functions!

#include "GrNonAtomicRef.h"
#include "SkMutex.h"
#include "SkTDArray.h"
#include "SkTo.h"
#include "gl/GrGLDefines.h"
#include "gl/GrGLInterface.h"

#include <type_traits>

// added to suppress 'no previous prototype' warning and because this code is duplicated in
// SkNullGLContext.cpp
namespace {

class GLObject : public GrNonAtomicRef<GLObject> {
public:
    GLObject(GrGLuint id) : fID(id) {}
    virtual ~GLObject() {}

    GrGLuint id() const { return fID; }

private:
    GrGLuint fID;
};

// This class maintains a sparsely populated array of object pointers.
template<typename T> class TGLObjectManager {
   static_assert(std::is_convertible<T*, GLObject*>::value, "T must be a subclass of GLObject");

public:
    TGLObjectManager() : fFreeListHead(kFreeListEnd) {
        *fGLObjects.append() = nullptr; // 0 is not a valid GL object id.
    }

    ~TGLObjectManager() {
        // nullptr out the entries that are really free list links rather than ptrs before deleting.
        intptr_t curr = fFreeListHead;
        while (kFreeListEnd != curr) {
            intptr_t next = reinterpret_cast<intptr_t>(fGLObjects[SkToS32(curr)]);
            fGLObjects[SkToS32(curr)] = nullptr;
            curr = next;
        }

        fGLObjects.safeUnrefAll();
    }

    T* lookUp(GrGLuint id) {
        T* object = fGLObjects[id];
        SkASSERT(object && object->id() == id);
        return object;
    }

    T* create() {
        GrGLuint id;
        T* object;

        if (kFreeListEnd == fFreeListHead) {
            // no free slots - create a new one
            id = fGLObjects.count();
            object = new T(id);
            *fGLObjects.append() = object;
        } else {
            // grab the head of the free list and advance the head to the next free slot.
            id = static_cast<GrGLuint>(fFreeListHead);
            fFreeListHead = reinterpret_cast<intptr_t>(fGLObjects[id]);

            object = new T(id);
            fGLObjects[id] = object;
        }

        return object;
    }

    void free(T* object) {
        SkASSERT(object);
        SkASSERT(fGLObjects.count() > 0);

        GrGLuint id = object->id();
        object->unref();

        fGLObjects[id] = reinterpret_cast<T*>(fFreeListHead);
        fFreeListHead = id;
    }

private:
    static const intptr_t kFreeListEnd = -1;
    // Index of the first entry of fGLObjects in the free list. Free slots in fGLObjects are indices
    // to the next free slot. The last free slot has a value of kFreeListEnd.
    intptr_t        fFreeListHead;
    SkTDArray<T*>   fGLObjects;
};

class Buffer : public GLObject {
public:
    Buffer(GrGLuint id) : INHERITED(id), fDataPtr(nullptr), fSize(0), fMapped(false) {}
    ~Buffer() { delete[] fDataPtr; }

    void allocate(GrGLsizeiptr size, const GrGLchar* dataPtr) {
        if (fDataPtr) {
            SkASSERT(0 != fSize);
            delete[] fDataPtr;
        }

        fSize = size;
        fDataPtr = new char[size];
    }

    GrGLchar* dataPtr()          { return fDataPtr; }
    GrGLsizeiptr size() const    { return fSize; }

    void setMapped(bool mapped)  { fMapped = mapped; }
    bool mapped() const          { return fMapped; }

private:
    GrGLchar*    fDataPtr;
    GrGLsizeiptr fSize;         // size in bytes
    bool         fMapped;

    typedef GLObject INHERITED;
};

class FramebufferAttachment : public GLObject {
public:
    int numSamples() const { return fNumSamples; }

protected:
    FramebufferAttachment(int id) : INHERITED(id), fNumSamples(1) {}

    int fNumSamples;

    typedef GLObject INHERITED;
};

class Renderbuffer : public FramebufferAttachment {
public:
    Renderbuffer(int id) : INHERITED(id) {}
    void setNumSamples(int numSamples) { fNumSamples = numSamples; }

private:
    typedef FramebufferAttachment INHERITED;
};

class Texture : public FramebufferAttachment {
public:
    Texture() : INHERITED(1) {}

private:
    typedef FramebufferAttachment INHERITED;
};

class Framebuffer : public GLObject {
public:
    Framebuffer(int id) : INHERITED(id) {}

    void setAttachment(GrGLenum attachmentPoint, const FramebufferAttachment* attachment) {
        switch (attachmentPoint) {
            default:
                SK_ABORT("Invalid framebuffer attachment.");
                break;
            case GR_GL_STENCIL_ATTACHMENT:
                fAttachments[(int)AttachmentPoint::kStencil].reset(SkRef(attachment));
                break;
            case GR_GL_DEPTH_ATTACHMENT:
                fAttachments[(int)AttachmentPoint::kDepth].reset(SkRef(attachment));
                break;
            case GR_GL_COLOR_ATTACHMENT0:
                fAttachments[(int)AttachmentPoint::kColor].reset(SkRef(attachment));
                break;
        }
    }

    void notifyAttachmentDeleteWhileBound(const FramebufferAttachment* deleted) {
        for (auto& attachment : fAttachments) {
            if (attachment.get() == deleted) {
                attachment.reset(nullptr);
            }
        }
    }

    int numSamples() const {
        int numSamples = 0;
        for (auto& attachment : fAttachments) {
            if (!attachment) {
                continue;
            }
            if (numSamples) {
                GrAlwaysAssert(attachment->numSamples() == numSamples);
                continue;
            }
            numSamples = attachment->numSamples();
        }
        GrAlwaysAssert(numSamples);
        return numSamples;
    }

private:
    enum AttachmentPoint {
        kStencil,
        kDepth,
        kColor
    };
    constexpr int static kNumAttachmentPoints = 1 + (int)AttachmentPoint::kColor;

    sk_sp<const FramebufferAttachment> fAttachments[kNumAttachmentPoints];

    typedef GLObject INHERITED;
};

class TestInterface : public GrGLInterface {
public:
    virtual GrGLvoid activeTexture(GrGLenum texture) {}
    virtual GrGLvoid attachShader(GrGLuint program, GrGLuint shader) {}
    virtual GrGLvoid beginQuery(GrGLenum target, GrGLuint id) {}
    virtual GrGLvoid bindAttribLocation(GrGLuint program, GrGLuint index, const char* name) {}
    virtual GrGLvoid bindBuffer(GrGLenum target, GrGLuint buffer) {}
    virtual GrGLvoid bindFramebuffer(GrGLenum target, GrGLuint framebuffer) {}
    virtual GrGLvoid bindRenderbuffer(GrGLenum target, GrGLuint renderbuffer) {}
    virtual GrGLvoid bindSampler(GrGLuint unit, GrGLuint sampler) {}
    virtual GrGLvoid bindTexture(GrGLenum target, GrGLuint texture) {}
    virtual GrGLvoid bindFragDataLocation(GrGLuint program, GrGLuint colorNumber, const GrGLchar* name) {}
    virtual GrGLvoid bindFragDataLocationIndexed(GrGLuint program, GrGLuint colorNumber, GrGLuint index, const GrGLchar * name) {}
    virtual GrGLvoid bindVertexArray(GrGLuint array) {}
    virtual GrGLvoid blendBarrier() {}
    virtual GrGLvoid blendColor(GrGLclampf red, GrGLclampf green, GrGLclampf blue, GrGLclampf alpha) {}
    virtual GrGLvoid blendEquation(GrGLenum mode) {}
    virtual GrGLvoid blendFunc(GrGLenum sfactor, GrGLenum dfactor) {}
    virtual GrGLvoid blitFramebuffer(GrGLint srcX0, GrGLint srcY0, GrGLint srcX1, GrGLint srcY1, GrGLint dstX0, GrGLint dstY0, GrGLint dstX1, GrGLint dstY1, GrGLbitfield mask, GrGLenum filter) {}
    virtual GrGLvoid bufferData(GrGLenum target, GrGLsizeiptr size, const GrGLvoid* data, GrGLenum usage) {}
    virtual GrGLvoid bufferSubData(GrGLenum target, GrGLintptr offset, GrGLsizeiptr size, const GrGLvoid* data) {}
    virtual GrGLenum checkFramebufferStatus(GrGLenum target) { return GR_GL_FRAMEBUFFER_COMPLETE; }
    virtual GrGLvoid clear(GrGLbitfield mask) {}
    virtual GrGLvoid clearColor(GrGLclampf red, GrGLclampf green, GrGLclampf blue, GrGLclampf alpha) {}
    virtual GrGLvoid clearStencil(GrGLint s) {}
    virtual GrGLvoid colorMask(GrGLboolean red, GrGLboolean green, GrGLboolean blue, GrGLboolean alpha) {}
    virtual GrGLvoid compileShader(GrGLuint shader) {}
    virtual GrGLvoid compressedTexImage2D(GrGLenum target, GrGLint level, GrGLenum internalformat, GrGLsizei width, GrGLsizei height, GrGLint border, GrGLsizei imageSize, const GrGLvoid* data) {}
    virtual GrGLvoid compressedTexSubImage2D(GrGLenum target, GrGLint level, GrGLint xoffset, GrGLint yoffset, GrGLsizei width, GrGLsizei height, GrGLenum format, GrGLsizei imageSize, const GrGLvoid* data) {}
    virtual GrGLvoid copyTexSubImage2D(GrGLenum target, GrGLint level, GrGLint xoffset, GrGLint yoffset, GrGLint x, GrGLint y, GrGLsizei width, GrGLsizei height) {}
    virtual GrGLuint createProgram() { return 0; }
    virtual GrGLuint createShader(GrGLenum type) { return 0; }
    virtual GrGLvoid cullFace(GrGLenum mode) {}
    virtual GrGLvoid deleteBuffers(GrGLsizei n, const GrGLuint* buffers) {}
    virtual GrGLvoid deleteFramebuffers(GrGLsizei n, const GrGLuint *framebuffers) {}
    virtual GrGLvoid deleteProgram(GrGLuint program) {}
    virtual GrGLvoid deleteQueries(GrGLsizei n, const GrGLuint *ids) {}
    virtual GrGLvoid deleteRenderbuffers(GrGLsizei n, const GrGLuint *renderbuffers) {}
    virtual GrGLvoid deleteSamplers(GrGLsizei n, const GrGLuint* samplers) {}
    virtual GrGLvoid deleteShader(GrGLuint shader) {}
    virtual GrGLvoid deleteTextures(GrGLsizei n, const GrGLuint* textures) {}
    virtual GrGLvoid deleteVertexArrays(GrGLsizei n, const GrGLuint *arrays) {}
    virtual GrGLvoid depthMask(GrGLboolean flag) {}
    virtual GrGLvoid disable(GrGLenum cap) {}
    virtual GrGLvoid disableVertexAttribArray(GrGLuint index) {}
    virtual GrGLvoid drawArrays(GrGLenum mode, GrGLint first, GrGLsizei count) {}
    virtual GrGLvoid drawArraysInstanced(GrGLenum mode, GrGLint first, GrGLsizei count, GrGLsizei primcount) {}
    virtual GrGLvoid drawArraysIndirect(GrGLenum mode, const GrGLvoid* indirect) {}
    virtual GrGLvoid drawBuffer(GrGLenum mode) {}
    virtual GrGLvoid drawBuffers(GrGLsizei n, const GrGLenum* bufs) {}
    virtual GrGLvoid drawElements(GrGLenum mode, GrGLsizei count, GrGLenum type, const GrGLvoid* indices) {}
    virtual GrGLvoid drawElementsInstanced(GrGLenum mode, GrGLsizei count, GrGLenum type, const GrGLvoid *indices, GrGLsizei primcount) {}
    virtual GrGLvoid drawElementsIndirect(GrGLenum mode, GrGLenum type, const GrGLvoid* indirect) {}
    virtual GrGLvoid drawRangeElements(GrGLenum mode, GrGLuint start, GrGLuint end, GrGLsizei count, GrGLenum type, const GrGLvoid* indices) {}
    virtual GrGLvoid enable(GrGLenum cap) {}
    virtual GrGLvoid enableVertexAttribArray(GrGLuint index) {}
    virtual GrGLvoid endQuery(GrGLenum target) {}
    virtual GrGLvoid finish() {}
    virtual GrGLvoid flush() {}
    virtual GrGLvoid flushMappedBufferRange(GrGLenum target, GrGLintptr offset, GrGLsizeiptr length) {}
    virtual GrGLvoid framebufferRenderbuffer(GrGLenum target, GrGLenum attachment, GrGLenum renderbuffertarget, GrGLuint renderbuffer) {}
    virtual GrGLvoid framebufferTexture2D(GrGLenum target, GrGLenum attachment, GrGLenum textarget, GrGLuint texture, GrGLint level) {}
    virtual GrGLvoid framebufferTexture2DMultisample(GrGLenum target, GrGLenum attachment, GrGLenum textarget, GrGLuint texture, GrGLint level, GrGLsizei samples) {}
    virtual GrGLvoid frontFace(GrGLenum mode) {}
    virtual GrGLvoid genBuffers(GrGLsizei n, GrGLuint* buffers) {}
    virtual GrGLvoid genFramebuffers(GrGLsizei n, GrGLuint *framebuffers) {}
    virtual GrGLvoid generateMipmap(GrGLenum target) {}
    virtual GrGLvoid genQueries(GrGLsizei n, GrGLuint *ids) {}
    virtual GrGLvoid genRenderbuffers(GrGLsizei n, GrGLuint *renderbuffers) {}
    virtual GrGLvoid genSamplers(GrGLsizei n, GrGLuint *samplers) {}
    virtual GrGLvoid genTextures(GrGLsizei n, GrGLuint* textures) {}
    virtual GrGLvoid genVertexArrays(GrGLsizei n, GrGLuint *arrays) {}
    virtual GrGLvoid getBufferParameteriv(GrGLenum target, GrGLenum pname, GrGLint* params) {}
    virtual GrGLenum getError() { return GR_GL_NO_ERROR; }
    virtual GrGLvoid getFramebufferAttachmentParameteriv(GrGLenum target, GrGLenum attachment, GrGLenum pname, GrGLint* params) {}
    virtual GrGLvoid getIntegerv(GrGLenum pname, GrGLint* params) {}
    virtual GrGLvoid getMultisamplefv(GrGLenum pname, GrGLuint index, GrGLfloat* val) {}
    virtual GrGLvoid getProgramInfoLog(GrGLuint program, GrGLsizei bufsize, GrGLsizei* length, char* infolog) {}
    virtual GrGLvoid getProgramiv(GrGLuint program, GrGLenum pname, GrGLint* params) {}
    virtual GrGLvoid getQueryiv(GrGLenum GLtarget, GrGLenum pname, GrGLint *params) {}
    virtual GrGLvoid getQueryObjecti64v(GrGLuint id, GrGLenum pname, GrGLint64 *params) {}
    virtual GrGLvoid getQueryObjectiv(GrGLuint id, GrGLenum pname, GrGLint *params) {}
    virtual GrGLvoid getQueryObjectui64v(GrGLuint id, GrGLenum pname, GrGLuint64 *params) {}
    virtual GrGLvoid getQueryObjectuiv(GrGLuint id, GrGLenum pname, GrGLuint *params) {}
    virtual GrGLvoid getRenderbufferParameteriv(GrGLenum target, GrGLenum pname, GrGLint* params) {}
    virtual GrGLvoid getShaderInfoLog(GrGLuint shader, GrGLsizei bufsize, GrGLsizei* length, char* infolog) {}
    virtual GrGLvoid getShaderiv(GrGLuint shader, GrGLenum pname, GrGLint* params) {}
    virtual GrGLvoid getShaderPrecisionFormat(GrGLenum shadertype, GrGLenum precisiontype, GrGLint *range, GrGLint *precision) {}
    virtual const GrGLubyte*  getString(GrGLenum name) { return nullptr; }
    virtual const GrGLubyte* getStringi(GrGLenum name, GrGLuint index) { return nullptr; }
    virtual GrGLvoid getTexLevelParameteriv(GrGLenum target, GrGLint level, GrGLenum pname, GrGLint* params) {}
    virtual GrGLint getUniformLocation(GrGLuint program, const char* name) { return 0; }
    virtual GrGLvoid insertEventMarker(GrGLsizei length, const char* marker) {}
    virtual GrGLvoid invalidateBufferData(GrGLuint buffer) {}
    virtual GrGLvoid invalidateBufferSubData(GrGLuint buffer, GrGLintptr offset, GrGLsizeiptr length) {}
    virtual GrGLvoid invalidateFramebuffer(GrGLenum target, GrGLsizei numAttachments,  const GrGLenum *attachments) {}
    virtual GrGLvoid invalidateSubFramebuffer(GrGLenum target, GrGLsizei numAttachments, const GrGLenum *attachments, GrGLint x, GrGLint y, GrGLsizei width, GrGLsizei height) {}
    virtual GrGLvoid invalidateTexImage(GrGLuint texture, GrGLint level) {}
    virtual GrGLvoid invalidateTexSubImage(GrGLuint texture, GrGLint level, GrGLint xoffset, GrGLint yoffset, GrGLint zoffset, GrGLsizei width, GrGLsizei height, GrGLsizei depth) {}
    virtual GrGLboolean isTexture(GrGLuint texture) { return GR_GL_FALSE; }
    virtual GrGLvoid lineWidth(GrGLfloat width) {}
    virtual GrGLvoid linkProgram(GrGLuint program) {}
    virtual GrGLvoid* mapBuffer(GrGLenum target, GrGLenum access) { return nullptr; }
    virtual GrGLvoid* mapBufferRange(GrGLenum target, GrGLintptr offset, GrGLsizeiptr length, GrGLbitfield access) { return nullptr; }
    virtual GrGLvoid* mapBufferSubData(GrGLuint target, GrGLintptr offset, GrGLsizeiptr size, GrGLenum access) { return nullptr; }
    virtual GrGLvoid* mapTexSubImage2D(GrGLenum target, GrGLint level, GrGLint xoffset, GrGLint yoffset, GrGLsizei width, GrGLsizei height, GrGLenum format, GrGLenum type, GrGLenum access) { return nullptr; }
    virtual GrGLvoid pixelStorei(GrGLenum pname, GrGLint param) {}
    virtual GrGLvoid polygonMode(GrGLenum face, GrGLenum mode) {}
    virtual GrGLvoid popGroupMarker() {}
    virtual GrGLvoid pushGroupMarker(GrGLsizei length, const char* marker) {}
    virtual GrGLvoid queryCounter(GrGLuint id, GrGLenum target) {}
    virtual GrGLvoid rasterSamples(GrGLuint samples, GrGLboolean fixedsamplelocations) {}
    virtual GrGLvoid readBuffer(GrGLenum src) {}
    virtual GrGLvoid readPixels(GrGLint x, GrGLint y, GrGLsizei width, GrGLsizei height, GrGLenum format, GrGLenum type, GrGLvoid* pixels) {}
    virtual GrGLvoid renderbufferStorage(GrGLenum target, GrGLenum internalformat, GrGLsizei width, GrGLsizei height) {}
    virtual GrGLvoid renderbufferStorageMultisample(GrGLenum target, GrGLsizei samples, GrGLenum internalformat, GrGLsizei width, GrGLsizei height) {}
    virtual GrGLvoid resolveMultisampleFramebuffer() {}
    virtual GrGLvoid samplerParameteri(GrGLuint sampler, GrGLenum pname, GrGLint param) {}
    virtual GrGLvoid samplerParameteriv(GrGLuint sampler, GrGLenum pname, const GrGLint* param) {}
    virtual GrGLvoid scissor(GrGLint x, GrGLint y, GrGLsizei width, GrGLsizei height) {}
    virtual GrGLvoid bindUniformLocation(GrGLuint program, GrGLint location, const char* name) {}
    virtual GrGLvoid shaderSource(GrGLuint shader, GrGLsizei count, const char* const * str, const GrGLint* length) {}
    virtual GrGLvoid stencilFunc(GrGLenum func, GrGLint ref, GrGLuint mask) {}
    virtual GrGLvoid stencilFuncSeparate(GrGLenum face, GrGLenum func, GrGLint ref, GrGLuint mask) {}
    virtual GrGLvoid stencilMask(GrGLuint mask) {}
    virtual GrGLvoid stencilMaskSeparate(GrGLenum face, GrGLuint mask) {}
    virtual GrGLvoid stencilOp(GrGLenum fail, GrGLenum zfail, GrGLenum zpass) {}
    virtual GrGLvoid stencilOpSeparate(GrGLenum face, GrGLenum fail, GrGLenum zfail, GrGLenum zpass) {}
    virtual GrGLvoid texBuffer(GrGLenum target, GrGLenum internalformat, GrGLuint buffer) {}
    virtual GrGLvoid texImage2D(GrGLenum target, GrGLint level, GrGLint internalformat, GrGLsizei width, GrGLsizei height, GrGLint border, GrGLenum format, GrGLenum type, const GrGLvoid* pixels) {}
    virtual GrGLvoid texParameterf(GrGLenum target, GrGLenum pname, GrGLfloat param) {}
    virtual GrGLvoid texParameterfv(GrGLenum target, GrGLenum pname, const GrGLfloat* params) {}
    virtual GrGLvoid texParameteri(GrGLenum target, GrGLenum pname, GrGLint param) {}
    virtual GrGLvoid texParameteriv(GrGLenum target, GrGLenum pname, const GrGLint* params) {}
    virtual GrGLvoid texStorage2D(GrGLenum target, GrGLsizei levels, GrGLenum internalformat, GrGLsizei width, GrGLsizei height) {}
    virtual GrGLvoid discardFramebuffer(GrGLenum target, GrGLsizei numAttachments, const GrGLenum* attachments) {}
    virtual GrGLvoid texSubImage2D(GrGLenum target, GrGLint level, GrGLint xoffset, GrGLint yoffset, GrGLsizei width, GrGLsizei height, GrGLenum format, GrGLenum type, const GrGLvoid* pixels) {}
    virtual GrGLvoid textureBarrier() {}
    virtual GrGLvoid uniform1f(GrGLint location, GrGLfloat v0) {}
    virtual GrGLvoid uniform1i(GrGLint location, GrGLint v0) {}
    virtual GrGLvoid uniform1fv(GrGLint location, GrGLsizei count, const GrGLfloat* v) {}
    virtual GrGLvoid uniform1iv(GrGLint location, GrGLsizei count, const GrGLint* v) {}
    virtual GrGLvoid uniform2f(GrGLint location, GrGLfloat v0, GrGLfloat v1) {}
    virtual GrGLvoid uniform2i(GrGLint location, GrGLint v0, GrGLint v1) {}
    virtual GrGLvoid uniform2fv(GrGLint location, GrGLsizei count, const GrGLfloat* v) {}
    virtual GrGLvoid uniform2iv(GrGLint location, GrGLsizei count, const GrGLint* v) {}
    virtual GrGLvoid uniform3f(GrGLint location, GrGLfloat v0, GrGLfloat v1, GrGLfloat v2) {}
    virtual GrGLvoid uniform3i(GrGLint location, GrGLint v0, GrGLint v1, GrGLint v2) {}
    virtual GrGLvoid uniform3fv(GrGLint location, GrGLsizei count, const GrGLfloat* v) {}
    virtual GrGLvoid uniform3iv(GrGLint location, GrGLsizei count, const GrGLint* v) {}
    virtual GrGLvoid uniform4f(GrGLint location, GrGLfloat v0, GrGLfloat v1, GrGLfloat v2, GrGLfloat v3) {}
    virtual GrGLvoid uniform4i(GrGLint location, GrGLint v0, GrGLint v1, GrGLint v2, GrGLint v3) {}
    virtual GrGLvoid uniform4fv(GrGLint location, GrGLsizei count, const GrGLfloat* v) {}
    virtual GrGLvoid uniform4iv(GrGLint location, GrGLsizei count, const GrGLint* v) {}
    virtual GrGLvoid uniformMatrix2fv(GrGLint location, GrGLsizei count, GrGLboolean transpose, const GrGLfloat* value) {}
    virtual GrGLvoid uniformMatrix3fv(GrGLint location, GrGLsizei count, GrGLboolean transpose, const GrGLfloat* value) {}
    virtual GrGLvoid uniformMatrix4fv(GrGLint location, GrGLsizei count, GrGLboolean transpose, const GrGLfloat* value) {}
    virtual GrGLboolean unmapBuffer(GrGLenum target) { return GR_GL_TRUE; }
    virtual GrGLvoid unmapBufferSubData(const GrGLvoid* mem) {}
    virtual GrGLvoid unmapTexSubImage2D(const GrGLvoid* mem) {}
    virtual GrGLvoid useProgram(GrGLuint program) {}
    virtual GrGLvoid vertexAttrib1f(GrGLuint indx, const GrGLfloat value) {}
    virtual GrGLvoid vertexAttrib2fv(GrGLuint indx, const GrGLfloat* values) {}
    virtual GrGLvoid vertexAttrib3fv(GrGLuint indx, const GrGLfloat* values) {}
    virtual GrGLvoid vertexAttrib4fv(GrGLuint indx, const GrGLfloat* values) {}
    virtual GrGLvoid vertexAttribDivisor(GrGLuint index, GrGLuint divisor) {}
    virtual GrGLvoid vertexAttribIPointer(GrGLuint indx, GrGLint size, GrGLenum type, GrGLsizei stride, const GrGLvoid* ptr) {}
    virtual GrGLvoid vertexAttribPointer(GrGLuint indx, GrGLint size, GrGLenum type, GrGLboolean normalized, GrGLsizei stride, const GrGLvoid* ptr) {}
    virtual GrGLvoid viewport(GrGLint x, GrGLint y, GrGLsizei width, GrGLsizei height) {}
    virtual GrGLvoid matrixLoadf(GrGLenum matrixMode, const GrGLfloat* m) {}
    virtual GrGLvoid matrixLoadIdentity(GrGLenum) {}
    virtual GrGLvoid pathCommands(GrGLuint path, GrGLsizei numCommands, const GrGLubyte *commands, GrGLsizei numCoords, GrGLenum coordType, const GrGLvoid *coords) {}
    virtual GrGLvoid pathParameteri(GrGLuint path, GrGLenum pname, GrGLint value) {}
    virtual GrGLvoid pathParameterf(GrGLuint path, GrGLenum pname, GrGLfloat value) {}
    virtual GrGLuint genPaths(GrGLsizei range) { return 0; }
    virtual GrGLvoid deletePaths(GrGLuint path, GrGLsizei range) {}
    virtual GrGLboolean isPath(GrGLuint path) { return true; }
    virtual GrGLvoid pathStencilFunc(GrGLenum func, GrGLint ref, GrGLuint mask) {}
    virtual GrGLvoid stencilFillPath(GrGLuint path, GrGLenum fillMode, GrGLuint mask) {}
    virtual GrGLvoid stencilStrokePath(GrGLuint path, GrGLint reference, GrGLuint mask) {}
    virtual GrGLvoid stencilFillPathInstanced(GrGLsizei numPaths, GrGLenum pathNameType, const GrGLvoid *paths, GrGLuint pathBase, GrGLenum fillMode, GrGLuint mask, GrGLenum transformType, const GrGLfloat *transformValues) {}
    virtual GrGLvoid stencilStrokePathInstanced(GrGLsizei numPaths, GrGLenum pathNameType, const GrGLvoid *paths, GrGLuint pathBase, GrGLint reference, GrGLuint mask, GrGLenum transformType, const GrGLfloat *transformValues) {}
    virtual GrGLvoid coverFillPath(GrGLuint path, GrGLenum coverMode) {}
    virtual GrGLvoid coverStrokePath(GrGLuint name, GrGLenum coverMode) {}
    virtual GrGLvoid coverFillPathInstanced(GrGLsizei numPaths, GrGLenum pathNameType, const GrGLvoid *paths, GrGLuint pathBase, GrGLenum coverMode, GrGLenum transformType, const GrGLfloat *transformValues) {}
    virtual GrGLvoid coverStrokePathInstanced(GrGLsizei numPaths, GrGLenum pathNameType, const GrGLvoid *paths, GrGLuint pathBase, GrGLenum coverMode, GrGLenum transformType, const GrGLfloat* transformValues) {}
    virtual GrGLvoid stencilThenCoverFillPath(GrGLuint path, GrGLenum fillMode, GrGLuint mask, GrGLenum coverMode) {}
    virtual GrGLvoid stencilThenCoverStrokePath(GrGLuint path, GrGLint reference, GrGLuint mask, GrGLenum coverMode) {}
    virtual GrGLvoid stencilThenCoverFillPathInstanced(GrGLsizei numPaths, GrGLenum pathNameType, const GrGLvoid *paths, GrGLuint pathBase, GrGLenum fillMode, GrGLuint mask, GrGLenum coverMode, GrGLenum transformType, const GrGLfloat *transformValues) {}
    virtual GrGLvoid stencilThenCoverStrokePathInstanced(GrGLsizei numPaths, GrGLenum pathNameType, const GrGLvoid *paths, GrGLuint pathBase, GrGLint reference, GrGLuint mask, GrGLenum coverMode, GrGLenum transformType, const GrGLfloat *transformValues) {}
    virtual GrGLvoid programPathFragmentInputGen(GrGLuint program, GrGLint location, GrGLenum genMode, GrGLint components,const GrGLfloat *coeffs) {}
    virtual GrGLvoid bindFragmentInputLocation(GrGLuint program, GrGLint location, const GrGLchar* name) {}
    virtual GrGLint getProgramResourceLocation(GrGLuint program, GrGLenum programInterface, const GrGLchar *name) { return 0; }
    virtual GrGLvoid coverageModulation(GrGLenum components) {}
    virtual GrGLvoid multiDrawArraysIndirect(GrGLenum mode, const GrGLvoid *indirect, GrGLsizei drawcount, GrGLsizei stride) {}
    virtual GrGLvoid multiDrawElementsIndirect(GrGLenum mode, GrGLenum type, const GrGLvoid *indirect, GrGLsizei drawcount, GrGLsizei stride) {}
    virtual GrGLuint64 getTextureHandle(GrGLuint texture) { return 0; }
    virtual GrGLuint64 getTextureSamplerHandle(GrGLuint texture, GrGLuint sampler) { return 0; }
    virtual GrGLvoid makeTextureHandleResident(GrGLuint64 handle) {}
    virtual GrGLvoid makeTextureHandleNonResident(GrGLuint64 handle) {}
    virtual GrGLuint64 getImageHandle(GrGLuint texture, GrGLint level, GrGLboolean layered, GrGLint layer, GrGLint format) { return 0; }
    virtual GrGLvoid makeImageHandleResident(GrGLuint64 handle, GrGLenum access) {}
    virtual GrGLvoid makeImageHandleNonResident(GrGLuint64 handle) {}
    virtual GrGLboolean isTextureHandleResident(GrGLuint64 handle) { return GR_GL_FALSE; }
    virtual GrGLboolean isImageHandleResident(GrGLuint64 handle) { return GR_GL_FALSE; }
    virtual GrGLvoid uniformHandleui64(GrGLint location, GrGLuint64 v0) {}
    virtual GrGLvoid uniformHandleui64v(GrGLint location, GrGLsizei count, const GrGLuint64 *value) {}
    virtual GrGLvoid programUniformHandleui64(GrGLuint program, GrGLint location, GrGLuint64 v0) {}
    virtual GrGLvoid programUniformHandleui64v(GrGLuint program, GrGLint location, GrGLsizei count, const GrGLuint64 *value) {}
    virtual GrGLvoid textureParameteri(GrGLuint texture, GrGLenum target, GrGLenum pname, GrGLint param) {}
    virtual GrGLvoid textureParameteriv(GrGLuint texture, GrGLenum target, GrGLenum pname, const GrGLint *param) {}
    virtual GrGLvoid textureParameterf(GrGLuint texture, GrGLenum target, GrGLenum pname, float param) {}
    virtual GrGLvoid textureParameterfv(GrGLuint texture, GrGLenum target, GrGLenum pname, const float *param) {}
    virtual GrGLvoid textureImage1D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLint GrGLinternalformat, GrGLsizei width, GrGLint border, GrGLenum format, GrGLenum type, const GrGLvoid *pixels) {}
    virtual GrGLvoid textureImage2D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLint GrGLinternalformat, GrGLsizei width, GrGLsizei height, GrGLint border, GrGLenum format, GrGLenum type, const GrGLvoid *pixels) {}
    virtual GrGLvoid textureSubImage1D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLint xoffset, GrGLsizei width, GrGLenum format, GrGLenum type, const GrGLvoid *pixels) {}
    virtual GrGLvoid textureSubImage2D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLint xoffset, GrGLint yoffset, GrGLsizei width, GrGLsizei height, GrGLenum format, GrGLenum type, const GrGLvoid *pixels) {}
    virtual GrGLvoid copyTextureImage1D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLenum GrGLinternalformat, GrGLint x, GrGLint y, GrGLsizei width, GrGLint border) {}
    virtual GrGLvoid copyTextureImage2D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLenum GrGLinternalformat, GrGLint x, GrGLint y, GrGLsizei width, GrGLsizei height, GrGLint border) {}
    virtual GrGLvoid copyTextureSubImage1D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLint xoffset, GrGLint x, GrGLint y, GrGLsizei width) {}
    virtual GrGLvoid copyTextureSubImage2D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLint xoffset, GrGLint yoffset, GrGLint x, GrGLint y, GrGLsizei width, GrGLsizei height) {}
    virtual GrGLvoid getTextureImage(GrGLuint texture, GrGLenum target, GrGLint level, GrGLenum format, GrGLenum type, GrGLvoid *pixels) {}
    virtual GrGLvoid getTextureParameterfv(GrGLuint texture, GrGLenum target, GrGLenum pname, float *params) {}
    virtual GrGLvoid getTextureParameteriv(GrGLuint texture, GrGLenum target, GrGLenum pname, GrGLint *params) {}
    virtual GrGLvoid getTextureLevelParameterfv(GrGLuint texture, GrGLenum target, GrGLint level, GrGLenum pname, float *params) {}
    virtual GrGLvoid getTextureLevelParameteriv(GrGLuint texture, GrGLenum target, GrGLint level, GrGLenum pname, GrGLint *params) {}
    virtual GrGLvoid textureImage3D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLint GrGLinternalformat, GrGLsizei width, GrGLsizei height, GrGLsizei depth, GrGLint border, GrGLenum format, GrGLenum type, const GrGLvoid *pixels) {}
    virtual GrGLvoid textureSubImage3D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLint xoffset, GrGLint yoffset, GrGLint zoffset, GrGLsizei width, GrGLsizei height, GrGLsizei depth, GrGLenum format, GrGLenum type, const GrGLvoid *pixels) {}
    virtual GrGLvoid copyTextureSubImage3D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLint xoffset, GrGLint yoffset, GrGLint zoffset, GrGLint x, GrGLint y, GrGLsizei width, GrGLsizei height) {}
    virtual GrGLvoid compressedTextureImage3D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLenum GrGLinternalformat, GrGLsizei width, GrGLsizei height, GrGLsizei depth, GrGLint border, GrGLsizei imageSize, const GrGLvoid *data) {}
    virtual GrGLvoid compressedTextureImage2D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLenum GrGLinternalformat, GrGLsizei width, GrGLsizei height, GrGLint border, GrGLsizei imageSize, const GrGLvoid *data) {}
    virtual GrGLvoid compressedTextureImage1D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLenum GrGLinternalformat, GrGLsizei width, GrGLint border, GrGLsizei imageSize, const GrGLvoid *data) {}
    virtual GrGLvoid compressedTextureSubImage3D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLint xoffset, GrGLint yoffset, GrGLint zoffset, GrGLsizei width, GrGLsizei height, GrGLsizei depth, GrGLenum format, GrGLsizei imageSize, const GrGLvoid *data) {}
    virtual GrGLvoid compressedTextureSubImage2D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLint xoffset, GrGLint yoffset, GrGLsizei width, GrGLsizei height, GrGLenum format, GrGLsizei imageSize, const GrGLvoid *data) {}
    virtual GrGLvoid compressedTextureSubImage1D(GrGLuint texture, GrGLenum target, GrGLint level, GrGLint xoffset, GrGLsizei width, GrGLenum format, GrGLsizei imageSize, const GrGLvoid *data) {}
    virtual GrGLvoid getCompressedTextureImage(GrGLuint texture, GrGLenum target, GrGLint level, GrGLvoid *img) {}
    virtual GrGLvoid namedBufferData(GrGLuint buffer, GrGLsizeiptr size, const GrGLvoid *data, GrGLenum usage) {}
    virtual GrGLvoid namedBufferSubData(GrGLuint buffer, GrGLintptr offset, GrGLsizeiptr size, const GrGLvoid *data) {}
    virtual GrGLvoid* mapNamedBuffer(GrGLuint buffer, GrGLenum access) { return nullptr; }
    virtual GrGLboolean unmapNamedBuffer(GrGLuint buffer) { return GR_GL_FALSE; }
    virtual GrGLvoid getNamedBufferParameteriv(GrGLuint buffer, GrGLenum pname, GrGLint *params) {}
    virtual GrGLvoid getNamedBufferPointerv(GrGLuint buffer, GrGLenum pname, GrGLvoid* *params) {}
    virtual GrGLvoid getNamedBufferSubData(GrGLuint buffer, GrGLintptr offset, GrGLsizeiptr size, GrGLvoid *data) {}
    virtual GrGLvoid programUniform1f(GrGLuint program, GrGLint location, float v0) {}
    virtual GrGLvoid programUniform2f(GrGLuint program, GrGLint location, float v0, float v1) {}
    virtual GrGLvoid programUniform3f(GrGLuint program, GrGLint location, float v0, float v1, float v2) {}
    virtual GrGLvoid programUniform4f(GrGLuint program, GrGLint location, float v0, float v1, float v2, float v3) {}
    virtual GrGLvoid programUniform1i(GrGLuint program, GrGLint location, GrGLint v0) {}
    virtual GrGLvoid programUniform2i(GrGLuint program, GrGLint location, GrGLint v0, GrGLint v1) {}
    virtual GrGLvoid programUniform3i(GrGLuint program, GrGLint location, GrGLint v0, GrGLint v1, GrGLint v2) {}
    virtual GrGLvoid programUniform4i(GrGLuint program, GrGLint location, GrGLint v0, GrGLint v1, GrGLint v2, GrGLint v3) {}
    virtual GrGLvoid programUniform1fv(GrGLuint program, GrGLint location, GrGLsizei count, const float *value) {}
    virtual GrGLvoid programUniform2fv(GrGLuint program, GrGLint location, GrGLsizei count, const float *value) {}
    virtual GrGLvoid programUniform3fv(GrGLuint program, GrGLint location, GrGLsizei count, const float *value) {}
    virtual GrGLvoid programUniform4fv(GrGLuint program, GrGLint location, GrGLsizei count, const float *value) {}
    virtual GrGLvoid programUniform1iv(GrGLuint program, GrGLint location, GrGLsizei count, const GrGLint *value) {}
    virtual GrGLvoid programUniform2iv(GrGLuint program, GrGLint location, GrGLsizei count, const GrGLint *value) {}
    virtual GrGLvoid programUniform3iv(GrGLuint program, GrGLint location, GrGLsizei count, const GrGLint *value) {}
    virtual GrGLvoid programUniform4iv(GrGLuint program, GrGLint location, GrGLsizei count, const GrGLint *value) {}
    virtual GrGLvoid programUniformMatrix2fv(GrGLuint program, GrGLint location, GrGLsizei count, GrGLboolean transpose, const float *value) {}
    virtual GrGLvoid programUniformMatrix3fv(GrGLuint program, GrGLint location, GrGLsizei count, GrGLboolean transpose, const float *value) {}
    virtual GrGLvoid programUniformMatrix4fv(GrGLuint program, GrGLint location, GrGLsizei count, GrGLboolean transpose, const float *value) {}
    virtual GrGLvoid programUniformMatrix2x3fv(GrGLuint program, GrGLint location, GrGLsizei count, GrGLboolean transpose, const float *value) {}
    virtual GrGLvoid programUniformMatrix3x2fv(GrGLuint program, GrGLint location, GrGLsizei count, GrGLboolean transpose, const float *value) {}
    virtual GrGLvoid programUniformMatrix2x4fv(GrGLuint program, GrGLint location, GrGLsizei count, GrGLboolean transpose, const float *value) {}
    virtual GrGLvoid programUniformMatrix4x2fv(GrGLuint program, GrGLint location, GrGLsizei count, GrGLboolean transpose, const float *value) {}
    virtual GrGLvoid programUniformMatrix3x4fv(GrGLuint program, GrGLint location, GrGLsizei count, GrGLboolean transpose, const float *value) {}
    virtual GrGLvoid programUniformMatrix4x3fv(GrGLuint program, GrGLint location, GrGLsizei count, GrGLboolean transpose, const float *value) {}
    virtual GrGLvoid namedRenderbufferStorage(GrGLuint renderbuffer, GrGLenum GrGLinternalformat, GrGLsizei width, GrGLsizei height) {}
    virtual GrGLvoid getNamedRenderbufferParameteriv(GrGLuint renderbuffer, GrGLenum pname, GrGLint *params) {}
    virtual GrGLvoid namedRenderbufferStorageMultisample(GrGLuint renderbuffer, GrGLsizei samples, GrGLenum GrGLinternalformat, GrGLsizei width, GrGLsizei height) {}
    virtual GrGLenum checkNamedFramebufferStatus(GrGLuint framebuffer, GrGLenum target) { return GR_GL_FRAMEBUFFER_COMPLETE; }
    virtual GrGLvoid namedFramebufferTexture1D(GrGLuint framebuffer, GrGLenum attachment, GrGLenum textarget, GrGLuint texture, GrGLint level) {}
    virtual GrGLvoid namedFramebufferTexture2D(GrGLuint framebuffer, GrGLenum attachment, GrGLenum textarget, GrGLuint texture, GrGLint level) {}
    virtual GrGLvoid namedFramebufferTexture3D(GrGLuint framebuffer, GrGLenum attachment, GrGLenum textarget, GrGLuint texture, GrGLint level, GrGLint zoffset) {}
    virtual GrGLvoid namedFramebufferRenderbuffer(GrGLuint framebuffer, GrGLenum attachment, GrGLenum renderbuffertarget, GrGLuint renderbuffer) {}
    virtual GrGLvoid getNamedFramebufferAttachmentParameteriv(GrGLuint framebuffer, GrGLenum attachment, GrGLenum pname, GrGLint *params) {}
    virtual GrGLvoid generateTextureMipmap(GrGLuint texture, GrGLenum target) {}
    virtual GrGLvoid framebufferDrawBuffer(GrGLuint framebuffer, GrGLenum mode) {}
    virtual GrGLvoid framebufferDrawBuffers(GrGLuint framebuffer, GrGLsizei n, const GrGLenum *bufs) {}
    virtual GrGLvoid framebufferReadBuffer(GrGLuint framebuffer, GrGLenum mode) {}
    virtual GrGLvoid getFramebufferParameteriv(GrGLuint framebuffer, GrGLenum pname, GrGLint *param) {}
    virtual GrGLvoid namedCopyBufferSubData(GrGLuint readBuffer, GrGLuint writeBuffer, GrGLintptr readOffset, GrGLintptr writeOffset, GrGLsizeiptr size) {}
    virtual GrGLvoid vertexArrayVertexOffset(GrGLuint vaobj, GrGLuint buffer, GrGLint size, GrGLenum type, GrGLsizei stride, GrGLintptr offset) {}
    virtual GrGLvoid vertexArrayColorOffset(GrGLuint vaobj, GrGLuint buffer, GrGLint size, GrGLenum type, GrGLsizei stride, GrGLintptr offset) {}
    virtual GrGLvoid vertexArrayEdgeFlagOffset(GrGLuint vaobj, GrGLuint buffer, GrGLsizei stride, GrGLintptr offset) {}
    virtual GrGLvoid vertexArrayIndexOffset(GrGLuint vaobj, GrGLuint buffer, GrGLenum type, GrGLsizei stride, GrGLintptr offset) {}
    virtual GrGLvoid vertexArrayNormalOffset(GrGLuint vaobj, GrGLuint buffer, GrGLenum type, GrGLsizei stride, GrGLintptr offset) {}
    virtual GrGLvoid vertexArrayTexCoordOffset(GrGLuint vaobj, GrGLuint buffer, GrGLint size, GrGLenum type, GrGLsizei stride, GrGLintptr offset) {}
    virtual GrGLvoid vertexArrayMultiTexCoordOffset(GrGLuint vaobj, GrGLuint buffer, GrGLenum texunit, GrGLint size, GrGLenum type, GrGLsizei stride, GrGLintptr offset) {}
    virtual GrGLvoid vertexArrayFogCoordOffset(GrGLuint vaobj, GrGLuint buffer, GrGLenum type, GrGLsizei stride, GrGLintptr offset) {}
    virtual GrGLvoid vertexArraySecondaryColorOffset(GrGLuint vaobj, GrGLuint buffer, GrGLint size, GrGLenum type, GrGLsizei stride, GrGLintptr offset) {}
    virtual GrGLvoid vertexArrayVertexAttribOffset(GrGLuint vaobj, GrGLuint buffer, GrGLuint index, GrGLint size, GrGLenum type, GrGLboolean normalized, GrGLsizei stride, GrGLintptr offset) {}
    virtual GrGLvoid vertexArrayVertexAttribIOffset(GrGLuint vaobj, GrGLuint buffer, GrGLuint index, GrGLint size, GrGLenum type, GrGLsizei stride, GrGLintptr offset) {}
    virtual GrGLvoid enableVertexArray(GrGLuint vaobj, GrGLenum array) {}
    virtual GrGLvoid disableVertexArray(GrGLuint vaobj, GrGLenum array) {}
    virtual GrGLvoid enableVertexArrayAttrib(GrGLuint vaobj, GrGLuint index) {}
    virtual GrGLvoid disableVertexArrayAttrib(GrGLuint vaobj, GrGLuint index) {}
    virtual GrGLvoid getVertexArrayIntegerv(GrGLuint vaobj, GrGLenum pname, GrGLint *param) {}
    virtual GrGLvoid getVertexArrayPointerv(GrGLuint vaobj, GrGLenum pname, GrGLvoid **param) {}
    virtual GrGLvoid getVertexArrayIntegeri_v(GrGLuint vaobj, GrGLuint index, GrGLenum pname, GrGLint *param) {}
    virtual GrGLvoid getVertexArrayPointeri_v(GrGLuint vaobj, GrGLuint index, GrGLenum pname, GrGLvoid **param) {}
    virtual GrGLvoid* mapNamedBufferRange(GrGLuint buffer, GrGLintptr offset, GrGLsizeiptr length, GrGLbitfield access) { return nullptr; }
    virtual GrGLvoid flushMappedNamedBufferRange(GrGLuint buffer, GrGLintptr offset, GrGLsizeiptr length) {}
    virtual GrGLvoid textureBuffer(GrGLuint texture, GrGLenum target, GrGLenum internalformat, GrGLuint buffer) {}
    virtual GrGLsync fenceSync(GrGLenum condition, GrGLbitfield flags) { return nullptr;  }
    virtual GrGLboolean isSync(GrGLsync) { return false;  }
    virtual GrGLenum clientWaitSync(GrGLsync sync, GrGLbitfield flags, GrGLuint64 timeout) { return GR_GL_WAIT_FAILED;  }
    virtual GrGLvoid waitSync(GrGLsync sync, GrGLbitfield flags, GrGLuint64 timeout) {}
    virtual GrGLvoid deleteSync(GrGLsync sync) {}
    virtual GrGLvoid debugMessageControl(GrGLenum source, GrGLenum type, GrGLenum severity, GrGLsizei count, const GrGLuint* ids, GrGLboolean enabled) {}
    virtual GrGLvoid debugMessageInsert(GrGLenum source, GrGLenum type, GrGLuint id, GrGLenum severity, GrGLsizei length,  const GrGLchar* buf) {}
    virtual GrGLvoid debugMessageCallback(GRGLDEBUGPROC callback, const GrGLvoid* userParam) {}
    virtual GrGLuint getDebugMessageLog(GrGLuint count, GrGLsizei bufSize, GrGLenum* sources, GrGLenum* types, GrGLuint* ids, GrGLenum* severities, GrGLsizei* lengths,  GrGLchar* messageLog) { return 0; }
    virtual GrGLvoid pushDebugGroup(GrGLenum source, GrGLuint id, GrGLsizei length,  const GrGLchar * message) {}
    virtual GrGLvoid popDebugGroup() {}
    virtual GrGLvoid objectLabel(GrGLenum identifier, GrGLuint name, GrGLsizei length, const GrGLchar *label) {}
    virtual GrGLvoid getInternalformativ(GrGLenum target, GrGLenum internalformat, GrGLenum pname, GrGLsizei bufSize, GrGLint *params) {}
    virtual GrGLvoid programBinary(GrGLuint program, GrGLenum binaryFormat, void *binary, GrGLsizei length) {}
    virtual GrGLvoid getProgramBinary(GrGLuint program, GrGLsizei bufsize, GrGLsizei* length, GrGLenum *binaryFormat, void *binary) {}
    virtual GrGLvoid programParameteri(GrGLuint program, GrGLenum pname, GrGLint value) {}

protected:
    // This must be called by leaf class
    void init(GrGLStandard standard) {
        fStandard = standard;
        fExtensions.init(standard, fFunctions.fGetString, fFunctions.fGetStringi,
                         fFunctions.fGetIntegerv, nullptr, GR_EGL_NO_DISPLAY);
    }
    TestInterface();
};

template <typename R, typename... A>
GrGLFunction<R GR_GL_FUNCTION_TYPE(A...)> bind_to_member(TestInterface* interface,
                                                         R (TestInterface::*member)(A...)) {
    return [interface, member](A... a) -> R { return (interface->*member)(a...); };
}

TestInterface::TestInterface() {
    fFunctions.fActiveTexture = bind_to_member(this, &TestInterface::activeTexture);
    fFunctions.fAttachShader = bind_to_member(this, &TestInterface::attachShader);
    fFunctions.fBeginQuery = bind_to_member(this, &TestInterface::beginQuery);
    fFunctions.fBindAttribLocation = bind_to_member(this, &TestInterface::bindAttribLocation);
    fFunctions.fBindBuffer = bind_to_member(this, &TestInterface::bindBuffer);
    fFunctions.fBindFramebuffer = bind_to_member(this, &TestInterface::bindFramebuffer);
    fFunctions.fBindRenderbuffer = bind_to_member(this, &TestInterface::bindRenderbuffer);
    fFunctions.fBindSampler = bind_to_member(this, &TestInterface::bindSampler);
    fFunctions.fBindTexture = bind_to_member(this, &TestInterface::bindTexture);
    fFunctions.fBindFragDataLocation = bind_to_member(this, &TestInterface::bindFragDataLocation);
    fFunctions.fBindFragDataLocationIndexed = bind_to_member(this, &TestInterface::bindFragDataLocationIndexed);
    fFunctions.fBindVertexArray = bind_to_member(this, &TestInterface::bindVertexArray);
    fFunctions.fBlendBarrier = bind_to_member(this, &TestInterface::blendBarrier);
    fFunctions.fBlendColor = bind_to_member(this, &TestInterface::blendColor);
    fFunctions.fBlendEquation = bind_to_member(this, &TestInterface::blendEquation);
    fFunctions.fBlendFunc = bind_to_member(this, &TestInterface::blendFunc);
    fFunctions.fBlitFramebuffer = bind_to_member(this, &TestInterface::blitFramebuffer);
    fFunctions.fBufferData = bind_to_member(this, &TestInterface::bufferData);
    fFunctions.fBufferSubData = bind_to_member(this, &TestInterface::bufferSubData);
    fFunctions.fCheckFramebufferStatus = bind_to_member(this, &TestInterface::checkFramebufferStatus);
    fFunctions.fClear = bind_to_member(this, &TestInterface::clear);
    fFunctions.fClearColor = bind_to_member(this, &TestInterface::clearColor);
    fFunctions.fClearStencil = bind_to_member(this, &TestInterface::clearStencil);
    fFunctions.fColorMask = bind_to_member(this, &TestInterface::colorMask);
    fFunctions.fCompileShader = bind_to_member(this, &TestInterface::compileShader);
    fFunctions.fCompressedTexImage2D = bind_to_member(this, &TestInterface::compressedTexImage2D);
    fFunctions.fCompressedTexSubImage2D = bind_to_member(this, &TestInterface::compressedTexSubImage2D);
    fFunctions.fCopyTexSubImage2D = bind_to_member(this, &TestInterface::copyTexSubImage2D);
    fFunctions.fCreateProgram = bind_to_member(this, &TestInterface::createProgram);
    fFunctions.fCreateShader = bind_to_member(this, &TestInterface::createShader);
    fFunctions.fCullFace = bind_to_member(this, &TestInterface::cullFace);
    fFunctions.fDeleteBuffers = bind_to_member(this, &TestInterface::deleteBuffers);
    fFunctions.fDeleteFramebuffers = bind_to_member(this, &TestInterface::deleteFramebuffers);
    fFunctions.fDeleteProgram = bind_to_member(this, &TestInterface::deleteProgram);
    fFunctions.fDeleteQueries = bind_to_member(this, &TestInterface::deleteQueries);
    fFunctions.fDeleteRenderbuffers = bind_to_member(this, &TestInterface::deleteRenderbuffers);
    fFunctions.fDeleteSamplers = bind_to_member(this, &TestInterface::deleteSamplers);
    fFunctions.fDeleteShader = bind_to_member(this, &TestInterface::deleteShader);
    fFunctions.fDeleteTextures = bind_to_member(this, &TestInterface::deleteTextures);
    fFunctions.fDeleteVertexArrays = bind_to_member(this, &TestInterface::deleteVertexArrays);
    fFunctions.fDepthMask = bind_to_member(this, &TestInterface::depthMask);
    fFunctions.fDisable = bind_to_member(this, &TestInterface::disable);
    fFunctions.fDisableVertexAttribArray = bind_to_member(this, &TestInterface::disableVertexAttribArray);
    fFunctions.fDrawArrays = bind_to_member(this, &TestInterface::drawArrays);
    fFunctions.fDrawArraysInstanced = bind_to_member(this, &TestInterface::drawArraysInstanced);
    fFunctions.fDrawArraysIndirect = bind_to_member(this, &TestInterface::drawArraysIndirect);
    fFunctions.fDrawBuffer = bind_to_member(this, &TestInterface::drawBuffer);
    fFunctions.fDrawBuffers = bind_to_member(this, &TestInterface::drawBuffers);
    fFunctions.fDrawElements = bind_to_member(this, &TestInterface::drawElements);
    fFunctions.fDrawElementsInstanced = bind_to_member(this, &TestInterface::drawElementsInstanced);
    fFunctions.fDrawElementsIndirect = bind_to_member(this, &TestInterface::drawElementsIndirect);
    fFunctions.fDrawRangeElements = bind_to_member(this, &TestInterface::drawRangeElements);
    fFunctions.fEnable = bind_to_member(this, &TestInterface::enable);
    fFunctions.fEnableVertexAttribArray = bind_to_member(this, &TestInterface::enableVertexAttribArray);
    fFunctions.fEndQuery = bind_to_member(this, &TestInterface::endQuery);
    fFunctions.fFinish = bind_to_member(this, &TestInterface::finish);
    fFunctions.fFlush = bind_to_member(this, &TestInterface::flush);
    fFunctions.fFlushMappedBufferRange = bind_to_member(this, &TestInterface::flushMappedBufferRange);
    fFunctions.fFramebufferRenderbuffer = bind_to_member(this, &TestInterface::framebufferRenderbuffer);
    fFunctions.fFramebufferTexture2D = bind_to_member(this, &TestInterface::framebufferTexture2D);
    fFunctions.fFramebufferTexture2DMultisample = bind_to_member(this, &TestInterface::framebufferTexture2DMultisample);
    fFunctions.fFrontFace = bind_to_member(this, &TestInterface::frontFace);
    fFunctions.fGenBuffers = bind_to_member(this, &TestInterface::genBuffers);
    fFunctions.fGenFramebuffers = bind_to_member(this, &TestInterface::genFramebuffers);
    fFunctions.fGenerateMipmap = bind_to_member(this, &TestInterface::generateMipmap);
    fFunctions.fGenQueries = bind_to_member(this, &TestInterface::genQueries);
    fFunctions.fGenRenderbuffers = bind_to_member(this, &TestInterface::genRenderbuffers);
    fFunctions.fGenSamplers = bind_to_member(this, &TestInterface::genSamplers);
    fFunctions.fGenTextures = bind_to_member(this, &TestInterface::genTextures);
    fFunctions.fGenVertexArrays = bind_to_member(this, &TestInterface::genVertexArrays);
    fFunctions.fGetBufferParameteriv = bind_to_member(this, &TestInterface::getBufferParameteriv);
    fFunctions.fGetError = bind_to_member(this, &TestInterface::getError);
    fFunctions.fGetFramebufferAttachmentParameteriv = bind_to_member(this, &TestInterface::getFramebufferAttachmentParameteriv);
    fFunctions.fGetIntegerv = bind_to_member(this, &TestInterface::getIntegerv);
    fFunctions.fGetMultisamplefv = bind_to_member(this, &TestInterface::getMultisamplefv);
    fFunctions.fGetProgramInfoLog = bind_to_member(this, &TestInterface::getProgramInfoLog);
    fFunctions.fGetProgramiv = bind_to_member(this, &TestInterface::getProgramiv);
    fFunctions.fGetQueryiv = bind_to_member(this, &TestInterface::getQueryiv);
    fFunctions.fGetQueryObjecti64v = bind_to_member(this, &TestInterface::getQueryObjecti64v);
    fFunctions.fGetQueryObjectiv = bind_to_member(this, &TestInterface::getQueryObjectiv);
    fFunctions.fGetQueryObjectui64v = bind_to_member(this, &TestInterface::getQueryObjectui64v);
    fFunctions.fGetQueryObjectuiv = bind_to_member(this, &TestInterface::getQueryObjectuiv);
    fFunctions.fGetRenderbufferParameteriv = bind_to_member(this, &TestInterface::getRenderbufferParameteriv);
    fFunctions.fGetShaderInfoLog = bind_to_member(this, &TestInterface::getShaderInfoLog);
    fFunctions.fGetShaderiv = bind_to_member(this, &TestInterface::getShaderiv);
    fFunctions.fGetShaderPrecisionFormat = bind_to_member(this, &TestInterface::getShaderPrecisionFormat);
    fFunctions.fGetString = bind_to_member(this, &TestInterface::getString);
    fFunctions.fGetStringi = bind_to_member(this, &TestInterface::getStringi);
    fFunctions.fGetTexLevelParameteriv = bind_to_member(this, &TestInterface::getTexLevelParameteriv);
    fFunctions.fGetUniformLocation = bind_to_member(this, &TestInterface::getUniformLocation);
    fFunctions.fInsertEventMarker = bind_to_member(this, &TestInterface::insertEventMarker);
    fFunctions.fInvalidateBufferData = bind_to_member(this, &TestInterface::invalidateBufferData);
    fFunctions.fInvalidateBufferSubData = bind_to_member(this, &TestInterface::invalidateBufferSubData);
    fFunctions.fInvalidateFramebuffer = bind_to_member(this, &TestInterface::invalidateFramebuffer);
    fFunctions.fInvalidateSubFramebuffer = bind_to_member(this, &TestInterface::invalidateSubFramebuffer);
    fFunctions.fInvalidateTexImage = bind_to_member(this, &TestInterface::invalidateTexImage);
    fFunctions.fInvalidateTexSubImage = bind_to_member(this, &TestInterface::invalidateTexSubImage);
    fFunctions.fIsTexture = bind_to_member(this, &TestInterface::isTexture);
    fFunctions.fLineWidth = bind_to_member(this, &TestInterface::lineWidth);
    fFunctions.fLinkProgram = bind_to_member(this, &TestInterface::linkProgram);
    fFunctions.fMapBuffer = bind_to_member(this, &TestInterface::mapBuffer);
    fFunctions.fMapBufferRange = bind_to_member(this, &TestInterface::mapBufferRange);
    fFunctions.fMapBufferSubData = bind_to_member(this, &TestInterface::mapBufferSubData);
    fFunctions.fMapTexSubImage2D = bind_to_member(this, &TestInterface::mapTexSubImage2D);
    fFunctions.fPixelStorei = bind_to_member(this, &TestInterface::pixelStorei);
    fFunctions.fPolygonMode = bind_to_member(this, &TestInterface::polygonMode);
    fFunctions.fPopGroupMarker = bind_to_member(this, &TestInterface::popGroupMarker);
    fFunctions.fPushGroupMarker = bind_to_member(this, &TestInterface::pushGroupMarker);
    fFunctions.fQueryCounter = bind_to_member(this, &TestInterface::queryCounter);
    fFunctions.fReadBuffer = bind_to_member(this, &TestInterface::readBuffer);
    fFunctions.fReadPixels = bind_to_member(this, &TestInterface::readPixels);
    fFunctions.fRenderbufferStorage = bind_to_member(this, &TestInterface::renderbufferStorage);
    fFunctions.fRenderbufferStorageMultisample = bind_to_member(this, &TestInterface::renderbufferStorageMultisample);
    fFunctions.fResolveMultisampleFramebuffer = bind_to_member(this, &TestInterface::resolveMultisampleFramebuffer);
    fFunctions.fScissor = bind_to_member(this, &TestInterface::scissor);
    fFunctions.fBindUniformLocation = bind_to_member(this, &TestInterface::bindUniformLocation);
    fFunctions.fSamplerParameteri = bind_to_member(this, &TestInterface::samplerParameteri);
    fFunctions.fSamplerParameteriv = bind_to_member(this, &TestInterface::samplerParameteriv);
    fFunctions.fShaderSource = bind_to_member(this, &TestInterface::shaderSource);
    fFunctions.fStencilFunc = bind_to_member(this, &TestInterface::stencilFunc);
    fFunctions.fStencilFuncSeparate = bind_to_member(this, &TestInterface::stencilFuncSeparate);
    fFunctions.fStencilMask = bind_to_member(this, &TestInterface::stencilMask);
    fFunctions.fStencilMaskSeparate = bind_to_member(this, &TestInterface::stencilMaskSeparate);
    fFunctions.fStencilOp = bind_to_member(this, &TestInterface::stencilOp);
    fFunctions.fStencilOpSeparate = bind_to_member(this, &TestInterface::stencilOpSeparate);
    fFunctions.fTexBuffer = bind_to_member(this, &TestInterface::texBuffer);
    fFunctions.fTexImage2D = bind_to_member(this, &TestInterface::texImage2D);
    fFunctions.fTexParameterf = bind_to_member(this, &TestInterface::texParameterf);
    fFunctions.fTexParameterfv = bind_to_member(this, &TestInterface::texParameterfv);
    fFunctions.fTexParameteri = bind_to_member(this, &TestInterface::texParameteri);
    fFunctions.fTexParameteriv = bind_to_member(this, &TestInterface::texParameteriv);
    fFunctions.fTexStorage2D = bind_to_member(this, &TestInterface::texStorage2D);
    fFunctions.fDiscardFramebuffer = bind_to_member(this, &TestInterface::discardFramebuffer);
    fFunctions.fTexSubImage2D = bind_to_member(this, &TestInterface::texSubImage2D);
    fFunctions.fTextureBarrier = bind_to_member(this, &TestInterface::textureBarrier);
    fFunctions.fUniform1f = bind_to_member(this, &TestInterface::uniform1f);
    fFunctions.fUniform1i = bind_to_member(this, &TestInterface::uniform1i);
    fFunctions.fUniform1fv = bind_to_member(this, &TestInterface::uniform1fv);
    fFunctions.fUniform1iv = bind_to_member(this, &TestInterface::uniform1iv);
    fFunctions.fUniform2f = bind_to_member(this, &TestInterface::uniform2f);
    fFunctions.fUniform2i = bind_to_member(this, &TestInterface::uniform2i);
    fFunctions.fUniform2fv = bind_to_member(this, &TestInterface::uniform2fv);
    fFunctions.fUniform2iv = bind_to_member(this, &TestInterface::uniform2iv);
    fFunctions.fUniform3f = bind_to_member(this, &TestInterface::uniform3f);
    fFunctions.fUniform3i = bind_to_member(this, &TestInterface::uniform3i);
    fFunctions.fUniform3fv = bind_to_member(this, &TestInterface::uniform3fv);
    fFunctions.fUniform3iv = bind_to_member(this, &TestInterface::uniform3iv);
    fFunctions.fUniform4f = bind_to_member(this, &TestInterface::uniform4f);
    fFunctions.fUniform4i = bind_to_member(this, &TestInterface::uniform4i);
    fFunctions.fUniform4fv = bind_to_member(this, &TestInterface::uniform4fv);
    fFunctions.fUniform4iv = bind_to_member(this, &TestInterface::uniform4iv);
    fFunctions.fUniformMatrix2fv = bind_to_member(this, &TestInterface::uniformMatrix2fv);
    fFunctions.fUniformMatrix3fv = bind_to_member(this, &TestInterface::uniformMatrix3fv);
    fFunctions.fUniformMatrix4fv = bind_to_member(this, &TestInterface::uniformMatrix4fv);
    fFunctions.fUnmapBuffer = bind_to_member(this, &TestInterface::unmapBuffer);
    fFunctions.fUnmapBufferSubData = bind_to_member(this, &TestInterface::unmapBufferSubData);
    fFunctions.fUnmapTexSubImage2D = bind_to_member(this, &TestInterface::unmapTexSubImage2D);
    fFunctions.fUseProgram = bind_to_member(this, &TestInterface::useProgram);
    fFunctions.fVertexAttrib1f = bind_to_member(this, &TestInterface::vertexAttrib1f);
    fFunctions.fVertexAttrib2fv = bind_to_member(this, &TestInterface::vertexAttrib2fv);
    fFunctions.fVertexAttrib3fv = bind_to_member(this, &TestInterface::vertexAttrib3fv);
    fFunctions.fVertexAttrib4fv = bind_to_member(this, &TestInterface::vertexAttrib4fv);
    fFunctions.fVertexAttribDivisor = bind_to_member(this, &TestInterface::vertexAttribDivisor);
    fFunctions.fVertexAttribIPointer = bind_to_member(this, &TestInterface::vertexAttribIPointer);
    fFunctions.fVertexAttribPointer = bind_to_member(this, &TestInterface::vertexAttribPointer);
    fFunctions.fViewport = bind_to_member(this, &TestInterface::viewport);
    fFunctions.fMatrixLoadf = bind_to_member(this, &TestInterface::matrixLoadf);
    fFunctions.fMatrixLoadIdentity = bind_to_member(this, &TestInterface::matrixLoadIdentity);
    fFunctions.fPathCommands = bind_to_member(this, &TestInterface::pathCommands);
    fFunctions.fPathParameteri = bind_to_member(this, &TestInterface::pathParameteri);
    fFunctions.fPathParameterf = bind_to_member(this, &TestInterface::pathParameterf);
    fFunctions.fGenPaths = bind_to_member(this, &TestInterface::genPaths);
    fFunctions.fDeletePaths = bind_to_member(this, &TestInterface::deletePaths);
    fFunctions.fIsPath = bind_to_member(this, &TestInterface::isPath);
    fFunctions.fPathStencilFunc = bind_to_member(this, &TestInterface::pathStencilFunc);
    fFunctions.fStencilFillPath = bind_to_member(this, &TestInterface::stencilFillPath);
    fFunctions.fStencilStrokePath = bind_to_member(this, &TestInterface::stencilStrokePath);
    fFunctions.fStencilFillPathInstanced = bind_to_member(this, &TestInterface::stencilFillPathInstanced);
    fFunctions.fStencilStrokePathInstanced = bind_to_member(this, &TestInterface::stencilStrokePathInstanced);
    fFunctions.fCoverFillPath = bind_to_member(this, &TestInterface::coverFillPath);
    fFunctions.fCoverStrokePath = bind_to_member(this, &TestInterface::coverStrokePath);
    fFunctions.fCoverFillPathInstanced = bind_to_member(this, &TestInterface::coverFillPathInstanced);
    fFunctions.fCoverStrokePathInstanced = bind_to_member(this, &TestInterface::coverStrokePathInstanced);
    fFunctions.fStencilThenCoverFillPath = bind_to_member(this, &TestInterface::stencilThenCoverFillPath);
    fFunctions.fStencilThenCoverStrokePath = bind_to_member(this, &TestInterface::stencilThenCoverStrokePath);
    fFunctions.fStencilThenCoverFillPathInstanced = bind_to_member(this, &TestInterface::stencilThenCoverFillPathInstanced);
    fFunctions.fStencilThenCoverStrokePathInstanced = bind_to_member(this, &TestInterface::stencilThenCoverStrokePathInstanced);
    fFunctions.fProgramPathFragmentInputGen = bind_to_member(this, &TestInterface::programPathFragmentInputGen);
    fFunctions.fBindFragmentInputLocation = bind_to_member(this, &TestInterface::bindFragmentInputLocation);
    fFunctions.fGetProgramResourceLocation = bind_to_member(this, &TestInterface::getProgramResourceLocation);
    fFunctions.fCoverageModulation = bind_to_member(this, &TestInterface::coverageModulation);
    fFunctions.fMultiDrawArraysIndirect = bind_to_member(this, &TestInterface::multiDrawArraysIndirect);
    fFunctions.fMultiDrawElementsIndirect = bind_to_member(this, &TestInterface::multiDrawElementsIndirect);
    fFunctions.fFenceSync = bind_to_member(this, &TestInterface::fenceSync);
    fFunctions.fIsSync = bind_to_member(this, &TestInterface::isSync);
    fFunctions.fClientWaitSync = bind_to_member(this, &TestInterface::clientWaitSync);
    fFunctions.fWaitSync = bind_to_member(this, &TestInterface::waitSync);
    fFunctions.fDeleteSync = bind_to_member(this, &TestInterface::deleteSync);
    fFunctions.fDebugMessageControl = bind_to_member(this, &TestInterface::debugMessageControl);
    fFunctions.fDebugMessageInsert = bind_to_member(this, &TestInterface::debugMessageInsert);
    fFunctions.fDebugMessageCallback = bind_to_member(this, &TestInterface::debugMessageCallback);
    fFunctions.fGetDebugMessageLog = bind_to_member(this, &TestInterface::getDebugMessageLog);
    fFunctions.fPushDebugGroup = bind_to_member(this, &TestInterface::pushDebugGroup);
    fFunctions.fPopDebugGroup = bind_to_member(this, &TestInterface::popDebugGroup);
    fFunctions.fObjectLabel = bind_to_member(this, &TestInterface::objectLabel);
    fFunctions.fGetInternalformativ = bind_to_member(this, &TestInterface::getInternalformativ);
    fFunctions.fProgramBinary = bind_to_member(this, &TestInterface::programBinary);
    fFunctions.fGetProgramBinary = bind_to_member(this, &TestInterface::getProgramBinary);
    fFunctions.fProgramParameteri = bind_to_member(this, &TestInterface::programParameteri);
}

/** Null interface implementation */
class NullInterface : public TestInterface {
public:
    NullInterface(bool enableNVPR)
        : fCurrDrawFramebuffer(0)
        , fCurrReadFramebuffer(0)
        , fCurrRenderbuffer(0)
        , fCurrProgramID(0)
        , fCurrShaderID(0)
        , fCurrGenericID(0)
        , fCurrUniformLocation(0)
        , fCurrPathID(0) {
        memset(fBoundBuffers, 0, sizeof(fBoundBuffers));
        fAdvertisedExtensions.push_back("GL_ARB_framebuffer_object");
        fAdvertisedExtensions.push_back("GL_ARB_blend_func_extended");
        fAdvertisedExtensions.push_back("GL_ARB_timer_query");
        fAdvertisedExtensions.push_back("GL_ARB_draw_buffers");
        fAdvertisedExtensions.push_back("GL_ARB_occlusion_query");
        fAdvertisedExtensions.push_back("GL_EXT_stencil_wrap");
        if (enableNVPR) {
            fAdvertisedExtensions.push_back("GL_NV_path_rendering");
            fAdvertisedExtensions.push_back("GL_ARB_program_interface_query");
        }
        fAdvertisedExtensions.push_back(nullptr);

        this->init(kGL_GrGLStandard);
    }

    GrGLenum checkFramebufferStatus(GrGLenum target) override {
        return GR_GL_FRAMEBUFFER_COMPLETE;
    }

    GrGLvoid genBuffers(GrGLsizei n, GrGLuint* ids) override {
        for (int i = 0; i < n; ++i) {
            Buffer* buffer = fBufferManager.create();
            ids[i] = buffer->id();
        }
    }

    GrGLvoid bufferData(GrGLenum target, GrGLsizeiptr size, const GrGLvoid* data,
                        GrGLenum usage) override {
        GrGLuint id = fBoundBuffers[GetBufferIndex(target)];
        if (id > 0) {
            Buffer* buffer = fBufferManager.lookUp(id);
            buffer->allocate(size, (const GrGLchar*) data);
        }
    }

    GrGLuint createProgram() override {
        return ++fCurrProgramID;
    }

    GrGLuint createShader(GrGLenum type) override {
        return ++fCurrShaderID;
    }

    GrGLvoid bindBuffer(GrGLenum target, GrGLuint buffer) override {
        fBoundBuffers[GetBufferIndex(target)] = buffer;
    }

   // deleting a bound buffer has the side effect of binding 0
   GrGLvoid deleteBuffers(GrGLsizei n, const GrGLuint* ids) override {
        // First potentially unbind the buffers.
        for (int buffIdx = 0; buffIdx < kNumBufferTargets; ++buffIdx) {
            if (!fBoundBuffers[buffIdx]) {
                continue;
            }
            for (int i = 0; i < n; ++i) {
                if (ids[i] == fBoundBuffers[buffIdx]) {
                    fBoundBuffers[buffIdx] = 0;
                    break;
                }
            }
        }

        // Then actually "delete" the buffers.
        for (int i = 0; i < n; ++i) {
            if (ids[i] > 0) {
                Buffer* buffer = fBufferManager.lookUp(ids[i]);
                fBufferManager.free(buffer);
            }
        }
    }

    GrGLvoid genFramebuffers(GrGLsizei n, GrGLuint *framebuffers) override {
        for (int i = 0; i < n; ++i) {
            Framebuffer* framebuffer = fFramebufferManager.create();
            framebuffers[i] = framebuffer->id();
        }
    }

    GrGLvoid bindFramebuffer(GrGLenum target, GrGLuint framebuffer) override {
        SkASSERT(GR_GL_FRAMEBUFFER == target || GR_GL_DRAW_FRAMEBUFFER == target ||
                 GR_GL_READ_FRAMEBUFFER == target);
        if (GR_GL_READ_FRAMEBUFFER != target) {
            fCurrDrawFramebuffer = framebuffer;
        }
        if (GR_GL_DRAW_FRAMEBUFFER != target) {
            fCurrReadFramebuffer = framebuffer;
        }
    }

    GrGLvoid deleteFramebuffers(GrGLsizei n, const GrGLuint* ids) override {
        for (int i = 0; i < n; ++i) {
            if (ids[i] == fCurrDrawFramebuffer) {
                fCurrDrawFramebuffer = 0;
            }
            if (ids[i] == fCurrReadFramebuffer) {
                fCurrReadFramebuffer = 0;
            }

            if (ids[i] > 0) {
                Framebuffer* framebuffer = fFramebufferManager.lookUp(ids[i]);
                fFramebufferManager.free(framebuffer);
            }
        }
    }

    GrGLvoid genQueries(GrGLsizei n, GrGLuint *ids) override { this->genGenericIds(n, ids); }

    GrGLvoid genRenderbuffers(GrGLsizei n, GrGLuint *renderbuffers) override {
        for (int i = 0; i < n; ++i) {
            Renderbuffer* renderbuffer = fRenderbufferManager.create();
            renderbuffers[i] = renderbuffer->id();
        }
    }

    GrGLvoid bindRenderbuffer(GrGLenum target, GrGLuint renderbuffer) override {
        SkASSERT(GR_GL_RENDERBUFFER == target);
        fCurrRenderbuffer = renderbuffer;
    }

    GrGLvoid deleteRenderbuffers(GrGLsizei n, const GrGLuint* ids) override {
        for (int i = 0; i < n; ++i) {
            if (ids[i] <= 0) {
                continue;
            }
            if (ids[i] == fCurrRenderbuffer) {
                fCurrRenderbuffer = 0;
            }
            Renderbuffer* renderbuffer = fRenderbufferManager.lookUp(ids[i]);

            if (fCurrDrawFramebuffer) {
                Framebuffer* drawFramebuffer = fFramebufferManager.lookUp(fCurrDrawFramebuffer);
                drawFramebuffer->notifyAttachmentDeleteWhileBound(renderbuffer);
            }
            if (fCurrReadFramebuffer) {
                Framebuffer* readFramebuffer = fFramebufferManager.lookUp(fCurrReadFramebuffer);
                readFramebuffer->notifyAttachmentDeleteWhileBound(renderbuffer);
            }

            fRenderbufferManager.free(renderbuffer);
        }
    }

    GrGLvoid renderbufferStorage(GrGLenum target, GrGLenum internalformat, GrGLsizei width,
                                 GrGLsizei height) override {
        GrAlwaysAssert(GR_GL_RENDERBUFFER == target);
        GrAlwaysAssert(fCurrRenderbuffer);
        Renderbuffer* renderbuffer = fRenderbufferManager.lookUp(fCurrRenderbuffer);
        renderbuffer->setNumSamples(1);
    }

    GrGLvoid renderbufferStorageMultisample(GrGLenum target, GrGLsizei samples,
                                            GrGLenum internalformat, GrGLsizei width,
                                            GrGLsizei height) override {
        GrAlwaysAssert(GR_GL_RENDERBUFFER == target);
        GrAlwaysAssert(samples > 0);
        GrAlwaysAssert(fCurrRenderbuffer);
        Renderbuffer* renderbuffer = fRenderbufferManager.lookUp(fCurrRenderbuffer);
        renderbuffer->setNumSamples(samples);
    }

    GrGLvoid namedRenderbufferStorage(GrGLuint renderbuffer, GrGLenum GrGLinternalformat,
                                      GrGLsizei width, GrGLsizei height) override {
        SK_ABORT("Not implemented");
    }

    GrGLvoid namedRenderbufferStorageMultisample(GrGLuint renderbuffer, GrGLsizei samples,
                                                 GrGLenum GrGLinternalformat, GrGLsizei width,
                                                 GrGLsizei height) override {
        SK_ABORT("Not implemented");
    }

    GrGLvoid framebufferRenderbuffer(GrGLenum target, GrGLenum attachment,
                                     GrGLenum renderbuffertarget,
                                     GrGLuint renderBufferID) override {
        GrGLuint id = this->getBoundFramebufferID(target);
        GrAlwaysAssert(id);
        Framebuffer* framebuffer = fFramebufferManager.lookUp(id);

        GrAlwaysAssert(GR_GL_RENDERBUFFER == renderbuffertarget);
        if (!renderBufferID && !fCurrRenderbuffer) {
           return;
        }
        GrAlwaysAssert(fCurrRenderbuffer);
        Renderbuffer* renderbuffer = fRenderbufferManager.lookUp(fCurrRenderbuffer);

        framebuffer->setAttachment(attachment, renderbuffer);
    }

    GrGLvoid namedFramebufferRenderbuffer(GrGLuint framebuffer, GrGLenum attachment,
                                          GrGLenum renderbuffertarget,
                                          GrGLuint renderbuffer) override {
        SK_ABORT("Not implemented");
    }

    GrGLvoid genSamplers(GrGLsizei n, GrGLuint* samplers) override {
        this->genGenericIds(n, samplers);
    }

    GrGLvoid genTextures(GrGLsizei n, GrGLuint *textures) override {
        this->genGenericIds(n, textures);
    }

    GrGLvoid framebufferTexture2D(GrGLenum target, GrGLenum attachment, GrGLenum textarget,
                                  GrGLuint textureID, GrGLint level) override {
        GrGLuint id = this->getBoundFramebufferID(target);
        GrAlwaysAssert(id);
        Framebuffer* framebuffer = fFramebufferManager.lookUp(id);
        framebuffer->setAttachment(attachment, this->getSingleTextureObject());
    }

    GrGLvoid framebufferTexture2DMultisample(GrGLenum target, GrGLenum attachment,
                                             GrGLenum textarget, GrGLuint texture, GrGLint level,
                                             GrGLsizei samples) override {
        SK_ABORT("Not implemented");
    }

    GrGLvoid namedFramebufferTexture1D(GrGLuint framebuffer, GrGLenum attachment,
                                       GrGLenum textarget, GrGLuint texture,
                                       GrGLint level) override {
        SK_ABORT("Not implemented");
    }

    GrGLvoid namedFramebufferTexture2D(GrGLuint framebuffer, GrGLenum attachment,
                                       GrGLenum textarget, GrGLuint texture,
                                       GrGLint level) override {
        SK_ABORT("Not implemented");
    }

    GrGLvoid namedFramebufferTexture3D(GrGLuint framebuffer, GrGLenum attachment,
                                       GrGLenum textarget, GrGLuint texture, GrGLint level,
                                       GrGLint zoffset) override {
        SK_ABORT("Not implemented");
    }

    GrGLvoid genVertexArrays(GrGLsizei n, GrGLuint *arrays) override {
        this->genGenericIds(n, arrays);
    }

    GrGLenum getError() override { return GR_GL_NO_ERROR; }

    GrGLvoid getIntegerv(GrGLenum pname, GrGLint* params) override {
        // TODO: remove from Ganesh the #defines for gets we don't use.
        // We would like to minimize gets overall due to performance issues
        switch (pname) {
            case GR_GL_CONTEXT_PROFILE_MASK:
                *params = GR_GL_CONTEXT_COMPATIBILITY_PROFILE_BIT;
                break;
            case GR_GL_STENCIL_BITS:
                *params = 8;
                break;
            case GR_GL_SAMPLES: {
                GrAlwaysAssert(fCurrDrawFramebuffer);
                Framebuffer* framebuffer = fFramebufferManager.lookUp(fCurrDrawFramebuffer);
                *params = framebuffer->numSamples();
                break;
            }
            case GR_GL_FRAMEBUFFER_BINDING:
                *params = 0;
                break;
            case GR_GL_VIEWPORT:
                params[0] = 0;
                params[1] = 0;
                params[2] = 800;
                params[3] = 600;
                break;
            case GR_GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS:
            case GR_GL_MAX_GEOMETRY_TEXTURE_IMAGE_UNITS:
            case GR_GL_MAX_TEXTURE_IMAGE_UNITS:
            case GR_GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS:
                *params = 8;
                break;
            case GR_GL_MAX_TEXTURE_COORDS:
                *params = 8;
                break;
            case GR_GL_MAX_VERTEX_UNIFORM_VECTORS:
                *params = kDefaultMaxVertexUniformVectors;
                break;
            case GR_GL_MAX_FRAGMENT_UNIFORM_VECTORS:
                *params = kDefaultMaxFragmentUniformVectors;
                break;
            case GR_GL_MAX_FRAGMENT_UNIFORM_COMPONENTS:
                *params = 16 * 4;
                break;
            case GR_GL_NUM_COMPRESSED_TEXTURE_FORMATS:
                *params = 0;
                break;
            case GR_GL_COMPRESSED_TEXTURE_FORMATS:
                break;
            case GR_GL_MAX_TEXTURE_SIZE:
                *params = 8192;
                break;
            case GR_GL_MAX_RENDERBUFFER_SIZE:
                *params = 8192;
                break;
            case GR_GL_MAX_SAMPLES:
                *params = 32;
                break;
            case GR_GL_MAX_VERTEX_ATTRIBS:
                *params = kDefaultMaxVertexAttribs;
                break;
            case GR_GL_MAX_VARYING_VECTORS:
                *params = kDefaultMaxVaryingVectors;
                break;
            case GR_GL_NUM_EXTENSIONS: {
                GrGLint i = 0;
                while (fAdvertisedExtensions[i++]);
                *params = i;
                break;
            }
            default:
                SK_ABORT("Unexpected pname to GetIntegerv");
        }
    }

    GrGLvoid getProgramiv(GrGLuint program, GrGLenum pname, GrGLint* params) override {
        this->getShaderOrProgramiv(program, pname, params);
    }

    GrGLvoid getProgramInfoLog(GrGLuint program, GrGLsizei bufsize, GrGLsizei* length,
                               char* infolog) override {
        this->getInfoLog(program, bufsize, length, infolog);
    }

    GrGLvoid getMultisamplefv(GrGLenum pname, GrGLuint index, GrGLfloat* val) override {
        val[0] = val[1] = 0.5f;
    }

    GrGLvoid getQueryiv(GrGLenum GLtarget, GrGLenum pname, GrGLint *params) override {
        switch (pname) {
            case GR_GL_CURRENT_QUERY:
                *params = 0;
                break;
            case GR_GL_QUERY_COUNTER_BITS:
                *params = 32;
                break;
            default:
                SK_ABORT("Unexpected pname passed GetQueryiv.");
        }
    }

    GrGLvoid getQueryObjecti64v(GrGLuint id, GrGLenum pname, GrGLint64 *params) override {
        this->queryResult(id, pname, params);
    }

    GrGLvoid getQueryObjectiv(GrGLuint id, GrGLenum pname, GrGLint *params) override {
        this->queryResult(id, pname, params);
    }

    GrGLvoid getQueryObjectui64v(GrGLuint id, GrGLenum pname, GrGLuint64 *params) override {
        this->queryResult(id, pname, params);
    }

    GrGLvoid getQueryObjectuiv(GrGLuint id, GrGLenum pname, GrGLuint *params) override {
        this->queryResult(id, pname, params);
    }

    GrGLvoid getShaderiv(GrGLuint shader, GrGLenum pname, GrGLint* params) override {
        this->getShaderOrProgramiv(shader, pname, params);
    }

    GrGLvoid getShaderInfoLog(GrGLuint shader, GrGLsizei bufsize, GrGLsizei* length,
                              char* infolog) override {
        this->getInfoLog(shader, bufsize, length, infolog);
    }

    const GrGLubyte* getString(GrGLenum name) override {
        switch (name) {
            case GR_GL_EXTENSIONS:
                return CombinedExtensionString();
            case GR_GL_VERSION:
                return (const GrGLubyte*)"4.0 Null GL";
            case GR_GL_SHADING_LANGUAGE_VERSION:
                return (const GrGLubyte*)"4.20.8 Null GLSL";
            case GR_GL_VENDOR:
                return (const GrGLubyte*)"Null Vendor";
            case GR_GL_RENDERER:
                return (const GrGLubyte*)"The Null (Non-)Renderer";
            default:
                SK_ABORT("Unexpected name passed to GetString");
                return nullptr;
        }
    }

    const GrGLubyte* getStringi(GrGLenum name, GrGLuint i) override {
        switch (name) {
            case GR_GL_EXTENSIONS: {
                GrGLint count;
                this->getIntegerv(GR_GL_NUM_EXTENSIONS, &count);
                if ((GrGLint)i <= count) {
                    return (const GrGLubyte*) fAdvertisedExtensions[i];
                } else {
                    return nullptr;
                }
            }
            default:
                SK_ABORT("Unexpected name passed to GetStringi");
                return nullptr;
        }
    }

    GrGLint getUniformLocation(GrGLuint program, const char* name) override {
        return ++fCurrUniformLocation;
    }

    GrGLvoid* mapBufferRange(GrGLenum target, GrGLintptr offset, GrGLsizeiptr length,
                             GrGLbitfield access) override {
        GrGLuint id = fBoundBuffers[GetBufferIndex(target)];
        if (id > 0) {
            // We just ignore the offset and length here.
            Buffer* buffer = fBufferManager.lookUp(id);
            SkASSERT(!buffer->mapped());
            buffer->setMapped(true);
            return buffer->dataPtr();
        }
        return nullptr;
    }

    GrGLvoid* mapBuffer(GrGLenum target, GrGLenum access) override {
        GrGLuint id = fBoundBuffers[GetBufferIndex(target)];
        if (id > 0) {
            Buffer* buffer = fBufferManager.lookUp(id);
            SkASSERT(!buffer->mapped());
            buffer->setMapped(true);
            return buffer->dataPtr();
        }

        SkASSERT(false);
        return nullptr;            // no buffer bound to target
    }

    GrGLboolean unmapBuffer(GrGLenum target) override {
        GrGLuint id = fBoundBuffers[GetBufferIndex(target)];
        if (id > 0) {
            Buffer* buffer = fBufferManager.lookUp(id);
            SkASSERT(buffer->mapped());
            buffer->setMapped(false);
            return GR_GL_TRUE;
        }

        GrAlwaysAssert(false);
        return GR_GL_FALSE; // GR_GL_INVALID_OPERATION;
    }

    GrGLvoid getBufferParameteriv(GrGLenum target, GrGLenum pname, GrGLint* params) override {
        switch (pname) {
            case GR_GL_BUFFER_MAPPED: {
                *params = GR_GL_FALSE;
                GrGLuint id = fBoundBuffers[GetBufferIndex(target)];
                if (id > 0) {
                    Buffer* buffer = fBufferManager.lookUp(id);
                    if (buffer->mapped()) {
                        *params = GR_GL_TRUE;
                    }
                }
                break; }
            default:
                SK_ABORT("Unexpected pname to GetBufferParamateriv");
                break;
        }
    }

    // NV_path_rendering
    GrGLuint genPaths(GrGLsizei range) override {
        return ++fCurrPathID;
    }


private:
    inline int static GetBufferIndex(GrGLenum glTarget) {
        switch (glTarget) {
            default:                           SK_ABORT("Unexpected GL target to GetBufferIndex");
            case GR_GL_ARRAY_BUFFER:           return 0;
            case GR_GL_ELEMENT_ARRAY_BUFFER:   return 1;
            case GR_GL_TEXTURE_BUFFER:         return 2;
            case GR_GL_DRAW_INDIRECT_BUFFER:   return 3;
            case GR_GL_PIXEL_PACK_BUFFER:      return 4;
            case GR_GL_PIXEL_UNPACK_BUFFER:    return 5;
        }
    }
    constexpr int static kNumBufferTargets = 6;

    TGLObjectManager<Buffer>         fBufferManager;
    GrGLuint                         fBoundBuffers[kNumBufferTargets];
    TGLObjectManager<Framebuffer>    fFramebufferManager;
    GrGLuint                         fCurrDrawFramebuffer;
    GrGLuint                         fCurrReadFramebuffer;
    TGLObjectManager<Renderbuffer>   fRenderbufferManager;
    GrGLuint                         fCurrRenderbuffer;
    GrGLuint                         fCurrProgramID;
    GrGLuint                         fCurrShaderID;
    GrGLuint                         fCurrGenericID;
    GrGLuint                         fCurrUniformLocation;
    GrGLuint                         fCurrPathID;
    sk_sp<const Texture>             fSingleTextureObject;
    SkTArray<const char*>            fAdvertisedExtensions;

    // the OpenGLES 2.0 spec says this must be >= 128
    static const GrGLint kDefaultMaxVertexUniformVectors = 128;

    // the OpenGLES 2.0 spec says this must be >=16
    static const GrGLint kDefaultMaxFragmentUniformVectors = 16;

    // the OpenGLES 2.0 spec says this must be >= 8
    static const GrGLint kDefaultMaxVertexAttribs = 8;

    // the OpenGLES 2.0 spec says this must be >= 8
    static const GrGLint kDefaultMaxVaryingVectors = 8;

    GrGLuint getBoundFramebufferID(GrGLenum target) {
        switch (target) {
            case GR_GL_FRAMEBUFFER:
            case GR_GL_DRAW_FRAMEBUFFER:
                return fCurrDrawFramebuffer;
            case GR_GL_READ_FRAMEBUFFER:
                return fCurrReadFramebuffer;
            default:
                SK_ABORT("Invalid framebuffer target.");
                return 0;
        }
    }

    const Texture* getSingleTextureObject() {
        // We currently only use FramebufferAttachment objects for a sample count, and all textures
        // in Skia have one sample, so there is no need as of yet to track individual textures. This
        // also works around a bug in chromium's cc_unittests where they send us texture IDs that
        // were generated by cc::TestGLES2Interface.
        if (!fSingleTextureObject) {
            fSingleTextureObject.reset(new Texture);
        }
        return fSingleTextureObject.get();
    }

    const GrGLubyte* CombinedExtensionString() {
        static SkString gExtString;
        static SkMutex gMutex;
        gMutex.acquire();
        if (0 == gExtString.size()) {
            int i = 0;
            while (fAdvertisedExtensions[i]) {
                if (i > 0) {
                    gExtString.append(" ");
                }
                gExtString.append(fAdvertisedExtensions[i]);
                ++i;
            }
        }
        gMutex.release();
        return (const GrGLubyte*) gExtString.c_str();
    }

    GrGLvoid genGenericIds(GrGLsizei n, GrGLuint* ids) {
        for (int i = 0; i < n; ++i) {
            ids[i] = ++fCurrGenericID;
        }
    }

    GrGLvoid getInfoLog(GrGLuint object, GrGLsizei bufsize, GrGLsizei* length,
                        char* infolog) {
        if (length) {
            *length = 0;
        }
        if (bufsize > 0) {
            *infolog = 0;
        }
    }

    GrGLvoid getShaderOrProgramiv(GrGLuint object,  GrGLenum pname, GrGLint* params) {
        switch (pname) {
            case GR_GL_LINK_STATUS:  // fallthru
            case GR_GL_COMPILE_STATUS:
                *params = GR_GL_TRUE;
                break;
            case GR_GL_INFO_LOG_LENGTH: // fallthru
            case GL_PROGRAM_BINARY_LENGTH:
                *params = 0;
                break;
                // we don't expect any other pnames
            default:
                SK_ABORT("Unexpected pname to GetProgramiv");
                break;
        }
    }

    template <typename T>
    void queryResult(GrGLenum GLtarget, GrGLenum pname, T *params) {
        switch (pname) {
            case GR_GL_QUERY_RESULT_AVAILABLE:
                *params = GR_GL_TRUE;
                break;
            case GR_GL_QUERY_RESULT:
                *params = 0;
                break;
            default:
                SK_ABORT("Unexpected pname passed to GetQueryObject.");
                break;
        }
    }

    typedef TestInterface INHERITED;
};

}  // anonymous namespace

namespace android {
namespace uirenderer {
namespace debug {

const GrGLInterface* CreateNullSkiaInterface() { return new NullInterface(false); }

}  // namespace debug
}  // namespace uirenderer
}  // namespace android
