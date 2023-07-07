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

#define LOG_TAG "CursorWindow"

#include <androidfw/CursorWindow.h>

#include <sys/mman.h>

#include "android-base/logging.h"
#include "cutils/ashmem.h"

namespace android {

/**
 * By default windows are lightweight inline allocations of this size;
 * they're only inflated to ashmem regions when more space is needed.
 */
static constexpr const size_t kInlineSize = 16384;

static constexpr const size_t kSlotShift = 4;
static constexpr const size_t kSlotSizeBytes = 1 << kSlotShift;

CursorWindow::CursorWindow() {
}

CursorWindow::~CursorWindow() {
    if (mAshmemFd != -1) {
        ::munmap(mData, mSize);
        ::close(mAshmemFd);
    } else {
        free(mData);
    }
}

status_t CursorWindow::create(const String8 &name, size_t inflatedSize, CursorWindow **outWindow) {
    *outWindow = nullptr;

    CursorWindow* window = new CursorWindow();
    if (!window) goto fail;

    window->mName = name;
    window->mSize = std::min(kInlineSize, inflatedSize);
    window->mInflatedSize = inflatedSize;
    window->mData = malloc(window->mSize);
    if (!window->mData) goto fail;
    window->mReadOnly = false;

    window->clear();
    window->updateSlotsData();

    *outWindow = window;
    return OK;

fail:
    LOG(ERROR) << "Failed create";
fail_silent:
    delete window;
    return UNKNOWN_ERROR;
}

status_t CursorWindow::maybeInflate() {
    int ashmemFd = 0;
    void* newData = nullptr;

    // Bail early when we can't expand any further
    if (mReadOnly || mSize == mInflatedSize) {
        return INVALID_OPERATION;
    }

    String8 ashmemName("CursorWindow: ");
    ashmemName.append(mName);

    ashmemFd = ashmem_create_region(ashmemName.string(), mInflatedSize);
    if (ashmemFd < 0) {
        PLOG(ERROR) << "Failed ashmem_create_region";
        goto fail_silent;
    }

    if (ashmem_set_prot_region(ashmemFd, PROT_READ | PROT_WRITE) < 0) {
        PLOG(ERROR) << "Failed ashmem_set_prot_region";
        goto fail_silent;
    }

    newData = ::mmap(nullptr, mInflatedSize, PROT_READ | PROT_WRITE, MAP_SHARED, ashmemFd, 0);
    if (newData == MAP_FAILED) {
        PLOG(ERROR) << "Failed mmap";
        goto fail_silent;
    }

    if (ashmem_set_prot_region(ashmemFd, PROT_READ) < 0) {
        PLOG(ERROR) << "Failed ashmem_set_prot_region";
        goto fail_silent;
    }

    {
        // Migrate existing contents into new ashmem region
        uint32_t slotsSize = sizeOfSlots();
        uint32_t newSlotsOffset = mInflatedSize - slotsSize;
        memcpy(static_cast<uint8_t*>(newData),
                static_cast<uint8_t*>(mData), mAllocOffset);
        memcpy(static_cast<uint8_t*>(newData) + newSlotsOffset,
                static_cast<uint8_t*>(mData) + mSlotsOffset, slotsSize);

        free(mData);
        mAshmemFd = ashmemFd;
        mData = newData;
        mSize = mInflatedSize;
        mSlotsOffset = newSlotsOffset;

        updateSlotsData();
    }

    LOG(DEBUG) << "Inflated: " << this->toString();
    return OK;

fail:
    LOG(ERROR) << "Failed maybeInflate";
fail_silent:
    ::munmap(newData, mInflatedSize);
    ::close(ashmemFd);
    return UNKNOWN_ERROR;
}

status_t CursorWindow::createFromParcel(Parcel* parcel, CursorWindow** outWindow) {
    *outWindow = nullptr;

    CursorWindow* window = new CursorWindow();
    if (!window) goto fail;

    if (parcel->readString8(&window->mName)) goto fail;
    if (parcel->readUint32(&window->mNumRows)) goto fail;
    if (parcel->readUint32(&window->mNumColumns)) goto fail;
    if (parcel->readUint32(&window->mSize)) goto fail;

    if ((window->mNumRows * window->mNumColumns * kSlotSizeBytes) > window->mSize) {
        LOG(ERROR) << "Unexpected size " << window->mSize << " for " << window->mNumRows
                << " rows and " << window->mNumColumns << " columns";
        goto fail_silent;
    }

    bool isAshmem;
    if (parcel->readBool(&isAshmem)) goto fail;
    if (isAshmem) {
        window->mAshmemFd = parcel->readFileDescriptor();
        if (window->mAshmemFd < 0) {
            LOG(ERROR) << "Failed readFileDescriptor";
            goto fail_silent;
        }

        window->mAshmemFd = ::fcntl(window->mAshmemFd, F_DUPFD_CLOEXEC, 0);
        if (window->mAshmemFd < 0) {
            PLOG(ERROR) << "Failed F_DUPFD_CLOEXEC";
            goto fail_silent;
        }

        window->mData = ::mmap(nullptr, window->mSize, PROT_READ, MAP_SHARED, window->mAshmemFd, 0);
        if (window->mData == MAP_FAILED) {
            PLOG(ERROR) << "Failed mmap";
            goto fail_silent;
        }
    } else {
        window->mAshmemFd = -1;

        if (window->mSize > kInlineSize) {
            LOG(ERROR) << "Unexpected size " << window->mSize << " for inline window";
            goto fail_silent;
        }

        window->mData = malloc(window->mSize);
        if (!window->mData) goto fail;

        if (parcel->read(window->mData, window->mSize)) goto fail;
    }

    // We just came from a remote source, so we're read-only
    // and we can't inflate ourselves
    window->mInflatedSize = window->mSize;
    window->mReadOnly = true;

    window->updateSlotsData();

    LOG(DEBUG) << "Created from parcel: " << window->toString();
    *outWindow = window;
    return OK;

fail:
    LOG(ERROR) << "Failed createFromParcel";
fail_silent:
    delete window;
    return UNKNOWN_ERROR;
}

status_t CursorWindow::writeToParcel(Parcel* parcel) {
    LOG(DEBUG) << "Writing to parcel: " << this->toString();

    if (parcel->writeString8(mName)) goto fail;
    if (parcel->writeUint32(mNumRows)) goto fail;
    if (parcel->writeUint32(mNumColumns)) goto fail;
    if (mAshmemFd != -1) {
        if (parcel->writeUint32(mSize)) goto fail;
        if (parcel->writeBool(true)) goto fail;
        if (parcel->writeDupFileDescriptor(mAshmemFd)) goto fail;
    } else {
        // Since we know we're going to be read-only on the remote side,
        // we can compact ourselves on the wire.
        size_t slotsSize = sizeOfSlots();
        size_t compactedSize = sizeInUse();
        if (parcel->writeUint32(compactedSize)) goto fail;
        if (parcel->writeBool(false)) goto fail;
        void* dest = parcel->writeInplace(compactedSize);
        if (!dest) goto fail;
        memcpy(static_cast<uint8_t*>(dest),
                static_cast<uint8_t*>(mData), mAllocOffset);
        memcpy(static_cast<uint8_t*>(dest) + compactedSize - slotsSize,
                static_cast<uint8_t*>(mData) + mSlotsOffset, slotsSize);
    }
    return OK;

fail:
    LOG(ERROR) << "Failed writeToParcel";
fail_silent:
    return UNKNOWN_ERROR;
}

status_t CursorWindow::clear() {
    if (mReadOnly) {
        return INVALID_OPERATION;
    }
    mAllocOffset = 0;
    mSlotsOffset = mSize;
    mNumRows = 0;
    mNumColumns = 0;
    return OK;
}

void CursorWindow::updateSlotsData() {
    mSlotsStart = static_cast<uint8_t*>(mData) + mSize - kSlotSizeBytes;
    mSlotsEnd = static_cast<uint8_t*>(mData) + mSlotsOffset;
}

void* CursorWindow::offsetToPtr(uint32_t offset, uint32_t bufferSize = 0) {
    if (offset > mSize) {
        LOG(ERROR) << "Offset " << offset
                << " out of bounds, max value " << mSize;
        return nullptr;
    }
    if (offset + bufferSize > mSize) {
        LOG(ERROR) << "End offset " << (offset + bufferSize)
                << " out of bounds, max value " << mSize;
        return nullptr;
    }
    return static_cast<uint8_t*>(mData) + offset;
}

uint32_t CursorWindow::offsetFromPtr(void* ptr) {
    return static_cast<uint8_t*>(ptr) - static_cast<uint8_t*>(mData);
}

status_t CursorWindow::setNumColumns(uint32_t numColumns) {
    if (mReadOnly) {
        return INVALID_OPERATION;
    }
    uint32_t cur = mNumColumns;
    if ((cur > 0 || mNumRows > 0) && cur != numColumns) {
        LOG(ERROR) << "Trying to go from " << cur << " columns to " << numColumns;
        return INVALID_OPERATION;
    }
    mNumColumns = numColumns;
    return OK;
}

status_t CursorWindow::allocRow() {
    if (mReadOnly) {
        return INVALID_OPERATION;
    }
    size_t size = mNumColumns * kSlotSizeBytes;
    int32_t newOffset = mSlotsOffset - size;
    if (newOffset < (int32_t) mAllocOffset) {
        maybeInflate();
        newOffset = mSlotsOffset - size;
        if (newOffset < (int32_t) mAllocOffset) {
            return NO_MEMORY;
        }
    }
    memset(offsetToPtr(newOffset), 0, size);
    mSlotsOffset = newOffset;
    updateSlotsData();
    mNumRows++;
    return OK;
}

status_t CursorWindow::freeLastRow() {
    if (mReadOnly) {
        return INVALID_OPERATION;
    }
    size_t size = mNumColumns * kSlotSizeBytes;
    size_t newOffset = mSlotsOffset + size;
    if (newOffset > mSize) {
        return NO_MEMORY;
    }
    mSlotsOffset = newOffset;
    updateSlotsData();
    mNumRows--;
    return OK;
}

status_t CursorWindow::alloc(size_t size, uint32_t* outOffset) {
    if (mReadOnly) {
        return INVALID_OPERATION;
    }
    size_t alignedSize = (size + 3) & ~3;
    size_t newOffset = mAllocOffset + alignedSize;
    if (newOffset > mSlotsOffset) {
        maybeInflate();
        newOffset = mAllocOffset + alignedSize;
        if (newOffset > mSlotsOffset) {
            return NO_MEMORY;
        }
    }
    *outOffset = mAllocOffset;
    mAllocOffset = newOffset;
    return OK;
}

CursorWindow::FieldSlot* CursorWindow::getFieldSlot(uint32_t row, uint32_t column) {
    // This is carefully tuned to use as few cycles as
    // possible, since this is an extremely hot code path;
    // see CursorWindow_bench.cpp for more details
    void *result = static_cast<uint8_t*>(mSlotsStart)
            - (((row * mNumColumns) + column) << kSlotShift);
    if (result < mSlotsEnd || result > mSlotsStart || column >= mNumColumns) {
        LOG(ERROR) << "Failed to read row " << row << ", column " << column
                << " from a window with " << mNumRows << " rows, " << mNumColumns << " columns";
        return nullptr;
    } else {
        return static_cast<FieldSlot*>(result);
    }
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

    uint32_t offset;
    if (alloc(size, &offset)) {
        return NO_MEMORY;
    }

    memcpy(offsetToPtr(offset), value, size);

    fieldSlot = getFieldSlot(row, column);
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
