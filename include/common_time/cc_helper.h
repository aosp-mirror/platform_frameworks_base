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
#include <common_time/ICommonClock.h>
#include <utils/threads.h>

namespace android {

// CCHelper is a simple wrapper class to help with centralizing access to the
// Common Clock service and implementing lifetime managment, as well as to
// implement a simple policy of making a basic attempt to reconnect to the
// common clock service when things go wrong.
//
// On platforms which run the native common_time service in auto-disable mode,
// the service will go into networkless mode whenever it has no active clients.
// It tracks active clients using registered CommonClockListeners (the callback
// interface for onTimelineChanged) since this provides a convienent death
// handler notification for when the service's clients die unexpectedly.  This
// means that users of the common time service should really always have a
// CommonClockListener, unless they know that the time service is not running in
// auto disabled mode, or that there is at least one other registered listener
// active in the system.  The CCHelper makes this a little easier by sharing a
// ref counted ICommonClock interface across all clients and automatically
// registering and unregistering a listener whenever there are CCHelper
// instances active in the process.
class CCHelper {
  public:
    CCHelper();
    ~CCHelper();

    status_t isCommonTimeValid(bool* valid, uint32_t* timelineID);
    status_t commonTimeToLocalTime(int64_t commonTime, int64_t* localTime);
    status_t localTimeToCommonTime(int64_t localTime, int64_t* commonTime);
    status_t getCommonTime(int64_t* commonTime);
    status_t getCommonFreq(uint64_t* freq);
    status_t getLocalTime(int64_t* localTime);
    status_t getLocalFreq(uint64_t* freq);

  private:
    class CommonClockListener : public BnCommonClockListener {
      public:
        void onTimelineChanged(uint64_t timelineID);
    };

    static bool verifyClock_l();

    static Mutex lock_;
    static sp<ICommonClock> common_clock_;
    static sp<ICommonClockListener> common_clock_listener_;
    static uint32_t ref_count_;
};


}  // namespace android
#endif  // __CC_HELPER_H__
