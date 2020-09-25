/*
 * Copyright (C) 2006-2007 The Android Open Source Project
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

#undef LOG_TAG
#define LOG_TAG "CursorWindow"

#include <androidfw/CursorWindow.h>
#include <binder/Parcel.h>
#include <utils/Log.h>

#include <cutils/ashmem.h>
#include <sys/mman.h>

#include <assert.h>
#include <string.h>
#include <stdlib.h>

namespace android {

/**
 * By default windows are lightweight inline allocations of this size;
 * they're only inflated to ashmem regions when more space is needed.
 */
static constexpr const size_t kInlineSize = 16384;

CursorWindow::CursorWindow(const String8& name, int ashmemFd, void* data, size_t size,
                           size_t inflatedSize, bool readOnly) :
        mName(name), mAshmemFd(ashmemFd), mData(data), mSize(size),
        mInflatedSize(inflatedSize), mReadOnly(readOnly) {
    mHeader = static_cast<Header*>(mData);
}

CursorWindow::~CursorWindow() {
    if (mAshmemFd != -1) {
        ::munmap(mData, mSize);
        ::close(mAshmemFd);
    } else {
        free(mData);
    }
}

status_t CursorWindow::create(const String8& name, size_t inflatedSize,
                              CursorWindow** outCursorWindow) {
    *outCursorWindow = nullptr;

    size_t size = std::min(kInlineSize, inflatedSize);
    void* data = calloc(size, 1);
    if (!data) return NO_MEMORY;

    CursorWindow* window = new CursorWindow(name, -1, data, size,
                                            inflatedSize, false /*readOnly*/);
    status_t result = window->clear();
    if (!result) {
        LOG_WINDOW("Created new CursorWindow: freeOffset=%d, "
                "numRows=%d, numColumns=%d, mSize=%zu, mData=%p",
                window->mHeader->freeOffset,
                window->mHeader->numRows,
                window->mHeader->numColumns,
                window->mSize, window->mData);
        *outCursorWindow = window;
        return OK;
    }
    delete window;
    return result;
}

status_t CursorWindow::inflate() {
    // Shortcut when we can't expand any further
    if (mSize == mInflatedSize) return INVALID_OPERATION;

    String8 ashmemName("CursorWindow: ");
    ashmemName.append(mName);

    status_t result;
    int ashmemFd = ashmem_create_region(ashmemName.string(), mInflatedSize);
    if (ashmemFd < 0) {
        result = -errno;
        ALOGE("CursorWindow: ashmem_create_region() failed: errno=%d.", errno);
    } else {
        result = ashmem_set_prot_region(ashmemFd, PROT_READ | PROT_WRITE);
        if (result < 0) {
            ALOGE("CursorWindow: ashmem_set_prot_region() failed: errno=%d",errno);
        } else {
            void* data = ::mmap(NULL, mInflatedSize, PROT_READ | PROT_WRITE,
                                MAP_SHARED, ashmemFd, 0);
            if (data == MAP_FAILED) {
                result = -errno;
                ALOGE("CursorWindow: mmap() failed: errno=%d.", errno);
            } else {
                result = ashmem_set_prot_region(ashmemFd, PROT_READ);
                if (result < 0) {
                    ALOGE("CursorWindow: ashmem_set_prot_region() failed: errno=%d.", errno);
                } else {
                    // Move inline contents into new ashmem region
                    memcpy(data, mData, mSize);
                    free(mData);
                    mAshmemFd = ashmemFd;
                    mData = data;
                    mHeader = static_cast<Header*>(mData);
                    mSize = mInflatedSize;
                    LOG_WINDOW("Inflated CursorWindow: freeOffset=%d, "
                            "numRows=%d, numColumns=%d, mSize=%zu, mData=%p",
                            mHeader->freeOffset,
                            mHeader->numRows,
                            mHeader->numColumns,
                            mSize, mData);
                    return OK;
                }
            }
            ::munmap(data, mInflatedSize);
        }
        ::close(ashmemFd);
    }
    return result;
}

status_t CursorWindow::createFromParcel(Parcel* parcel, CursorWindow** outCursorWindow) {
    *outCursorWindow = nullptr;

    String8 name;
    status_t result = parcel->readString8(&name);
    if (result) return result;

    bool isAshmem;
    result = parcel->readBool(&isAshmem);
    if (result) return result;

    if (isAshmem) {
        return createFromParcelAshmem(parcel, name, outCursorWindow);
    } else {
        return createFromParcelInline(parcel, name, outCursorWindow);
    }
}

