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

#include "EncodedBuffer.h"
#include "io_util.h"
#include "protobuf.h"

#include <deque>

const size_t BUFFER_SIZE = 4 * 1024; // 4 KB

/**
 * Read varint from iterator, the iterator will point to next available byte.
 * Return the number of bytes of the varint.
 */
static uint32_t
read_raw_varint(FdBuffer::iterator* it)
{
    uint32_t val = 0;
    int i = 0;
    bool hasNext = true;
    while (hasNext) {
        hasNext = ((**it & 0x80) != 0);
        val += (**it & 0x7F) << (7*i);
        (*it)++;
        i++;
    }
    return val;
}

/**
 * Write the field to buf based on the wire type, iterator will point to next field.
 * If skip is set to true, no data will be written to buf. Return number of bytes written.
 */
static size_t
write_field_or_skip(FdBuffer::iterator* iter, vector<uint8_t>* buf, uint8_t wireType, bool skip)
{
    FdBuffer::iterator snapshot = iter->snapshot();
    size_t bytesToWrite = 0;
    uint32_t varint = 0;
    switch (wireType) {
        case WIRE_TYPE_VARINT:
            varint = read_raw_varint(iter);
            if(!skip) return write_raw_varint(buf, varint);
            break;
        case WIRE_TYPE_FIXED64:
            bytesToWrite = 8;
            break;
        case WIRE_TYPE_LENGTH_DELIMITED:
            bytesToWrite = read_raw_varint(iter);
            if(!skip) write_raw_varint(buf, bytesToWrite);
            break;
        case WIRE_TYPE_FIXED32:
            bytesToWrite = 4;
            break;
    }
    if (skip) {
        *iter += bytesToWrite;
    } else {
        for (size_t i=0; i<bytesToWrite; i++) {
            buf->push_back(**iter);
            (*iter)++;
        }
    }
    return skip ? 0 : *iter - snapshot;
}

/**
 * Strip next field based on its private policy and request spec, then stores data in buf.
 * Return NO_ERROR if succeeds, otherwise BAD_VALUE is returned to indicate bad data in FdBuffer.
 *
 * The iterator must point to the head of a protobuf formatted field for successful operation.
 * After exit with NO_ERROR, iterator points to the next protobuf field's head.
 */
static status_t
stripField(FdBuffer::iterator* iter, vector<uint8_t>* buf, const Privacy* parentPolicy, const PrivacySpec& spec)
{
    if (iter->outOfBound() || parentPolicy == NULL) return BAD_VALUE;

    uint32_t varint = read_raw_varint(iter);
    uint8_t wireType = read_wire_type(varint);
    uint32_t fieldId = read_field_id(varint);
    const Privacy* policy = parentPolicy->lookup(fieldId);

    if (policy == NULL || !policy->IsMessageType() || !policy->HasChildren()) {
        bool skip = !spec.CheckPremission(policy);
        size_t amt = buf->size();
        if (!skip) amt += write_header(buf, fieldId, wireType);
        amt += write_field_or_skip(iter, buf, wireType, skip); // point to head of next field
        return buf->size() != amt ? BAD_VALUE : NO_ERROR;
    }
    // current field is message type and its sub-fields have extra privacy policies
    deque<vector<uint8_t>> q;
    uint32_t msgSize = read_raw_varint(iter);
    size_t finalSize = 0;
    FdBuffer::iterator start = iter->snapshot();
    while ((*iter - start) != (int)msgSize) {
        vector<uint8_t> v;
        status_t err = stripField(iter, &v, policy, spec);
        if (err != NO_ERROR) return err;
        if (v.empty()) continue;
        q.push_back(v);
        finalSize += v.size();
    }

    write_header(buf, fieldId, wireType);
    write_raw_varint(buf, finalSize);
    buf->reserve(finalSize); // reserve the size of the field
    while (!q.empty()) {
        vector<uint8_t> subField = q.front();
        for (vector<uint8_t>::iterator it = subField.begin(); it != subField.end(); it++) {
            buf->push_back(*it);
        }
        q.pop_front();
    }
    return NO_ERROR;
}

// ================================================================================
EncodedBuffer::EncodedBuffer(const FdBuffer& buffer, const Privacy* policy)
        : mFdBuffer(buffer),
          mPolicy(policy),
          mBuffers(),
          mSize(0)
{
}

EncodedBuffer::~EncodedBuffer()
{
}

status_t
EncodedBuffer::strip(const PrivacySpec& spec)
{
    // optimization when no strip happens
    if (mPolicy == NULL || !mPolicy->HasChildren() || spec.RequireAll()) {
        if (spec.CheckPremission(mPolicy)) mSize = mFdBuffer.size();
        return NO_ERROR;
    }

    FdBuffer::iterator it = mFdBuffer.begin();
    vector<uint8_t> field;
    field.reserve(BUFFER_SIZE);

    while (it != mFdBuffer.end()) {
        status_t err = stripField(&it, &field, mPolicy, spec);
        if (err != NO_ERROR) return err;
        if (field.size() > BUFFER_SIZE) { // rotate to another chunk if buffer size exceeds
            mBuffers.push_back(field);
            mSize += field.size();
            field.clear();
        }
    }
    if (!field.empty()) {
        mBuffers.push_back(field);
        mSize += field.size();
    }
    return NO_ERROR;
}

void
EncodedBuffer::clear()
{
    mSize = 0;
    mBuffers.clear();
}

size_t
EncodedBuffer::size() const { return mSize; }

status_t
EncodedBuffer::flush(int fd)
{
    if (size() == mFdBuffer.size()) return mFdBuffer.flush(fd);

    for (vector<vector<uint8_t>>::iterator it = mBuffers.begin(); it != mBuffers.end(); it++) {
        status_t err = write_all(fd, it->data(), it->size());
        if (err != NO_ERROR) return err;
    }
    return NO_ERROR;
}

