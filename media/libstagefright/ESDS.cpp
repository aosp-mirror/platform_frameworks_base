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

//#define LOG_NDEBUG 0
#define LOG_TAG "ESDS"
#include <utils/Log.h>

#include "include/ESDS.h"

#include <string.h>

namespace android {

ESDS::ESDS(const void *data, size_t size)
    : mData(new uint8_t[size]),
      mSize(size),
      mInitCheck(NO_INIT),
      mDecoderSpecificOffset(0),
      mDecoderSpecificLength(0),
      mObjectTypeIndication(0) {
    memcpy(mData, data, size);

    mInitCheck = parse();
}

ESDS::~ESDS() {
    delete[] mData;
    mData = NULL;
}

status_t ESDS::InitCheck() const {
    return mInitCheck;
}

status_t ESDS::getObjectTypeIndication(uint8_t *objectTypeIndication) const {
    if (mInitCheck != OK) {
        return mInitCheck;
    }

    *objectTypeIndication = mObjectTypeIndication;

    return OK;
}

status_t ESDS::getCodecSpecificInfo(const void **data, size_t *size) const {
    if (mInitCheck != OK) {
        return mInitCheck;
    }

    *data = &mData[mDecoderSpecificOffset];
    *size = mDecoderSpecificLength;

    return OK;
}

status_t ESDS::skipDescriptorHeader(
        size_t offset, size_t size,
        uint8_t *tag, size_t *data_offset, size_t *data_size) const {
    if (size == 0) {
        return ERROR_MALFORMED;
    }

    *tag = mData[offset++];
    --size;

    *data_size = 0;
    bool more;
    do {
        if (size == 0) {
            return ERROR_MALFORMED;
        }

        uint8_t x = mData[offset++];
        --size;

        *data_size = (*data_size << 7) | (x & 0x7f);
        more = (x & 0x80) != 0;
    }
    while (more);

    ALOGV("tag=0x%02x data_size=%d", *tag, *data_size);

    if (*data_size > size) {
        return ERROR_MALFORMED;
    }

    *data_offset = offset;

    return OK;
}

status_t ESDS::parse() {
    uint8_t tag;
    size_t data_offset;
    size_t data_size;
    status_t err =
        skipDescriptorHeader(0, mSize, &tag, &data_offset, &data_size);

    if (err != OK) {
        return err;
    }

    if (tag != kTag_ESDescriptor) {
        return ERROR_MALFORMED;
    }

    return parseESDescriptor(data_offset, data_size);
}

status_t ESDS::parseESDescriptor(size_t offset, size_t size) {
    if (size < 3) {
        return ERROR_MALFORMED;
    }

    offset += 2;  // skip ES_ID
    size -= 2;

    unsigned streamDependenceFlag = mData[offset] & 0x80;
    unsigned URL_Flag = mData[offset] & 0x40;
    unsigned OCRstreamFlag = mData[offset] & 0x20;

    ++offset;
    --size;

    if (streamDependenceFlag) {
        offset += 2;
        size -= 2;
    }

    if (URL_Flag) {
        if (offset >= size) {
            return ERROR_MALFORMED;
        }
        unsigned URLlength = mData[offset];
        offset += URLlength + 1;
        size -= URLlength + 1;
    }

    if (OCRstreamFlag) {
        offset += 2;
        size -= 2;

        if ((offset >= size || mData[offset] != kTag_DecoderConfigDescriptor)
                && offset - 2 < size
                && mData[offset - 2] == kTag_DecoderConfigDescriptor) {
            // Content found "in the wild" had OCRstreamFlag set but was
            // missing OCR_ES_Id, the decoder config descriptor immediately
            // followed instead.
            offset -= 2;
            size += 2;

            ALOGW("Found malformed 'esds' atom, ignoring missing OCR_ES_Id.");
        }
    }

    if (offset >= size) {
        return ERROR_MALFORMED;
    }

    uint8_t tag;
    size_t sub_offset, sub_size;
    status_t err = skipDescriptorHeader(
            offset, size, &tag, &sub_offset, &sub_size);

    if (err != OK) {
        return err;
    }

    if (tag != kTag_DecoderConfigDescriptor) {
        return ERROR_MALFORMED;
    }

    err = parseDecoderConfigDescriptor(sub_offset, sub_size);

    return err;
}

status_t ESDS::parseDecoderConfigDescriptor(size_t offset, size_t size) {
    if (size < 13) {
        return ERROR_MALFORMED;
    }

    mObjectTypeIndication = mData[offset];

    offset += 13;
    size -= 13;

    if (size == 0) {
        mDecoderSpecificOffset = 0;
        mDecoderSpecificLength = 0;
        return OK;
    }

    uint8_t tag;
    size_t sub_offset, sub_size;
    status_t err = skipDescriptorHeader(
            offset, size, &tag, &sub_offset, &sub_size);

    if (err != OK) {
        return err;
    }

    if (tag != kTag_DecoderSpecificInfo) {
        return ERROR_MALFORMED;
    }

    mDecoderSpecificOffset = sub_offset;
    mDecoderSpecificLength = sub_size;

    return OK;
}

}  // namespace android

