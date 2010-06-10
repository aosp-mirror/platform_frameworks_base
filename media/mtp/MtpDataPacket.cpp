/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <stdio.h>
#include <sys/types.h>
#include <fcntl.h>

#include "MtpDataPacket.h"
#include "MtpStringBuffer.h"

namespace android {

MtpDataPacket::MtpDataPacket()
    :   MtpPacket(512),
        mOffset(MTP_CONTAINER_HEADER_SIZE)
{
}

MtpDataPacket::~MtpDataPacket() {
}

void MtpDataPacket::reset() {
    MtpPacket::reset();
    mOffset = MTP_CONTAINER_HEADER_SIZE;
}

void MtpDataPacket::setOperationCode(MtpOperationCode code) {
    MtpPacket::putUInt16(MTP_CONTAINER_CODE_OFFSET, code);
}

void MtpDataPacket::setTransactionID(MtpTransactionID id) {
    MtpPacket::putUInt32(MTP_CONTAINER_TRANSACTION_ID_OFFSET, id);
}

uint16_t MtpDataPacket::getUInt16() {
    int offset = mOffset;
    uint16_t result = (uint16_t)mBuffer[offset] | ((uint16_t)mBuffer[offset + 1] << 8);
    mOffset += 2;
    return result;
}

uint32_t MtpDataPacket::getUInt32() {
    int offset = mOffset;
    uint32_t result = (uint32_t)mBuffer[offset] | ((uint32_t)mBuffer[offset + 1] << 8) |
           ((uint32_t)mBuffer[offset + 2] << 16)  | ((uint32_t)mBuffer[offset + 3] << 24);
    mOffset += 4;
    return result;
}

uint64_t MtpDataPacket::getUInt64() {
    int offset = mOffset;
    uint64_t result = (uint64_t)mBuffer[offset] | ((uint64_t)mBuffer[offset + 1] << 8) |
           ((uint64_t)mBuffer[offset + 2] << 16) | ((uint64_t)mBuffer[offset + 3] << 24) |
           ((uint64_t)mBuffer[offset + 4] << 32) | ((uint64_t)mBuffer[offset + 5] << 40) |
           ((uint64_t)mBuffer[offset + 6] << 48)  | ((uint64_t)mBuffer[offset + 7] << 56);
    mOffset += 8;
    return result;
}

void MtpDataPacket::getUInt128(uint128_t& value) {
    value[0] = getUInt32();
    value[1] = getUInt32();
    value[2] = getUInt32();
    value[3] = getUInt32();
}

void MtpDataPacket::getString(MtpStringBuffer& string)
{
    string.readFromPacket(this);
}

Int8List* MtpDataPacket::getAInt8() {
    Int8List* result = new Int8List;
    int count = getUInt32();
    for (int i = 0; i < count; i++)
        result->push(getInt8());
    return result;
}

UInt8List* MtpDataPacket::getAUInt8() {
    UInt8List* result = new UInt8List;
    int count = getUInt32();
    for (int i = 0; i < count; i++)
        result->push(getUInt8());
    return result;
}

Int16List* MtpDataPacket::getAInt16() {
    Int16List* result = new Int16List;
    int count = getUInt32();
    for (int i = 0; i < count; i++)
        result->push(getInt16());
    return result;
}

UInt16List* MtpDataPacket::getAUInt16() {
    UInt16List* result = new UInt16List;
    int count = getUInt32();
    for (int i = 0; i < count; i++)
        result->push(getUInt16());
    return result;
}

Int32List* MtpDataPacket::getAInt32() {
    Int32List* result = new Int32List;
    int count = getUInt32();
    for (int i = 0; i < count; i++)
        result->push(getInt32());
    return result;
}

UInt32List* MtpDataPacket::getAUInt32() {
    UInt32List* result = new UInt32List;
    int count = getUInt32();
    for (int i = 0; i < count; i++)
        result->push(getUInt32());
    return result;
}

Int64List* MtpDataPacket::getAInt64() {
    Int64List* result = new Int64List;
    int count = getUInt32();
    for (int i = 0; i < count; i++)
        result->push(getInt64());
    return result;
}

UInt64List* MtpDataPacket::getAUInt64() {
    UInt64List* result = new UInt64List;
    int count = getUInt32();
    for (int i = 0; i < count; i++)
        result->push(getUInt64());
    return result;
}

void MtpDataPacket::putInt8(int8_t value) {
    allocate(mOffset + 1);
    mBuffer[mOffset++] = (uint8_t)value;
    if (mPacketSize < mOffset)
        mPacketSize = mOffset;
}

void MtpDataPacket::putUInt8(uint8_t value) {
    allocate(mOffset + 1);
    mBuffer[mOffset++] = (uint8_t)value;
    if (mPacketSize < mOffset)
        mPacketSize = mOffset;
}

void MtpDataPacket::putInt16(int16_t value) {
    allocate(mOffset + 2);
    mBuffer[mOffset++] = (uint8_t)(value & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 8) & 0xFF);
    if (mPacketSize < mOffset)
        mPacketSize = mOffset;
}