status_t CursorWindow::createFromParcelAshmem(Parcel* parcel, String8& name,
                                              CursorWindow** outCursorWindow) {
    status_t result;
    int actualSize;
    int ashmemFd = parcel->readFileDescriptor();
    if (ashmemFd == int(BAD_TYPE)) {
        result = BAD_TYPE;
        ALOGE("CursorWindow: readFileDescriptor() failed");
    } else {
        ssize_t size = ashmem_get_size_region(ashmemFd);
        if (size < 0) {
            result = UNKNOWN_ERROR;
            ALOGE("CursorWindow: ashmem_get_size_region() failed: errno=%d.", errno);
        } else {
            int dupAshmemFd = ::fcntl(ashmemFd, F_DUPFD_CLOEXEC, 0);
            if (dupAshmemFd < 0) {
                result = -errno;
                ALOGE("CursorWindow: fcntl() failed: errno=%d.", errno);
            } else {
                // the size of the ashmem descriptor can be modified between ashmem_get_size_region
                // call and mmap, so we'll check again immediately after memory is mapped
                void* data = ::mmap(NULL, size, PROT_READ, MAP_SHARED, dupAshmemFd, 0);
                if (data == MAP_FAILED) {
                    result = -errno;
                    ALOGE("CursorWindow: mmap() failed: errno=%d.", errno);
                } else if ((actualSize = ashmem_get_size_region(dupAshmemFd)) != size) {
                    ::munmap(data, size);
                    result = BAD_VALUE;
                    ALOGE("CursorWindow: ashmem_get_size_region() returned %d, expected %d"
                            " errno=%d",
                            actualSize, (int) size, errno);
                } else {
                    CursorWindow* window = new CursorWindow(name, dupAshmemFd,
                            data, size, size, true /*readOnly*/);
                    LOG_WINDOW("Created CursorWindow from ashmem parcel: freeOffset=%d, "
                            "numRows=%d, numColumns=%d, mSize=%zu, mData=%p",
                            window->mHeader->freeOffset,
                            window->mHeader->numRows,
                            window->mHeader->numColumns,
                            window->mSize, window->mData);
                    *outCursorWindow = window;
                    return OK;
                }
                ::close(dupAshmemFd);
            }
        }
    }
    *outCursorWindow = NULL;
    return result;
}

status_t CursorWindow::createFromParcelInline(Parcel* parcel, String8& name,
                                              CursorWindow** outCursorWindow) {
    uint32_t sentSize;
    status_t result = parcel->readUint32(&sentSize);
    if (result) return result;
    if (sentSize > kInlineSize) return NO_MEMORY;

    void* data = calloc(sentSize, 1);
    if (!data) return NO_MEMORY;

    result = parcel->read(data, sentSize);
    if (result) return result;

    CursorWindow* window = new CursorWindow(name, -1, data, sentSize,
                                            sentSize, true /*readOnly*/);
    LOG_WINDOW("Created CursorWindow from inline parcel: freeOffset=%d, "
            "numRows=%d, numColumns=%d, mSize=%zu, mData=%p",
            window->mHeader->freeOffset,
            window->mHeader->numRows,
            window->mHeader->numColumns,
            window->mSize, window->mData);
    *outCursorWindow = window;
    return OK;
}

status_t CursorWindow::writeToParcel(Parcel* parcel) {
        LOG_WINDOW("Writing CursorWindow: freeOffset=%d, "
                "numRows=%d, numColumns=%d, mSize=%zu, mData=%p",
                mHeader->freeOffset,
                mHeader->numRows,
                mHeader->numColumns,
                mSize, mData);

    status_t result = parcel->writeString8(mName);
    if (result) return result;

    if (mAshmemFd != -1) {
        result = parcel->writeBool(true);
        if (result) return result;
        return writeToParcelAshmem(parcel);
    } else {
        result = parcel->writeBool(false);
        if (result) return result;
        return writeToParcelInline(parcel);
    }
}

status_t CursorWindow::writeToParcelAshmem(Parcel* parcel) {
    return parcel->writeDupFileDescriptor(mAshmemFd);
}

status_t CursorWindow::writeToParcelInline(Parcel* parcel) {
    status_t result = parcel->writeUint32(mHeader->freeOffset);
    if (result) return result;

    return parcel->write(mData, mHeader->freeOffset);
}

