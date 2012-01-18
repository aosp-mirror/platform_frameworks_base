/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef __CC_HELPER_H__
#define __CC_HELPER_H__

#include <stdint.h>
#include <utils/threads.h>

namespace android {

class ICommonClock;

// CCHelper is a simple wrapper class to help with centralizing access to the
// Common Clock service as well as to implement a simple policy of making a
// basic attempt to reconnect to the common clock service when things go wrong.
class CCHelper {
  public:
    static status_t isCommonTimeValid(bool* valid, uint32_t* timelineID);
    static status_t commonTimeToLocalTime(int64_t commonTime, int64_t* localTime);
    static status_t localTimeToCommonTime(int64_t localTime, int64_t* commonTime);
    static status_t getCommonTime(int64_t* commonTime);
    static status_t getCommonFreq(uint64_t* freq);
    static status_t getLocalTime(int64_t* localTime);
    static status_t getLocalFreq(uint64_t* freq);

  private:
    static bool verifyClock_l();

    static Mutex lock_;
    static sp<ICommonClock> common_clock_;
};


}  // namespace android
#endif  // __CC_HELPER_H__
