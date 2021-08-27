/*
 * Copyright (C) 2006 The Android Open Source Project
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

#ifndef _ANDROID__DATABASE_WINDOW_H
#define _ANDROID__DATABASE_WINDOW_H

#include <inttypes.h>
#include <stddef.h>
#include <stdint.h>
#include <string>

#include "android-base/stringprintf.h"
#include "binder/Parcel.h"
#include "utils/String8.h"

#define LOG_WINDOW(...)

namespace android {

/**
 * This class stores a set of rows from a database in a buffer. Internally
 * data is structured as a "heap" of string/blob allocations at the bottom
 * of the memory region, and a "stack" of FieldSlot allocations at the top
 * of the memory region. Here's an example visual representation:
 *
 *   +----------------------------------------------------------------+
 *   |heap\0of\0strings\0                                 222211110000| ...
 *   +-------------------+--------------------------------+-------+---+
 *    ^                  ^                                ^       ^   ^     ^
 *    |                  |                                |       |   |     |
 *    |                  +- mAllocOffset    mSlotsOffset -+       |   |     |
 *    +- mData                                       mSlotsStart -+   |     |
 *                                                             mSize -+     |
 *                                                           mInflatedSize -+
 *
 * Strings are stored in UTF-8.
 */
class CursorWindow {
    CursorWindow();

public:
    /* Field types. */
    enum {
        FIELD_TYPE_NULL = 0,
        FIELD_TYPE_INTEGER = 1,
        FIELD_TYPE_FLOAT = 2,
        FIELD_TYPE_STRING = 3,
        FIELD_TYPE_BLOB = 4,
    };

    /* Opaque type that describes a field slot. */
    struct FieldSlot {
    private:
        int32_t type;
        union {
            double d;
            int64_t l;
            struct {
                uint32_t offset;
                uint32_t size;
            } buffer;
        } data;

        friend class CursorWindow;
    } __attribute((packed));

    ~CursorWindow();

    static status_t create(const String8& name, size_t size, CursorWindow** outCursorWindow);
    static status_t createFromParcel(Parcel* parcel, CursorWindow** outCursorWindow);

    status_t writeToParcel(Parcel* parcel);

    inline String8 name() { return mName; }
    inline size_t size() { return mSize; }
    inline size_t freeSpace() { return mSlotsOffset - mAllocOffset; }
    inline uint32_t getNumRows() { return mNumRows; }
    inline uint32_t getNumColumns() { return mNumColumns; }

    status_t clear();
    status_t setNumColumns(uint32_t numColumns);

    /**
     * Allocate a row slot and its directory.
     * The row is initialized will null entries for each field.
     */
    status_t allocRow();
    status_t freeLastRow();

    status_t putBlob(uint32_t row, uint32_t column, const void* value, size_t size);
    status_t putString(uint32_t row, uint32_t column, const char* value, size_t sizeIncludingNull);
    status_t putLong(uint32_t row, uint32_t column, int64_t value);
    status_t putDouble(uint32_t row, uint32_t column, double value);
    status_t putNull(uint32_t row, uint32_t column);

    /**
     * Gets the field slot at the specified row and column.
     * Returns null if the requested row or column is not in the window.
     */
    FieldSlot* getFieldSlot(uint32_t row, uint32_t column);

    inline int32_t getFieldSlotType(FieldSlot* fieldSlot) {
        return fieldSlot->type;
    }

    inline int64_t getFieldSlotValueLong(FieldSlot* fieldSlot) {
        return fieldSlot->data.l;
    }

    inline double getFieldSlotValueDouble(FieldSlot* fieldSlot) {
        return fieldSlot->data.d;
    }

    inline const char* getFieldSlotValueString(FieldSlot* fieldSlot,
            size_t* outSizeIncludingNull) {
        *outSizeIncludingNull = fieldSlot->data.buffer.size;
        return static_cast<char*>(offsetToPtr(
                fieldSlot->data.buffer.offset, fieldSlot->data.buffer.size));
    }

    inline const void* getFieldSlotValueBlob(FieldSlot* fieldSlot, size_t* outSize) {
        *outSize = fieldSlot->data.buffer.size;
        return offsetToPtr(fieldSlot->data.buffer.offset, fieldSlot->data.buffer.size);
    }

    inline std::string toString() const {
        return android::base::StringPrintf("CursorWindow{name=%s, fd=%d, size=%d, inflatedSize=%d, "
                "allocOffset=%d, slotsOffset=%d, numRows=%d, numColumns=%d}", mName.c_str(),
                mAshmemFd, mSize, mInflatedSize, mAllocOffset, mSlotsOffset, mNumRows, mNumColumns);
    }

private:
    String8 mName;
    int mAshmemFd = -1;
    void* mData = nullptr;
    /**
     * Pointer to the first FieldSlot, used to optimize the extremely
     * hot code path of getFieldSlot().
     */
    void* mSlotsStart = nullptr;
    void* mSlotsEnd = nullptr;
    uint32_t mSize = 0;
    /**
     * When a window starts as lightweight inline allocation, this value
     * holds the "full" size to be created after ashmem inflation.
     */
    uint32_t mInflatedSize = 0;
    /**
     * Offset to the top of the "heap" of string/blob allocations. By
     * storing these allocations at the bottom of our memory region we
     * avoid having to rewrite offsets when inflating.
     */
    uint32_t mAllocOffset = 0;
    /**
     * Offset to the bottom of the "stack" of FieldSlot allocations.
     */
    uint32_t mSlotsOffset = 0;
    uint32_t mNumRows = 0;
    uint32_t mNumColumns = 0;
    bool mReadOnly = false;

    void updateSlotsData();

    void* offsetToPtr(uint32_t offset, uint32_t bufferSize);
    uint32_t offsetFromPtr(void* ptr);

    /**
     * By default windows are lightweight inline allocations; this method
     * inflates the window into a larger ashmem region.
     */
    status_t maybeInflate();

    /**
     * Allocate a portion of the window.
     */
    status_t alloc(size_t size, uint32_t* outOffset);

    status_t putBlobOrString(uint32_t row, uint32_t column,
            const void* value, size_t size, int32_t type);
};

}; // namespace android

#endif
