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

#include <common_time/local_clock.h>
#include <utils/String8.h>

#include "common_clock_service.h"
#include "common_clock.h"
#include "common_time_server.h"

namespace android {

sp<CommonClockService> CommonClockService::instantiate(
        CommonTimeServer& timeServer) {
    sp<CommonClockService> tcc = new CommonClockService(timeServer);
    if (tcc == NULL)
        return NULL;

    defaultServiceManager()->addService(ICommonClock::kServiceName, tcc);
    return tcc;
}

status_t CommonClockService::dump(int fd, const Vector<String16>& args) {
    Mutex::Autolock lock(mRegistrationLock);
    return mTimeServer.dumpClockInterface(fd, args, mListeners.size());
}

status_t CommonClockService::isCommonTimeValid(bool* valid,
                                               uint32_t* timelineID) {
    return mTimeServer.isCommonTimeValid(valid, timelineID);
}

status_t CommonClockService::commonTimeToLocalTime(int64_t  commonTime,
                                                   int64_t* localTime) {
    return mTimeServer.getCommonClock().commonToLocal(commonTime, localTime);
}

status_t CommonClockService::localTimeToCommonTime(int64_t  localTime,
                                                   int64_t* commonTime) {
    return mTimeServer.getCommonClock().localToCommon(localTime, commonTime);
}

status_t CommonClockService::getCommonTime(int64_t* commonTime) {
    return localTimeToCommonTime(mTimeServer.getLocalClock().getLocalTime(), commonTime);
}

status_t CommonClockService::getCommonFreq(uint64_t* freq) {
    *freq = mTimeServer.getCommonClock().getCommonFreq();
    return OK;
}

status_t CommonClockService::getLocalTime(int64_t* localTime) {
    *localTime = mTimeServer.getLocalClock().getLocalTime();
    return OK;
}

status_t CommonClockService::getLocalFreq(uint64_t* freq) {
    *freq = mTimeServer.getLocalClock().getLocalFreq();
    return OK;
}

status_t CommonClockService::getEstimatedError(int32_t* estimate) {
    *estimate = mTimeServer.getEstimatedError();
    return OK;
}

status_t CommonClockService::getTimelineID(uint64_t* id) {
    *id = mTimeServer.getTimelineID();
    return OK;
}

status_t CommonClockService::getState(State* state) {
    *state = mTimeServer.getState();
    return OK;
}

status_t CommonClockService::getMasterAddr(struct sockaddr_storage* addr) {
    return mTimeServer.getMasterAddr(addr);
}

status_t CommonClockService::registerListener(
        const sp<ICommonClockListener>& listener) {
    Mutex::Autolock lock(mRegistrationLock);

    {   // scoping for autolock pattern
        Mutex::Autolock lock(mCallbackLock);
        // check whether this is a duplicate
        for (size_t i = 0; i < mListeners.size(); i++) {
            if (mListeners[i]->asBinder() == listener->asBinder())
                return ALREADY_EXISTS;
        }
    }

    mListeners.add(listener);
    mTimeServer.reevaluateAutoDisableState(0 != mListeners.size());
    return listener->asBinder()->linkToDeath(this);
}

status_t CommonClockService::unregisterListener(
        const sp<ICommonClockListener>& listener) {
    Mutex::Autolock lock(mRegistrationLock);
    status_t ret_val = NAME_NOT_FOUND;

    {   // scoping for autolock pattern
        Mutex::Autolock lock(mCallbackLock);
        for (size_t i = 0; i < mListeners.size(); i++) {
            if (mListeners[i]->asBinder() == listener->asBinder()) {
                mListeners[i]->asBinder()->unlinkToDeath(this);
                mListeners.removeAt(i);
                ret_val = OK;
                break;
            }
        }
    }

    mTimeServer.reevaluateAutoDisableState(0 != mListeners.size());
    return ret_val;
}

void CommonClockService::binderDied(const wp<IBinder>& who) {
    Mutex::Autolock lock(mRegistrationLock);

    {   // scoping for autolock pattern
        Mutex::Autolock lock(mCallbackLock);
        for (size_t i = 0; i < mListeners.size(); i++) {
            if (mListeners[i]->asBinder() == who) {
                mListeners.removeAt(i);
                break;
            }
        }
    }

    mTimeServer.reevaluateAutoDisableState(0 != mListeners.size());
}

void CommonClockService::notifyOnTimelineChanged(uint64_t timelineID) {
    Mutex::Autolock lock(mCallbackLock);

    for (size_t i = 0; i < mListeners.size(); i++) {
        mListeners[i]->onTimelineChanged(timelineID);
    }
}

}; // namespace android
