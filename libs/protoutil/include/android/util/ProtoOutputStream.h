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

#include <cstdint>
#include <string>
#include <vector>

#include <android/util/EncodedBuffer.h>

namespace android {
namespace util {

/**
 * Position of the field type in a 64-bits fieldId.
 */
const uint64_t FIELD_TYPE_SHIFT = 32;

/**
 * Mask for the field types stored in a fieldId.  Leaves a whole
 * byte for future expansion, even though there are currently only 17 types.
 */
const uint64_t FIELD_TYPE_MASK = 0x0ffULL << FIELD_TYPE_SHIFT;

/**
 * The types are copied from external/protobuf/src/google/protobuf/descriptor.h directly,
 * so no extra mapping needs to be maintained in this case.
 */
const uint64_t FIELD_TYPE_UNKNOWN  = 0;
const uint64_t FIELD_TYPE_DOUBLE   = 1ULL << FIELD_TYPE_SHIFT;   // double, exactly eight bytes on the wire.
const uint64_t FIELD_TYPE_FLOAT    = 2ULL << FIELD_TYPE_SHIFT;   // float, exactly four bytes on the wire.
const uint64_t FIELD_TYPE_INT64    = 3ULL << FIELD_TYPE_SHIFT;   // int64, varint on the wire.  Negative numbers
                                                                 // take 10 bytes.  Use TYPE_SINT64 if negative
                                                                 // values are likely.
const uint64_t FIELD_TYPE_UINT64   = 4ULL << FIELD_TYPE_SHIFT;   // uint64, varint on the wire.
const uint64_t FIELD_TYPE_INT32    = 5ULL << FIELD_TYPE_SHIFT;   // int32, varint on the wire.  Negative numbers
                                                                 // take 10 bytes.  Use TYPE_SINT32 if negative
                                                                 // values are likely.
const uint64_t FIELD_TYPE_FIXED64  = 6ULL << FIELD_TYPE_SHIFT;   // uint64, exactly eight bytes on the wire.
const uint64_t FIELD_TYPE_FIXED32  = 7ULL << FIELD_TYPE_SHIFT;   // uint32, exactly four bytes on the wire.
const uint64_t FIELD_TYPE_BOOL     = 8ULL << FIELD_TYPE_SHIFT;   // bool, varint on the wire.
const uint64_t FIELD_TYPE_STRING   = 9ULL << FIELD_TYPE_SHIFT;   // UTF-8 text.
// const uint64_t FIELD_TYPE_GROUP = 10ULL << FIELD_TYPE_SHIFT;  // Tag-delimited message.  Deprecated.
const uint64_t FIELD_TYPE_MESSAGE  = 11ULL << FIELD_TYPE_SHIFT;  // Length-delimited message.

const uint64_t FIELD_TYPE_BYTES    = 12ULL << FIELD_TYPE_SHIFT;  // Arbitrary byte array.
const uint64_t FIELD_TYPE_UINT32   = 13ULL << FIELD_TYPE_SHIFT;  // uint32, varint on the wire
const uint64_t FIELD_TYPE_ENUM     = 14ULL << FIELD_TYPE_SHIFT;  // Enum, varint on the wire
const uint64_t FIELD_TYPE_SFIXED32 = 15ULL << FIELD_TYPE_SHIFT;  // int32, exactly four bytes on the wire
const uint64_t FIELD_TYPE_SFIXED64 = 16ULL << FIELD_TYPE_SHIFT;  // int64, exactly eight bytes on the wire
const uint64_t FIELD_TYPE_SINT32   = 17ULL << FIELD_TYPE_SHIFT;  // int32, ZigZag-encoded varint on the wire
const uint64_t FIELD_TYPE_SINT64   = 18ULL << FIELD_TYPE_SHIFT;  // int64, ZigZag-encoded varint on the wire

//
// FieldId flags for whether the field is single, repeated or packed.
// TODO: packed is not supported yet.
//
const uint64_t FIELD_COUNT_SHIFT = 40;
const uint64_t FIELD_COUNT_MASK = 0x0fULL << FIELD_COUNT_SHIFT;
const uint64_t FIELD_COUNT_UNKNOWN = 0;
const uint64_t FIELD_COUNT_SINGLE = 1ULL << FIELD_COUNT_SHIFT;
const uint64_t FIELD_COUNT_REPEATED = 2ULL << FIELD_COUNT_SHIFT;
const uint64_t FIELD_COUNT_PACKED = 5ULL << FIELD_COUNT_SHIFT;

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
    bool write(uint64_t fieldId, long val);
    bool write(uint64_t fieldId, long long val);
    bool write(uint64_t fieldId, bool val);
    bool write(uint64_t fieldId, std::string val);
    bool write(uint64_t fieldId, const char* val, size_t size);

