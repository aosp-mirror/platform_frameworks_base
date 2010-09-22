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
#include "rsMesh.h"

#include <utils/String8.h>
#include "rsStream.h"
#include <stdio.h>

#define A3D_MAGIC_KEY "Android3D_ff"

// ---------------------------------------------------------------------------
namespace android {

namespace renderscript {

class FileA3D : public ObjectBase
{
public:
    FileA3D(Context *rsc);
    ~FileA3D();

    uint32_t mMajorVersion;
    uint32_t mMinorVersion;
    uint64_t mIndexOffset;
    uint64_t mStringTableOffset;
    bool mUse64BitOffsets;

    class A3DIndexEntry {
        String8 mObjectName;
        RsA3DClassID mType;
        uint64_t mOffset;
        uint64_t mLength;
        ObjectBase *mRsObj;
    public:
        friend class FileA3D;
        const String8 &getObjectName() const {
            return mObjectName;
        }
        RsA3DClassID getType() const {
            return mType;
        }
    };

    bool load(FILE *f);
    bool load(const void *data, size_t length);

    size_t getNumIndexEntries() const;
    const A3DIndexEntry* getIndexEntry(size_t index) const;
    ObjectBase *initializeFromEntry(size_t index);

    void appendToFile(ObjectBase *obj);
    bool writeFile(const char *filename);

    // Currently files do not get serialized,
    // but we need to inherit from ObjectBase for ref tracking
    virtual void serialize(OStream *stream) const {
    }
    virtual RsA3DClassID getClassId() const {
        return RS_A3D_CLASS_ID_UNKNOWN;
    }

protected:

    void parseHeader(IStream *headerStream);

    const uint8_t * mData;
    void * mAlloc;
    uint64_t mDataSize;

    OStream *mWriteStream;
    Vector<A3DIndexEntry*> mWriteIndex;

    IStream *mReadStream;
    Vector<A3DIndexEntry*> mIndex;
};


}
}
#endif //ANDROID_RS_FILE_A3D_H


