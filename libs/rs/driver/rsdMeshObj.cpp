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

#include <GLES/gl.h>
#include <GLES2/gl2.h>
#include <GLES/glext.h>

#include <rs_hal.h>
#include <rsContext.h>
#include <rsMesh.h>

#include "rsdAllocation.h"
#include "rsdMeshObj.h"
#include "rsdGL.h"

using namespace android;
using namespace android::renderscript;

RsdMeshObj::RsdMeshObj(const Context *rsc, const Mesh *rsMesh) {
    mRSMesh = rsMesh;

    mAttribs = NULL;
    mAttribAllocationIndex = NULL;
    mGLPrimitives = NULL;

    mAttribCount = 0;
}

RsdMeshObj::~RsdMeshObj() {
    if (mAttribs) {
        delete[] mAttribs;
        delete[] mAttribAllocationIndex;
    }
    if (mGLPrimitives) {
        delete[] mGLPrimitives;
    }
}

bool RsdMeshObj::isValidGLComponent(const Element *elem, uint32_t fieldIdx) {
    // Only GL_BYTE, GL_UNSIGNED_BYTE, GL_SHORT, GL_UNSIGNED_SHORT, GL_FIXED, GL_FLOAT are accepted.
    // Filter rs types accordingly
    RsDataType dt = elem->mHal.state.fields[fieldIdx]->mHal.state.dataType;
    if (dt != RS_TYPE_FLOAT_32 && dt != RS_TYPE_UNSIGNED_8 &&
        dt != RS_TYPE_UNSIGNED_16 && dt != RS_TYPE_SIGNED_8 &&
        dt != RS_TYPE_SIGNED_16) {
        return false;
    }

    // Now make sure they are not arrays
    uint32_t arraySize = elem->mHal.state.fieldArraySizes[fieldIdx];
    if (arraySize != 1) {
        return false;
    }

    return true;
}

bool RsdMeshObj::init() {

    updateGLPrimitives();

    // Count the number of gl attrs to initialize
    mAttribCount = 0;
    for (uint32_t ct=0; ct < mRSMesh->mHal.state.vertexBuffersCount; ct++) {
        const Element *elem = mRSMesh->mHal.state.vertexBuffers[ct]->getType()->getElement();
        for (uint32_t ct=0; ct < elem->mHal.state.fieldsCount; ct++) {
            if (isValidGLComponent(elem, ct)) {
                mAttribCount ++;
            }
        }
    }

    if (mAttribs) {
        delete [] mAttribs;
        delete [] mAttribAllocationIndex;
        mAttribs = NULL;
        mAttribAllocationIndex = NULL;
    }
    if (!mAttribCount) {
        return false;
    }

    mAttribs = new RsdVertexArray::Attrib[mAttribCount];
    mAttribAllocationIndex = new uint32_t[mAttribCount];

    uint32_t userNum = 0;
    for (uint32_t ct=0; ct < mRSMesh->mHal.state.vertexBuffersCount; ct++) {
        const Element *elem = mRSMesh->mHal.state.vertexBuffers[ct]->getType()->getElement();
        uint32_t stride = elem->mHal.state.elementSizeBytes;
        for (uint32_t fieldI=0; fieldI < elem->mHal.state.fieldsCount; fieldI++) {
            const Element *f = elem->mHal.state.fields[fieldI];

            if (!isValidGLComponent(elem, fieldI)) {
                continue;
            }

            mAttribs[userNum].size = f->mHal.state.vectorSize;
            mAttribs[userNum].offset = elem->mHal.state.fieldOffsetBytes[fieldI];
            mAttribs[userNum].type = rsdTypeToGLType(f->mHal.state.dataType);
            mAttribs[userNum].normalized = f->mHal.state.dataType != RS_TYPE_FLOAT_32;
            mAttribs[userNum].stride = stride;
            String8 tmp(RS_SHADER_ATTR);
            tmp.append(elem->mHal.state.fieldNames[fieldI]);
            mAttribs[userNum].name.setTo(tmp.string());

            // Remember which allocation this attribute came from
            mAttribAllocationIndex[userNum] = ct;
            userNum ++;
        }
    }

    return true;
}

