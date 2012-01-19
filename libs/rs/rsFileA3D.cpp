
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
#include "rsFileA3D.h"

#include "rsMesh.h"
#include "rsAnimation.h"


using namespace android;
using namespace android::renderscript;

FileA3D::FileA3D(Context *rsc) : ObjectBase(rsc) {
    mAlloc = NULL;
    mData = NULL;
    mWriteStream = NULL;
    mReadStream = NULL;
    mAsset = NULL;

    mMajorVersion = 0;
    mMinorVersion = 1;
    mDataSize = 0;
}

FileA3D::~FileA3D() {
    for (size_t i = 0; i < mIndex.size(); i ++) {
        delete mIndex[i];
    }
    for (size_t i = 0; i < mWriteIndex.size(); i ++) {
        delete mWriteIndex[i];
    }
    if (mWriteStream) {
        delete mWriteStream;
    }
    if (mReadStream) {
        delete mWriteStream;
    }
    if (mAlloc) {
        free(mAlloc);
    }
    if (mAsset) {
        delete mAsset;
    }
}

void FileA3D::parseHeader(IStream *headerStream) {
    mMajorVersion = headerStream->loadU32();
    mMinorVersion = headerStream->loadU32();
    uint32_t flags = headerStream->loadU32();
    mUse64BitOffsets = (flags & 1) != 0;

    uint32_t numIndexEntries = headerStream->loadU32();
    for (uint32_t i = 0; i < numIndexEntries; i ++) {
        A3DIndexEntry *entry = new A3DIndexEntry();
        headerStream->loadString(&entry->mObjectName);
        //ALOGV("Header data, entry name = %s", entry->mObjectName.string());
        entry->mType = (RsA3DClassID)headerStream->loadU32();
        if (mUse64BitOffsets){
            entry->mOffset = headerStream->loadOffset();
            entry->mLength = headerStream->loadOffset();
        } else {
            entry->mOffset = headerStream->loadU32();
            entry->mLength = headerStream->loadU32();
        }
        entry->mRsObj = NULL;
        mIndex.push(entry);
    }
}

bool FileA3D::load(Asset *asset) {
    mAsset = asset;
    return load(asset->getBuffer(false), asset->getLength());
}

bool FileA3D::load(const void *data, size_t length) {
    const uint8_t *localData = (const uint8_t *)data;

    size_t lengthRemaining = length;
    size_t magicStrLen = 12;
    if ((length < magicStrLen) ||
        memcmp(data, "Android3D_ff", magicStrLen)) {
        return false;
    }

    localData += magicStrLen;
    lengthRemaining -= magicStrLen;

    // Next we get our header size
    uint64_t headerSize = 0;
    if (lengthRemaining < sizeof(headerSize)) {
        return false;
    }

    memcpy(&headerSize, localData, sizeof(headerSize));
    localData += sizeof(headerSize);
    lengthRemaining -= sizeof(headerSize);

    if (lengthRemaining < headerSize) {
        return false;
    }

    // Now open the stream to parse the header
    IStream headerStream(localData, false);
    parseHeader(&headerStream);

    localData += headerSize;
    lengthRemaining -= headerSize;

    if (lengthRemaining < sizeof(mDataSize)) {
        return false;
    }

    // Read the size of the data
    memcpy(&mDataSize, localData, sizeof(mDataSize));
    localData += sizeof(mDataSize);
    lengthRemaining -= sizeof(mDataSize);

    if (lengthRemaining < mDataSize) {
        return false;
    }

    // We should know enough to read the file in at this point.
    mData = (uint8_t *)localData;
    mReadStream = new IStream(mData, mUse64BitOffsets);

    return true;
}

bool FileA3D::load(FILE *f) {
    char magicString[12];
    size_t len;

    ALOGV("file open 1");
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
    if (!headerData) {
        return false;
    }

    len = fread(headerData, 1, headerSize, f);
    if (len != headerSize) {
        return false;
    }

    // Now open the stream to parse the header
    IStream headerStream(headerData, false);
    parseHeader(&headerStream);

    free(headerData);

    // Next thing is the size of the header
    len = fread(&mDataSize, 1, sizeof(mDataSize), f);
    if (len != sizeof(mDataSize) || mDataSize == 0) {
        return false;
    }

    ALOGV("file open size = %lli", mDataSize);

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

    ALOGV("Header is read an stream initialized");
    return true;
}

