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

#include "gl/GrGLTestInterface.h"
#include "GrNonAtomicRef.h"
#include "SkMutex.h"
#include "SkTDArray.h"
#include "SkTo.h"
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

/** Null interface implementation */
class NullInterface : public GrGLTestInterface {
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

    typedef GrGLTestInterface INHERITED;
};

}  // anonymous namespace

namespace android {
namespace uirenderer {
namespace debug {

const GrGLInterface* CreateNullSkiaInterface() { return new NullInterface(false); }

}  // namespace debug
}  // namespace uirenderer
}  // namespace android
