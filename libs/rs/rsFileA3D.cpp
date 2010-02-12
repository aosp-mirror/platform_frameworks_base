
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

#include "rsContext.h"


#include <utils/String8.h>
#include "rsFileA3D.h"

#include "rsMesh.h"

using namespace android;
using namespace android::renderscript;



FileA3D::FileA3D()
{
    mRsc = NULL;
}

FileA3D::~FileA3D()
{
}

bool FileA3D::load(Context *rsc, FILE *f)
{
    char magicString[12];
    size_t len;

    LOGE("file open 1");
    len = fread(magicString, 1, 12, f);
    if ((len != 12) ||
        memcmp(magicString, "Android3D_ff", 12)) {
        return false;
    }

    LOGE("file open 2");
    len = fread(&mMajorVersion, 1, sizeof(mMajorVersion), f);
    if (len != sizeof(mMajorVersion)) {
        return false;
    }

    LOGE("file open 3");
    len = fread(&mMinorVersion, 1, sizeof(mMinorVersion), f);
    if (len != sizeof(mMinorVersion)) {
        return false;
    }

    LOGE("file open 4");
    uint32_t flags;
    len = fread(&flags, 1, sizeof(flags), f);
    if (len != sizeof(flags)) {
        return false;
    }
    mUse64BitOffsets = (flags & 1) != 0;

    LOGE("file open 64bit = %i", mUse64BitOffsets);

    if (mUse64BitOffsets) {
        len = fread(&mDataSize, 1, sizeof(mDataSize), f);
        if (len != sizeof(mDataSize)) {
            return false;
        }
    } else {
        uint32_t tmp;
        len = fread(&tmp, 1, sizeof(tmp), f);
        if (len != sizeof(tmp)) {
            return false;
        }
        mDataSize = tmp;
    }

    LOGE("file open size = %lli", mDataSize);

    // We should know enough to read the file in at this point.
    fseek(f, SEEK_SET, 0);
    mAlloc= malloc(mDataSize);
    if (!mAlloc) {
        return false;
    }
    mData = (uint8_t *)mAlloc;
    len = fread(mAlloc, 1, mDataSize, f);
    if (len != mDataSize) {
        return false;
    }

    LOGE("file start processing");
    return process(rsc);
}

bool FileA3D::processIndex(Context *rsc, A3DIndexEntry *ie)
{
    bool ret = false;
    IO io(mData + ie->mOffset, mUse64BitOffsets);

    LOGE("process index, type %i", ie->mType);

    switch(ie->mType) {
    case CHUNK_ELEMENT:
        processChunk_Element(rsc, &io, ie);
        break;
    case CHUNK_ELEMENT_SOURCE:
        processChunk_ElementSource(rsc, &io, ie);
        break;
    case CHUNK_VERTICIES:
        processChunk_Verticies(rsc, &io, ie);
        break;
    case CHUNK_MESH:
        processChunk_Mesh(rsc, &io, ie);
        break;
    case CHUNK_PRIMITIVE:
        processChunk_Primitive(rsc, &io, ie);
        break;
    default:
        LOGE("FileA3D Unknown chunk type");
        break;
    }
    return (ie->mRsObj != NULL);
}