size_t FileA3D::getNumIndexEntries() const {
    return mIndex.size();
}

const FileA3D::A3DIndexEntry *FileA3D::getIndexEntry(size_t index) const {
    if (index < mIndex.size()) {
        return mIndex[index];
    }
    return NULL;
}

ObjectBase *FileA3D::initializeFromEntry(size_t index) {
    if (index >= mIndex.size()) {
        return NULL;
    }

    FileA3D::A3DIndexEntry *entry = mIndex[index];
    if (!entry) {
        return NULL;
    }

    if (entry->mRsObj) {
        entry->mRsObj->incUserRef();
        return entry->mRsObj;
    }

    // Seek to the beginning of object
    mReadStream->reset(entry->mOffset);
    switch (entry->mType) {
        case RS_A3D_CLASS_ID_UNKNOWN:
            return NULL;
        case RS_A3D_CLASS_ID_MESH:
            entry->mRsObj = Mesh::createFromStream(mRSC, mReadStream);
            break;
        case RS_A3D_CLASS_ID_TYPE:
            entry->mRsObj = Type::createFromStream(mRSC, mReadStream);
            break;
        case RS_A3D_CLASS_ID_ELEMENT:
            entry->mRsObj = Element::createFromStream(mRSC, mReadStream);
            break;
        case RS_A3D_CLASS_ID_ALLOCATION:
            entry->mRsObj = Allocation::createFromStream(mRSC, mReadStream);
            break;
        case RS_A3D_CLASS_ID_PROGRAM_VERTEX:
            //entry->mRsObj = ProgramVertex::createFromStream(mRSC, mReadStream);
            break;
        case RS_A3D_CLASS_ID_PROGRAM_RASTER:
            //entry->mRsObj = ProgramRaster::createFromStream(mRSC, mReadStream);
            break;
        case RS_A3D_CLASS_ID_PROGRAM_FRAGMENT:
            //entry->mRsObj = ProgramFragment::createFromStream(mRSC, mReadStream);
            break;
        case RS_A3D_CLASS_ID_PROGRAM_STORE:
            //entry->mRsObj = ProgramStore::createFromStream(mRSC, mReadStream);
            break;
        case RS_A3D_CLASS_ID_SAMPLER:
            //entry->mRsObj = Sampler::createFromStream(mRSC, mReadStream);
            break;
        case RS_A3D_CLASS_ID_ANIMATION:
            //entry->mRsObj = Animation::createFromStream(mRSC, mReadStream);
            break;
        case RS_A3D_CLASS_ID_ADAPTER_1D:
            //entry->mRsObj = Adapter1D::createFromStream(mRSC, mReadStream);
            break;
        case RS_A3D_CLASS_ID_ADAPTER_2D:
            //entry->mRsObj = Adapter2D::createFromStream(mRSC, mReadStream);
            break;
        case RS_A3D_CLASS_ID_SCRIPT_C:
            break;
    }
    if (entry->mRsObj) {
        entry->mRsObj->incUserRef();
    }
    return entry->mRsObj;
}

