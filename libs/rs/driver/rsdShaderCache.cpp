/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <rs_hal.h>
#include <rsContext.h>

#include "rsdShader.h"
#include "rsdShaderCache.h"
#include "rsdGL.h"

#include <GLES/gl.h>
#include <GLES2/gl2.h>

using namespace android;
using namespace android::renderscript;


RsdShaderCache::RsdShaderCache() {
    mEntries.setCapacity(16);
    mVertexDirty = true;
    mFragmentDirty = true;
}

RsdShaderCache::~RsdShaderCache() {
    cleanupAll();
}

void RsdShaderCache::updateUniformArrayData(const Context *rsc, RsdShader *prog, uint32_t linkedID,
                                         UniformData *data, const char* logTag,
                                         UniformQueryData **uniformList, uint32_t uniListSize) {

    for (uint32_t ct=0; ct < prog->getUniformCount(); ct++) {
        if (data[ct].slot >= 0 && data[ct].arraySize > 1) {
            //Iterate over the list of active GL uniforms and find highest array index
            for (uint32_t ui = 0; ui < uniListSize; ui ++) {
                if (prog->getUniformName(ct) == uniformList[ui]->name) {
                    data[ct].arraySize = (uint32_t)uniformList[ui]->arraySize;
                    break;
                }
            }
        }

        if (rsc->props.mLogShaders) {
             ALOGV("%s U, %s = %d, arraySize = %d\n", logTag,
                  prog->getUniformName(ct).string(), data[ct].slot, data[ct].arraySize);
        }
    }
}

void RsdShaderCache::populateUniformData(RsdShader *prog, uint32_t linkedID, UniformData *data) {
    for (uint32_t ct=0; ct < prog->getUniformCount(); ct++) {
       data[ct].slot = glGetUniformLocation(linkedID, prog->getUniformName(ct));
       data[ct].arraySize = prog->getUniformArraySize(ct);
    }
}

bool RsdShaderCache::hasArrayUniforms(RsdShader *vtx, RsdShader *frag) {
    UniformData *data = mCurrent->vtxUniforms;
    for (uint32_t ct=0; ct < vtx->getUniformCount(); ct++) {
        if (data[ct].slot >= 0 && data[ct].arraySize > 1) {
            return true;
        }
    }
    data = mCurrent->fragUniforms;
    for (uint32_t ct=0; ct < frag->getUniformCount(); ct++) {
        if (data[ct].slot >= 0 && data[ct].arraySize > 1) {
            return true;
        }
    }
    return false;
}

bool RsdShaderCache::setup(const Context *rsc) {
    if (!mVertexDirty && !mFragmentDirty) {
        return true;
    }

    if (!link(rsc)) {
        return false;
    }

    if (mFragmentDirty) {
        mFragment->setup(rsc, this);
        mFragmentDirty = false;
    }
    if (mVertexDirty) {
        mVertex->setup(rsc, this);
        mVertexDirty = false;
    }

    return true;
}

