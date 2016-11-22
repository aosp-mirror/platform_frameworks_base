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

#define LOG_TAG "incidentd"

#include "FdBuffer.h"

#include <cutils/log.h>
#include <utils/SystemClock.h>

#include <fcntl.h>
#include <poll.h>
#include <unistd.h>

const ssize_t BUFFER_SIZE = 16 * 1024;
const ssize_t MAX_BUFFER_COUNT = 256; // 4 MB max


FdBuffer::FdBuffer()
    :mBuffers(),
     mStartTime(-1),
     mFinishTime(-1),
     mCurrentWritten(-1),
     mTimedOut(false),
     mTruncated(false)
{
}

FdBuffer::~FdBuffer()
{
    const int N = mBuffers.size();
    for (int i=0; i<N; i++) {
        uint8_t* buf = mBuffers[i];
        free(buf);
    }
}

status_t
FdBuffer::read(int fd, int64_t timeout)
{
    struct pollfd pfds = {
        .fd = fd,
        .events = POLLIN
    };
    mStartTime = uptimeMillis();

    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);

    uint8_t* buf = NULL;
    while (true) {
        if (mCurrentWritten >= BUFFER_SIZE || mCurrentWritten < 0) {
            if (mBuffers.size() == MAX_BUFFER_COUNT) {
                mTruncated = true;
                break;
            }
            buf = (uint8_t*)malloc(BUFFER_SIZE);
            if (buf == NULL) {
                return NO_MEMORY;
            }
            mBuffers.push_back(buf);
            mCurrentWritten = 0;
        }

        int64_t remainingTime = (mStartTime + timeout) - uptimeMillis();
        if (remainingTime <= 0) {
            mTimedOut = true;
            break;
        }

        int count = poll(&pfds, 1, remainingTime);
        if (count == 0) {
            mTimedOut = true;
            break;
        } else if (count < 0) {
            return -errno;
        } else {
            if ((pfds.revents & POLLERR) != 0) {
                return errno != 0 ? -errno : UNKNOWN_ERROR;
            } else {
                ssize_t amt = ::read(fd, buf + mCurrentWritten, BUFFER_SIZE - mCurrentWritten);
                if (amt < 0) {
                    if (errno == EAGAIN || errno == EWOULDBLOCK) {
                        continue;
                    } else {
                        return -errno;
                    }
                } else if (amt == 0) {
                    break;
                }
                mCurrentWritten += amt;
            }
        }
    }

    mFinishTime = uptimeMillis();
    return NO_ERROR;
}

size_t
FdBuffer::size()
{
    return ((mBuffers.size() - 1) * BUFFER_SIZE) + mCurrentWritten;
}

status_t
FdBuffer::write(ReportRequestSet* reporter)
{
    const int N = mBuffers.size() - 1;
    for (int i=0; i<N; i++) {
        reporter->write(mBuffers[i], BUFFER_SIZE);
    }
    reporter->write(mBuffers[N], mCurrentWritten);
    return NO_ERROR;
}


