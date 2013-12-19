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

#ifndef ANDROID_COMMON_TIME_SERVER_H
#define ANDROID_COMMON_TIME_SERVER_H

#include <arpa/inet.h>
#include <stdint.h>
#include <sys/socket.h>

#include <common_time/ICommonClock.h>
#include <common_time/local_clock.h>
#include <utils/String8.h>

#include "clock_recovery.h"
#include "common_clock.h"
#include "common_time_server_packets.h"
#include "utils.h"

#define RTT_LOG_SIZE 30

namespace android {

class CommonClockService;
class CommonTimeConfigService;

/***** time service implementation *****/

class CommonTimeServer : public Thread {
  public:
    CommonTimeServer();
    ~CommonTimeServer();

    bool startServices();

    // Common Clock API methods
    CommonClock&        getCommonClock()        { return mCommonClock; }
    LocalClock&         getLocalClock()         { return mLocalClock; }
    uint64_t            getTimelineID();
    int32_t             getEstimatedError();
    ICommonClock::State getState();
    status_t            getMasterAddr(struct sockaddr_storage* addr);
    status_t            isCommonTimeValid(bool* valid, uint32_t* timelineID);

    // Config API methods
    status_t getMasterElectionPriority(uint8_t *priority);
    status_t setMasterElectionPriority(uint8_t priority);
    status_t getMasterElectionEndpoint(struct sockaddr_storage *addr);
    status_t setMasterElectionEndpoint(const struct sockaddr_storage *addr);
    status_t getMasterElectionGroupId(uint64_t *id);
    status_t setMasterElectionGroupId(uint64_t id);
    status_t getInterfaceBinding(String8& ifaceName);
    status_t setInterfaceBinding(const String8& ifaceName);
    status_t getMasterAnnounceInterval(int *interval);
    status_t setMasterAnnounceInterval(int interval);
    status_t getClientSyncInterval(int *interval);
    status_t setClientSyncInterval(int interval);
    status_t getPanicThreshold(int *threshold);
    status_t setPanicThreshold(int threshold);
    status_t getAutoDisable(bool *autoDisable);
    status_t setAutoDisable(bool autoDisable);
    status_t forceNetworklessMasterMode();

    // Method used by the CommonClockService to notify the core service about
    // changes in the number of active common clock clients.
    void reevaluateAutoDisableState(bool commonClockHasClients);

    status_t dumpClockInterface(int fd, const Vector<String16>& args,
                                size_t activeClients);
    status_t dumpConfigInterface(int fd, const Vector<String16>& args);

  private:
    class PacketRTTLog {
      public:
        PacketRTTLog() {
            resetLog();
        }

        void resetLog() {
            wrPtr = 0;
            logFull = 0;
        }

        void logTX(int64_t txTime);
        void logRX(int64_t txTime, int64_t rxTime);
        void dumpLog(int fd, const CommonClock& cclk);

      private:
        uint32_t wrPtr;
        bool logFull;
        int64_t txTimes[RTT_LOG_SIZE];
        int64_t rxTimes[RTT_LOG_SIZE];
    };

    bool threadLoop();

    bool runStateMachine_l();
    bool setupSocket_l();

    void assignTimelineID();
    bool assignDeviceID();

    static bool arbitrateMaster(uint64_t deviceID1, uint8_t devicePrio1,
                                uint64_t deviceID2, uint8_t devicePrio2);

    bool handlePacket();
    bool handleWhoIsMasterRequest (const WhoIsMasterRequestPacket* request,
                                   const sockaddr_storage& srcAddr);
    bool handleWhoIsMasterResponse(const WhoIsMasterResponsePacket* response,
                                   const sockaddr_storage& srcAddr);
    bool handleSyncRequest        (const SyncRequestPacket* request,
                                   const sockaddr_storage& srcAddr);
    bool handleSyncResponse       (const SyncResponsePacket* response,
                                   const sockaddr_storage& srcAddr);
    bool handleMasterAnnouncement (const MasterAnnouncementPacket* packet,
                                   const sockaddr_storage& srcAddr);

