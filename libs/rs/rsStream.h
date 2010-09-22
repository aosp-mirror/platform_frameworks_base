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

#ifndef ANDROID_RS_STREAM_H
#define ANDROID_RS_STREAM_H

#include <utils/String8.h>
#include <stdio.h>

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class IStream
{
public:
    IStream(const uint8_t *, bool use64);

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
    inline uint8_t loadU8() {
        uint8_t tmp = reinterpret_cast<const uint8_t *>(&mData[mPos])[0];
        mPos += sizeof(uint8_t);
        return tmp;
    }
    void loadByteArray(void *dest, size_t numBytes);
    uint64_t loadOffset();
    void loadString(String8 *s);
    uint64_t getPos() const {
        return mPos;
    }
    void reset(uint64_t pos) {
        mPos = pos;
    }
    void reset() {
        mPos = 0;
    }
    
    const uint8_t * getPtr() const {
        return mData;
    }
protected:
    const uint8_t * mData;
    uint64_t mPos;
    bool mUse64;
};

class OStream
{
public:
    OStream(uint64_t length, bool use64);
    ~OStream();
    
    void align(uint32_t bytes) {
        mPos = (mPos + (bytes - 1)) & (~(bytes - 1));
        if(mPos >= mLength) {
            growSize();
        }
    }
    
    void addF(float v) {
        uint32_t uintV = *reinterpret_cast<uint32_t*> (&v);
        addU32(uintV);
    }
    void addI32(int32_t v) {
        mPos = (mPos + 3) & (~3);
        if(mPos + sizeof(v) >= mLength) {
            growSize();
        }
        mData[mPos++] = (uint8_t)(v & 0xff);
        mData[mPos++] = (uint8_t)((v >> 8) & 0xff);
        mData[mPos++] = (uint8_t)((v >> 16) & 0xff);
        mData[mPos++] = (uint8_t)((v >> 24) & 0xff);
    }
    void addU32(uint32_t v) {
        mPos = (mPos + 3) & (~3);
        if(mPos + sizeof(v) >= mLength) {
            growSize();
        }
        mData[mPos++] = (uint8_t)(v & 0xff);
        mData[mPos++] = (uint8_t)((v >> 8) & 0xff);
        mData[mPos++] = (uint8_t)((v >> 16) & 0xff);
        mData[mPos++] = (uint8_t)((v >> 24) & 0xff);
    }
    void addU16(uint16_t v) {
        mPos = (mPos + 1) & (~1);
        if(mPos + sizeof(v) >= mLength) {
            growSize();
        }
        mData[mPos++] = (uint8_t)(v & 0xff);
        mData[mPos++] = (uint8_t)(v >> 8);
    }
    inline void addU8(uint8_t v) {
        if(mPos + 1 >= mLength) {
            growSize();
        }
        reinterpret_cast<uint8_t *>(&mData[mPos])[0] = v;
        mPos ++;
    }
    void addByteArray(const void *src, size_t numBytes);
    void addOffset(uint64_t v);
    void addString(String8 *s);
    uint64_t getPos() const {
        return mPos;
    }
    void reset(uint64_t pos) {
        mPos = pos;
    }
    void reset() {
        mPos = 0;
    }
    const uint8_t * getPtr() const {
        return mData;
    }
protected:
    void growSize();
    uint8_t * mData;
    uint64_t mLength;
    uint64_t mPos;
    bool mUse64;
};
    

} // renderscript
} // android
#endif //ANDROID_RS_STREAM_H


