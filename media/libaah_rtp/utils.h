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

#include <netinet/in.h>

#include <media/stagefright/foundation/ABase.h>
#include <utils/Timers.h>

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

}  // namespace android

#endif  // __UTILS_H__
