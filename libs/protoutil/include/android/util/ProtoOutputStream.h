/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef ANDROID_UTIL_PROTOOUTPUT_STREAM_H
#define ANDROID_UTIL_PROTOOUTPUT_STREAM_H

#include <android/util/EncodedBuffer.h>

#include <stdint.h>
#include <string>

namespace android {
namespace util {

/**
 * Class to write to a protobuf stream.
 *
 * Each write method takes an ID code from the protoc generated classes
 * and the value to write.  To make a nested object, call start
 * and then end when you are done.
 *
 * See the java version implementation (ProtoOutputStream.java) for more infos.
 */
class ProtoOutputStream
{
public:
    ProtoOutputStream();
    ~ProtoOutputStream();

    /**
     * Write APIs for dumping protobuf data. Returns true if the write succeeds.
     */
    bool write(uint64_t fieldId, double val);
    bool write(uint64_t fieldId, float val);
    bool write(uint64_t fieldId, int val);
    bool write(uint64_t fieldId, long long val);
    bool write(uint64_t fieldId, bool val);
    bool write(uint64_t fieldId, std::string val);
    bool write(uint64_t fieldId, const char* val, size_t size);

    /**
     * Starts a sub-message write session.
     * Returns a token of this write session.
     * Must call end(token) when finish write this sub-message.
     */
    long long start(uint64_t fieldId);
    void end(long long token);

    /**
     * Flushes the protobuf data out to given fd.
     */
    size_t size();
    EncodedBuffer::iterator data();
    bool flush(int fd);

    // Please don't use the following functions to dump protos unless you are sure about it.
    void writeRawVarint(uint64_t varint);
    void writeLengthDelimitedHeader(uint32_t id, size_t size);
    void writeRawByte(uint8_t byte);

private:
    EncodedBuffer mBuffer;
    size_t mCopyBegin;
    bool mCompact;
    int mDepth;
    int mObjectId;
    long long mExpectedObjectToken;

    inline void writeDoubleImpl(uint32_t id, double val);
    inline void writeFloatImpl(uint32_t id, float val);
    inline void writeInt64Impl(uint32_t id, long long val);
    inline void writeInt32Impl(uint32_t id, int val);
    inline void writeUint64Impl(uint32_t id, uint64_t val);
    inline void writeUint32Impl(uint32_t id, uint32_t val);
    inline void writeFixed64Impl(uint32_t id, uint64_t val);
    inline void writeFixed32Impl(uint32_t id, uint32_t val);
    inline void writeSFixed64Impl(uint32_t id, long long val);
    inline void writeSFixed32Impl(uint32_t id, int val);
    inline void writeZigzagInt64Impl(uint32_t id, long long val);
    inline void writeZigzagInt32Impl(uint32_t id, int val);
    inline void writeEnumImpl(uint32_t id, int val);
    inline void writeBoolImpl(uint32_t id, bool val);
    inline void writeUtf8StringImpl(uint32_t id, const char* val, size_t size);

    bool compact();
    size_t editEncodedSize(size_t rawSize);
    bool compactSize(size_t rawSize);
};

}
}

#endif // ANDROID_UTIL_PROTOOUTPUT_STREAM_H