void RsdMeshObj::renderPrimitiveRange(const Context *rsc, uint32_t primIndex,
                                      uint32_t start, uint32_t len) const {
    if (len < 1 || primIndex >= mRSMesh->mHal.state.primitivesCount || mAttribCount == 0) {
        LOGE("Invalid mesh or parameters");
        return;
    }

    for (uint32_t ct=0; ct < mRSMesh->mHal.state.vertexBuffersCount; ct++) {
        const Allocation *alloc = mRSMesh->mHal.state.vertexBuffers[ct];
        DrvAllocation *drv = (DrvAllocation *)alloc->mHal.drv;
        if (drv->uploadDeferred) {
            rsdAllocationSyncAll(rsc, alloc, RS_ALLOCATION_USAGE_SCRIPT);
        }
    }

    // update attributes with either buffer information or data ptr based on their current state
    for (uint32_t ct=0; ct < mAttribCount; ct++) {
        uint32_t allocIndex = mAttribAllocationIndex[ct];
        Allocation *alloc = mRSMesh->mHal.state.vertexBuffers[allocIndex];
        DrvAllocation *drvAlloc = (DrvAllocation *)alloc->mHal.drv;

        if (drvAlloc->bufferID) {
            mAttribs[ct].buffer = drvAlloc->bufferID;
            mAttribs[ct].ptr = NULL;
        } else {
            mAttribs[ct].buffer = 0;
            mAttribs[ct].ptr = (const uint8_t*)drvAlloc->mallocPtr;
        }
    }

    RsdVertexArray va(mAttribs, mAttribCount);
    va.setup(rsc);

    const Allocation *idxAlloc = mRSMesh->mHal.state.indexBuffers[primIndex];
    if (idxAlloc) {
        DrvAllocation *drvAlloc = (DrvAllocation *)idxAlloc->mHal.drv;
        if (drvAlloc->uploadDeferred) {
            rsdAllocationSyncAll(rsc, idxAlloc, RS_ALLOCATION_USAGE_SCRIPT);
        }

        if (drvAlloc->bufferID) {
            RSD_CALL_GL(glBindBuffer, GL_ELEMENT_ARRAY_BUFFER, drvAlloc->bufferID);
            RSD_CALL_GL(glDrawElements, mGLPrimitives[primIndex], len, GL_UNSIGNED_SHORT,
                        (uint16_t *)(start * 2));
        } else {
            RSD_CALL_GL(glBindBuffer, GL_ELEMENT_ARRAY_BUFFER, 0);
            RSD_CALL_GL(glDrawElements, mGLPrimitives[primIndex], len, GL_UNSIGNED_SHORT,
                        drvAlloc->mallocPtr);
        }
    } else {
        RSD_CALL_GL(glDrawArrays, mGLPrimitives[primIndex], start, len);
    }

    rsdGLCheckError(rsc, "Mesh::renderPrimitiveRange");
}

void RsdMeshObj::updateGLPrimitives() {
    mGLPrimitives = new uint32_t[mRSMesh->mHal.state.primitivesCount];
    for (uint32_t i = 0; i < mRSMesh->mHal.state.primitivesCount; i ++) {
        switch (mRSMesh->mHal.state.primitives[i]) {
            case RS_PRIMITIVE_POINT:          mGLPrimitives[i] = GL_POINTS; break;
            case RS_PRIMITIVE_LINE:           mGLPrimitives[i] = GL_LINES; break;
            case RS_PRIMITIVE_LINE_STRIP:     mGLPrimitives[i] = GL_LINE_STRIP; break;
            case RS_PRIMITIVE_TRIANGLE:       mGLPrimitives[i] = GL_TRIANGLES; break;
            case RS_PRIMITIVE_TRIANGLE_STRIP: mGLPrimitives[i] = GL_TRIANGLE_STRIP; break;
            case RS_PRIMITIVE_TRIANGLE_FAN:   mGLPrimitives[i] = GL_TRIANGLE_FAN; break;
        }
    }
}
