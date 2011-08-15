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

#include <common_time/ICommonClock.h>

#ifndef ANDROID_COMMON_CLOCK_SERVICE_H
#define ANDROID_COMMON_CLOCK_SERVICE_H

namespace android {

class CommonTimeServer;

class CommonClockService : public BnCommonClock,
                           public android::IBinder::DeathRecipient {
  public:
    static sp<CommonClockService> instantiate(CommonTimeServer& timeServer);

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
    virtual status_t getEstimatedError(int32_t* estimate);
    virtual status_t getTimelineID(uint64_t* id);
    virtual status_t getState(ICommonClock::State* state);
    virtual status_t getMasterAddr(struct sockaddr_storage* addr);

    virtual status_t registerListener(
            const sp<ICommonClockListener>& listener);
    virtual status_t unregisterListener(
            const sp<ICommonClockListener>& listener);

    void notifyOnTimelineChanged(uint64_t timelineID);

  private:
    CommonClockService(CommonTimeServer& timeServer)
        : mTimeServer(timeServer) { };

    virtual void binderDied(const wp<IBinder>& who);

    CommonTimeServer& mTimeServer;

    // locks used to synchronize access to the list of registered listeners.
    // The callback lock is held whenever the list is used to perform callbacks
    // or while the list is being modified.  The registration lock used to
    // serialize access across registerListener, unregisterListener, and
    // binderDied.
    //
    // The reason for two locks is that registerListener, unregisterListener,
    // and binderDied each call into the core service and obtain the core
    // service thread lock when they call reevaluateAutoDisableState.  The core
    // service thread obtains the main thread lock whenever its thread is
    // running, and sometimes needs to call notifyOnTimelineChanged which then
    // obtains the callback lock.  If callers of registration functions were
    // holding the callback lock when they called into the core service, we
    // would have a classic A/B, B/A ordering deadlock.  To avoid this, the
    // registration functions hold the registration lock for the duration of
    // their call, but hold the callback lock only while they mutate the list.
    // This way, the list's size cannot change (because of the registration
    // lock) during the call into reevaluateAutoDisableState, but the core work
    // thread can still safely call notifyOnTimelineChanged while holding the
    // main thread lock.
    Mutex mCallbackLock;
    Mutex mRegistrationLock;

    Vector<sp<ICommonClockListener> > mListeners;
};

};  // namespace android

#endif  // ANDROID_COMMON_CLOCK_SERVICE_H
