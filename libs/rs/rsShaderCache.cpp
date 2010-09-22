/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_RS_BUILD_FOR_HOST
#include "rsContext.h"
#include <GLES/gl.h>
#include <GLES2/gl2.h>
#else
#include "rsContextHostStub.h"
#include <OpenGL/gl.h>
#endif //ANDROID_RS_BUILD_FOR_HOST

using namespace android;
using namespace android::renderscript;


ShaderCache::ShaderCache()
{
    mEntryCount = 0;
    mEntryAllocationCount = 16;
    mEntries = (entry_t *)calloc(mEntryAllocationCount, sizeof(entry_t));
}

ShaderCache::~ShaderCache()
{
    for (uint32_t ct=0; ct < mEntryCount; ct++) {
        glDeleteProgram(mEntries[ct].program);
    }

    mEntryCount = 0;
    mEntryAllocationCount = 0;
    free(mEntries);
}

bool ShaderCache::lookup(Context *rsc, ProgramVertex *vtx, ProgramFragment *frag)
{
    if (!vtx->getShaderID()) {
        vtx->loadShader(rsc);
    }
    if (!frag->getShaderID()) {
        frag->loadShader(rsc);
    }
    //LOGV("ShaderCache lookup  vtx %i, frag %i", vtx->getShaderID(), frag->getShaderID());

    for (uint32_t ct=0; ct < mEntryCount; ct++) {
        if ((mEntries[ct].vtx == vtx->getShaderID()) &&
            (mEntries[ct].frag == frag->getShaderID())) {

            //LOGV("SC using program %i", mEntries[ct].program);
            glUseProgram(mEntries[ct].program);
            mCurrent = &mEntries[ct];
            //LOGV("ShaderCache hit, using %i", ct);
            rsc->checkError("ShaderCache::lookup (hit)");
            return true;
        }
    }
    // Not in cache, add it.

    if (mEntryAllocationCount == mEntryCount) {
        // Out of space, make some.
        mEntryAllocationCount *= 2;
        entry_t *e = (entry_t *)calloc(mEntryAllocationCount, sizeof(entry_t));
        if (!e) {
            LOGE("Out of memory for ShaderCache::lookup");
            return false;
        }
        memcpy(e, mEntries, sizeof(entry_t) * mEntryCount);
        free(mEntries);
        mEntries = e;
    }

    //LOGV("ShaderCache miss, using %i", mEntryCount);
    //LOGE("e0 %x", glGetError());

    entry_t *e = &mEntries[mEntryCount];
    mCurrent = e;
    e->vtx = vtx->getShaderID();
    e->frag = frag->getShaderID();
    e->program = glCreateProgram();
    e->mUserVertexProgram = vtx->isUserProgram();
    if (mEntries[mEntryCount].program) {
        GLuint pgm = e->program;
        glAttachShader(pgm, vtx->getShaderID());
        //LOGE("e1 %x", glGetError());
        glAttachShader(pgm, frag->getShaderID());

        if (!vtx->isUserProgram()) {
            glBindAttribLocation(pgm, 0, "ATTRIB_position");
            glBindAttribLocation(pgm, 1, "ATTRIB_color");
            glBindAttribLocation(pgm, 2, "ATTRIB_normal");
            glBindAttribLocation(pgm, 3, "ATTRIB_texture0");
        }

        //LOGE("e2 %x", glGetError());
        glLinkProgram(pgm);
        //LOGE("e3 %x", glGetError());
        GLint linkStatus = GL_FALSE;
        glGetProgramiv(pgm, GL_LINK_STATUS, &linkStatus);
        if (linkStatus != GL_TRUE) {
            GLint bufLength = 0;
            glGetProgramiv(pgm, GL_INFO_LOG_LENGTH, &bufLength);
            if (bufLength) {
                char* buf = (char*) malloc(bufLength);
                if (buf) {
                    glGetProgramInfoLog(pgm, bufLength, NULL, buf);
                    LOGE("Could not link program:\n%s\n", buf);
                    free(buf);
                }
            }
            glDeleteProgram(pgm);
            rsc->setError(RS_ERROR_BAD_SHADER, "Error linking GL Programs");
            return false;
        }
        if (vtx->isUserProgram()) {
            for (uint32_t ct=0; ct < vtx->getAttribCount(); ct++) {
                e->mVtxAttribSlots[ct] = glGetAttribLocation(pgm, vtx->getAttribName(ct));
                if (rsc->props.mLogShaders) {
                    LOGV("vtx A %i, %s = %d\n", ct, vtx->getAttribName(ct).string(), e->mVtxAttribSlots[ct]);
                }
            }
        }
        for (uint32_t ct=0; ct < vtx->getUniformCount(); ct++) {
            e->mVtxUniformSlots[ct] = glGetUniformLocation(pgm, vtx->getUniformName(ct));
            if (rsc->props.mLogShaders) {
                LOGV("vtx U, %s = %d\n", vtx->getUniformName(ct).string(), e->mVtxUniformSlots[ct]);
            }
        }
        for (uint32_t ct=0; ct < frag->getUniformCount(); ct++) {
            e->mFragUniformSlots[ct] = glGetUniformLocation(pgm, frag->getUniformName(ct));
            if (rsc->props.mLogShaders) {
                LOGV("frag U, %s = %d\n", frag->getUniformName(ct).string(), e->mFragUniformSlots[ct]);
            }
        }
    }

    e->mIsValid = true;
    //LOGV("SC made program %i", e->program);
    glUseProgram(e->program);
    mEntryCount++;
    rsc->checkError("ShaderCache::lookup (miss)");
    return true;
}

void ShaderCache::cleanupVertex(uint32_t id)
{
}

void ShaderCache::cleanupFragment(uint32_t id)
{
}

void ShaderCache::cleanupAll()
{
}

