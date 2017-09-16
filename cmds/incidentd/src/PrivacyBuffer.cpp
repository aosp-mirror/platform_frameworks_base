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



#include "PrivacyBuffer.h"
#include "io_util.h"

#include <android/util/protobuf.h>
#include <deque>

using namespace android::util;

/**
 * Write the field to buf based on the wire type, iterator will point to next field.
 * If skip is set to true, no data will be written to buf. Return number of bytes written.
 */
static size_t
write_field_or_skip(EncodedBuffer::iterator* iter, EncodedBuffer* buf, uint8_t wireType, bool skip)
{
    EncodedBuffer::Pointer snapshot = iter->rp()->copy();
    size_t bytesToWrite = 0;
    uint32_t varint = 0;
    switch (wireType) {
        case WIRE_TYPE_VARINT:
            varint = iter->readRawVarint();
            if(!skip) return buf->writeRawVarint(varint);
            break;
        case WIRE_TYPE_FIXED64:
            bytesToWrite = 8;
            break;
        case WIRE_TYPE_LENGTH_DELIMITED:
            bytesToWrite = iter->readRawVarint();
            if(!skip) buf->writeRawVarint(bytesToWrite);
            break;
        case WIRE_TYPE_FIXED32:
            bytesToWrite = 4;
            break;
    }
    if (skip) {
        iter->rp()->move(bytesToWrite);
    } else {
        for (size_t i=0; i<bytesToWrite; i++) {
            *buf->writeBuffer() = iter->next();
            buf->wp()->move();
        }
    }
    return skip ? 0 : iter->rp()->pos() - snapshot.pos();
}

/**
 * Strip next field based on its private policy and request spec, then stores data in buf.
 * Return NO_ERROR if succeeds, otherwise BAD_VALUE is returned to indicate bad data in FdBuffer.
 *
 * The iterator must point to the head of a protobuf formatted field for successful operation.
 * After exit with NO_ERROR, iterator points to the next protobuf field's head.
 */
static status_t
stripField(EncodedBuffer::iterator* iter, EncodedBuffer* buf, const Privacy* parentPolicy, const PrivacySpec& spec)
{
    if (!iter->hasNext() || parentPolicy == NULL) return BAD_VALUE;
    uint32_t varint = iter->readRawVarint();
    uint8_t wireType = read_wire_type(varint);
    uint32_t fieldId = read_field_id(varint);
    const Privacy* policy = parentPolicy->lookup(fieldId);

    if (policy == NULL || !policy->IsMessageType() || !policy->HasChildren()) {
        bool skip = !spec.CheckPremission(policy);
        size_t amt = buf->size();
        if (!skip) amt += buf->writeHeader(fieldId, wireType);
        amt += write_field_or_skip(iter, buf, wireType, skip); // point to head of next field
        return buf->size() != amt ? BAD_VALUE : NO_ERROR;
    }
    // current field is message type and its sub-fields have extra privacy policies
    deque<EncodedBuffer*> q;
    uint32_t msgSize = iter->readRawVarint();
    size_t finalSize = 0;
    EncodedBuffer::Pointer start = iter->rp()->copy();
    while (iter->rp()->pos() - start.pos() != msgSize) {
        EncodedBuffer* v = new EncodedBuffer();
        status_t err = stripField(iter, v, policy, spec);
        if (err != NO_ERROR) return err;
        if (v->size() == 0) continue;
        q.push_back(v);
        finalSize += v->size();
    }

    buf->writeHeader(fieldId, wireType);
    buf->writeRawVarint(finalSize);
    while (!q.empty()) {
        EncodedBuffer* subField = q.front();
        EncodedBuffer::iterator it = subField->begin();
        while (it.hasNext()) {
            *buf->writeBuffer() = it.next();
            buf->wp()->move();
        }
        q.pop_front();
        delete subField;
    }
    return NO_ERROR;
}

// ================================================================================
PrivacyBuffer::PrivacyBuffer(const Privacy* policy, EncodedBuffer::iterator& data)
        :mPolicy(policy),
         mData(data),
         mBuffer(0),
         mSize(0)
{
}

PrivacyBuffer::~PrivacyBuffer()
{
}

status_t
PrivacyBuffer::strip(const PrivacySpec& spec)
{
    // optimization when no strip happens
    if (mPolicy == NULL || !mPolicy->HasChildren() || spec.RequireAll()) {
        if (spec.CheckPremission(mPolicy)) mSize = mData.size();
        return NO_ERROR;
    }
    while (mData.hasNext()) {
        status_t err = stripField(&mData, &mBuffer, mPolicy, spec);
        if (err != NO_ERROR) return err;
    }
    if (mData.bytesRead() != mData.size()) return BAD_VALUE;
    mSize = mBuffer.size();
    mData.rp()->rewind(); // rewind the read pointer back to beginning after the strip.
    return NO_ERROR;
}

void
PrivacyBuffer::clear()
{
    mSize = 0;
    mBuffer.wp()->rewind();
}

size_t
PrivacyBuffer::size() const { return mSize; }

status_t
PrivacyBuffer::flush(int fd)
{
    status_t err = NO_ERROR;
    EncodedBuffer::iterator iter = size() == mData.size() ? mData : mBuffer.begin();
    while (iter.readBuffer() != NULL) {
        err = write_all(fd, iter.readBuffer(), iter.currentToRead());
        iter.rp()->move(iter.currentToRead());
        if (err != NO_ERROR) return err;
    }
    return NO_ERROR;
}
