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

#pragma once

#include <utils/Errors.h>
#include <utils/RefBase.h>

#include <stdint.h>
#include <vector>

namespace android {
namespace util {

class ProtoReader : public virtual RefBase {
public:
    ProtoReader();
    ~ProtoReader();

    /**
     * Returns the number of bytes written in the buffer
     */
    virtual ssize_t size() const = 0;

    /**
     * Returns the size of total bytes read.
     */
    virtual size_t bytesRead() const = 0;

    /**
     * Returns the current position of read pointer, if NULL is returned, it reaches
     * end of buffer.
     */
    virtual uint8_t const* readBuffer() = 0;

    /**
     * Returns the readable size in the current read buffer.
     */
    virtual size_t currentToRead() = 0;

    /**
     * Returns true if next bytes is available for read.
     */
    virtual bool hasNext() = 0;

    /**
     * Reads the current byte and moves pointer 1 bit.
     */
    virtual uint8_t next() = 0;

    /**
     * Read varint from the reader, the reader will point to next available byte.
     */
    virtual uint64_t readRawVarint() = 0;

    /**
     * Advance the read pointer.
     */
    virtual void move(size_t amt) = 0;
};

} // util
} // android

