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

/*
 * A service that exchanges time synchronization information between
 * a master that defines a timeline and clients that follow the timeline.
 */

#define LOG_TAG "common_time"
#include <utils/Log.h>

#include <binder/IServiceManager.h>
#include <binder/IPCThreadState.h>

#include "common_time_server.h"

namespace android {

//
// Clock API
//
uint64_t CommonTimeServer::getTimelineID() {
    AutoMutex _lock(&mLock);
    return mTimelineID;
}

ICommonClock::State CommonTimeServer::getState() {
    AutoMutex _lock(&mLock);
    return mState;
}

status_t CommonTimeServer::getMasterAddr(struct sockaddr_storage* addr) {
    AutoMutex _lock(&mLock);
    if (mMasterEPValid) {
        memcpy(addr, &mMasterEP, sizeof(*addr));
        return OK;
    }

    return UNKNOWN_ERROR;
}

int32_t CommonTimeServer::getEstimatedError() {
    AutoMutex _lock(&mLock);

    if (ICommonClock::STATE_MASTER == mState)
        return 0;

    if (!mClockSynced)
        return ICommonClock::kErrorEstimateUnknown;

    return mClockRecovery.getLastErrorEstimate();
}

status_t CommonTimeServer::isCommonTimeValid(bool* valid,
                                             uint32_t* timelineID) {
    AutoMutex _lock(&mLock);
    *valid = mCommonClock.isValid();
    *timelineID = mTimelineID;
    return OK;
}

//
// Config API
//
status_t CommonTimeServer::getMasterElectionPriority(uint8_t *priority) {
    AutoMutex _lock(&mLock);
    *priority = mMasterPriority;
    return OK;
}

status_t CommonTimeServer::setMasterElectionPriority(uint8_t priority) {
    AutoMutex _lock(&mLock);

    if (priority > 0x7F)
        return BAD_VALUE;

    mMasterPriority = priority;
    return OK;
}

status_t CommonTimeServer::getMasterElectionEndpoint(
        struct sockaddr_storage *addr) {
    AutoMutex _lock(&mLock);
    memcpy(addr, &mMasterElectionEP, sizeof(*addr));
    return OK;
}

status_t CommonTimeServer::setMasterElectionEndpoint(
        const struct sockaddr_storage *addr) {
    AutoMutex _lock(&mLock);

    if (!addr)
        return BAD_VALUE;

    // TODO: add proper support for IPv6
    if (addr->ss_family != AF_INET)
        return BAD_VALUE;

    // Only multicast and broadcast endpoints with explicit ports are allowed.
    uint16_t ipv4Port = ntohs(
        reinterpret_cast<const struct sockaddr_in*>(addr)->sin_port);
    if (!ipv4Port)
        return BAD_VALUE;

    uint32_t ipv4Addr = ntohl(
        reinterpret_cast<const struct sockaddr_in*>(addr)->sin_addr.s_addr);
    if ((ipv4Addr != 0xFFFFFFFF) && (0xE0000000 != (ipv4Addr & 0xF0000000)))
        return BAD_VALUE;

    memcpy(&mMasterElectionEP, addr, sizeof(mMasterElectionEP));

    // Force a rebind in order to change election enpoints.
    mBindIfaceDirty = true;
    wakeupThread_l();
    return OK;
}

status_t CommonTimeServer::getMasterElectionGroupId(uint64_t *id) {
    AutoMutex _lock(&mLock);
    *id = mSyncGroupID;
    return OK;
}

status_t CommonTimeServer::setMasterElectionGroupId(uint64_t id) {
    AutoMutex _lock(&mLock);
    mSyncGroupID = id;
    return OK;
}

status_t CommonTimeServer::getInterfaceBinding(String8& ifaceName) {
    AutoMutex _lock(&mLock);
    if (!mBindIfaceValid)
        return INVALID_OPERATION;
    ifaceName = mBindIface;
    return OK;
}

status_t CommonTimeServer::setInterfaceBinding(const String8& ifaceName) {
    AutoMutex _lock(&mLock);

    mBindIfaceDirty = true;
    if (ifaceName.size()) {
        mBindIfaceValid = true;
        mBindIface = ifaceName;
    } else {
        mBindIfaceValid = false;
        mBindIface.clear();
    }

    wakeupThread_l();
    return OK;
}

status_t CommonTimeServer::getMasterAnnounceInterval(int *interval) {
    AutoMutex _lock(&mLock);
    *interval = mMasterAnnounceIntervalMs;
    return OK;
}

status_t CommonTimeServer::setMasterAnnounceInterval(int interval) {
    AutoMutex _lock(&mLock);

    if (interval > (6 *3600000)) // Max interval is once every 6 hrs
        return BAD_VALUE;

    if (interval < 500) // Min interval is once per 0.5 seconds
        return BAD_VALUE;

    mMasterAnnounceIntervalMs = interval;
    if (ICommonClock::STATE_MASTER == mState) {
        int pendingTimeout = mCurTimeout.msecTillTimeout();
        if ((kInfiniteTimeout == pendingTimeout) ||
            (pendingTimeout > interval)) {
            mCurTimeout.setTimeout(mMasterAnnounceIntervalMs);
            wakeupThread_l();
        }
    }

    return OK;
}

status_t CommonTimeServer::getClientSyncInterval(int *interval) {
    AutoMutex _lock(&mLock);
    *interval = mSyncRequestIntervalMs;
    return OK;
}

status_t CommonTimeServer::setClientSyncInterval(int interval) {
    AutoMutex _lock(&mLock);

    if (interval > (3600000)) // Max interval is once every 60 min
        return BAD_VALUE;

    if (interval < 250) // Min interval is once per 0.25 seconds
        return BAD_VALUE;

    mSyncRequestIntervalMs = interval;
    if (ICommonClock::STATE_CLIENT == mState) {
        int pendingTimeout = mCurTimeout.msecTillTimeout();
        if ((kInfiniteTimeout == pendingTimeout) ||
            (pendingTimeout > interval)) {
            mCurTimeout.setTimeout(mSyncRequestIntervalMs);
            wakeupThread_l();
        }
    }

    return OK;
}

status_t CommonTimeServer::getPanicThreshold(int *threshold) {
    AutoMutex _lock(&mLock);
    *threshold = mPanicThresholdUsec;
    return OK;
}

status_t CommonTimeServer::setPanicThreshold(int threshold) {
    AutoMutex _lock(&mLock);

    if (threshold < 1000) // Min threshold is 1mSec
        return BAD_VALUE;

    mPanicThresholdUsec = threshold;
    return OK;
}

status_t CommonTimeServer::getAutoDisable(bool *autoDisable) {
    AutoMutex _lock(&mLock);
    *autoDisable = mAutoDisable;
    return OK;
}

status_t CommonTimeServer::setAutoDisable(bool autoDisable) {
    AutoMutex _lock(&mLock);
    mAutoDisable = autoDisable;
    wakeupThread_l();
    return OK;
}

status_t CommonTimeServer::forceNetworklessMasterMode() {
    AutoMutex _lock(&mLock);

    // Can't force networkless master mode if we are currently bound to a
    // network.
    if (mSocket >= 0)
        return INVALID_OPERATION;

    becomeMaster("force networkless");

    return OK;
}

void CommonTimeServer::reevaluateAutoDisableState(bool commonClockHasClients) {
    AutoMutex _lock(&mLock);
    bool needWakeup = (mAutoDisable && mMasterEPValid &&
                      (commonClockHasClients != mCommonClockHasClients));

    mCommonClockHasClients = commonClockHasClients;

    if (needWakeup) {
        ALOGI("Waking up service, auto-disable is engaged and service now has%s"
             " clients", mCommonClockHasClients ? "" : " no");
        wakeupThread_l();
    }
}

#define dump_printf(a, b...) do {                 \
    int res;                                      \
    res = snprintf(buffer, sizeof(buffer), a, b); \
    buffer[sizeof(buffer) - 1] = 0;               \
    if (res > 0)                                  \
        write(fd, buffer, res);                   \
} while (0)
#define checked_percentage(a, b) ((0 == b) ? 0.0f : ((100.0f * a) / b))

