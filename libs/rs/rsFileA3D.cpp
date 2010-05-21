
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
#else
#include "rsContextHostStub.h"
#endif

#include "rsFileA3D.h"

#include "rsMesh.h"
#include "rsAnimation.h"

using namespace android;
using namespace android::renderscript;



FileA3D::FileA3D()
{
    mRsc = NULL;
    mAlloc = NULL;
    mData = NULL;
    mWriteStream = NULL;
    mReadStream = NULL;

    mMajorVersion = 0;
    mMinorVersion = 1;
    mDataSize = 0;
}

FileA3D::~FileA3D()
{
    for(size_t i = 0; i < mIndex.size(); i ++) {
        delete mIndex[i];
    }
    for(size_t i = 0; i < mWriteIndex.size(); i ++) {
        delete mWriteIndex[i];
    }
    if(mWriteStream) {
        delete mWriteStream;
    }
    if(mReadStream) {
        delete mWriteStream;
    }
    if(mAlloc) {
        free(mAlloc);
    }
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

    // Next thing is the size of the header
    uint64_t headerSize = 0;
    len = fread(&headerSize, 1, sizeof(headerSize), f);
    if (len != sizeof(headerSize) || headerSize == 0) {
        return false;
    }

    uint8_t *headerData = (uint8_t *)malloc(headerSize);
    if(!headerData) {
        return false;
    }

    len = fread(headerData, 1, headerSize, f);
    if (len != headerSize) {
        return false;
    }

    // Now open the stream to parse the header
    IStream headerStream(headerData, false);

    mMajorVersion = headerStream.loadU32();
    mMinorVersion = headerStream.loadU32();
    uint32_t flags = headerStream.loadU32();
    mUse64BitOffsets = (flags & 1) != 0;

    LOGE("file open 64bit = %i", mUse64BitOffsets);

    uint32_t numIndexEntries = headerStream.loadU32();
    for(uint32_t i = 0; i < numIndexEntries; i ++) {
        A3DIndexEntry *entry = new A3DIndexEntry();
        headerStream.loadString(&entry->mID);
        entry->mType = (A3DClassID)headerStream.loadU32();
        if(mUse64BitOffsets){
            entry->mOffset = headerStream.loadOffset();
        }
        else {
            entry->mOffset = headerStream.loadU32();
        }
        entry->mRsObj = NULL;
        mIndex.push(entry);
    }

    // Next thing is the size of the header
    len = fread(&mDataSize, 1, sizeof(mDataSize), f);
    if (len != sizeof(mDataSize) || mDataSize == 0) {
        return false;
    }

    LOGE("file open size = %lli", mDataSize);

    // We should know enough to read the file in at this point.
    mAlloc = malloc(mDataSize);
    if (!mAlloc) {
        return false;
    }
    mData = (uint8_t *)mAlloc;
    len = fread(mAlloc, 1, mDataSize, f);
    if (len != mDataSize) {
        return false;
    }

    mReadStream = new IStream(mData, mUse64BitOffsets);

    mRsc = rsc;

    LOGE("Header is read an stream initialized");
    return true;
}

size_t FileA3D::getNumLoadedEntries() const {
    return mIndex.size();
}

const FileA3D::A3DIndexEntry *FileA3D::getLoadedEntry(size_t index) const {
    if(index < mIndex.size()) {
        return mIndex[index];
    }
    return NULL;
}

ObjectBase *FileA3D::initializeFromEntry(const FileA3D::A3DIndexEntry *entry) {
    if(!entry) {
        return NULL;
    }

    // Seek to the beginning of object
    mReadStream->reset(entry->mOffset);
    switch (entry->mType) {
        case A3D_CLASS_ID_UNKNOWN:
            return NULL;
        case A3D_CLASS_ID_MESH:
            return Mesh::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_SIMPLE_MESH:
            return SimpleMesh::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_TYPE:
            return Type::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_ELEMENT:
            return Element::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_ALLOCATION:
            return Allocation::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_PROGRAM_VERTEX:
            return ProgramVertex::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_PROGRAM_RASTER:
            return ProgramRaster::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_PROGRAM_FRAGMENT:
            return ProgramFragment::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_PROGRAM_STORE:
            return ProgramStore::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_SAMPLER:
            return Sampler::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_ANIMATION:
            return Animation::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_LIGHT:
            return Light::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_ADAPTER_1D:
            return Adapter1D::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_ADAPTER_2D:
            return Adapter2D::createFromStream(mRsc, mReadStream);
        case A3D_CLASS_ID_SCRIPT_C:
            return NULL;
    }
    return NULL;
}

bool FileA3D::writeFile(const char *filename)
{
    if(!mWriteStream) {
        LOGE("No objects to write\n");
        return false;
    }
    if(mWriteStream->getPos() == 0) {
        LOGE("No objects to write\n");
        return false;
    }

    FILE *writeHandle = fopen(filename, "wb");
    if(!writeHandle) {
        LOGE("Couldn't open the file for writing\n");
        return false;
    }

    // Open a new stream to make writing the header easier
    OStream headerStream(5*1024, false);
    headerStream.addU32(mMajorVersion);
    headerStream.addU32(mMinorVersion);
    uint32_t is64Bit = 0;
    headerStream.addU32(is64Bit);

    uint32_t writeIndexSize = mWriteIndex.size();
    headerStream.addU32(writeIndexSize);
    for(uint32_t i = 0; i < writeIndexSize; i ++) {
        headerStream.addString(&mWriteIndex[i]->mID);
        headerStream.addU32((uint32_t)mWriteIndex[i]->mType);
        if(mUse64BitOffsets){
            headerStream.addOffset(mWriteIndex[i]->mOffset);
        }
        else {
            uint32_t offset = (uint32_t)mWriteIndex[i]->mOffset;
            headerStream.addU32(offset);
        }
    }

    // Write our magic string so we know we are reading the right file
    String8 magicString(A3D_MAGIC_KEY);
    fwrite(magicString.string(), sizeof(char), magicString.size(), writeHandle);

    // Store the size of the header to make it easier to parse when we read it
    uint64_t headerSize = headerStream.getPos();
    fwrite(&headerSize, sizeof(headerSize), 1, writeHandle);

    // Now write our header
    fwrite(headerStream.getPtr(), sizeof(uint8_t), headerStream.getPos(), writeHandle);

    // Now write the size of the data part of the file for easier parsing later
    uint64_t fileDataSize = mWriteStream->getPos();
    fwrite(&fileDataSize, sizeof(fileDataSize), 1, writeHandle);

    fwrite(mWriteStream->getPtr(), sizeof(uint8_t), mWriteStream->getPos(), writeHandle);

    int status = fclose(writeHandle);

    if(status != 0) {
        LOGE("Couldn't close file\n");
        return false;
    }

    return true;
}

void FileA3D::appendToFile(ObjectBase *obj) {
    if(!obj) {
        return;
    }
    if(!mWriteStream) {
        const uint64_t initialStreamSize = 256*1024;
        mWriteStream = new OStream(initialStreamSize, false);
    }
    A3DIndexEntry *indexEntry = new A3DIndexEntry();
    indexEntry->mID.setTo(obj->getName());
    indexEntry->mType = obj->getClassId();
    indexEntry->mOffset = mWriteStream->getPos();
    indexEntry->mRsObj = (void*)obj;
    mWriteIndex.push(indexEntry);
    obj->serialize(mWriteStream);
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
