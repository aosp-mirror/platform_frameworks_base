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

#include <cstdint>
#include <string>

#include <android/util/EncodedBuffer.h>

namespace android {
namespace util {

/**
 * A ProtoReader on top of a file descriptor.
 */
class ProtoFileReader : public ProtoReader
{
public:
    /**
     * Read from this file descriptor.
     */
    ProtoFileReader(int fd);

    /**
     * Does NOT close the file.
     */
    virtual ~ProtoFileReader();

    // From ProtoReader.
    virtual ssize_t size() const;
    virtual size_t bytesRead() const;
    virtual uint8_t const* readBuffer();
    virtual size_t currentToRead();
    virtual bool hasNext();
    virtual uint8_t next();
    virtual uint64_t readRawVarint();
    virtual void move(size_t amt);

    status_t getError() const;
private:
    int mFd;                // File descriptor for input.
    status_t mStatus;       // Any errors encountered during read.
    ssize_t mSize;          // How much total data there is, or -1 if we can't tell.
    size_t mPos;            // How much data has been read so far.
    size_t mOffset;         // Offset in current buffer.
    size_t mMaxOffset;      // How much data is left to read in mBuffer.
    const int mChunkSize;   // Size of mBuffer.
    uint8_t mBuffer[32*1024];

    /**
     * If there is currently more data to read in the buffer, returns true.
     * If there is not more, then tries to read.  If more data can be read,
     * it does so and returns true.  If there is no more data, returns false.
     * Resets mOffset and mMaxOffset as necessary.  Does not advance mOffset.
     */
    bool ensure_data();
};

}
}

