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


#endif // FD_BUFFER_H
