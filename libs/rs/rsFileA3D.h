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

#ifndef ANDROID_RS_FILE_A3D_H
#define ANDROID_RS_FILE_A3D_H

#include "RenderScript.h"
#include "rsFileA3DDecls.h"
#include "rsMesh.h"

#include <utils/String8.h>
#include "rsStream.h"
#include <stdio.h>

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class FileA3D
{
public:
    FileA3D();
    ~FileA3D();

    uint32_t mMajorVersion;
    uint32_t mMinorVersion;
    uint64_t mIndexOffset;
    uint64_t mStringTableOffset;
    bool mUse64BitOffsets;

    struct A3DIndexEntry {
        String8 mID;
        A3DClassID mType;
        uint64_t mOffset;
        void * mRsObj;
    };

    bool load(Context *rsc, FILE *f);
    size_t getNumLoadedEntries() const;
    const A3DIndexEntry* getLoadedEntry(size_t index) const;
    ObjectBase *initializeFromEntry(const A3DIndexEntry *entry);

    void appendToFile(ObjectBase *obj);
    bool writeFile(const char *filename);

protected:

    const uint8_t * mData;
    void * mAlloc;
    uint64_t mDataSize;
    Context * mRsc;

    OStream *mWriteStream;
    Vector<A3DIndexEntry*> mWriteIndex;

    IStream *mReadStream;
    Vector<A3DIndexEntry*> mIndex;
};


}
}
#endif //ANDROID_RS_FILE_A3D_H


