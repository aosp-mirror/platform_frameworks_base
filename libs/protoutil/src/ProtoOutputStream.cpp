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
#define LOG_TAG "libprotoutil"

#include <cinttypes>
#include <type_traits>

#include <android-base/file.h>
#include <android/util/protobuf.h>
#include <android/util/ProtoOutputStream.h>
#include <cutils/log.h>

namespace android {
namespace util {

ProtoOutputStream::ProtoOutputStream(): ProtoOutputStream(new EncodedBuffer())
{
}

ProtoOutputStream::ProtoOutputStream(sp<EncodedBuffer> buffer)
        :mBuffer(buffer),
         mCopyBegin(0),
         mCompact(false),
         mDepth(0),
         mObjectId(0),
         mExpectedObjectToken(UINT64_C(-1))
{
}

ProtoOutputStream::~ProtoOutputStream()
{
}


void
ProtoOutputStream::clear()
{
    mBuffer->clear();
    mCopyBegin = 0;
    mCompact = false;
    mDepth = 0;
    mObjectId = 0;
    mExpectedObjectToken = UINT64_C(-1);
}

template<typename T>
bool
ProtoOutputStream::internalWrite(uint64_t fieldId, T val, const char* typeName)
{
    if (mCompact) return false;
    const uint32_t id = (uint32_t)fieldId;
    switch (fieldId & FIELD_TYPE_MASK) {
        case FIELD_TYPE_DOUBLE:   writeDoubleImpl(id, (double)val);           break;
        case FIELD_TYPE_FLOAT:    writeFloatImpl(id, (float)val);             break;
        case FIELD_TYPE_INT64:    writeInt64Impl(id, (int64_t)val);           break;
        case FIELD_TYPE_UINT64:   writeUint64Impl(id, (uint64_t)val);         break;
        case FIELD_TYPE_INT32:    writeInt32Impl(id, (int32_t)val);           break;
        case FIELD_TYPE_FIXED64:  writeFixed64Impl(id, (uint64_t)val);        break;
        case FIELD_TYPE_FIXED32:  writeFixed32Impl(id, (uint32_t)val);        break;
        case FIELD_TYPE_UINT32:   writeUint32Impl(id, (uint32_t)val);         break;
        case FIELD_TYPE_SFIXED32: writeSFixed32Impl(id, (int32_t)val);        break;
        case FIELD_TYPE_SFIXED64: writeSFixed64Impl(id, (int64_t)val);        break;
        case FIELD_TYPE_SINT32:   writeZigzagInt32Impl(id, (int32_t)val);     break;
        case FIELD_TYPE_SINT64:   writeZigzagInt64Impl(id, (int64_t)val);     break;
        case FIELD_TYPE_ENUM:
            if (std::is_integral<T>::value) {
                writeEnumImpl(id, (int)val);
            } else {
                goto unsupported;
            }
            break;
        case FIELD_TYPE_BOOL:
            if (std::is_integral<T>::value) {
                writeBoolImpl(id, val != 0);
            } else {
                goto unsupported;
            }
            break;
        default:
            goto unsupported;
    }
    return true;

unsupported:
    ALOGW("Field type %" PRIu64 " is not supported when writing %s val.",
            (fieldId & FIELD_TYPE_MASK) >> FIELD_TYPE_SHIFT, typeName);
    return false;
}

bool
ProtoOutputStream::write(uint64_t fieldId, double val)
{
    return internalWrite(fieldId, val, "double");
}


bool
ProtoOutputStream::write(uint64_t fieldId, float val)
{
    return internalWrite(fieldId, val, "float");
}

bool
ProtoOutputStream::write(uint64_t fieldId, int val)
{
    return internalWrite(fieldId, val, "int");
}

bool
ProtoOutputStream::write(uint64_t fieldId, long val)
{
    if (mCompact) return false;
    const uint32_t id = (uint32_t)fieldId;
    switch (fieldId & FIELD_TYPE_MASK) {
        case FIELD_TYPE_DOUBLE:   writeDoubleImpl(id, (double)val);           break;
        case FIELD_TYPE_FLOAT:    writeFloatImpl(id, (float)val);             break;
        case FIELD_TYPE_INT64:    writeInt64Impl(id, (long long)val);         break;
        case FIELD_TYPE_UINT64:   writeUint64Impl(id, (uint64_t)val);         break;
        case FIELD_TYPE_INT32:    writeInt32Impl(id, (int)val);               break;
        case FIELD_TYPE_FIXED64:  writeFixed64Impl(id, (uint64_t)val);        break;
        case FIELD_TYPE_FIXED32:  writeFixed32Impl(id, (uint32_t)val);        break;
        case FIELD_TYPE_UINT32:   writeUint32Impl(id, (uint32_t)val);         break;
        case FIELD_TYPE_SFIXED32: writeSFixed32Impl(id, (int)val);            break;
        case FIELD_TYPE_SFIXED64: writeSFixed64Impl(id, (long long)val);      break;
        case FIELD_TYPE_SINT32:   writeZigzagInt32Impl(id, (int)val);         break;
        case FIELD_TYPE_SINT64:   writeZigzagInt64Impl(id, (long long)val);   break;
        case FIELD_TYPE_ENUM:     writeEnumImpl(id, (int)val);                break;
        case FIELD_TYPE_BOOL:     writeBoolImpl(id, val != 0);                break;
        default:
            ALOGW("Field type %d is not supported when writing long val.",
                    (int)((fieldId & FIELD_TYPE_MASK) >> FIELD_TYPE_SHIFT));
            return false;
    }
    return true;
}

bool
ProtoOutputStream::write(uint64_t fieldId, long long val)
{
    return internalWrite(fieldId, val, "long long");
}

bool
ProtoOutputStream::write(uint64_t fieldId, bool val)
{
    if (mCompact) return false;
    const uint32_t id = (uint32_t)fieldId;
    switch (fieldId & FIELD_TYPE_MASK) {
        case FIELD_TYPE_BOOL:
            writeBoolImpl(id, val);
            return true;
        default:
            ALOGW("Field type %" PRIu64 " is not supported when writing bool val.",
                    (fieldId & FIELD_TYPE_MASK) >> FIELD_TYPE_SHIFT);
            return false;
    }
}

bool
ProtoOutputStream::write(uint64_t fieldId, std::string_view val)
{
    if (mCompact) return false;
    const uint32_t id = (uint32_t)fieldId;
    switch (fieldId & FIELD_TYPE_MASK) {
        case FIELD_TYPE_STRING:
            writeUtf8StringImpl(id, val.data(), val.size());
            return true;
        default:
            ALOGW("Field type %" PRIu64 " is not supported when writing string val.",
                    (fieldId & FIELD_TYPE_MASK) >> FIELD_TYPE_SHIFT);
            return false;
    }
}

bool
ProtoOutputStream::write(uint64_t fieldId, const char* val, size_t size)
{
    if (mCompact) return false;
    const uint32_t id = (uint32_t)fieldId;
    switch (fieldId & FIELD_TYPE_MASK) {
        case FIELD_TYPE_STRING:
        case FIELD_TYPE_BYTES:
            writeUtf8StringImpl(id, val, size);
            return true;
        case FIELD_TYPE_MESSAGE:
            // can directly write valid format of message bytes into ProtoOutputStream without calling start/end
            writeMessageBytesImpl(id, val, size);
            return true;
        default:
            ALOGW("Field type %" PRIu64 " is not supported when writing char[] val.",
                    (fieldId & FIELD_TYPE_MASK) >> FIELD_TYPE_SHIFT);
            return false;
    }
}

/**
 * Make a token.
 *  Bits 61-63 - tag size (So we can go backwards later if the object had not data)
 *                - 3 bits, max value 7, max value needed 5
 *  Bit  60    - true if the object is repeated
 *  Bits 59-51 - depth (For error checking)
 *                - 9 bits, max value 511, when checking, value is masked (if we really
 *                  are more than 511 levels deep)
 *  Bits 32-50 - objectId (For error checking)
 *                - 19 bits, max value 524,287. that's a lot of objects. IDs will wrap
 *                  because of the overflow, and only the tokens are compared.
 *  Bits  0-31 - offset of the first size field in the buffer.
 */
static uint64_t
makeToken(uint32_t tagSize, bool repeated, uint32_t depth, uint32_t objectId, size_t sizePos) {
    return ((UINT64_C(0x07) & (uint64_t)tagSize) << 61)
            | (repeated ? (UINT64_C(1) << 60) : 0)
            | (UINT64_C(0x01ff) & (uint64_t)depth) << 51
            | (UINT64_C(0x07ffff) & (uint64_t)objectId) << 32
            | (UINT64_C(0x0ffffffff) & (uint64_t)sizePos);
}

/**
 * Get the encoded tag size from the token.
 */
static uint32_t getTagSizeFromToken(uint64_t token) {
    return 0x7 & (token >> 61);
}

/**
 * Get the nesting depth of startObject calls from the token.
 */
static uint32_t getDepthFromToken(uint64_t token) {
    return 0x01ff & (token >> 51);
}

/**
 * Get the location of the childRawSize (the first 32 bit size field) in this object.
 */
static uint32_t getSizePosFromToken(uint64_t token) {
    return (uint32_t)token;
}

uint64_t
ProtoOutputStream::start(uint64_t fieldId)
{
    if ((fieldId & FIELD_TYPE_MASK) != FIELD_TYPE_MESSAGE) {
        ALOGE("Can't call start for non-message type field: 0x%" PRIx64, fieldId);
        return 0;
    }

    uint32_t id = (uint32_t)fieldId;
    size_t prevPos = mBuffer->wp()->pos();
    mBuffer->writeHeader(id, WIRE_TYPE_LENGTH_DELIMITED);
    size_t sizePos = mBuffer->wp()->pos();

    mDepth++;
    mObjectId++;
    mBuffer->writeRawFixed64(mExpectedObjectToken); // push previous token into stack.

    mExpectedObjectToken = makeToken(sizePos - prevPos,
        (bool)(fieldId & FIELD_COUNT_REPEATED), mDepth, mObjectId, sizePos);
    return mExpectedObjectToken;
}

void
ProtoOutputStream::end(uint64_t token)
{
    if (token != mExpectedObjectToken) {
        ALOGE("Unexpected token: 0x%" PRIx64 ", should be 0x%" PRIx64, token, mExpectedObjectToken);
        mDepth = UINT32_C(-1); // make depth invalid
        return;
    }

    uint32_t depth = getDepthFromToken(token);
    if (depth != (mDepth & 0x01ff)) {
        ALOGE("Unexpected depth: %" PRIu32 ", should be %" PRIu32, depth, mDepth);
        mDepth = UINT32_C(-1); // make depth invalid
        return;
    }
    mDepth--;

    uint32_t sizePos = getSizePosFromToken(token);
    // number of bytes written in this start-end session.
    int childRawSize = mBuffer->wp()->pos() - sizePos - 8;

    // retrieve the old token from stack.
    mBuffer->ep()->rewind()->move(sizePos);
    mExpectedObjectToken = mBuffer->readRawFixed64();

    // If raw size is larger than 0, write the negative value here to indicate a compact is needed.
    if (childRawSize > 0) {
        mBuffer->editRawFixed32(sizePos, -childRawSize);
        mBuffer->editRawFixed32(sizePos+4, -1);
    } else {
        // reset wp which erase the header tag of the message when its size is 0.
        mBuffer->wp()->rewind()->move(sizePos - getTagSizeFromToken(token));
    }
}

size_t
ProtoOutputStream::bytesWritten()
{
    return mBuffer->size();
}

bool
ProtoOutputStream::compact() {
    if (mCompact) return true;
    if (mDepth != 0) {
        ALOGE("Can't compact when depth(%" PRIu32 ") is not zero. Missing or extra calls to end.", mDepth);
        return false;
    }
    // record the size of the original buffer.
    size_t rawBufferSize = mBuffer->size();
    if (rawBufferSize == 0) return true; // nothing to do if the buffer is empty;

    // reset edit pointer and recursively compute encoded size of messages.
    mBuffer->ep()->rewind();
    if (editEncodedSize(rawBufferSize) == 0) {
        ALOGE("Failed to editEncodedSize.");
        return false;
    }

    // reset both edit pointer and write pointer, and compact recursively.
    mBuffer->ep()->rewind();
    mBuffer->wp()->rewind();
    if (!compactSize(rawBufferSize)) {
        ALOGE("Failed to compactSize.");
        return false;
    }
    // copy the reset to the buffer.
    if (mCopyBegin < rawBufferSize) {
        mBuffer->copy(mCopyBegin, rawBufferSize - mCopyBegin);
    }

    // mark true means it is not legal to write to this ProtoOutputStream anymore
    mCompact = true;
    return true;
}

/**
 * First compaction pass.  Iterate through the data, and fill in the
 * nested object sizes so the next pass can compact them.
 */
size_t
ProtoOutputStream::editEncodedSize(size_t rawSize)
{
    size_t objectStart = mBuffer->ep()->pos();
    size_t objectEnd = objectStart + rawSize;
    size_t encodedSize = 0;
    int childRawSize, childEncodedSize;
    size_t childEncodedSizePos;

    while (mBuffer->ep()->pos() < objectEnd) {
        uint32_t tag = (uint32_t)mBuffer->readRawVarint();
        encodedSize += get_varint_size(tag);
        switch (read_wire_type(tag)) {
            case WIRE_TYPE_VARINT:
                do {
                    encodedSize++;
                } while ((mBuffer->readRawByte() & 0x80) != 0);
                break;
            case WIRE_TYPE_FIXED64:
                encodedSize += 8;
                mBuffer->ep()->move(8);
                break;
            case WIRE_TYPE_LENGTH_DELIMITED:
                childRawSize = (int)mBuffer->readRawFixed32();
                childEncodedSizePos = mBuffer->ep()->pos();
                childEncodedSize = (int)mBuffer->readRawFixed32();
                if (childRawSize >= 0 && childRawSize == childEncodedSize) {
                    mBuffer->ep()->move(childRawSize);
                } else if (childRawSize < 0 && childEncodedSize == -1){
                    childEncodedSize = editEncodedSize(-childRawSize);
                    mBuffer->editRawFixed32(childEncodedSizePos, childEncodedSize);
                } else {
                    ALOGE("Bad raw or encoded values: raw=%d, encoded=%d at %zu",
                            childRawSize, childEncodedSize, childEncodedSizePos);
                    return 0;
                }
                encodedSize += get_varint_size(childEncodedSize) + childEncodedSize;
                break;
            case WIRE_TYPE_FIXED32:
                encodedSize += 4;
                mBuffer->ep()->move(4);
                break;
            default:
                ALOGE("Unexpected wire type %d in editEncodedSize at [%zu, %zu]",
                        read_wire_type(tag), objectStart, objectEnd);
                return 0;
        }
    }
    return encodedSize;
}

/**
 * Second compaction pass.  Iterate through the data, and copy the data
 * forward in the buffer, converting the pairs of uint32s into a single
 * unsigned varint of the size.
 */
bool
ProtoOutputStream::compactSize(size_t rawSize)
{
    size_t objectStart = mBuffer->ep()->pos();
    size_t objectEnd = objectStart + rawSize;
    int childRawSize, childEncodedSize;

    while (mBuffer->ep()->pos() < objectEnd) {
        uint32_t tag = (uint32_t)mBuffer->readRawVarint();
        switch (read_wire_type(tag)) {
            case WIRE_TYPE_VARINT:
                while ((mBuffer->readRawByte() & 0x80) != 0) {}
                break;
            case WIRE_TYPE_FIXED64:
                mBuffer->ep()->move(8);
                break;
            case WIRE_TYPE_LENGTH_DELIMITED:
                mBuffer->copy(mCopyBegin, mBuffer->ep()->pos() - mCopyBegin);

                childRawSize = (int)mBuffer->readRawFixed32();
                childEncodedSize = (int)mBuffer->readRawFixed32();
                mCopyBegin = mBuffer->ep()->pos();

                // write encoded size to buffer.
                mBuffer->writeRawVarint32(childEncodedSize);
                if (childRawSize >= 0 && childRawSize == childEncodedSize) {
                    mBuffer->ep()->move(childEncodedSize);
                } else if (childRawSize < 0){
                    if (!compactSize(-childRawSize)) return false;
                } else {
                    ALOGE("Bad raw or encoded values: raw=%d, encoded=%d",
                            childRawSize, childEncodedSize);
                    return false;
                }
                break;
            case WIRE_TYPE_FIXED32:
                mBuffer->ep()->move(4);
                break;
            default:
                ALOGE("Unexpected wire type %d in compactSize at [%zu, %zu]",
                        read_wire_type(tag), objectStart, objectEnd);
                return false;
        }
    }
    return true;
}

size_t
ProtoOutputStream::size()
{
    if (!compact()) {
        ALOGE("compact failed, the ProtoOutputStream data is corrupted!");
        return 0;
    }
    return mBuffer->size();
}

bool
ProtoOutputStream::flush(int fd)
{
    if (fd < 0) return false;
    if (!compact()) return false;

    sp<ProtoReader> reader = mBuffer->read();
    while (reader->readBuffer() != NULL) {
        if (!android::base::WriteFully(fd, reader->readBuffer(), reader->currentToRead())) {
            return false;
        }
        reader->move(reader->currentToRead());
    }
    return true;
}

bool
ProtoOutputStream::serializeToString(std::string* out)
{
    if (out == nullptr) return false;
    if (!compact()) return false;

    sp<ProtoReader> reader = mBuffer->read();
    out->reserve(reader->size());
    while (reader->hasNext()) {
        out->append(static_cast<const char*>(static_cast<const void*>(reader->readBuffer())),
                    reader->currentToRead());
        reader->move(reader->currentToRead());
    }
    return true;
}

bool
ProtoOutputStream::serializeToVector(std::vector<uint8_t>* out)
{
    if (out == nullptr) return false;
    if (!compact()) return false;

    sp<ProtoReader> reader = mBuffer->read();
    out->reserve(reader->size());
    while (reader->hasNext()) {
        const uint8_t* buf = reader->readBuffer();
        size_t size = reader->currentToRead();
        out->insert(out->end(), buf, buf + size);
        reader->move(size);
    }
    return true;
}

sp<ProtoReader>
ProtoOutputStream::data()
{
    if (!compact()) {
        ALOGE("compact failed, the ProtoOutputStream data is corrupted!");
        mBuffer->clear();
    }
    return mBuffer->read();
}

void
ProtoOutputStream::writeRawVarint(uint64_t varint)
{
    mBuffer->writeRawVarint64(varint);
}

void
ProtoOutputStream::writeLengthDelimitedHeader(uint32_t id, size_t size)
{
    mBuffer->writeHeader(id, WIRE_TYPE_LENGTH_DELIMITED);
    // reserves 64 bits for length delimited fields, if first field is negative, compact it.
    mBuffer->writeRawFixed32(size);
    mBuffer->writeRawFixed32(size);
}

void
ProtoOutputStream::writeRawByte(uint8_t byte)
{
    mBuffer->writeRawByte(byte);
}


// =========================================================================
// Private functions

/**
 * bit_cast
 */
template <class From, class To>
inline To bit_cast(From const &from) {
    To to;
    memcpy(&to, &from, sizeof(to));
    return to;
}

inline void
ProtoOutputStream::writeDoubleImpl(uint32_t id, double val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_FIXED64);
    mBuffer->writeRawFixed64(bit_cast<double, uint64_t>(val));
}

inline void
ProtoOutputStream::writeFloatImpl(uint32_t id, float val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_FIXED32);
    mBuffer->writeRawFixed32(bit_cast<float, uint32_t>(val));
}

