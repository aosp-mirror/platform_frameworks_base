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
#define LOG_TAG "MetaData"
#include <utils/Log.h>

#include <stdlib.h>
#include <string.h>

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MetaData.h>

namespace android {

MetaData::MetaData() {
}

MetaData::MetaData(const MetaData &from)
    : RefBase(),
      mItems(from.mItems) {
}

MetaData::~MetaData() {
    clear();
}

void MetaData::clear() {
    mItems.clear();
}

bool MetaData::remove(uint32_t key) {
    ssize_t i = mItems.indexOfKey(key);

    if (i < 0) {
        return false;
    }

    mItems.removeItemsAt(i);

    return true;
}

bool MetaData::setCString(uint32_t key, const char *value) {
    return setData(key, TYPE_C_STRING, value, strlen(value) + 1);
}

bool MetaData::setInt32(uint32_t key, int32_t value) {
    return setData(key, TYPE_INT32, &value, sizeof(value));
}

bool MetaData::setInt64(uint32_t key, int64_t value) {
    return setData(key, TYPE_INT64, &value, sizeof(value));
}

bool MetaData::setFloat(uint32_t key, float value) {
    return setData(key, TYPE_FLOAT, &value, sizeof(value));
}

bool MetaData::setPointer(uint32_t key, void *value) {
    return setData(key, TYPE_POINTER, &value, sizeof(value));
}

bool MetaData::setRect(
        uint32_t key,
        int32_t left, int32_t top,
        int32_t right, int32_t bottom) {
    Rect r;
    r.mLeft = left;
    r.mTop = top;
    r.mRight = right;
    r.mBottom = bottom;

    return setData(key, TYPE_RECT, &r, sizeof(r));
}

bool MetaData::findCString(uint32_t key, const char **value) {
    uint32_t type;
    const void *data;
    size_t size;
    if (!findData(key, &type, &data, &size) || type != TYPE_C_STRING) {
        return false;
    }

    *value = (const char *)data;

    return true;
}

bool MetaData::findInt32(uint32_t key, int32_t *value) {
    uint32_t type;
    const void *data;
    size_t size;
    if (!findData(key, &type, &data, &size) || type != TYPE_INT32) {
        return false;
    }

    CHECK_EQ(size, sizeof(*value));

    *value = *(int32_t *)data;

    return true;
}

bool MetaData::findInt64(uint32_t key, int64_t *value) {
    uint32_t type;
    const void *data;
    size_t size;
    if (!findData(key, &type, &data, &size) || type != TYPE_INT64) {
        return false;
    }

    CHECK_EQ(size, sizeof(*value));

    *value = *(int64_t *)data;

    return true;
}

bool MetaData::findFloat(uint32_t key, float *value) {
    uint32_t type;
    const void *data;
    size_t size;
    if (!findData(key, &type, &data, &size) || type != TYPE_FLOAT) {
        return false;
    }

    CHECK_EQ(size, sizeof(*value));

    *value = *(float *)data;

    return true;
}

bool MetaData::findPointer(uint32_t key, void **value) {
    uint32_t type;
    const void *data;
    size_t size;
    if (!findData(key, &type, &data, &size) || type != TYPE_POINTER) {
        return false;
    }

    CHECK_EQ(size, sizeof(*value));

    *value = *(void **)data;

    return true;
}

bool MetaData::findRect(
        uint32_t key,
        int32_t *left, int32_t *top,
        int32_t *right, int32_t *bottom) {
    uint32_t type;
    const void *data;
    size_t size;
    if (!findData(key, &type, &data, &size) || type != TYPE_RECT) {
        return false;
    }

    CHECK_EQ(size, sizeof(Rect));

    const Rect *r = (const Rect *)data;
    *left = r->mLeft;
    *top = r->mTop;
    *right = r->mRight;
    *bottom = r->mBottom;

    return true;
}

bool MetaData::setData(
        uint32_t key, uint32_t type, const void *data, size_t size) {
    bool overwrote_existing = true;

    ssize_t i = mItems.indexOfKey(key);
    if (i < 0) {
        typed_data item;
        i = mItems.add(key, item);

        overwrote_existing = false;
    }

    typed_data &item = mItems.editValueAt(i);

    item.setData(type, data, size);

    return overwrote_existing;
}

bool MetaData::findData(uint32_t key, uint32_t *type,
                        const void **data, size_t *size) const {
    ssize_t i = mItems.indexOfKey(key);

    if (i < 0) {
        return false;
    }

    const typed_data &item = mItems.valueAt(i);

    item.getData(type, data, size);

    return true;
}

MetaData::typed_data::typed_data()
    : mType(0),
      mSize(0) {
}

MetaData::typed_data::~typed_data() {
    clear();
}

MetaData::typed_data::typed_data(const typed_data &from)
    : mType(from.mType),
      mSize(0) {
    allocateStorage(from.mSize);
    memcpy(storage(), from.storage(), mSize);
}

MetaData::typed_data &MetaData::typed_data::operator=(
        const MetaData::typed_data &from) {
    if (this != &from) {
        clear();
        mType = from.mType;
        allocateStorage(from.mSize);
        memcpy(storage(), from.storage(), mSize);
    }

    return *this;
}

void MetaData::typed_data::clear() {
    freeStorage();

    mType = 0;
}

void MetaData::typed_data::setData(
        uint32_t type, const void *data, size_t size) {
    clear();

    mType = type;
    allocateStorage(size);
    memcpy(storage(), data, size);
}

void MetaData::typed_data::getData(
        uint32_t *type, const void **data, size_t *size) const {
    *type = mType;
    *size = mSize;
    *data = storage();
}

void MetaData::typed_data::allocateStorage(size_t size) {
    mSize = size;

    if (usesReservoir()) {
        return;
    }

    u.ext_data = malloc(mSize);
}

void MetaData::typed_data::freeStorage() {
    if (!usesReservoir()) {
        if (u.ext_data) {
            free(u.ext_data);
        }
    }

    mSize = 0;
}

String8 MetaData::typed_data::asString() const {
    String8 out;
    const void *data = storage();
    switch(mType) {
        case TYPE_NONE:
            out = String8::format("no type, size %d)", mSize);
            break;
        case TYPE_C_STRING:
            out = String8::format("(char*) %s", (const char *)data);
            break;
        case TYPE_INT32:
            out = String8::format("(int32_t) %d", *(int32_t *)data);
            break;
        case TYPE_INT64:
            out = String8::format("(int64_t) %lld", *(int64_t *)data);
            break;
        case TYPE_FLOAT:
            out = String8::format("(float) %f", *(float *)data);
            break;
        case TYPE_POINTER:
            out = String8::format("(void*) %p", *(void **)data);
            break;
        case TYPE_RECT:
        {
            const Rect *r = (const Rect *)data;
            out = String8::format("Rect(%d, %d, %d, %d)",
                                  r->mLeft, r->mTop, r->mRight, r->mBottom);
            break;
        }

        default:
            out = String8::format("(unknown type %d, size %d)", mType, mSize);
            break;
    }
    return out;
}

static void MakeFourCCString(uint32_t x, char *s) {
    s[0] = x >> 24;
    s[1] = (x >> 16) & 0xff;
    s[2] = (x >> 8) & 0xff;
    s[3] = x & 0xff;
    s[4] = '\0';
}

void MetaData::dumpToLog() const {
    for (int i = mItems.size(); --i >= 0;) {
        int32_t key = mItems.keyAt(i);
        char cc[5];
        MakeFourCCString(key, cc);
        const typed_data &item = mItems.valueAt(i);
        ALOGI("%s: %s", cc, item.asString().string());
    }
}

}  // namespace android

