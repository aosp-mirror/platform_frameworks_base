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
#define LOG_TAG "Metadata"
#include <utils/Log.h>

#include <sys/types.h>
#include <media/Metadata.h>
#include <binder/Parcel.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>

// This file contains code to serialize Metadata triples (key, type,
// value) into a parcel. The Parcel is destinated to be decoded by the
// Metadata.java class.

namespace {
// All these constants below must be kept in sync with Metadata.java.
enum MetadataId {
    FIRST_SYSTEM_ID = 1,
    LAST_SYSTEM_ID = 31,
    FIRST_CUSTOM_ID = 8192
};

// Types
enum Types {
    STRING_VAL = 1,
    INTEGER_VAL,
    BOOLEAN_VAL,
    LONG_VAL,
    DOUBLE_VAL,
    DATE_VAL,
    BYTE_ARRAY_VAL,
};

const size_t kRecordHeaderSize = 3 * sizeof(int32_t);
const int32_t kMetaMarker = 0x4d455441;  // 'M' 'E' 'T' 'A'

}  // anonymous namespace

namespace android {
namespace media {

Metadata::Metadata(Parcel *p)
    :mData(p),
     mBegin(p->dataPosition()) { }

Metadata::~Metadata() { }

void Metadata::resetParcel()
{
    mData->setDataPosition(mBegin);
}

// Update the 4 bytes int at the beginning of the parcel which holds
// the number of bytes written so far.
void Metadata::updateLength()
{
    const size_t end = mData->dataPosition();

    mData->setDataPosition(mBegin);
    mData->writeInt32(end - mBegin);
    mData->setDataPosition(end);
}

// Write the header. The java layer will look for the marker.
bool Metadata::appendHeader()
{
    bool ok = true;

    // Placeholder for the length of the metadata
    ok = ok && mData->writeInt32(-1) == OK;
    ok = ok && mData->writeInt32(kMetaMarker) == OK;
    return ok;
}

bool Metadata::appendBool(int key, bool val)
{
    if (!checkKey(key)) {
        return false;
    }

    const size_t begin = mData->dataPosition();
    bool ok = true;

    // 4 int32s: size, key, type, value.
    ok = ok && mData->writeInt32(4 * sizeof(int32_t)) == OK;
    ok = ok && mData->writeInt32(key) == OK;
    ok = ok && mData->writeInt32(BOOLEAN_VAL) == OK;
    ok = ok && mData->writeInt32(val ? 1 : 0) == OK;
    if (!ok) {
        mData->setDataPosition(begin);
    }
    return ok;
}

bool Metadata::appendInt32(int key, int32_t val)
{
    if (!checkKey(key)) {
        return false;
    }

    const size_t begin = mData->dataPosition();
    bool ok = true;

    // 4 int32s: size, key, type, value.
    ok = ok && mData->writeInt32(4 * sizeof(int32_t)) == OK;
    ok = ok && mData->writeInt32(key) == OK;
    ok = ok && mData->writeInt32(INTEGER_VAL) == OK;
    ok = ok && mData->writeInt32(val) == OK;
    if (!ok) {
        mData->setDataPosition(begin);
    }
    return ok;
}

// Check the key (i.e metadata id) is valid if it is a system one.
// Loop over all the exiting ones in the Parcel to check for duplicate
// (not allowed).
bool Metadata::checkKey(int key)
{
    if (key < FIRST_SYSTEM_ID ||
        (LAST_SYSTEM_ID < key && key < FIRST_CUSTOM_ID)) {
        ALOGE("Bad key %d", key);
        return false;
    }
    size_t curr = mData->dataPosition();
    // Loop over the keys to check if it has been used already.
    mData->setDataPosition(mBegin);

    bool error = false;
    size_t left = curr - mBegin;
    while (left > 0) {
        size_t pos = mData->dataPosition();
        size_t size = mData->readInt32();
        if (size < kRecordHeaderSize || size > left) {
            error = true;
            break;
        }
        if (mData->readInt32() == key) {
            ALOGE("Key exists already %d", key);
            error = true;
            break;
        }
        mData->setDataPosition(pos + size);
        left -= size;
    }
    mData->setDataPosition(curr);
    return !error;
}

}  // namespace android::media
}  // namespace android