    bool handleTimeout();
    bool handleTimeoutInitial();
    bool handleTimeoutClient();
    bool handleTimeoutMaster();
    bool handleTimeoutRonin();
    bool handleTimeoutWaitForElection();

    bool sendWhoIsMasterRequest();
    bool sendSyncRequest();
    bool sendMasterAnnouncement();

    bool becomeClient(const sockaddr_storage& masterAddr,
                      uint64_t masterDeviceID,
                      uint8_t  masterDevicePriority,
                      uint64_t timelineID,
                      const char* cause);
    bool becomeMaster(const char* cause);
    bool becomeRonin(const char* cause);
    bool becomeWaitForElection(const char* cause);
    bool becomeInitial(const char* cause);

    void notifyClockSync();
    void notifyClockSyncLoss();

    ICommonClock::State mState;
    void setState(ICommonClock::State s);

    void clearPendingWakeupEvents_l();
    void wakeupThread_l();
    void cleanupSocket_l();
    void shutdownThread();

    inline uint8_t effectivePriority() const {
        return (mMasterPriority & 0x7F) |
               (mForceLowPriority ? 0x00 : 0x80);
    }

    inline bool shouldAutoDisable() const {
        return (mAutoDisable && !mCommonClockHasClients);
    }

    inline void resetSyncStats() {
        mClient_SyncRequestPending = false;
        mClient_SyncRequestTimeouts = 0;
        mClient_SyncsSentToCurMaster = 0;
        mClient_SyncRespsRXedFromCurMaster = 0;
        mClient_ExpiredSyncRespsRXedFromCurMaster = 0;
        mClient_FirstSyncTX = 0;
        mClient_LastGoodSyncRX = 0;
        mClient_PacketRTTLog.resetLog();
    }

    bool shouldPanicNotGettingGoodData();

    // Helper to keep track of the state machine's current timeout
    Timeout mCurTimeout;

    // common clock, local clock abstraction, and clock recovery loop
    CommonClock mCommonClock;
    LocalClock mLocalClock;
    ClockRecoveryLoop mClockRecovery;

    // implementation of ICommonClock
    sp<CommonClockService> mICommonClock;

    // implementation of ICommonTimeConfig
    sp<CommonTimeConfigService> mICommonTimeConfig;

    // UDP socket for the time sync protocol
    int mSocket;

    // eventfd used to wakeup the work thread in response to configuration
    // changes.
    int mWakeupThreadFD;

    // timestamp captured when a packet is received
    int64_t mLastPacketRxLocalTime;

    // ID of the timeline that this device is following
    uint64_t mTimelineID;

    // flag for whether the clock has been synced to a timeline
    bool mClockSynced;

    // flag used to indicate that clients should be considered to be lower
    // priority than all of their peers during elections.  This flag is set and
    // cleared by the state machine.  It is set when the client joins a new
    // network.  If the client had been a master in the old network (or an
    // isolated master with no network connectivity) it should defer to any
    // masters which may already be on the network.  It will be cleared whenever
    // the state machine transitions to the master state.
    bool mForceLowPriority;
    inline void setForceLowPriority(bool val) {
        mForceLowPriority = val;
        if (mState == ICommonClock::STATE_MASTER)
            mClient_MasterDevicePriority = effectivePriority();
    }

    // Lock to synchronize access to internal state and configuration.
    Mutex mLock;

    // Flag updated by the common clock service to indicate that it does or does
    // not currently have registered clients.  When the the auto disable flag is
    // cleared on the common time service, the service will participate in
    // network synchronization whenever it has a valid network interface to bind
    // to.  When the auto disable flag is set on the common time service, it
    // will only participate in network synchronization when it has both a valid
    // interface AND currently active common clock clients.
    bool mCommonClockHasClients;

