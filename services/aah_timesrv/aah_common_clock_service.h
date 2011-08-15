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

#include <aah_timesrv/ICommonClock.h>

#ifndef ANDROID_AAH_COMMONCLOCK_SERVICE_H
#define ANDROID_AAH_COMMONCLOCK_SERVICE_H

namespace android {

class CommonClock;
class LocalClock;

class AAHCommonClock : public BnCommonClock,
                       public android::IBinder::DeathRecipient {
  public:
    static sp<AAHCommonClock> instantiate(CommonClock* common_clock,
                                          LocalClock* local_clock);

    virtual status_t dump(int fd, const Vector<String16>& args);

    virtual status_t isCommonTimeValid(bool* valid, uint32_t *timelineID);
    virtual status_t commonTimeToLocalTime(int64_t  common_time,
                                           int64_t* local_time);
    virtual status_t localTimeToCommonTime(int64_t  local_time,
                                           int64_t* common_time);
    virtual status_t getCommonTime(int64_t* common_time);
    virtual status_t getCommonFreq(uint64_t* freq);
    virtual status_t getLocalTime(int64_t* local_time);
    virtual status_t getLocalFreq(uint64_t* freq);

    virtual status_t registerListener(
            const sp<ICommonClockListener>& listener);
    virtual status_t unregisterListener(
            const sp<ICommonClockListener>& listener);

    void notifyOnClockSync(uint32_t timelineID);
    void notifyOnClockSyncLoss();

  private:
    AAHCommonClock() {}
    bool init(CommonClock* common_clock, LocalClock* local_clock);

    virtual void binderDied(const wp<IBinder>& who);

    CommonClock* mCommonClock;
    LocalClock*  mLocalClock;

    // this lock serializes access to mTimelineID and mListeners
    Mutex mLock;

    uint32_t mTimelineID;
    Vector<sp<ICommonClockListener> > mListeners;
};

};  // namespace android

#endif  // ANDROID_AAH_COMMONCLOCK_SERVICE_H
