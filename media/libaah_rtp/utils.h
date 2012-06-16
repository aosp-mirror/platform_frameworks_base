/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef __UTILS_H__
#define __UTILS_H__

#include <stdint.h>
#include <unistd.h>

#include <netinet/in.h>

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/ADebug.h>
#include <utils/Timers.h>
#include <utils/threads.h>
#include <utils/LinearTransform.h>
#include <common_time/cc_helper.h>

#define MIN(a, b) ((a) < (b) ? (a) : (b))

#define IP_PRINTF_HELPER(a) ((a >> 24) & 0xFF), ((a >> 16) & 0xFF), \
                            ((a >>  8) & 0xFF),  (a        & 0xFF)

namespace android {

// Definition of a helper class used to track things like when we need to
// transmit a heartbeat, or when we will need to wake up and trim the retry
// buffers.
class Timeout {
  public:
    Timeout() : mSystemEndTime(0) { }

    // Set a timeout which should occur msec milliseconds from now.
    // Negative values will cancel any current timeout;
    void setTimeout(int msec);

    // Return the number of milliseconds until the timeout occurs, or -1 if
    // no timeout is scheduled.
    int msecTillTimeout(nsecs_t nowTime);
    int msecTillTimeout() { return msecTillTimeout(systemTime()); }

  private:
    // The systemTime() at which the timeout will be complete, or 0 if no
    // timeout is currently scheduled.
    nsecs_t mSystemEndTime;

    DISALLOW_EVIL_CONSTRUCTORS(Timeout);
};

inline bool matchSockaddrs(const struct sockaddr_in* a,
                           const struct sockaddr_in* b) {
    return ((a->sin_family      == b->sin_family)      &&
            (a->sin_addr.s_addr == b->sin_addr.s_addr) &&
            (a->sin_port        == b->sin_port));
}

inline bool isMulticastSockaddr(const struct sockaddr_in* sa) {
    if (sa->sin_family != AF_INET)
        return false;

    uint32_t addr = ntohl(sa->sin_addr.s_addr);
    return ((addr & 0xF0000000) == 0xE0000000);
}

// Return the minimum timeout between a and b where timeouts less than 0 are
// considered to be infinite
inline int minTimeout(int a, int b) {
    if (a < 0) {
        return b;
    }

    if (b < 0) {
        return a;
    }

    return ((a < b) ? a : b);
}

inline void signalEventFD(int fd) {
    if (fd >= 0) {
        uint64_t tmp = 1;
        ::write(fd, &tmp, sizeof(tmp));
    }
}

inline void clearEventFD(int fd) {
    if (fd >= 0) {
        uint64_t tmp;
        ::read(fd, &tmp, sizeof(tmp));
    }
}

// thread safe circular array
template<typename T, uint32_t LEN>
class CircularArray {
 public:
    CircularArray()
            : mReadIndex(0)
            , mWriteIndex(0)
            , mLength(0) {}
    bool write(const T& t) {
        Mutex::Autolock autolock(&mLock);
        if (mLength < LEN) {
            mData[mWriteIndex] = t;
            mWriteIndex = (mWriteIndex + 1) % LEN;
            mLength++;
            return true;
        }
        return false;
    }
    void writeAllowOverflow(const T& t) {
        Mutex::Autolock autolock(&mLock);
        mData[mWriteIndex] = t;
        mWriteIndex = (mWriteIndex + 1) % LEN;
        if (mLength < LEN) {
            mLength++;
        } else {
            mReadIndex = (mReadIndex + 1) % LEN;
        }
    }
    bool read(T* t) {
        CHECK(t != NULL);
        Mutex::Autolock autolock(&mLock);
        if (mLength > 0) {
            *t = mData[mReadIndex];
            mReadIndex = (mReadIndex + 1) % LEN;
            mLength--;
            return true;
        }
        return false;
    }
    uint32_t readBulk(T* t, uint32_t count) {
        return readBulk(t, 0, count);
    }
    uint32_t readBulk(T* t, uint32_t mincount, uint32_t count) {
        CHECK(t != NULL);
        Mutex::Autolock autolock(&mLock);
        if (mincount > count) {
            // illegal argument
            return 0;
        }
        if (mincount > mLength) {
            // not enough items
            return 0;
        }
        uint32_t i;
        for (i = 0; i < count && mLength; i++) {
            *t = mData[mReadIndex];
            mReadIndex = (mReadIndex + 1) % LEN;
            mLength--;
            t++;
        }
        return i;
    }
 private:
    Mutex mLock;
    T mData[LEN];
    uint32_t mReadIndex;
    uint32_t mWriteIndex;
    uint32_t mLength;
};

class CommonToSystemTransform {
 public:
    CommonToSystemTransform();
    const LinearTransform& getCommonToSystem();
 private:
    LinearTransform mCommonToSystem;
    uint64_t mCommonFreq;
    CCHelper mCCHelper;
    int64_t mLastTs;
};

class MediaToSystemTransform {
 public:
    MediaToSystemTransform();
    void setMediaToCommonTransform(const LinearTransform&);
    void prepareCommonToSystem();
    bool mediaToSystem(int64_t* ts);
 private:
    bool mMediaToCommonValid;
    LinearTransform mMediaToCommon;
    LinearTransform mCommonToSystem;
    CommonToSystemTransform mCommonToSystemTrans;
};

}  // namespace android

#endif  // __UTILS_H__