bool FileA3D::writeFile(const char *filename) {
    if (!mWriteStream) {
        LOGE("No objects to write\n");
        return false;
    }
    if (mWriteStream->getPos() == 0) {
        LOGE("No objects to write\n");
        return false;
    }

    FILE *writeHandle = fopen(filename, "wb");
    if (!writeHandle) {
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
    for (uint32_t i = 0; i < writeIndexSize; i ++) {
        headerStream.addString(&mWriteIndex[i]->mObjectName);
        headerStream.addU32((uint32_t)mWriteIndex[i]->mType);
        if (mUse64BitOffsets){
            headerStream.addOffset(mWriteIndex[i]->mOffset);
            headerStream.addOffset(mWriteIndex[i]->mLength);
        } else {
            uint32_t offset = (uint32_t)mWriteIndex[i]->mOffset;
            headerStream.addU32(offset);
            offset = (uint32_t)mWriteIndex[i]->mLength;
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

    if (status != 0) {
        LOGE("Couldn't close file\n");
        return false;
    }

    return true;
}

void FileA3D::appendToFile(ObjectBase *obj) {
    if (!obj) {
        return;
    }
    if (!mWriteStream) {
        const uint64_t initialStreamSize = 256*1024;
        mWriteStream = new OStream(initialStreamSize, false);
    }
    A3DIndexEntry *indexEntry = new A3DIndexEntry();
    indexEntry->mObjectName.setTo(obj->getName());
    indexEntry->mType = obj->getClassId();
    indexEntry->mOffset = mWriteStream->getPos();
    indexEntry->mRsObj = obj;
    mWriteIndex.push(indexEntry);
    obj->serialize(mWriteStream);
    indexEntry->mLength = mWriteStream->getPos() - indexEntry->mOffset;
    mWriteStream->align(4);
}

RsObjectBase rsaFileA3DGetEntryByIndex(RsContext con, uint32_t index, RsFile file) {
    FileA3D *fa3d = static_cast<FileA3D *>(file);
    if (!fa3d) {
        LOGE("Can't load entry. No valid file");
        return NULL;
    }

    ObjectBase *obj = fa3d->initializeFromEntry(index);
    //ALOGV("Returning object with name %s", obj->getName());

    return obj;
}


void rsaFileA3DGetNumIndexEntries(RsContext con, int32_t *numEntries, RsFile file) {
    FileA3D *fa3d = static_cast<FileA3D *>(file);

    if (fa3d) {
        *numEntries = fa3d->getNumIndexEntries();
    } else {
        *numEntries = 0;
    }
}

void rsaFileA3DGetIndexEntries(RsContext con, RsFileIndexEntry *fileEntries, uint32_t numEntries, RsFile file) {
    FileA3D *fa3d = static_cast<FileA3D *>(file);

    if (!fa3d) {
        LOGE("Can't load index entries. No valid file");
        return;
    }

    uint32_t numFileEntries = fa3d->getNumIndexEntries();
    if (numFileEntries != numEntries || numEntries == 0 || fileEntries == NULL) {
        LOGE("Can't load index entries. Invalid number requested");
        return;
    }

    for (uint32_t i = 0; i < numFileEntries; i ++) {
        const FileA3D::A3DIndexEntry *entry = fa3d->getIndexEntry(i);
        fileEntries[i].classID = entry->getType();
        fileEntries[i].objectName = entry->getObjectName().string();
    }
}

RsFile rsaFileA3DCreateFromMemory(RsContext con, const void *data, uint32_t len) {
    if (data == NULL) {
        LOGE("File load failed. Asset stream is NULL");
        return NULL;
    }

    Context *rsc = static_cast<Context *>(con);
    FileA3D *fa3d = new FileA3D(rsc);
    fa3d->incUserRef();

    fa3d->load(data, len);
    return fa3d;
}

RsFile rsaFileA3DCreateFromAsset(RsContext con, void *_asset) {
    Context *rsc = static_cast<Context *>(con);
    Asset *asset = static_cast<Asset *>(_asset);
    FileA3D *fa3d = new FileA3D(rsc);
    fa3d->incUserRef();

    fa3d->load(asset);
    return fa3d;
}

RsFile rsaFileA3DCreateFromFile(RsContext con, const char *path) {
    if (path == NULL) {
        LOGE("File load failed. Path is NULL");
        return NULL;
    }

    Context *rsc = static_cast<Context *>(con);
    FileA3D *fa3d = NULL;

    FILE *f = fopen(path, "rb");
    if (f) {
        fa3d = new FileA3D(rsc);
        fa3d->incUserRef();
        fa3d->load(f);
        fclose(f);
    } else {
        LOGE("Could not open file %s", path);
    }

    return fa3d;
}
