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


#ifndef __LOCAL_CLOCK_H__
#define __LOCAL_CLOCK_H__

#include <stdint.h>

#include <hardware/local_time_hal.h>
#include <utils/Errors.h>

namespace android {

class LocalClock {
  public:
     LocalClock();
    ~LocalClock();

    bool initCheck();

    int64_t  getLocalTime();
    uint64_t getLocalFreq();
    status_t setLocalSlew(int16_t rate);
    int32_t  getDebugLog(struct local_time_debug_event* records,
                         int max_records);

  private:
    local_time_hw_device_t* dev_;
};

}  // namespace android
#endif  // __LOCAL_CLOCK_H__
