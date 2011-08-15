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

#ifndef ANDROID_ICOMMONCLOCK_H
#define ANDROID_ICOMMONCLOCK_H

#include <stdint.h>

#include <binder/IInterface.h>
#include <binder/IServiceManager.h>

namespace android {

class ICommonClockListener : public IInterface {
  public:
    DECLARE_META_INTERFACE(CommonClockListener);

    virtual void onClockSync(uint32_t timelineID) = 0;
    virtual void onClockSyncLoss() = 0;
};

class BnCommonClockListener : public BnInterface<ICommonClockListener> {
  public:
    virtual status_t onTransact(uint32_t code, const Parcel& data,
                                Parcel* reply, uint32_t flags = 0);
};

class ICommonClock : public IInterface {
  public:
    DECLARE_META_INTERFACE(CommonClock);

    // Name of the ICommonClock service registered with the service manager.
    static const String16 kServiceName;

    // a reserved invalid timeline ID
    static const uint32_t kInvalidTimelineID;

    virtual status_t isCommonTimeValid(bool* valid, uint32_t* timelineID) = 0;
    virtual status_t commonTimeToLocalTime(int64_t commonTime,
                                           int64_t* localTime) = 0;
    virtual status_t localTimeToCommonTime(int64_t localTime,
                                           int64_t* commonTime) = 0;
    virtual status_t getCommonTime(int64_t* commonTime) = 0;
    virtual status_t getCommonFreq(uint64_t* freq) = 0;
    virtual status_t getLocalTime(int64_t* localTime) = 0;
    virtual status_t getLocalFreq(uint64_t* freq) = 0;

    virtual status_t registerListener(
            const sp<ICommonClockListener>& listener) = 0;
    virtual status_t unregisterListener(
            const sp<ICommonClockListener>& listener) = 0;

    // Simple helper to make it easier to connect to the CommonClock service.
    static inline sp<ICommonClock> getInstance() {
        sp<IBinder> binder = defaultServiceManager()->checkService(
                ICommonClock::kServiceName);
        sp<ICommonClock> clk = interface_cast<ICommonClock>(binder);
        return clk;
    }
};

class BnCommonClock : public BnInterface<ICommonClock> {
  public:
    virtual status_t onTransact(uint32_t code, const Parcel& data,
                                Parcel* reply, uint32_t flags = 0);
};

};  // namespace android

#endif  // ANDROID_ICOMMONCLOCK_H