bool RsdShaderCache::link(const Context *rsc) {

    RsdShader *vtx = mVertex;
    RsdShader *frag = mFragment;
    if (!vtx->getShaderID()) {
        vtx->loadShader(rsc);
    }
    if (!frag->getShaderID()) {
        frag->loadShader(rsc);
    }

    // Don't try to cache if shaders failed to load
    if (!vtx->getShaderID() || !frag->getShaderID()) {
        return false;
    }
    //ALOGV("rsdShaderCache lookup  vtx %i, frag %i", vtx->getShaderID(), frag->getShaderID());
    uint32_t entryCount = mEntries.size();
    for (uint32_t ct = 0; ct < entryCount; ct ++) {
        if ((mEntries[ct]->vtx == vtx->getShaderID()) &&
            (mEntries[ct]->frag == frag->getShaderID())) {

            //ALOGV("SC using program %i", mEntries[ct]->program);
            glUseProgram(mEntries[ct]->program);
            mCurrent = mEntries[ct];
            //ALOGV("RsdShaderCache hit, using %i", ct);
            rsdGLCheckError(rsc, "RsdShaderCache::link (hit)");
            return true;
        }
    }

    //ALOGV("RsdShaderCache miss");
    //ALOGE("e0 %x", glGetError());
    ProgramEntry *e = new ProgramEntry(vtx->getAttribCount(),
                                       vtx->getUniformCount(),
                                       frag->getUniformCount());
    mEntries.push(e);
    mCurrent = e;
    e->vtx = vtx->getShaderID();
    e->frag = frag->getShaderID();
    e->program = glCreateProgram();
    if (e->program) {
        GLuint pgm = e->program;
        glAttachShader(pgm, vtx->getShaderID());
        //ALOGE("e1 %x", glGetError());
        glAttachShader(pgm, frag->getShaderID());

        glBindAttribLocation(pgm, 0, "ATTRIB_position");
        glBindAttribLocation(pgm, 1, "ATTRIB_color");
        glBindAttribLocation(pgm, 2, "ATTRIB_normal");
        glBindAttribLocation(pgm, 3, "ATTRIB_texture0");

        //ALOGE("e2 %x", glGetError());
        glLinkProgram(pgm);
        //ALOGE("e3 %x", glGetError());
        GLint linkStatus = GL_FALSE;
        glGetProgramiv(pgm, GL_LINK_STATUS, &linkStatus);
        if (linkStatus != GL_TRUE) {
            GLint bufLength = 0;
            glGetProgramiv(pgm, GL_INFO_LOG_LENGTH, &bufLength);
            if (bufLength) {
                char* buf = (char*) malloc(bufLength);
                if (buf) {
                    glGetProgramInfoLog(pgm, bufLength, NULL, buf);
                    ALOGE("Could not link program:\n%s\n", buf);
                    free(buf);
                }
            }
            glDeleteProgram(pgm);
            rsc->setError(RS_ERROR_FATAL_PROGRAM_LINK, "Error linking GL Programs");
            return false;
        }

        for (uint32_t ct=0; ct < e->vtxAttrCount; ct++) {
            e->vtxAttrs[ct].slot = glGetAttribLocation(pgm, vtx->getAttribName(ct));
            e->vtxAttrs[ct].name = vtx->getAttribName(ct).string();
            if (rsc->props.mLogShaders) {
                ALOGV("vtx A %i, %s = %d\n", ct, vtx->getAttribName(ct).string(), e->vtxAttrs[ct].slot);
            }
        }

        populateUniformData(vtx, pgm, e->vtxUniforms);
        populateUniformData(frag, pgm, e->fragUniforms);

        // Only populate this list if we have arrays in our uniforms
        UniformQueryData **uniformList = NULL;
        GLint numUniforms = 0;
        bool hasArrays = hasArrayUniforms(vtx, frag);
        if (hasArrays) {
            // Get the number of active uniforms and the length of the longest name
            glGetProgramiv(pgm, GL_ACTIVE_UNIFORMS, &numUniforms);
            GLint maxNameLength = 0;
            glGetProgramiv(pgm, GL_ACTIVE_UNIFORM_MAX_LENGTH, &maxNameLength);
            if (numUniforms > 0 && maxNameLength > 0) {
                uniformList = new UniformQueryData*[numUniforms];
                // Iterate over all the uniforms and build the list we
                // can later use to match our uniforms to
                for (uint32_t ct = 0; ct < (uint32_t)numUniforms; ct++) {
                    uniformList[ct] = new UniformQueryData(maxNameLength);
                    glGetActiveUniform(pgm, ct, maxNameLength, &uniformList[ct]->writtenLength,
                                       &uniformList[ct]->arraySize, &uniformList[ct]->type,
                                       uniformList[ct]->name);
                    //ALOGE("GL UNI idx=%u, arraySize=%u, name=%s", ct,
                    //     uniformList[ct]->arraySize, uniformList[ct]->name);
                }
            }
        }

        // We now know the highest index of all of the array uniforms
        // and we need to update our cache to reflect that
        // we may have declared [n], but only m < n elements are used
        updateUniformArrayData(rsc, vtx, pgm, e->vtxUniforms, "vtx",
                               uniformList, (uint32_t)numUniforms);
        updateUniformArrayData(rsc, frag, pgm, e->fragUniforms, "frag",
                               uniformList, (uint32_t)numUniforms);

        // Clean up the uniform data from GL
        if (uniformList != NULL) {
            for (uint32_t ct = 0; ct < (uint32_t)numUniforms; ct++) {
                delete uniformList[ct];
            }
            delete[] uniformList;
            uniformList = NULL;
        }
    }

    //ALOGV("SC made program %i", e->program);
    glUseProgram(e->program);
    rsdGLCheckError(rsc, "RsdShaderCache::link (miss)");

    return true;
}

int32_t RsdShaderCache::vtxAttribSlot(const String8 &attrName) const {
    for (uint32_t ct=0; ct < mCurrent->vtxAttrCount; ct++) {
        if (attrName == mCurrent->vtxAttrs[ct].name) {
            return mCurrent->vtxAttrs[ct].slot;
        }
    }
    return -1;
}

void RsdShaderCache::cleanupVertex(uint32_t id) {
    int32_t numEntries = (int32_t)mEntries.size();
    for (int32_t ct = 0; ct < numEntries; ct ++) {
        if (mEntries[ct]->vtx == id) {
            glDeleteProgram(mEntries[ct]->program);

            delete mEntries[ct];
            mEntries.removeAt(ct);
            numEntries = (int32_t)mEntries.size();
            ct --;
        }
    }
}

void RsdShaderCache::cleanupFragment(uint32_t id) {
    int32_t numEntries = (int32_t)mEntries.size();
    for (int32_t ct = 0; ct < numEntries; ct ++) {
        if (mEntries[ct]->frag == id) {
            glDeleteProgram(mEntries[ct]->program);

            delete mEntries[ct];
            mEntries.removeAt(ct);
            numEntries = (int32_t)mEntries.size();
            ct --;
        }
    }
}

void RsdShaderCache::cleanupAll() {
    for (uint32_t ct=0; ct < mEntries.size(); ct++) {
        glDeleteProgram(mEntries[ct]->program);
        free(mEntries[ct]);
    }
    mEntries.clear();
}