inline void
ProtoOutputStream::writeInt64Impl(uint32_t id, int64_t val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_VARINT);
    mBuffer->writeRawVarint64(val);
}

inline void
ProtoOutputStream::writeInt32Impl(uint32_t id, int32_t val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_VARINT);
    mBuffer->writeRawVarint32(val);
}

inline void
ProtoOutputStream::writeUint64Impl(uint32_t id, uint64_t val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_VARINT);
    mBuffer->writeRawVarint64(val);
}

inline void
ProtoOutputStream::writeUint32Impl(uint32_t id, uint32_t val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_VARINT);
    mBuffer->writeRawVarint32(val);
}

inline void
ProtoOutputStream::writeFixed64Impl(uint32_t id, uint64_t val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_FIXED64);
    mBuffer->writeRawFixed64(val);
}

inline void
ProtoOutputStream::writeFixed32Impl(uint32_t id, uint32_t val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_FIXED32);
    mBuffer->writeRawFixed32(val);
}

inline void
ProtoOutputStream::writeSFixed64Impl(uint32_t id, int64_t val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_FIXED64);
    mBuffer->writeRawFixed64(val);
}

inline void
ProtoOutputStream::writeSFixed32Impl(uint32_t id, int32_t val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_FIXED32);
    mBuffer->writeRawFixed32(val);
}