status_t CommonTimeServer::dumpClockInterface(int fd,
                                              const Vector<String16>& args,
                                              size_t activeClients) {
    AutoMutex _lock(&mLock);
    const size_t SIZE = 256;
    char buffer[SIZE];

    if (checkCallingPermission(String16("android.permission.DUMP")) == false) {
        snprintf(buffer, SIZE, "Permission Denial: "
                 "can't dump CommonClockService from pid=%d, uid=%d\n",
                 IPCThreadState::self()->getCallingPid(),
                 IPCThreadState::self()->getCallingUid());
        write(fd, buffer, strlen(buffer));
    } else {
        int64_t commonTime;
        int64_t localTime;
        bool    synced;
        char maStr[64];

        localTime  = mLocalClock.getLocalTime();
        synced     = (OK == mCommonClock.localToCommon(localTime, &commonTime));
        sockaddrToString(mMasterEP, mMasterEPValid, maStr, sizeof(maStr));

        dump_printf("Common Clock Service Status\nLocal time     : %lld\n",
                    localTime);

        if (synced)
            dump_printf("Common time    : %lld\n", commonTime);
        else
            dump_printf("Common time    : %s\n", "not synced");

        dump_printf("Timeline ID    : %016llx\n", mTimelineID);
        dump_printf("State          : %s\n", stateToString(mState));
        dump_printf("Master Addr    : %s\n", maStr);


        if (synced) {
            int32_t est = (ICommonClock::STATE_MASTER != mState)
                        ? mClockRecovery.getLastErrorEstimate()
                        : 0;
            dump_printf("Error Est.     : %.3f msec\n",
                        static_cast<float>(est) / 1000.0);
        } else {
            dump_printf("Error Est.     : %s\n", "unknown");
        }

        dump_printf("Syncs TXes     : %u\n", mClient_SyncsSentToCurMaster);
        dump_printf("Syncs RXes     : %u (%.2f%%)\n",
                    mClient_SyncRespsRXedFromCurMaster,
                    checked_percentage(
                        mClient_SyncRespsRXedFromCurMaster,
                        mClient_SyncsSentToCurMaster));
        dump_printf("RXs Expired    : %u (%.2f%%)\n",
                    mClient_ExpiredSyncRespsRXedFromCurMaster,
                    checked_percentage(
                        mClient_ExpiredSyncRespsRXedFromCurMaster,
                        mClient_SyncsSentToCurMaster));

        if (!mClient_LastGoodSyncRX) {
            dump_printf("Last Good RX   : %s\n", "unknown");
        } else {
            int64_t localDelta, usecDelta;
            localDelta = localTime - mClient_LastGoodSyncRX;
            usecDelta  = mCommonClock.localDurationToCommonDuration(localDelta);
            dump_printf("Last Good RX   : %lld uSec ago\n", usecDelta);
        }

        dump_printf("Active Clients : %u\n", activeClients);
        mClient_PacketRTTLog.dumpLog(fd, mCommonClock);
        mStateChangeLog.dumpLog(fd);
        mElectionLog.dumpLog(fd);
        mBadPktLog.dumpLog(fd);
    }

    return NO_ERROR;
}

