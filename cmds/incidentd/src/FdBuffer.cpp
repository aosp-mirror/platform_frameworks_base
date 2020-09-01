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
#define DEBUG false
#include "Log.h"

#include "FdBuffer.h"
#include "incidentd_util.h"

#include <log/log.h>
#include <utils/SystemClock.h>

#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <wait.h>

namespace android {
namespace os {
namespace incidentd {

const ssize_t BUFFER_SIZE = 16 * 1024;  // 16 KB
const ssize_t MAX_BUFFER_SIZE = 96 * 1024 * 1024;  // 96 MB

FdBuffer::FdBuffer(): FdBuffer(get_buffer_from_pool(), /* isBufferPooled= */ true)  {
}

FdBuffer::FdBuffer(sp<EncodedBuffer> buffer, bool isBufferPooled)
        :mBuffer(buffer),
         mStartTime(-1),
         mFinishTime(-1),
         mTimedOut(false),
         mTruncated(false),
         mIsBufferPooled(isBufferPooled) {
}

FdBuffer::~FdBuffer() {
    if (mIsBufferPooled) {
        return_buffer_to_pool(mBuffer);
    }
}

status_t FdBuffer::read(int fd, int64_t timeout) {
    struct pollfd pfds = {.fd = fd, .events = POLLIN};
    mStartTime = uptimeMillis();

    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);