inline void
ProtoOutputStream::writeZigzagInt64Impl(uint32_t id, int64_t val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_VARINT);
    mBuffer->writeRawVarint64((val << 1) ^ (val >> 63));
}

inline void
ProtoOutputStream::writeZigzagInt32Impl(uint32_t id, int32_t val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_VARINT);
    mBuffer->writeRawVarint32((val << 1) ^ (val >> 31));
}

inline void
ProtoOutputStream::writeEnumImpl(uint32_t id, int val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_VARINT);
    mBuffer->writeRawVarint32((uint32_t) val);
}

inline void
ProtoOutputStream::writeBoolImpl(uint32_t id, bool val)
{
    mBuffer->writeHeader(id, WIRE_TYPE_VARINT);
    mBuffer->writeRawVarint32(val ? 1 : 0);
}

inline void
ProtoOutputStream::writeUtf8StringImpl(uint32_t id, const char* val, size_t size)
{
    if (val == NULL) return;
    writeLengthDelimitedHeader(id, size);
    for (size_t i=0; i<size; i++) {
        mBuffer->writeRawByte((uint8_t)val[i]);
    }
}

inline void
ProtoOutputStream::writeMessageBytesImpl(uint32_t id, const char* val, size_t size)
{
    if (val == NULL) return;
    writeLengthDelimitedHeader(id, size);
    for (size_t i=0; i<size; i++) {
        mBuffer->writeRawByte(val[i]);
    }
}

} // util
} // android