    /**
     * Starts a sub-message write session.
     * Returns a token of this write session.
     * Must call end(token) exactly once when finish write this sub-message.
     */
    uint64_t start(uint64_t fieldId);
    void end(uint64_t token);

    /**
     * Returns how many bytes are buffered in ProtoOutputStream.
     * Notice, this is not the actual(compact) size of the output data.
     */
    size_t bytesWritten();

    /**
     * Flushes the protobuf data out to given fd. When the following functions are called,
     * it is not able to write to ProtoOutputStream any more since the data is compact.
     */
    size_t size(); // Get the size of the serialized protobuf.
    sp<ProtoReader> data(); // Get the reader apis of the data.
    bool flush(int fd); // Flush data directly to a file descriptor.
    bool serializeToString(std::string* out); // Serializes the proto to a string.
    bool serializeToVector(std::vector<uint8_t>* out); // Serializes the proto to a vector<uint8_t>.

    /**
     * Clears the ProtoOutputStream so the buffer can be reused instead of deallocation/allocation again.
     */
    void clear();

    // Please don't use the following functions to dump protos unless you are familiar with protobuf encoding.
    void writeRawVarint(uint64_t varint);
    void writeLengthDelimitedHeader(uint32_t id, size_t size);
    void writeRawByte(uint8_t byte);

private:
    sp<EncodedBuffer> mBuffer;
    size_t mCopyBegin;
    bool mCompact;
    uint32_t mDepth;
    uint32_t mObjectId;
    uint64_t mExpectedObjectToken;

    inline void writeDoubleImpl(uint32_t id, double val);
    inline void writeFloatImpl(uint32_t id, float val);
    inline void writeInt64Impl(uint32_t id, int64_t val);
    inline void writeInt32Impl(uint32_t id, int32_t val);
    inline void writeUint64Impl(uint32_t id, uint64_t val);
    inline void writeUint32Impl(uint32_t id, uint32_t val);
    inline void writeFixed64Impl(uint32_t id, uint64_t val);
    inline void writeFixed32Impl(uint32_t id, uint32_t val);
    inline void writeSFixed64Impl(uint32_t id, int64_t val);
    inline void writeSFixed32Impl(uint32_t id, int32_t val);
    inline void writeZigzagInt64Impl(uint32_t id, int64_t val);
    inline void writeZigzagInt32Impl(uint32_t id, int32_t val);
    inline void writeEnumImpl(uint32_t id, int val);
    inline void writeBoolImpl(uint32_t id, bool val);
    inline void writeUtf8StringImpl(uint32_t id, const char* val, size_t size);
    inline void writeMessageBytesImpl(uint32_t id, const char* val, size_t size);

    bool compact();
    size_t editEncodedSize(size_t rawSize);
    bool compactSize(size_t rawSize);

    template<typename T>
    bool internalWrite(uint64_t fieldId, T val, const char* typeName);
};

}
}

#endif // ANDROID_UTIL_PROTOOUTPUT_STREAM_H
