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

#include <utils/Timers.h>

namespace android {

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
};

}  // namespace android

#endif  // __UTILS_H__