status_t CommonTimeServer::dumpConfigInterface(int fd,
                                               const Vector<String16>& args) {
    AutoMutex _lock(&mLock);
    const size_t SIZE = 256;
    char buffer[SIZE];

    if (checkCallingPermission(String16("android.permission.DUMP")) == false) {
        snprintf(buffer, SIZE, "Permission Denial: "
                 "can't dump CommonTimeConfigService from pid=%d, uid=%d\n",
                 IPCThreadState::self()->getCallingPid(),
                 IPCThreadState::self()->getCallingUid());
        write(fd, buffer, strlen(buffer));
    } else {
        char meStr[64];

        sockaddrToString(mMasterElectionEP, true, meStr, sizeof(meStr));

        dump_printf("Common Time Config Service Status\n"
                    "Bound Interface           : %s\n",
                    mBindIfaceValid ? mBindIface.string() : "<unbound>");
        dump_printf("Master Election Endpoint  : %s\n", meStr);
        dump_printf("Master Election Group ID  : %016llx\n", mSyncGroupID);
        dump_printf("Master Announce Interval  : %d mSec\n",
                    mMasterAnnounceIntervalMs);
        dump_printf("Client Sync Interval      : %d mSec\n",
                    mSyncRequestIntervalMs);
        dump_printf("Panic Threshold           : %d uSec\n",
                    mPanicThresholdUsec);
        dump_printf("Base ME Prio              : 0x%02x\n",
                    static_cast<uint32_t>(mMasterPriority));
        dump_printf("Effective ME Prio         : 0x%02x\n",
                    static_cast<uint32_t>(effectivePriority()));
        dump_printf("Auto Disable Allowed      : %s\n",
                    mAutoDisable ? "yes" : "no");
        dump_printf("Auto Disable Engaged      : %s\n",
                    shouldAutoDisable() ? "yes" : "no");
    }

    return NO_ERROR;
}

void CommonTimeServer::PacketRTTLog::dumpLog(int fd, const CommonClock& cclk) {
    const size_t SIZE = 256;
    char buffer[SIZE];
    uint32_t avail = !logFull ? wrPtr : RTT_LOG_SIZE;

    if (!avail)
        return;

    dump_printf("\nPacket Log (%d entries)\n", avail);

    uint32_t ndx = 0;
    uint32_t i = logFull ? wrPtr : 0;
    do {
        if (rxTimes[i]) {
            int64_t delta = rxTimes[i] - txTimes[i];
            int64_t deltaUsec = cclk.localDurationToCommonDuration(delta);
            dump_printf("pkt[%2d] : localTX %12lld localRX %12lld "
                        "(%.3f msec RTT)\n",
                        ndx, txTimes[i], rxTimes[i],
                        static_cast<float>(deltaUsec) / 1000.0);
        } else {
            dump_printf("pkt[%2d] : localTX %12lld localRX never\n",
                        ndx, txTimes[i]);
        }
        i = (i + 1) % RTT_LOG_SIZE;
        ndx++;
    } while (i != wrPtr);
}

#undef dump_printf
#undef checked_percentage

}  // namespace android
