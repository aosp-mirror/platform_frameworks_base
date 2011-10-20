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

#define LOG_TAG "MtpPacket"

#include "MtpDebug.h"
#include "MtpPacket.h"
#include "mtp.h"

#include <stdio.h>
#include <stdlib.h>
#include <stdio.h>

#include <usbhost/usbhost.h>

namespace android {

MtpPacket::MtpPacket(int bufferSize)
    :   mBuffer(NULL),
        mBufferSize(bufferSize),
        mAllocationIncrement(bufferSize),
        mPacketSize(0)
{
    mBuffer = (uint8_t *)malloc(bufferSize);
    if (!mBuffer) {
        LOGE("out of memory!");
        abort();
    }
}

MtpPacket::~MtpPacket() {
    if (mBuffer)
        free(mBuffer);
}

void MtpPacket::reset() {
    allocate(MTP_CONTAINER_HEADER_SIZE);
    mPacketSize = MTP_CONTAINER_HEADER_SIZE;
    memset(mBuffer, 0, mBufferSize);
}

void MtpPacket::allocate(int length) {
    if (length > mBufferSize) {
        int newLength = length + mAllocationIncrement;
        mBuffer = (uint8_t *)realloc(mBuffer, newLength);
        if (!mBuffer) {
            LOGE("out of memory!");
            abort();
        }
        mBufferSize = newLength;
    }
}

void MtpPacket::dump() {
#define DUMP_BYTES_PER_ROW  16
    char buffer[500];
    char* bufptr = buffer;

    for (int i = 0; i < mPacketSize; i++) {
        sprintf(bufptr, "%02X ", mBuffer[i]);
        bufptr += strlen(bufptr);
        if (i % DUMP_BYTES_PER_ROW == (DUMP_BYTES_PER_ROW - 1)) {
            ALOGV("%s", buffer);
            bufptr = buffer;
        }
    }
    if (bufptr != buffer) {
        // print last line
        ALOGV("%s", buffer);
    }
    ALOGV("\n");
}

void MtpPacket::copyFrom(const MtpPacket& src) {
    int length = src.mPacketSize;
    allocate(length);
    mPacketSize = length;
    memcpy(mBuffer, src.mBuffer, length);
}

uint16_t MtpPacket::getUInt16(int offset) const {
    return ((uint16_t)mBuffer[offset + 1] << 8) | (uint16_t)mBuffer[offset];
}

uint32_t MtpPacket::getUInt32(int offset) const {
    return ((uint32_t)mBuffer[offset + 3] << 24) | ((uint32_t)mBuffer[offset + 2] << 16) |
           ((uint32_t)mBuffer[offset + 1] << 8)  | (uint32_t)mBuffer[offset];
}

void MtpPacket::putUInt16(int offset, uint16_t value) {
    mBuffer[offset++] = (uint8_t)(value & 0xFF);
    mBuffer[offset++] = (uint8_t)((value >> 8) & 0xFF);
}

void MtpPacket::putUInt32(int offset, uint32_t value) {
    mBuffer[offset++] = (uint8_t)(value & 0xFF);
    mBuffer[offset++] = (uint8_t)((value >> 8) & 0xFF);
    mBuffer[offset++] = (uint8_t)((value >> 16) & 0xFF);
    mBuffer[offset++] = (uint8_t)((value >> 24) & 0xFF);
}

uint16_t MtpPacket::getContainerCode() const {
    return getUInt16(MTP_CONTAINER_CODE_OFFSET);
}

void MtpPacket::setContainerCode(uint16_t code) {
    putUInt16(MTP_CONTAINER_CODE_OFFSET, code);
}

uint16_t MtpPacket::getContainerType() const {
    return getUInt16(MTP_CONTAINER_TYPE_OFFSET);
}

MtpTransactionID MtpPacket::getTransactionID() const {
    return getUInt32(MTP_CONTAINER_TRANSACTION_ID_OFFSET);
}

void MtpPacket::setTransactionID(MtpTransactionID id) {
    putUInt32(MTP_CONTAINER_TRANSACTION_ID_OFFSET, id);
}

uint32_t MtpPacket::getParameter(int index) const {
    if (index < 1 || index > 5) {
        LOGE("index %d out of range in MtpPacket::getParameter", index);
        return 0;
    }
    return getUInt32(MTP_CONTAINER_PARAMETER_OFFSET + (index - 1) * sizeof(uint32_t));
}

void MtpPacket::setParameter(int index, uint32_t value) {
    if (index < 1 || index > 5) {
        LOGE("index %d out of range in MtpPacket::setParameter", index);
        return;
    }
    int offset = MTP_CONTAINER_PARAMETER_OFFSET + (index - 1) * sizeof(uint32_t);
    if (mPacketSize < offset + sizeof(uint32_t))
        mPacketSize = offset + sizeof(uint32_t);
    putUInt32(offset, value);
}

#ifdef MTP_HOST
int MtpPacket::transfer(struct usb_request* request) {
    int result = usb_device_bulk_transfer(request->dev,
                            request->endpoint,
                            request->buffer,
                            request->buffer_length,
                            0);
    request->actual_length = result;
    return result;
}
#endif

}  // namespace android