status_t CursorWindow::clear() {
    if (mReadOnly) {
        return INVALID_OPERATION;
    }

    mHeader->freeOffset = sizeof(Header) + sizeof(RowSlotChunk);
    mHeader->firstChunkOffset = sizeof(Header);
    mHeader->numRows = 0;
    mHeader->numColumns = 0;

    RowSlotChunk* firstChunk = static_cast<RowSlotChunk*>(offsetToPtr(mHeader->firstChunkOffset));
    firstChunk->nextChunkOffset = 0;
    return OK;
}

status_t CursorWindow::setNumColumns(uint32_t numColumns) {
    if (mReadOnly) {
        return INVALID_OPERATION;
    }

    uint32_t cur = mHeader->numColumns;
    if ((cur > 0 || mHeader->numRows > 0) && cur != numColumns) {
        ALOGE("Trying to go from %d columns to %d", cur, numColumns);
        return INVALID_OPERATION;
    }
    mHeader->numColumns = numColumns;
    return OK;
}

status_t CursorWindow::allocRow() {
    if (mReadOnly) {
        return INVALID_OPERATION;
    }

    // Fill in the row slot
    RowSlot* rowSlot = allocRowSlot();
    if (rowSlot == NULL) {
        return NO_MEMORY;
    }
    uint32_t rowSlotOffset = offsetFromPtr(rowSlot);

    // Allocate the slots for the field directory
    size_t fieldDirSize = mHeader->numColumns * sizeof(FieldSlot);
    uint32_t fieldDirOffset = alloc(fieldDirSize, true /*aligned*/);
    if (!fieldDirOffset) {
        mHeader->numRows--;
        LOG_WINDOW("The row failed, so back out the new row accounting "
                "from allocRowSlot %d", mHeader->numRows);
        return NO_MEMORY;
    }
    FieldSlot* fieldDir = static_cast<FieldSlot*>(offsetToPtr(fieldDirOffset));
    memset(fieldDir, 0, fieldDirSize);

    LOG_WINDOW("Allocated row %u, rowSlot is at offset %u, fieldDir is %zu bytes at offset %u\n",
            mHeader->numRows - 1, rowSlotOffset, fieldDirSize, fieldDirOffset);
    rowSlot = static_cast<RowSlot*>(offsetToPtr(rowSlotOffset));
    rowSlot->offset = fieldDirOffset;
    return OK;
}

status_t CursorWindow::freeLastRow() {
    if (mReadOnly) {
        return INVALID_OPERATION;
    }

    if (mHeader->numRows > 0) {
        mHeader->numRows--;
    }
    return OK;
}

uint32_t CursorWindow::alloc(size_t size, bool aligned) {
    uint32_t padding;
    if (aligned) {
        // 4 byte alignment
        padding = (~mHeader->freeOffset + 1) & 3;
    } else {
        padding = 0;
    }

    uint32_t offset = mHeader->freeOffset + padding;
    uint32_t nextFreeOffset = offset + size;
    if (nextFreeOffset > mSize) {
        // Try inflating to ashmem before finally giving up
        inflate();
        if (nextFreeOffset > mSize) {
            ALOGW("Window is full: requested allocation %zu bytes, "
                    "free space %zu bytes, window size %zu bytes",
                    size, freeSpace(), mSize);
            return 0;
        }
    }

    mHeader->freeOffset = nextFreeOffset;
    return offset;
}

CursorWindow::RowSlot* CursorWindow::getRowSlot(uint32_t row) {
    uint32_t chunkPos = row;
    RowSlotChunk* chunk = static_cast<RowSlotChunk*>(
            offsetToPtr(mHeader->firstChunkOffset));
    while (chunkPos >= ROW_SLOT_CHUNK_NUM_ROWS) {
        chunk = static_cast<RowSlotChunk*>(offsetToPtr(chunk->nextChunkOffset));
        chunkPos -= ROW_SLOT_CHUNK_NUM_ROWS;
    }
    return &chunk->slots[chunkPos];
}

