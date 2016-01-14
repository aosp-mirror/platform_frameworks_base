/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_IO_DATA_H
#define AAPT_IO_DATA_H

#include <utils/FileMap.h>

#include <memory>

namespace aapt {
namespace io {

/**
 * Interface for a block of contiguous memory. An instance of this interface owns the data.
 */
class IData {
public:
    virtual ~IData() = default;

    virtual const void* data() const = 0;
    virtual size_t size() const = 0;
};

/**
 * Implementation of IData that exposes a memory mapped file. The mmapped file is owned by this
 * object.
 */
class MmappedData : public IData {
public:
    explicit MmappedData(android::FileMap&& map) : mMap(std::forward<android::FileMap>(map)) {
    }

    const void* data() const override {
        return mMap.getDataPtr();
    }

    size_t size() const override {
        return mMap.getDataLength();
    }

private:
    android::FileMap mMap;
};

/**
 * Implementation of IData that exposes a block of memory that was malloc'ed (new'ed). The
 * memory is owned by this object.
 */
class MallocData : public IData {
public:
    MallocData(std::unique_ptr<const uint8_t[]> data, size_t size) :
            mData(std::move(data)), mSize(size) {
    }

    const void* data() const override {
        return mData.get();
    }

    size_t size() const override {
        return mSize;
    }

private:
    std::unique_ptr<const uint8_t[]> mData;
    size_t mSize;
};

/**
 * When mmap fails because the file has length 0, we use the EmptyData to simulate data of length 0.
 */
class EmptyData : public IData {
public:
    const void* data() const override {
        static const uint8_t d = 0;
        return &d;
    }

    size_t size() const override {
        return 0u;
    }
};

} // namespace io
} // namespace aapt

#endif /* AAPT_IO_DATA_H */
