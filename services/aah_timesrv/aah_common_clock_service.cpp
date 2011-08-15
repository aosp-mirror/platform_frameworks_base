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

#include <aah_timesrv/local_clock.h>
#include <binder/IServiceManager.h>
#include <binder/IPCThreadState.h>
#include <utils/String8.h>

#include "aah_common_clock_service.h"
#include "common_clock.h"

namespace android {

bool AAHCommonClock::init(CommonClock* common_clock,
                          LocalClock*  local_clock) {
    mCommonClock = common_clock;
    mLocalClock  = local_clock;
    mTimelineID  = kInvalidTimelineID;

    return ((NULL != mCommonClock) && (NULL != mLocalClock));
}

status_t AAHCommonClock::dump(int fd, const Vector<String16>& args) {
    const size_t SIZE = 256;
    char buffer[SIZE];

    if (checkCallingPermission(String16("android.permission.DUMP")) == false) {
        snprintf(buffer, SIZE, "Permission Denial: "
                 "can't dump AAHCommonClock from pid=%d, uid=%d\n",
                 IPCThreadState::self()->getCallingPid(),
                 IPCThreadState::self()->getCallingUid());
    } else {
        int64_t localTime = mLocalClock->getLocalTime();
        int64_t commonTime;
        bool synced = (OK == mCommonClock->localToCommon(localTime,
                                                         &commonTime));
        if (synced) {
            snprintf(buffer, SIZE,
                     "Common time synced\nLocal time: %lld\nCommon time: %lld\n"
                     "Timeline ID: %u\n",
                     localTime, commonTime, mTimelineID);
        } else {
            snprintf(buffer, SIZE,
                     "Common time not synced\nLocal time: %lld\n",
                     localTime);
        }
    }

    write(fd, buffer, strlen(buffer));
    return NO_ERROR;
}

sp<AAHCommonClock> AAHCommonClock::instantiate(CommonClock* common_clock,
                                               LocalClock* local_clock) {
    sp<AAHCommonClock> tcc = new AAHCommonClock();
    if (tcc == NULL || !tcc->init(common_clock, local_clock))
        return NULL;

    defaultServiceManager()->addService(ICommonClock::kServiceName, tcc);
    return tcc;
}

status_t AAHCommonClock::isCommonTimeValid(bool* valid,
                                           uint32_t* timelineID) {
    Mutex::Autolock lock(mLock);

    *valid = mCommonClock->isValid();
    *timelineID = mTimelineID;
    return OK;
}

status_t AAHCommonClock::commonTimeToLocalTime(int64_t  commonTime,
                                               int64_t* localTime) {
    return mCommonClock->commonToLocal(commonTime, localTime);
}

status_t AAHCommonClock::localTimeToCommonTime(int64_t  localTime,
                                               int64_t* commonTime) {
    return mCommonClock->localToCommon(localTime, commonTime);
}

status_t AAHCommonClock::getCommonTime(int64_t* commonTime) {
    return localTimeToCommonTime(mLocalClock->getLocalTime(), commonTime);
}

status_t AAHCommonClock::getCommonFreq(uint64_t* freq) {
    *freq = mCommonClock->getCommonFreq();
    return OK;
}

status_t AAHCommonClock::getLocalTime(int64_t* localTime) {
    *localTime = mLocalClock->getLocalTime();
    return OK;
}

status_t AAHCommonClock::getLocalFreq(uint64_t* freq) {
    *freq = mLocalClock->getLocalFreq();
    return OK;
}

status_t AAHCommonClock::registerListener(
        const sp<ICommonClockListener>& listener) {
    Mutex::Autolock lock(mLock);

    // check whether this is a duplicate
    for (size_t i = 0; i < mListeners.size(); i++) {
        if (mListeners[i]->asBinder() == listener->asBinder())
            return ALREADY_EXISTS;
    }

    mListeners.add(listener);
    return listener->asBinder()->linkToDeath(this);
}

status_t AAHCommonClock::unregisterListener(
        const sp<ICommonClockListener>& listener) {
    Mutex::Autolock lock(mLock);

    for (size_t i = 0; i < mListeners.size(); i++) {
        if (mListeners[i]->asBinder() == listener->asBinder()) {
            mListeners[i]->asBinder()->unlinkToDeath(this);
            mListeners.removeAt(i);
            return OK;
        }
    }

    return NAME_NOT_FOUND;
}

void AAHCommonClock::binderDied(const wp<IBinder>& who) {
    Mutex::Autolock lock(mLock);

    for (size_t i = 0; i < mListeners.size(); i++) {
        if (mListeners[i]->asBinder() == who) {
            mListeners.removeAt(i);
            return;
        }
    }
}

void AAHCommonClock::notifyOnClockSync(uint32_t timelineID) {
    Mutex::Autolock lock(mLock);

    mTimelineID = timelineID;
    for (size_t i = 0; i < mListeners.size(); i++) {
        mListeners[i]->onClockSync(mTimelineID);
    }
}

void AAHCommonClock::notifyOnClockSyncLoss() {
    Mutex::Autolock lock(mLock);

    mTimelineID = kInvalidTimelineID;
    for (size_t i = 0; i < mListeners.size(); i++) {
        mListeners[i]->onClockSyncLoss();
    }
}

}; // namespace android
