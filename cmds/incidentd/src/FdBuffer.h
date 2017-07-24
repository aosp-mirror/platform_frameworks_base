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

#ifndef FD_BUFFER_H
#define FD_BUFFER_H

#include "Reporter.h"

#include <utils/Errors.h>

#include <set>
#include <vector>

using namespace android;
using namespace std;

/**
 * Reads a file into a buffer, and then writes that data to an FdSet.
 */
class FdBuffer
{
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
     * Read processed results by streaming data to a parsing process, e.g. incident helper.
     * The parsing process provides IO fds which are 'toFd' and 'fromFd'. The function
     * reads original data in 'fd' and writes to parsing process through 'toFd', then it reads
     * and stores the processed data from 'fromFd' in memory for later usage.
     * This function behaves in a streaming fashion in order to save memory usage.
     * Returns NO_ERROR if there were no errors or if we timed out.
     */
    status_t readProcessedDataInStream(int fd, int toFd, int fromFd, int64_t timeoutMs);

    /**
     * Whether we timed out.
     */
    bool timedOut() { return mTimedOut; }

    /**
     * If more than 4 MB is read, we truncate the data and return success.
     * Downstream tools must handle truncated incident reports as best as possible
     * anyway because they could be cut off for a lot of reasons and it's best
     * to get as much useful information out of the system as possible. If this
     * happens, truncated() will return true so it can be marked. If the data is
     * exactly 4 MB, truncated is still set. Sorry.
     */
    bool truncated() { return mTruncated; }

    /**
     * How much data was read.
     */
    size_t size();

    /**
     * Write the data that we recorded to the fd given.
     */
    status_t write(ReportRequestSet* requests);

    /**
     * How long the read took in milliseconds.
     */
    int64_t durationMs() { return mFinishTime - mStartTime; }

private:
    vector<uint8_t*> mBuffers;
    int64_t mStartTime;
    int64_t mFinishTime;
    ssize_t mCurrentWritten;
    bool mTimedOut;
    bool mTruncated;
};

class Fpipe {
public:
    Fpipe() {}
    bool close() { return !(::close(mFds[0]) || ::close(mFds[1])); }
    ~Fpipe() { close(); }

    inline status_t init() { return pipe(mFds); }
    inline int readFd() const { return mFds[0]; }
    inline int writeFd() const { return mFds[1]; }

private:
    int mFds[2];
};


#endif // FD_BUFFER_H