bool FileA3D::process(Context *rsc)
{
    LOGE("process");
    IO io(mData + 12, mUse64BitOffsets);
    bool ret = true;

    // Build the index first
    LOGE("process 1");
    io.loadU32(); // major version, already loaded
    io.loadU32(); // minor version, already loaded
    LOGE("process 2");

    io.loadU32();  // flags
    io.loadOffset(); // filesize, already loaded.
    LOGE("process 4");
    uint64_t mIndexOffset = io.loadOffset();
    uint64_t mStringOffset = io.loadOffset();

    LOGE("process mIndexOffset= 0x%016llx", mIndexOffset);
    LOGE("process mStringOffset= 0x%016llx", mStringOffset);

    IO index(mData + mIndexOffset, mUse64BitOffsets);
    IO stringTable(mData + mStringOffset, mUse64BitOffsets);

    uint32_t stringEntryCount = stringTable.loadU32();
    LOGE("stringEntryCount %i", stringEntryCount);
    mStrings.setCapacity(stringEntryCount);
    mStringIndexValues.setCapacity(stringEntryCount);
    if (stringEntryCount) {
        uint32_t stringType = stringTable.loadU32();
        LOGE("stringType %i", stringType);
        rsAssert(stringType==0);
        for (uint32_t ct = 0; ct < stringEntryCount; ct++) {
            uint64_t offset = stringTable.loadOffset();
            LOGE("string offset 0x%016llx", offset);
            IO tmp(mData + offset, mUse64BitOffsets);
            String8 s;
            tmp.loadString(&s);
            LOGE("string %s", s.string());
            mStrings.push(s);
        }
    }

    LOGE("strings done");
    uint32_t indexEntryCount = index.loadU32();
    LOGE("index count %i", indexEntryCount);
    mIndex.setCapacity(indexEntryCount);
    for (uint32_t ct = 0; ct < indexEntryCount; ct++) {
        A3DIndexEntry e;
        uint32_t stringIndex = index.loadU32();
        LOGE("index %i", ct);
        LOGE("  string index %i", stringIndex);
        e.mType = (A3DChunkType)index.loadU32();
        LOGE("  type %i", e.mType);
        e.mOffset = index.loadOffset();
        LOGE("  offset 0x%016llx", e.mOffset);

        if (stringIndex && (stringIndex < mStrings.size())) {
            e.mID = mStrings[stringIndex];
            mStringIndexValues.editItemAt(stringIndex) = ct;
            LOGE("  id %s", e.mID.string());
        }

        mIndex.push(e);
    }
    LOGE("index done");

    // At this point the index should be fully populated.
    // We can now walk though it and load all the objects.
    for (uint32_t ct = 0; ct < indexEntryCount; ct++) {
        LOGE("processing index entry %i", ct);
        processIndex(rsc, &mIndex.editItemAt(ct));
    }

    return ret;
}


FileA3D::IO::IO(const uint8_t *buf, bool use64)
{
    mData = buf;
    mPos = 0;
    mUse64 = use64;
}

uint64_t FileA3D::IO::loadOffset()
{
    uint64_t tmp;
    if (mUse64) {
        mPos = (mPos + 7) & (~7);
        tmp = reinterpret_cast<const uint64_t *>(&mData[mPos])[0];
        mPos += sizeof(uint64_t);
        return tmp;
    }
    return loadU32();
}

void FileA3D::IO::loadString(String8 *s)
{
    LOGE("loadString");
    uint32_t len = loadU32();
    LOGE("loadString len %i", len);
    s->setTo((const char *)&mData[mPos], len);
    mPos += len;
}


void FileA3D::processChunk_Mesh(Context *rsc, IO *io, A3DIndexEntry *ie)
{
    Mesh * m = new Mesh(rsc);

    m->mPrimitivesCount = io->loadU32();
    m->mPrimitives = new Mesh::Primitive_t *[m->mPrimitivesCount];

    for (uint32_t ct = 0; ct < m->mPrimitivesCount; ct++) {
        uint32_t index = io->loadU32();

        m->mPrimitives[ct] = (Mesh::Primitive_t *)mIndex[index].mRsObj;
    }
    ie->mRsObj = m;
}