    // Internal logs used for dumpsys.
    LogRing                 mStateChangeLog;
    LogRing                 mElectionLog;
    LogRing                 mBadPktLog;

    // Configuration info
    struct sockaddr_storage mMasterElectionEP;          // Endpoint over which we conduct master election
    String8                 mBindIface;                 // Endpoint for the service to bind to.
    bool                    mBindIfaceValid;            // whether or not the bind Iface is valid.
    bool                    mBindIfaceDirty;            // whether or not the bind Iface is valid.
    struct sockaddr_storage mMasterEP;                  // Endpoint of our current master (if any)
    bool                    mMasterEPValid;
    uint64_t                mDeviceID;                  // unique ID of this device
    uint64_t                mSyncGroupID;               // synchronization group ID of this device.
    uint8_t                 mMasterPriority;            // Priority of this device in master election.
    uint32_t                mMasterAnnounceIntervalMs;
    uint32_t                mSyncRequestIntervalMs;
    uint32_t                mPanicThresholdUsec;
    bool                    mAutoDisable;

    // Config defaults.
    static const char*      kDefaultMasterElectionAddr;
    static const uint16_t   kDefaultMasterElectionPort;
    static const uint64_t   kDefaultSyncGroupID;
    static const uint8_t    kDefaultMasterPriority;
    static const uint32_t   kDefaultMasterAnnounceIntervalMs;
    static const uint32_t   kDefaultSyncRequestIntervalMs;
    static const uint32_t   kDefaultPanicThresholdUsec;
    static const bool       kDefaultAutoDisable;

    // Priority mask and shift fields.
    static const uint64_t kDeviceIDMask;
    static const uint8_t  kDevicePriorityMask;
    static const uint8_t  kDevicePriorityHiLowBit;
    static const uint32_t kDevicePriorityShift;

    // Unconfgurable constants
    static const int      kSetupRetryTimeoutMs;
    static const int64_t  kNoGoodDataPanicThresholdUsec;
    static const uint32_t kRTTDiscardPanicThreshMultiplier;

    /*** status while in the Initial state ***/
    int mInitial_WhoIsMasterRequestTimeouts;
    static const int kInitial_NumWhoIsMasterRetries;
    static const int kInitial_WhoIsMasterTimeoutMs;

    /*** status while in the Client state ***/
    uint64_t mClient_MasterDeviceID;
    uint8_t mClient_MasterDevicePriority;
    bool mClient_SyncRequestPending;
    int mClient_SyncRequestTimeouts;
    uint32_t mClient_SyncsSentToCurMaster;
    uint32_t mClient_SyncRespsRXedFromCurMaster;
    uint32_t mClient_ExpiredSyncRespsRXedFromCurMaster;
    int64_t mClient_FirstSyncTX;
    int64_t mClient_LastGoodSyncRX;
    PacketRTTLog mClient_PacketRTTLog;
    static const int kClient_NumSyncRequestRetries;


    /*** status while in the Master state ***/
    static const uint32_t kDefaultMaster_AnnouncementIntervalMs;

    /*** status while in the Ronin state ***/
    int mRonin_WhoIsMasterRequestTimeouts;
    static const int kRonin_NumWhoIsMasterRetries;
    static const int kRonin_WhoIsMasterTimeoutMs;

    /*** status while in the WaitForElection state ***/
    static const int kWaitForElection_TimeoutMs;

    static const int kInfiniteTimeout;

    static const char* stateToString(ICommonClock::State s);
    static void sockaddrToString(const sockaddr_storage& addr, bool addrValid,
                                 char* buf, size_t bufLen);
    static bool sockaddrMatch(const sockaddr_storage& a1,
                              const sockaddr_storage& a2,
                              bool matchAddressOnly);
};

}  // namespace android

#endif  // ANDROID_COMMON_TIME_SERVER_H