void MtpDataPacket::putUInt16(uint16_t value) {
    allocate(mOffset + 2);
    mBuffer[mOffset++] = (uint8_t)(value & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 8) & 0xFF);
    if (mPacketSize < mOffset)
        mPacketSize = mOffset;
}

void MtpDataPacket::putInt32(int32_t value) {
    allocate(mOffset + 4);
    mBuffer[mOffset++] = (uint8_t)(value & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 8) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 16) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 24) & 0xFF);
    if (mPacketSize < mOffset)
        mPacketSize = mOffset;
}

void MtpDataPacket::putUInt32(uint32_t value) {
    allocate(mOffset + 4);
    mBuffer[mOffset++] = (uint8_t)(value & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 8) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 16) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 24) & 0xFF);
    if (mPacketSize < mOffset)
        mPacketSize = mOffset;
}

void MtpDataPacket::putInt64(int64_t value) {
    allocate(mOffset + 8);
    mBuffer[mOffset++] = (uint8_t)(value & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 8) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 16) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 24) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 32) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 40) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 48) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 56) & 0xFF);
    if (mPacketSize < mOffset)
        mPacketSize = mOffset;
}

void MtpDataPacket::putUInt64(uint64_t value) {
    allocate(mOffset + 8);
    mBuffer[mOffset++] = (uint8_t)(value & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 8) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 16) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 24) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 32) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 40) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 48) & 0xFF);
    mBuffer[mOffset++] = (uint8_t)((value >> 56) & 0xFF);
    if (mPacketSize < mOffset)
        mPacketSize = mOffset;
}

void MtpDataPacket::putInt128(const int128_t& value) {
    putInt32(value[0]);
    putInt32(value[1]);
    putInt32(value[2]);
    putInt32(value[3]);
}

void MtpDataPacket::putUInt128(const uint128_t& value) {
    putUInt32(value[0]);
    putUInt32(value[1]);
    putUInt32(value[2]);
    putUInt32(value[3]);
}

void MtpDataPacket::putAInt8(const int8_t* values, int count) {
    putUInt32(count);
    for (int i = 0; i < count; i++)
        putInt8(*values++);
}

void MtpDataPacket::putAUInt8(const uint8_t* values, int count) {
    putUInt32(count);
    for (int i = 0; i < count; i++)
        putUInt8(*values++);
}

void MtpDataPacket::putAInt16(const int16_t* values, int count) {
    putUInt32(count);
    for (int i = 0; i < count; i++)
        putInt16(*values++);
}

void MtpDataPacket::putAUInt16(const uint16_t* values, int count) {
    putUInt32(count);
    for (int i = 0; i < count; i++)
        putUInt16(*values++);
}

void MtpDataPacket::putAInt32(const int32_t* values, int count) {
    putUInt32(count);
    for (int i = 0; i < count; i++)
        putInt32(*values++);
}

void MtpDataPacket::putAUInt32(const uint32_t* values, int count) {
    putUInt32(count);
    for (int i = 0; i < count; i++)
        putUInt32(*values++);
}

void MtpDataPacket::putAUInt32(const UInt32List* list) {
    if (!list) {
        putEmptyArray();
    } else {
        size_t size = list->size();
        putUInt32(size);
        for (size_t i = 0; i < size; i++)
            putUInt32((*list)[i]);
    }
}

void MtpDataPacket::putAInt64(const int64_t* values, int count) {
    putUInt32(count);
    for (int i = 0; i < count; i++)
        putInt64(*values++);
}

void MtpDataPacket::putAUInt64(const uint64_t* values, int count) {
    putUInt32(count);
    for (int i = 0; i < count; i++)
        putUInt64(*values++);
}

void MtpDataPacket::putString(const MtpStringBuffer& string)
{
    string.writeToPacket(this);
}

void MtpDataPacket::putString(const char* s)
{
    MtpStringBuffer string(s);
    string.writeToPacket(this);
}

