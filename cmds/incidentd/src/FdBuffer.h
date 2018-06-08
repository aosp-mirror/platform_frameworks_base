/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef FD_BUFFER_H
#define FD_BUFFER_H

#include <android-base/unique_fd.h>
#include <android/util/EncodedBuffer.h>
#include <utils/Errors.h>

namespace android {
namespace os {
namespace incidentd {

using namespace android::base;
using namespace android::util;

/**
 * Reads data from fd into a buffer, fd must be closed explicitly.
 */
class FdBuffer {
public:
    FdBuffer();
    ~FdBuffer();

    /**
     * Read the data until the timeout is hit or we hit eof.
     * Returns NO_ERROR if there were no errors or if we timed out.
     * Will mark the file O_NONBLOCK.
     */
    status_t read(int fd, int64_t timeoutMs);

    /**
     * Read the data until we hit eof.
     * Returns NO_ERROR if there were no errors.
     */
    status_t readFully(int fd);

    /**
     * Read processed results by streaming data to a parsing process, e.g. incident helper.
     * The parsing process provides IO fds which are 'toFd' and 'fromFd'. The function
     * reads original data in 'fd' and writes to parsing process through 'toFd', then it reads
     * and stores the processed data from 'fromFd' in memory for later usage.
     * This function behaves in a streaming fashion in order to save memory usage.
     * Returns NO_ERROR if there were no errors or if we timed out.
     *
     * Poll will return POLLERR if fd is from sysfs, handle this edge case.
     */
    status_t readProcessedDataInStream(int fd, unique_fd toFd, unique_fd fromFd, int64_t timeoutMs,
                                       const bool isSysfs = false);

    /**
     * Whether we timed out.
     */
    bool timedOut() const { return mTimedOut; }

    /**
     * If more than 4 MB is read, we truncate the data and return success.
     * Downstream tools must handle truncated incident reports as best as possible
     * anyway because they could be cut off for a lot of reasons and it's best
     * to get as much useful information out of the system as possible. If this
     * happens, truncated() will return true so it can be marked. If the data is
     * exactly 4 MB, truncated is still set. Sorry.
     */
    bool truncated() const { return mTruncated; }

    /**
     * How much data was read.
     */
    size_t size() const;

    /**
     * How long the read took in milliseconds.
     */
    int64_t durationMs() const { return mFinishTime - mStartTime; }

    /**
     * Reader API for data stored in FdBuffer
     */
    EncodedBuffer::iterator data() const;

    /**
     * Return the internal buffer, don't call unless you are familiar with EncodedBuffer.
     */
    EncodedBuffer* getInternalBuffer() { return &mBuffer; }

private:
    EncodedBuffer mBuffer;
    int64_t mStartTime;
    int64_t mFinishTime;
    bool mTimedOut;
    bool mTruncated;
};

}  // namespace incidentd
}  // namespace os
}  // namespace android

#endif  // FD_BUFFER_H
