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

#define LOG_TAG "incidentd"

#include "PrivacyBuffer.h"
#include "io_util.h"

#include <android/util/protobuf.h>
#include <cutils/log.h>

using namespace android::util;

const bool DEBUG = false;

/**
 * Write the field to buf based on the wire type, iterator will point to next field.
 * If skip is set to true, no data will be written to buf. Return number of bytes written.
 */
void
PrivacyBuffer::writeFieldOrSkip(uint32_t fieldTag, bool skip)
{
    if (DEBUG) ALOGD("%s field %d (wiretype = %d)", skip ? "skip" : "write",
        read_field_id(fieldTag), read_wire_type(fieldTag));

    uint8_t wireType = read_wire_type(fieldTag);
    size_t bytesToWrite = 0;
    uint32_t varint = 0;

    switch (wireType) {
        case WIRE_TYPE_VARINT:
            varint = mData.readRawVarint();
            if (!skip) {
                mProto.writeRawVarint(fieldTag);
                mProto.writeRawVarint(varint);
            }
            return;
        case WIRE_TYPE_FIXED64:
            if (!skip) mProto.writeRawVarint(fieldTag);
            bytesToWrite = 8;
            break;
        case WIRE_TYPE_LENGTH_DELIMITED:
            bytesToWrite = mData.readRawVarint();
            if(!skip) mProto.writeLengthDelimitedHeader(read_field_id(fieldTag), bytesToWrite);
            break;
        case WIRE_TYPE_FIXED32:
            if (!skip) mProto.writeRawVarint(fieldTag);
            bytesToWrite = 4;
            break;
    }
    if (DEBUG) ALOGD("%s %d bytes of data", skip ? "skip" : "write", (int)bytesToWrite);
    if (skip) {
        mData.rp()->move(bytesToWrite);
    } else {
        for (size_t i=0; i<bytesToWrite; i++) {
            mProto.writeRawByte(mData.next());
        }
    }
}

/**
 * Strip next field based on its private policy and request spec, then stores data in buf.
 * Return NO_ERROR if succeeds, otherwise BAD_VALUE is returned to indicate bad data in FdBuffer.
 *
 * The iterator must point to the head of a protobuf formatted field for successful operation.
 * After exit with NO_ERROR, iterator points to the next protobuf field's head.
 */
status_t
PrivacyBuffer::stripField(const Privacy* parentPolicy, const PrivacySpec& spec)
{
    if (!mData.hasNext() || parentPolicy == NULL) return BAD_VALUE;
    uint32_t fieldTag = mData.readRawVarint();
    const Privacy* policy = lookup(parentPolicy, read_field_id(fieldTag));

    if (policy == NULL || policy->children == NULL) {
        if (DEBUG) ALOGD("Not a message field %d: dest(%d)", read_field_id(fieldTag),
            policy != NULL ? policy->dest : parentPolicy->dest);

        bool skip = !spec.CheckPremission(policy, parentPolicy->dest);
        // iterator will point to head of next field
        writeFieldOrSkip(fieldTag, skip);
        return NO_ERROR;
    }
    // current field is message type and its sub-fields have extra privacy policies
    uint32_t msgSize = mData.readRawVarint();
    EncodedBuffer::Pointer start = mData.rp()->copy();
    long long token = mProto.start(encode_field_id(policy));
    while (mData.rp()->pos() - start.pos() != msgSize) {
        status_t err = stripField(policy, spec);
        if (err != NO_ERROR) return err;
    }
    mProto.end(token);
    return NO_ERROR;
}

// ================================================================================
PrivacyBuffer::PrivacyBuffer(const Privacy* policy, EncodedBuffer::iterator& data)
        :mPolicy(policy),
         mData(data),
         mProto(),
         mSize(0)
{
}

PrivacyBuffer::~PrivacyBuffer()
{
}

status_t
PrivacyBuffer::strip(const PrivacySpec& spec)
{
    if (DEBUG) ALOGD("Strip with spec %d", spec.dest);
    // optimization when no strip happens
    if (mPolicy == NULL || mPolicy->children == NULL || spec.RequireAll()) {
        if (spec.CheckPremission(mPolicy)) mSize = mData.size();
        return NO_ERROR;
    }
    while (mData.hasNext()) {
        status_t err = stripField(mPolicy, spec);
        if (err != NO_ERROR) return err;
    }
    if (mData.bytesRead() != mData.size()) return BAD_VALUE;
    mSize = mProto.size();
    mData.rp()->rewind(); // rewind the read pointer back to beginning after the strip.
    return NO_ERROR;
}

void
PrivacyBuffer::clear()
{
    mSize = 0;
    mProto.clear();
}

size_t
PrivacyBuffer::size() const { return mSize; }

status_t
PrivacyBuffer::flush(int fd)
{
    status_t err = NO_ERROR;
    EncodedBuffer::iterator iter = size() == mData.size() ? mData : mProto.data();
    while (iter.readBuffer() != NULL) {
        err = write_all(fd, iter.readBuffer(), iter.currentToRead());
        iter.rp()->move(iter.currentToRead());
        if (err != NO_ERROR) return err;
    }
    return NO_ERROR;
}