#ifdef MTP_DEVICE 
int MtpDataPacket::read(int fd) {
    // first read the header
    int ret = ::read(fd, mBuffer, MTP_CONTAINER_HEADER_SIZE);
printf("MtpDataPacket::read 1 returned %d\n", ret);
    if (ret != MTP_CONTAINER_HEADER_SIZE)
        return -1;
    // then the following data
    int total = MtpPacket::getUInt32(MTP_CONTAINER_LENGTH_OFFSET);
    int remaining = total - MTP_CONTAINER_HEADER_SIZE;
printf("total: %d, remaining: %d\n", total, remaining);
    ret = ::read(fd, &mBuffer[0] + MTP_CONTAINER_HEADER_SIZE, remaining);
printf("MtpDataPacket::read 2 returned %d\n", ret);
    if (ret != remaining)
        return -1;

    mPacketSize = total;
    mOffset = MTP_CONTAINER_HEADER_SIZE;
    return total;
}

int MtpDataPacket::readDataHeader(int fd) {
    int ret = ::read(fd, mBuffer, MTP_CONTAINER_HEADER_SIZE);
    if (ret > 0)
        mPacketSize = ret;
    else
        mPacketSize = 0;
    return ret;
}

int MtpDataPacket::write(int fd) {
    MtpPacket::putUInt32(MTP_CONTAINER_LENGTH_OFFSET, mPacketSize);
    MtpPacket::putUInt16(MTP_CONTAINER_TYPE_OFFSET, MTP_CONTAINER_TYPE_DATA);

    // send header separately from data
    int ret = ::write(fd, mBuffer, MTP_CONTAINER_HEADER_SIZE);
    if (ret == MTP_CONTAINER_HEADER_SIZE)
        ret = ::write(fd, mBuffer + MTP_CONTAINER_HEADER_SIZE,
                        mPacketSize - MTP_CONTAINER_HEADER_SIZE);
    return (ret < 0 ? ret : 0);
}

int MtpDataPacket::writeDataHeader(int fd, uint32_t length) {
    MtpPacket::putUInt32(MTP_CONTAINER_LENGTH_OFFSET, length);
    MtpPacket::putUInt16(MTP_CONTAINER_TYPE_OFFSET, MTP_CONTAINER_TYPE_DATA);
    int ret = ::write(fd, mBuffer, MTP_CONTAINER_HEADER_SIZE);
    return (ret < 0 ? ret : 0);
}
#endif // MTP_DEVICE

#ifdef MTP_HOST
int MtpDataPacket::read(struct usb_endpoint *ep) {
    // first read the header
    int length = transfer(ep, mBuffer, mBufferSize);
    if (length > MTP_CONTAINER_HEADER_SIZE) {
        // look at the length field to see if the data spans multiple packets
        uint32_t totalLength = MtpPacket::getUInt32(MTP_CONTAINER_LENGTH_OFFSET);
        while (totalLength > length) {
            allocate(length + mAllocationIncrement);
            int ret = transfer(ep, mBuffer + length, mAllocationIncrement);
            if (ret >= 0)
                length += ret;
            else {
                length = ret;
                break;
            }
        }
    }
    if (length >= 0)
        mPacketSize = length;
    return length;
}

int MtpDataPacket::write(struct usb_endpoint *ep) {
    MtpPacket::putUInt32(MTP_CONTAINER_LENGTH_OFFSET, mPacketSize);
    MtpPacket::putUInt16(MTP_CONTAINER_TYPE_OFFSET, MTP_CONTAINER_TYPE_DATA);

    // send header separately from data
    int ret = transfer(ep, mBuffer, MTP_CONTAINER_HEADER_SIZE);
    if (ret == MTP_CONTAINER_HEADER_SIZE)
        ret = transfer(ep, mBuffer + MTP_CONTAINER_HEADER_SIZE,
                        mPacketSize - MTP_CONTAINER_HEADER_SIZE);
    return (ret < 0 ? ret : 0);
}

#endif // MTP_HOST

void* MtpDataPacket::getData(int& outLength) const {
    int length = mPacketSize - MTP_CONTAINER_HEADER_SIZE;
    if (length > 0) {
        void* result = malloc(length);
        if (result) {
            memcpy(result, mBuffer + MTP_CONTAINER_HEADER_SIZE, length);
            outLength = length;
            return result;
        }
    }
    outLength = 0;
    return NULL;
}

}  // namespace android
