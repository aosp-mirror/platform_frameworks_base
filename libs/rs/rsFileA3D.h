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
        A3DChunkType mType;
        uint64_t mOffset;
        void * mRsObj;
    };

    bool load(Context *rsc, FILE *f);

protected:
    class IO
    {
    public:
        IO(const uint8_t *, bool use64);
    
        float loadF() {
            mPos = (mPos + 3) & (~3);
            float tmp = reinterpret_cast<const float *>(&mData[mPos])[0];
            mPos += sizeof(float);
            return tmp;
        }
        int32_t loadI32() {
            mPos = (mPos + 3) & (~3);
            int32_t tmp = reinterpret_cast<const int32_t *>(&mData[mPos])[0];
            mPos += sizeof(int32_t);
            return tmp;
        }
        uint32_t loadU32() {
            mPos = (mPos + 3) & (~3);
            uint32_t tmp = reinterpret_cast<const uint32_t *>(&mData[mPos])[0];
            mPos += sizeof(uint32_t);
            return tmp;
        }
        uint16_t loadU16() {
            mPos = (mPos + 1) & (~1);
            uint16_t tmp = reinterpret_cast<const uint16_t *>(&mData[mPos])[0];
            mPos += sizeof(uint16_t);
            return tmp;
        }
        uint8_t loadU8() {
            uint8_t tmp = reinterpret_cast<const uint8_t *>(&mData[mPos])[0];
            mPos += sizeof(uint8_t);
            return tmp;
        }
        uint64_t loadOffset();
        void loadString(String8 *s);
        uint64_t getPos() const {return mPos;}
        const uint8_t * getPtr() const;
    protected:
        const uint8_t * mData;
        uint64_t mPos;
        bool mUse64;
    };


    bool process(Context *rsc);
    bool processIndex(Context *rsc, A3DIndexEntry *);
    void processChunk_Mesh(Context *rsc, IO *io, A3DIndexEntry *ie);
    void processChunk_Primitive(Context *rsc, IO *io, A3DIndexEntry *ie);
    void processChunk_Verticies(Context *rsc, IO *io, A3DIndexEntry *ie);
    void processChunk_Element(Context *rsc, IO *io, A3DIndexEntry *ie);
    void processChunk_ElementSource(Context *rsc, IO *io, A3DIndexEntry *ie);

    const uint8_t * mData;
    void * mAlloc;
    uint64_t mDataSize;
    Context * mRsc;

    Vector<A3DIndexEntry> mIndex;
    Vector<String8> mStrings;
    Vector<uint32_t> mStringIndexValues;

};


}
}
#endif //ANDROID_RS_FILE_A3D_H