    while (true) {
        if (mBuffer->size() >= MAX_BUFFER_SIZE) {
            mTruncated = true;
            VLOG("Truncating data");
            break;
        }
        if (mBuffer->writeBuffer() == NULL) {
            VLOG("No memory");
            return NO_MEMORY;
        }

        int64_t remainingTime = (mStartTime + timeout) - uptimeMillis();
        if (remainingTime <= 0) {
            VLOG("timed out due to long read");
            mTimedOut = true;
            break;
        }

        int count = TEMP_FAILURE_RETRY(poll(&pfds, 1, remainingTime));
        if (count == 0) {
            VLOG("timed out due to block calling poll");
            mTimedOut = true;
            break;
        } else if (count < 0) {
            VLOG("poll failed: %s", strerror(errno));
            return -errno;
        } else {
            if ((pfds.revents & POLLERR) != 0) {
                VLOG("return event has error %s", strerror(errno));
                return errno != 0 ? -errno : UNKNOWN_ERROR;
            } else {
                ssize_t amt = TEMP_FAILURE_RETRY(
                        ::read(fd, mBuffer->writeBuffer(), mBuffer->currentToWrite()));
                if (amt < 0) {
                    if (errno == EAGAIN || errno == EWOULDBLOCK) {
                        continue;
                    } else {
                        VLOG("Fail to read %d: %s", fd, strerror(errno));
                        return -errno;
                    }
                } else if (amt == 0) {
                    VLOG("Reached EOF of fd=%d", fd);
                    break;
                }
                mBuffer->wp()->move(amt);
            }
        }
    }
    mFinishTime = uptimeMillis();
    return NO_ERROR;
}

status_t FdBuffer::readFully(int fd) {
    mStartTime = uptimeMillis();

    while (true) {
        if (mBuffer->size() >= MAX_BUFFER_SIZE) {
            // Don't let it get too big.
            mTruncated = true;
            VLOG("Truncating data");
            break;
        }
        if (mBuffer->writeBuffer() == NULL) {
            VLOG("No memory");
            return NO_MEMORY;
        }

        ssize_t amt =
                TEMP_FAILURE_RETRY(::read(fd, mBuffer->writeBuffer(), mBuffer->currentToWrite()));
        if (amt < 0) {
            VLOG("Fail to read %d: %s", fd, strerror(errno));
            return -errno;
        } else if (amt == 0) {
            VLOG("Done reading %zu bytes", mBuffer->size());
            // We're done.
            break;
        }
        mBuffer->wp()->move(amt);
    }

    mFinishTime = uptimeMillis();
    return NO_ERROR;
}

status_t FdBuffer::readProcessedDataInStream(int fd, unique_fd toFd, unique_fd fromFd,
                                             int64_t timeoutMs, const bool isSysfs) {
    struct pollfd pfds[] = {
            {.fd = fd, .events = POLLIN},
            {.fd = toFd.get(), .events = POLLOUT},
            {.fd = fromFd.get(), .events = POLLIN},
    };

    mStartTime = uptimeMillis();

    // mark all fds non blocking
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
    fcntl(toFd.get(), F_SETFL, fcntl(toFd.get(), F_GETFL, 0) | O_NONBLOCK);
    fcntl(fromFd.get(), F_SETFL, fcntl(fromFd.get(), F_GETFL, 0) | O_NONBLOCK);

    // A circular buffer holds data read from fd and writes to parsing process
    uint8_t cirBuf[BUFFER_SIZE];
    size_t cirSize = 0;
    int rpos = 0, wpos = 0;

    // This is the buffer used to store processed data
    while (true) {
        if (mBuffer->size() >= MAX_BUFFER_SIZE) {
            VLOG("Truncating data");
            mTruncated = true;
            break;
        }
        if (mBuffer->writeBuffer() == NULL) {
            VLOG("No memory");
            return NO_MEMORY;
        }

        int64_t remainingTime = (mStartTime + timeoutMs) - uptimeMillis();
        if (remainingTime <= 0) {
            VLOG("timed out due to long read");
            mTimedOut = true;
            break;
        }

        // wait for any pfds to be ready to perform IO
        int count = TEMP_FAILURE_RETRY(poll(pfds, 3, remainingTime));
        if (count == 0) {
            VLOG("timed out due to block calling poll");
            mTimedOut = true;
            break;
        } else if (count < 0) {
            VLOG("Fail to poll: %s", strerror(errno));
            return -errno;
        }

        // make sure no errors occur on any fds
        for (int i = 0; i < 3; ++i) {
            if ((pfds[i].revents & POLLERR) != 0) {
                if (i == 0 && isSysfs) {
                    VLOG("fd %d is sysfs, ignore its POLLERR return value", fd);
                    continue;
                }
                VLOG("fd[%d]=%d returns error events: %s", i, fd, strerror(errno));
                return errno != 0 ? -errno : UNKNOWN_ERROR;
            }
        }

        // read from fd
        if (cirSize != BUFFER_SIZE && pfds[0].fd != -1) {
            ssize_t amt;
            if (rpos >= wpos) {
                amt = TEMP_FAILURE_RETRY(::read(fd, cirBuf + rpos, BUFFER_SIZE - rpos));
            } else {
                amt = TEMP_FAILURE_RETRY(::read(fd, cirBuf + rpos, wpos - rpos));
            }
            if (amt < 0) {
                if (!(errno == EAGAIN || errno == EWOULDBLOCK)) {
                    VLOG("Fail to read fd %d: %s", fd, strerror(errno));
                    return -errno;
                }  // otherwise just continue
            } else if (amt == 0) {
                VLOG("Reached EOF of input file %d", fd);
                pfds[0].fd = -1;  // reach EOF so don't have to poll pfds[0].
            } else {
                rpos += amt;
                cirSize += amt;
            }
        }

        // write to parsing process
        if (cirSize > 0 && pfds[1].fd != -1) {
            ssize_t amt;
            if (rpos > wpos) {
                amt = TEMP_FAILURE_RETRY(::write(toFd.get(), cirBuf + wpos, rpos - wpos));
            } else {
                amt = TEMP_FAILURE_RETRY(::write(toFd.get(), cirBuf + wpos, BUFFER_SIZE - wpos));
            }
            if (amt < 0) {
                if (!(errno == EAGAIN || errno == EWOULDBLOCK)) {
                    VLOG("Fail to write toFd %d: %s", toFd.get(), strerror(errno));
                    return -errno;
                }  // otherwise just continue
            } else {
                wpos += amt;
                cirSize -= amt;
            }
        }

        // if buffer is empty and fd is closed, close write fd.
        if (cirSize == 0 && pfds[0].fd == -1 && pfds[1].fd != -1) {
            VLOG("Close write pipe %d", toFd.get());
            toFd.reset();
            pfds[1].fd = -1;
        }

        // circular buffer, reset rpos and wpos
        if (rpos >= BUFFER_SIZE) {
            rpos = 0;
        }
        if (wpos >= BUFFER_SIZE) {
            wpos = 0;
        }

        // read from parsing process
        ssize_t amt = TEMP_FAILURE_RETRY(
                ::read(fromFd.get(), mBuffer->writeBuffer(), mBuffer->currentToWrite()));
        if (amt < 0) {
            if (!(errno == EAGAIN || errno == EWOULDBLOCK)) {
                VLOG("Fail to read fromFd %d: %s", fromFd.get(), strerror(errno));
                return -errno;
            }  // otherwise just continue
        } else if (amt == 0) {
            VLOG("Reached EOF of fromFd %d", fromFd.get());
            break;
        } else {
            mBuffer->wp()->move(amt);
        }
    }

    mFinishTime = uptimeMillis();
    return NO_ERROR;
}

status_t FdBuffer::write(uint8_t const* buf, size_t size) {
    return mBuffer->writeRaw(buf, size);
}

status_t FdBuffer::write(const sp<ProtoReader>& reader) {
    return mBuffer->writeRaw(reader);
}

status_t FdBuffer::write(const sp<ProtoReader>& reader, size_t size) {
    return mBuffer->writeRaw(reader, size);
}

size_t FdBuffer::size() const {
    return mBuffer->size();
}

sp<EncodedBuffer> FdBuffer::data() const {
    return mBuffer;
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