CursorWindow::RowSlot* CursorWindow::allocRowSlot() {
    uint32_t chunkPos = mHeader->numRows;
    RowSlotChunk* chunk = static_cast<RowSlotChunk*>(
            offsetToPtr(mHeader->firstChunkOffset));
    while (chunkPos > ROW_SLOT_CHUNK_NUM_ROWS) {
        chunk = static_cast<RowSlotChunk*>(offsetToPtr(chunk->nextChunkOffset));
        chunkPos -= ROW_SLOT_CHUNK_NUM_ROWS;
    }
    if (chunkPos == ROW_SLOT_CHUNK_NUM_ROWS) {
        if (!chunk->nextChunkOffset) {
            uint32_t chunkOffset = offsetFromPtr(chunk);
            uint32_t newChunk = alloc(sizeof(RowSlotChunk), true /*aligned*/);
            chunk = static_cast<RowSlotChunk*>(offsetToPtr(chunkOffset));
            chunk->nextChunkOffset = newChunk;
            if (!chunk->nextChunkOffset) {
                return NULL;
            }
        }
        chunk = static_cast<RowSlotChunk*>(offsetToPtr(chunk->nextChunkOffset));
        chunk->nextChunkOffset = 0;
        chunkPos = 0;
    }
    mHeader->numRows += 1;
    return &chunk->slots[chunkPos];
}

CursorWindow::FieldSlot* CursorWindow::getFieldSlot(uint32_t row, uint32_t column) {
    if (row >= mHeader->numRows || column >= mHeader->numColumns) {
        ALOGE("Failed to read row %d, column %d from a CursorWindow which "
                "has %d rows, %d columns.",
                row, column, mHeader->numRows, mHeader->numColumns);
        return NULL;
    }
    RowSlot* rowSlot = getRowSlot(row);
    if (!rowSlot) {
        ALOGE("Failed to find rowSlot for row %d.", row);
        return NULL;
    }
    FieldSlot* fieldDir = static_cast<FieldSlot*>(offsetToPtr(rowSlot->offset));
    return &fieldDir[column];
}

status_t CursorWindow::putBlob(uint32_t row, uint32_t column, const void* value, size_t size) {
    return putBlobOrString(row, column, value, size, FIELD_TYPE_BLOB);
}

status_t CursorWindow::putString(uint32_t row, uint32_t column, const char* value,
        size_t sizeIncludingNull) {
    return putBlobOrString(row, column, value, sizeIncludingNull, FIELD_TYPE_STRING);
}

status_t CursorWindow::putBlobOrString(uint32_t row, uint32_t column,
        const void* value, size_t size, int32_t type) {
    if (mReadOnly) {
        return INVALID_OPERATION;
    }

    FieldSlot* fieldSlot = getFieldSlot(row, column);
    if (!fieldSlot) {
        return BAD_VALUE;
    }
    uint32_t fieldSlotOffset = offsetFromPtr(fieldSlot);

    uint32_t offset = alloc(size);
    if (!offset) {
        return NO_MEMORY;
    }

    memcpy(offsetToPtr(offset), value, size);

    fieldSlot = static_cast<FieldSlot*>(offsetToPtr(fieldSlotOffset));
    fieldSlot->type = type;
    fieldSlot->data.buffer.offset = offset;
    fieldSlot->data.buffer.size = size;
    return OK;
}

status_t CursorWindow::putLong(uint32_t row, uint32_t column, int64_t value) {
    if (mReadOnly) {
        return INVALID_OPERATION;
    }

    FieldSlot* fieldSlot = getFieldSlot(row, column);
    if (!fieldSlot) {
        return BAD_VALUE;
    }

    fieldSlot->type = FIELD_TYPE_INTEGER;
    fieldSlot->data.l = value;
    return OK;
}

status_t CursorWindow::putDouble(uint32_t row, uint32_t column, double value) {
    if (mReadOnly) {
        return INVALID_OPERATION;
    }

    FieldSlot* fieldSlot = getFieldSlot(row, column);
    if (!fieldSlot) {
        return BAD_VALUE;
    }

    fieldSlot->type = FIELD_TYPE_FLOAT;
    fieldSlot->data.d = value;
    return OK;
}

status_t CursorWindow::putNull(uint32_t row, uint32_t column) {
    if (mReadOnly) {
        return INVALID_OPERATION;
    }

    FieldSlot* fieldSlot = getFieldSlot(row, column);
    if (!fieldSlot) {
        return BAD_VALUE;
    }

    fieldSlot->type = FIELD_TYPE_NULL;
    fieldSlot->data.buffer.offset = 0;
    fieldSlot->data.buffer.size = 0;
    return OK;
}

}; // namespace android