void FileA3D::processChunk_Primitive(Context *rsc, IO *io, A3DIndexEntry *ie)
{
    Mesh::Primitive_t * p = new Mesh::Primitive_t;

    p->mIndexCount = io->loadU32();
    uint32_t vertIdx = io->loadU32();
    p->mRestartCounts = io->loadU16();
    uint32_t bits = io->loadU8();
    p->mType = (RsPrimitive)io->loadU8();

    LOGE("processChunk_Primitive count %i, bits %i", p->mIndexCount, bits);

    p->mVerticies = (Mesh::Verticies_t *)mIndex[vertIdx].mRsObj;

    p->mIndicies = new uint16_t[p->mIndexCount];
    for (uint32_t ct = 0; ct < p->mIndexCount; ct++) {
        switch(bits) {
        case 8:
            p->mIndicies[ct] = io->loadU8();
            break;
        case 16:
            p->mIndicies[ct] = io->loadU16();
            break;
        case 32:
            p->mIndicies[ct] = io->loadU32();
            break;
        }
        LOGE("  idx %i", p->mIndicies[ct]);
    }

    if (p->mRestartCounts) {
        p->mRestarts = new uint16_t[p->mRestartCounts];
        for (uint32_t ct = 0; ct < p->mRestartCounts; ct++) {
            switch(bits) {
            case 8:
                p->mRestarts[ct] = io->loadU8();
                break;
            case 16:
                p->mRestarts[ct] = io->loadU16();
                break;
            case 32:
                p->mRestarts[ct] = io->loadU32();
                break;
            }
            LOGE("  idx %i", p->mRestarts[ct]);
        }
    } else {
        p->mRestarts = NULL;
    }

    ie->mRsObj = p;
}

void FileA3D::processChunk_Verticies(Context *rsc, IO *io, A3DIndexEntry *ie)
{
    Mesh::Verticies_t *cv = new Mesh::Verticies_t;
    cv->mAllocationCount = io->loadU32();
    cv->mAllocations = new Allocation *[cv->mAllocationCount];
    LOGE("processChunk_Verticies count %i", cv->mAllocationCount);
    for (uint32_t ct = 0; ct < cv->mAllocationCount; ct++) {
        uint32_t i = io->loadU32();
        cv->mAllocations[ct] = (Allocation *)mIndex[i].mRsObj;
        LOGE("  idx %i", i);
    }
    ie->mRsObj = cv;
}

void FileA3D::processChunk_Element(Context *rsc, IO *io, A3DIndexEntry *ie)
{
    /*
    rsi_ElementBegin(rsc);

    uint32_t count = io->loadU32();
    LOGE("processChunk_Element count %i", count);
    while (count--) {
        RsDataKind dk = (RsDataKind)io->loadU8();
        RsDataType dt = (RsDataType)io->loadU8();
        uint32_t bits = io->loadU8();
        bool isNorm = io->loadU8() != 0;
        LOGE("  %i %i %i %i", dk, dt, bits, isNorm);
        rsi_ElementAdd(rsc, dk, dt, isNorm, bits, 0);
    }
    LOGE("processChunk_Element create");
    ie->mRsObj = rsi_ElementCreate(rsc);
    */
}

void FileA3D::processChunk_ElementSource(Context *rsc, IO *io, A3DIndexEntry *ie)
{
    uint32_t index = io->loadU32();
    uint32_t count = io->loadU32();

    LOGE("processChunk_ElementSource count %i, index %i", count, index);

    RsElement e = (RsElement)mIndex[index].mRsObj;

    RsAllocation a = rsi_AllocationCreateSized(rsc, e, count);
    Allocation * alloc = static_cast<Allocation *>(a);

    float * data = (float *)alloc->getPtr();
    while(count--) {
        *data = io->loadF();
        LOGE("  %f", *data);
        data++;
    }
    ie->mRsObj = alloc;
}

namespace android {
namespace renderscript {


RsFile rsi_FileOpen(Context *rsc, char const *path, unsigned int len)
{
    FileA3D *fa3d = new FileA3D;

    FILE *f = fopen("/sdcard/test.a3d", "rb");
    if (f) {
        fa3d->load(rsc, f);
        fclose(f);
        return fa3d;
    }
    delete fa3d;
    return NULL;
}


}
}
