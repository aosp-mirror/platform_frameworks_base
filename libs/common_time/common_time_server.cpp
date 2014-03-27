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

#include <arpa/inet.h>
#include <assert.h>
#include <fcntl.h>
#include <linux/if_ether.h>
#include <net/if.h>
#include <net/if_arp.h>
#include <netinet/ip.h>
#include <poll.h>
#include <stdio.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/socket.h>

#include <common_time/local_clock.h>
#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <utils/Timers.h>

#include "common_clock_service.h"
#include "common_time_config_service.h"
#include "common_time_server.h"
#include "common_time_server_packets.h"
#include "clock_recovery.h"
#include "common_clock.h"

#define MAX_INT ((int)0x7FFFFFFF)

namespace android {

const char*    CommonTimeServer::kDefaultMasterElectionAddr = "255.255.255.255";
const uint16_t CommonTimeServer::kDefaultMasterElectionPort = 8886;
const uint64_t CommonTimeServer::kDefaultSyncGroupID = 1;
const uint8_t  CommonTimeServer::kDefaultMasterPriority = 1;
const uint32_t CommonTimeServer::kDefaultMasterAnnounceIntervalMs = 10000;
const uint32_t CommonTimeServer::kDefaultSyncRequestIntervalMs = 1000;
const uint32_t CommonTimeServer::kDefaultPanicThresholdUsec = 50000;
const bool     CommonTimeServer::kDefaultAutoDisable = true;
const int      CommonTimeServer::kSetupRetryTimeoutMs = 30000;
const int64_t  CommonTimeServer::kNoGoodDataPanicThresholdUsec = 600000000ll;
const uint32_t CommonTimeServer::kRTTDiscardPanicThreshMultiplier = 5;

// timeout value representing an infinite timeout
const int CommonTimeServer::kInfiniteTimeout = -1;

/*** Initial state constants ***/

// number of WhoIsMaster attempts sent before giving up
const int CommonTimeServer::kInitial_NumWhoIsMasterRetries = 6;

// timeout used when waiting for a response to a WhoIsMaster request
const int CommonTimeServer::kInitial_WhoIsMasterTimeoutMs = 500;

/*** Client state constants ***/

// number of sync requests that can fail before a client assumes its master
// is dead
const int CommonTimeServer::kClient_NumSyncRequestRetries = 10;

/*** Master state constants ***/

/*** Ronin state constants ***/

// number of WhoIsMaster attempts sent before declaring ourselves master
const int CommonTimeServer::kRonin_NumWhoIsMasterRetries = 20;

// timeout used when waiting for a response to a WhoIsMaster request
const int CommonTimeServer::kRonin_WhoIsMasterTimeoutMs = 500;

/*** WaitForElection state constants ***/

// how long do we wait for an announcement from a master before
// trying another election?
const int CommonTimeServer::kWaitForElection_TimeoutMs = 12500;

CommonTimeServer::CommonTimeServer()
    : Thread(false)
    , mState(ICommonClock::STATE_INITIAL)
    , mClockRecovery(&mLocalClock, &mCommonClock)
    , mSocket(-1)
    , mLastPacketRxLocalTime(0)
    , mTimelineID(ICommonClock::kInvalidTimelineID)
    , mClockSynced(false)
    , mCommonClockHasClients(false)
    , mStateChangeLog("Recent State Change Events", 30)
    , mElectionLog("Recent Master Election Traffic", 30)
    , mBadPktLog("Recent Bad Packet RX Info", 8)
    , mInitial_WhoIsMasterRequestTimeouts(0)
    , mClient_MasterDeviceID(0)
    , mClient_MasterDevicePriority(0)
    , mRonin_WhoIsMasterRequestTimeouts(0) {
    // zero out sync stats
    resetSyncStats();

    // Setup the master election endpoint to use the default.
    struct sockaddr_in* meep =
        reinterpret_cast<struct sockaddr_in*>(&mMasterElectionEP);
    memset(&mMasterElectionEP, 0, sizeof(mMasterElectionEP));
    inet_aton(kDefaultMasterElectionAddr, &meep->sin_addr);
    meep->sin_family = AF_INET;
    meep->sin_port   = htons(kDefaultMasterElectionPort);

    // Zero out the master endpoint.
    memset(&mMasterEP, 0, sizeof(mMasterEP));
    mMasterEPValid    = false;
    mBindIfaceValid   = false;
    setForceLowPriority(false);

    // Set all remaining configuration parameters to their defaults.
    mDeviceID                 = 0;
    mSyncGroupID              = kDefaultSyncGroupID;
    mMasterPriority           = kDefaultMasterPriority;
    mMasterAnnounceIntervalMs = kDefaultMasterAnnounceIntervalMs;
    mSyncRequestIntervalMs    = kDefaultSyncRequestIntervalMs;
    mPanicThresholdUsec       = kDefaultPanicThresholdUsec;
    mAutoDisable              = kDefaultAutoDisable;

    // Create the eventfd we will use to signal our thread to wake up when
    // needed.
    mWakeupThreadFD = eventfd(0, EFD_NONBLOCK);

    // seed the random number generator (used to generated timeline IDs)
    srand48(static_cast<unsigned int>(systemTime()));
}

CommonTimeServer::~CommonTimeServer() {
    shutdownThread();

    // No need to grab the lock here.  We are in the destructor; if the the user
    // has a thread in any of the APIs while the destructor is being called,
    // there is a threading problem a the application level we cannot reasonably
    // do anything about.
    cleanupSocket_l();

    if (mWakeupThreadFD >= 0) {
        close(mWakeupThreadFD);
        mWakeupThreadFD = -1;
    }
}

bool CommonTimeServer::startServices() {
    // start the ICommonClock service
    mICommonClock = CommonClockService::instantiate(*this);
    if (mICommonClock == NULL)
        return false;

    // start the ICommonTimeConfig service
    mICommonTimeConfig = CommonTimeConfigService::instantiate(*this);
    if (mICommonTimeConfig == NULL)
        return false;

    return true;
}

bool CommonTimeServer::threadLoop() {
    // Register our service interfaces.
    if (!startServices())
        return false;

    // Hold the lock while we are in the main thread loop.  It will release the
    // lock when it blocks, and hold the lock at all other times.
    mLock.lock();
    runStateMachine_l();
    mLock.unlock();

    IPCThreadState::self()->stopProcess();
    return false;
}

bool CommonTimeServer::runStateMachine_l() {
    if (!mLocalClock.initCheck())
        return false;

    if (!mCommonClock.init(mLocalClock.getLocalFreq()))
        return false;

    // Enter the initial state.
    becomeInitial("startup");

    // run the state machine
    while (!exitPending()) {
        struct pollfd pfds[2];
        int rc, timeout;
        int eventCnt = 0;
        int64_t wakeupTime;
        uint32_t t1, t2;
        bool needHandleTimeout = false;

        // We are always interested in our wakeup FD.
        pfds[eventCnt].fd      = mWakeupThreadFD;
        pfds[eventCnt].events  = POLLIN;
        pfds[eventCnt].revents = 0;
        eventCnt++;

        // If we have a valid socket, then we are interested in what it has to
        // say as well.
        if (mSocket >= 0) {
            pfds[eventCnt].fd      = mSocket;
            pfds[eventCnt].events  = POLLIN;
            pfds[eventCnt].revents = 0;
            eventCnt++;
        }

        t1 = static_cast<uint32_t>(mCurTimeout.msecTillTimeout());
        t2 = static_cast<uint32_t>(mClockRecovery.applyRateLimitedSlew());
        timeout = static_cast<int>(t1 < t2 ? t1 : t2);

        // Note, we were holding mLock when this function was called.  We
        // release it only while we are blocking and hold it at all other times.
        mLock.unlock();
        rc          = poll(pfds, eventCnt, timeout);
        wakeupTime  = mLocalClock.getLocalTime();
        mLock.lock();

        // Is it time to shutdown?  If so, don't hesitate... just do it.
        if (exitPending())
            break;

        // Did the poll fail?  This should never happen and is fatal if it does.
        if (rc < 0) {
            ALOGE("%s:%d poll failed", __PRETTY_FUNCTION__, __LINE__);
            return false;
        }

        if (rc == 0) {
            needHandleTimeout = !mCurTimeout.msecTillTimeout();
            if (needHandleTimeout)
                mCurTimeout.setTimeout(kInfiniteTimeout);
        }

        // Were we woken up on purpose?  If so, clear the eventfd with a read.
        if (pfds[0].revents)
            clearPendingWakeupEvents_l();

        // Is out bind address dirty?  If so, clean up our socket (if any).
        // Alternatively, do we have an active socket but should be auto
        // disabled?  If so, release the socket and enter the proper sync state.
        bool droppedSocket = false;
        if (mBindIfaceDirty || ((mSocket >= 0) && shouldAutoDisable())) {
            cleanupSocket_l();
            mBindIfaceDirty = false;
            droppedSocket = true;
        }

        // Do we not have a socket but should have one?  If so, try to set one
        // up.
        if ((mSocket < 0) && mBindIfaceValid && !shouldAutoDisable()) {
            if (setupSocket_l()) {
                // Success!  We are now joining a new network (either coming
                // from no network, or coming from a potentially different
                // network).  Force our priority to be lower so that we defer to
                // any other masters which may already be on the network we are
                // joining.  Later, when we enter either the client or the
                // master state, we will clear this flag and go back to our
                // normal election priority.
                setForceLowPriority(true);
                switch (mState) {
                    // If we were in initial (whether we had a immediately
                    // before this network or not) we want to simply reset the
                    // system and start again.  Forcing a transition from
                    // INITIAL to INITIAL should do the job.
                    case CommonClockService::STATE_INITIAL:
                        becomeInitial("bound interface");
                        break;

                    // If we were in the master state, then either we were the
                    // master in a no-network situation, or we were the master
                    // of a different network and have moved to a new interface.
                    // In either case, immediately transition to Ronin at low
                    // priority.  If there is no one in the network we just
                    // joined, we will become master soon enough.  If there is,
                    // we want to be certain to defer master status to the
                    // existing timeline currently running on the network.
                    //
                    case CommonClockService::STATE_MASTER:
                        becomeRonin("leaving networkless mode");
                        break;

                    // If we were in any other state (CLIENT, RONIN, or
                    // WAIT_FOR_ELECTION) then we must be moving from one
                    // network to another.  We have lost our old master;
                    // transition to RONIN in an attempt to find a new master.
                    // If there are none out there, we will just assume
                    // responsibility for the timeline we used to be a client
                    // of.
                    default:
                        becomeRonin("bound interface");
                        break;
                }
            } else {
                // That's odd... we failed to set up our socket.  This could be
                // due to some transient network change which will work itself
                // out shortly; schedule a retry attempt in the near future.
                mCurTimeout.setTimeout(kSetupRetryTimeoutMs);
            }

            // One way or the other, we don't have any data to process at this
            // point (since we just tried to bulid a new socket).  Loop back
            // around and wait for the next thing to do.
            continue;
        } else if (droppedSocket) {
            // We just lost our socket, and for whatever reason (either no
            // config, or auto disable engaged) we are not supposed to rebuild
            // one at this time.  We are not going to rebuild our socket until
            // something about our config/auto-disabled status changes, so we
            // are basically in network-less mode.  If we are already in either
            // INITIAL or MASTER, just stay there until something changes.  If
            // we are in any other state (CLIENT, RONIN or WAIT_FOR_ELECTION),
            // then transition to either INITIAL or MASTER depending on whether
            // or not our timeline is valid.
            mStateChangeLog.log(ANDROID_LOG_INFO, LOG_TAG,
                    "Entering networkless mode interface is %s, "
                    "shouldAutoDisable = %s",
                    mBindIfaceValid ? "valid" : "invalid",
                    shouldAutoDisable() ? "true" : "false");
            if ((mState != ICommonClock::STATE_INITIAL) &&
                (mState != ICommonClock::STATE_MASTER)) {
                if (mTimelineID == ICommonClock::kInvalidTimelineID)
                    becomeInitial("network-less mode");
                else
                    becomeMaster("network-less mode");
            }

            continue;
        }

        // Time to handle the timeouts?
        if (needHandleTimeout) {
            if (!handleTimeout())
                ALOGE("handleTimeout failed");
            continue;
        }

        // Does our socket have data for us (assuming we still have one, we
        // may have RXed a packet at the same time as a config change telling us
        // to shut our socket down)?  If so, process its data.
        if ((mSocket >= 0) && (eventCnt > 1) && (pfds[1].revents)) {
            mLastPacketRxLocalTime = wakeupTime;
            if (!handlePacket())
                ALOGE("handlePacket failed");
        }
    }

    cleanupSocket_l();
    return true;
}

void CommonTimeServer::clearPendingWakeupEvents_l() {
    int64_t tmp;
    read(mWakeupThreadFD, &tmp, sizeof(tmp));
}

void CommonTimeServer::wakeupThread_l() {
    int64_t tmp = 1;
    write(mWakeupThreadFD, &tmp, sizeof(tmp));
}

void CommonTimeServer::cleanupSocket_l() {
    if (mSocket >= 0) {
        close(mSocket);
        mSocket = -1;
    }
}

void CommonTimeServer::shutdownThread() {
    // Flag the work thread for shutdown.
    this->requestExit();

    // Signal the thread in case its sleeping.
    mLock.lock();
    wakeupThread_l();
    mLock.unlock();

    // Wait for the thread to exit.
    this->join();
}

bool CommonTimeServer::setupSocket_l() {
    int rc;
    bool ret_val = false;
    struct sockaddr_in* ipv4_addr = NULL;
    char masterElectionEPStr[64];
    const int one = 1;

    // This should never be needed, but if we happened to have an old socket
    // lying around, be sure to not leak it before proceeding.
    cleanupSocket_l();

    // If we don't have a valid endpoint to bind to, then how did we get here in
    // the first place?  Regardless, we know that we are going to fail to bind,
    // so don't even try.
    if (!mBindIfaceValid)
        return false;

    sockaddrToString(mMasterElectionEP, true, masterElectionEPStr,
                     sizeof(masterElectionEPStr));
    mStateChangeLog.log(ANDROID_LOG_INFO, LOG_TAG,
                        "Building socket :: bind = %s master election = %s",
                        mBindIface.string(), masterElectionEPStr);

    // TODO: add proper support for IPv6.  Right now, we block IPv6 addresses at
    // the configuration interface level.
    if (AF_INET != mMasterElectionEP.ss_family) {
        mStateChangeLog.log(ANDROID_LOG_WARN, LOG_TAG,
                            "TODO: add proper IPv6 support");
        goto bailout;
    }

    // open a UDP socket for the timeline serivce
    mSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (mSocket < 0) {
        mStateChangeLog.log(ANDROID_LOG_ERROR, LOG_TAG,
                            "Failed to create socket (errno = %d)", errno);
        goto bailout;
    }

    // Bind to the selected interface using Linux's spiffy SO_BINDTODEVICE.
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    snprintf(ifr.ifr_name, sizeof(ifr.ifr_name), "%s", mBindIface.string());
    ifr.ifr_name[sizeof(ifr.ifr_name) - 1] = 0;
    rc = setsockopt(mSocket, SOL_SOCKET, SO_BINDTODEVICE,
                    (void *)&ifr, sizeof(ifr));
    if (rc) {
        mStateChangeLog.log(ANDROID_LOG_ERROR, LOG_TAG,
                            "Failed to bind socket at to interface %s "
                            "(errno = %d)", ifr.ifr_name, errno);
        goto bailout;
    }

    // Bind our socket to INADDR_ANY and the master election port.  The
    // interface binding we made using SO_BINDTODEVICE should limit us to
    // traffic only on the interface we are interested in.  We need to bind to
    // INADDR_ANY and the specific master election port in order to be able to
    // receive both unicast traffic and master election multicast traffic with
    // just a single socket.
    struct sockaddr_in bindAddr;
    ipv4_addr = reinterpret_cast<struct sockaddr_in*>(&mMasterElectionEP);
    memcpy(&bindAddr, ipv4_addr, sizeof(bindAddr));
    bindAddr.sin_addr.s_addr = INADDR_ANY;
    rc = bind(mSocket,
              reinterpret_cast<const sockaddr *>(&bindAddr),
              sizeof(bindAddr));
    if (rc) {
        mStateChangeLog.log(ANDROID_LOG_ERROR, LOG_TAG,
                            "Failed to bind socket to port %hu (errno = %d)",
                            ntohs(bindAddr.sin_port), errno);
        goto bailout;
    }

    if (0xE0000000 == (ntohl(ipv4_addr->sin_addr.s_addr) & 0xF0000000)) {
        // If our master election endpoint is a multicast address, be sure to join
        // the multicast group.
        struct ip_mreq mreq;
        mreq.imr_multiaddr = ipv4_addr->sin_addr;
        mreq.imr_interface.s_addr = htonl(INADDR_ANY);
        rc = setsockopt(mSocket, IPPROTO_IP, IP_ADD_MEMBERSHIP,
                        &mreq, sizeof(mreq));
        if (rc == -1) {
            ALOGE("Failed to join multicast group at %s.  (errno = %d)",
                 masterElectionEPStr, errno);
            goto bailout;
        }

        // disable loopback of multicast packets
        const int zero = 0;
        rc = setsockopt(mSocket, IPPROTO_IP, IP_MULTICAST_LOOP,
                        &zero, sizeof(zero));
        if (rc == -1) {
            mStateChangeLog.log(ANDROID_LOG_ERROR, LOG_TAG,
                                "Failed to disable multicast loopback "
                                "(errno = %d)", errno);
            goto bailout;
        }
    } else
    if (ntohl(ipv4_addr->sin_addr.s_addr) == 0xFFFFFFFF) {
        // If the master election address is the broadcast address, then enable
        // the broadcast socket option
        rc = setsockopt(mSocket, SOL_SOCKET, SO_BROADCAST, &one, sizeof(one));
        if (rc == -1) {
            mStateChangeLog.log(ANDROID_LOG_ERROR, LOG_TAG,
                                "Failed to enable broadcast (errno = %d)",
                                errno);
            goto bailout;
        }
    } else {
        // If the master election address is neither broadcast, nor multicast,
        // then we are misconfigured.  The config API layer should prevent this
        // from ever happening.
        goto bailout;
    }

    // Set the TTL of sent packets to 1.  (Time protocol sync should never leave
    // the local subnet)
    rc = setsockopt(mSocket, IPPROTO_IP, IP_TTL, &one, sizeof(one));
    if (rc == -1) {
        mStateChangeLog.log(ANDROID_LOG_ERROR, LOG_TAG,
                            "Failed to set TTL to %d (errno = %d)", one, errno);
        goto bailout;
    }

    // get the device's unique ID
    if (!assignDeviceID())
        goto bailout;

    ret_val = true;

bailout:
    if (!ret_val)
        cleanupSocket_l();
    return ret_val;
}

// generate a unique device ID that can be used for arbitration
bool CommonTimeServer::assignDeviceID() {
    if (!mBindIfaceValid)
        return false;

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    ifr.ifr_addr.sa_family = AF_INET;
    strlcpy(ifr.ifr_name, mBindIface.string(), IFNAMSIZ);

    int rc = ioctl(mSocket, SIOCGIFHWADDR, &ifr);
    if (rc) {
        ALOGE("%s:%d ioctl failed", __PRETTY_FUNCTION__, __LINE__);
        return false;
    }

    if (ifr.ifr_addr.sa_family != ARPHRD_ETHER) {
        ALOGE("%s:%d got non-Ethernet address", __PRETTY_FUNCTION__, __LINE__);
        return false;
    }

    mDeviceID = 0;
    for (int i = 0; i < ETH_ALEN; i++) {
        mDeviceID = (mDeviceID << 8) | ifr.ifr_hwaddr.sa_data[i];
    }

    return true;
}

// generate a new timeline ID
void CommonTimeServer::assignTimelineID() {
    do {
        mTimelineID = (static_cast<uint64_t>(lrand48()) << 32)
                    |  static_cast<uint64_t>(lrand48());
    } while (mTimelineID == ICommonClock::kInvalidTimelineID);
}

// Select a preference between the device IDs of two potential masters.
// Returns true if the first ID wins, or false if the second ID wins.
bool CommonTimeServer::arbitrateMaster(
        uint64_t deviceID1, uint8_t devicePrio1,
        uint64_t deviceID2, uint8_t devicePrio2) {
    return ((devicePrio1 >  devicePrio2) ||
           ((devicePrio1 == devicePrio2) && (deviceID1 > deviceID2)));
}

static void hexDumpToString(const uint8_t* src, size_t src_len,
                            char* dst, size_t dst_len) {
    size_t offset = 0;
    size_t i;

    for (i = 0; (i < src_len) && (offset < dst_len); ++i) {
        int res;
        if (0 == (i % 16)) {
            res = snprintf(dst + offset, dst_len - offset, "\n%04zx :", i);
            if (res < 0)
                break;
            offset += res;
            if (offset >= dst_len)
                break;
        }

        res = snprintf(dst + offset, dst_len - offset, " %02x", src[i]);
        if (res < 0)
            break;
        offset += res;
    }

    dst[dst_len - 1] = 0;
}

bool CommonTimeServer::handlePacket() {
    uint8_t buf[256];
    struct sockaddr_storage srcAddr;
    socklen_t srcAddrLen = sizeof(srcAddr);

    ssize_t recvBytes = recvfrom(
            mSocket, buf, sizeof(buf), 0,
            reinterpret_cast<const sockaddr *>(&srcAddr), &srcAddrLen);

    if (recvBytes < 0) {
        mBadPktLog.log(ANDROID_LOG_ERROR, LOG_TAG,
                       "recvfrom failed (res %d, errno %d)",
                       recvBytes, errno);
        return false;
    }

    UniversalTimeServicePacket pkt;
    if (pkt.deserializePacket(buf, recvBytes, mSyncGroupID) < 0) {
        char hex[256];
        char srcEPStr[64];

        hexDumpToString(buf, static_cast<size_t>(recvBytes), hex, sizeof(hex));
        sockaddrToString(srcAddr, true, srcEPStr, sizeof(srcEPStr));

        mBadPktLog.log("Failed to parse %d byte packet from %s.%s",
                       recvBytes, srcEPStr, hex);
        return false;
    }

    bool result;
    switch (pkt.packetType) {
        case TIME_PACKET_WHO_IS_MASTER_REQUEST:
            result = handleWhoIsMasterRequest(&pkt.p.who_is_master_request,
                                              srcAddr);
            break;

        case TIME_PACKET_WHO_IS_MASTER_RESPONSE:
            result = handleWhoIsMasterResponse(&pkt.p.who_is_master_response,
                                               srcAddr);
            break;

        case TIME_PACKET_SYNC_REQUEST:
            result = handleSyncRequest(&pkt.p.sync_request, srcAddr);
            break;

        case TIME_PACKET_SYNC_RESPONSE:
            result = handleSyncResponse(&pkt.p.sync_response, srcAddr);
            break;

        case TIME_PACKET_MASTER_ANNOUNCEMENT:
            result = handleMasterAnnouncement(&pkt.p.master_announcement,
                                              srcAddr);
            break;

        default: {
            char srcEPStr[64];
            sockaddrToString(srcAddr, true, srcEPStr, sizeof(srcEPStr));

            mBadPktLog.log(ANDROID_LOG_WARN, LOG_TAG,
                           "unknown packet type (%d) from %s",
                           pkt.packetType, srcEPStr);

            result = false;
        } break;
    }

    return result;
}

bool CommonTimeServer::handleTimeout() {
    // If we have no socket, then this must be a timeout to retry socket setup.
    if (mSocket < 0)
        return true;

    switch (mState) {
        case ICommonClock::STATE_INITIAL:
            return handleTimeoutInitial();
        case ICommonClock::STATE_CLIENT:
            return handleTimeoutClient();
        case ICommonClock::STATE_MASTER:
            return handleTimeoutMaster();
        case ICommonClock::STATE_RONIN:
            return handleTimeoutRonin();
        case ICommonClock::STATE_WAIT_FOR_ELECTION:
            return handleTimeoutWaitForElection();
    }

    return false;
}

bool CommonTimeServer::handleTimeoutInitial() {
    if (++mInitial_WhoIsMasterRequestTimeouts ==
            kInitial_NumWhoIsMasterRetries) {
        // none of our attempts to discover a master succeeded, so make
        // this device the master
        return becomeMaster("initial timeout");
    } else {
        // retry the WhoIsMaster request
        return sendWhoIsMasterRequest();
    }
}

bool CommonTimeServer::handleTimeoutClient() {
    if (shouldPanicNotGettingGoodData())
        return becomeInitial("timeout panic, no good data");

    if (mClient_SyncRequestPending) {
        mClient_SyncRequestPending = false;

        if (++mClient_SyncRequestTimeouts < kClient_NumSyncRequestRetries) {
            // a sync request has timed out, so retry
            return sendSyncRequest();
        } else {
            // The master has failed to respond to a sync request for too many
            // times in a row.  Assume the master is dead and start electing
            // a new master.
            return becomeRonin("master not responding");
        }
    } else {
        // initiate the next sync request
        return sendSyncRequest();
    }
}

bool CommonTimeServer::handleTimeoutMaster() {
    // send another announcement from the master
    return sendMasterAnnouncement();
}

bool CommonTimeServer::handleTimeoutRonin() {
    if (++mRonin_WhoIsMasterRequestTimeouts == kRonin_NumWhoIsMasterRetries) {
        // no other master is out there, so we won the election
        return becomeMaster("no better masters detected");
    } else {
        return sendWhoIsMasterRequest();
    }
}

bool CommonTimeServer::handleTimeoutWaitForElection() {
    return becomeRonin("timeout waiting for election conclusion");
}

bool CommonTimeServer::handleWhoIsMasterRequest(
        const WhoIsMasterRequestPacket* request,
        const sockaddr_storage& srcAddr) {
    // Skip our own messages which come back via broadcast loopback.
    if (request->senderDeviceID == mDeviceID)
        return true;

    char srcEPStr[64];
    sockaddrToString(srcAddr, true, srcEPStr, sizeof(srcEPStr));
    mElectionLog.log("RXed WhoIs master request while in state %s.  "
                     "src %s reqTID %016llx ourTID %016llx",
                     stateToString(mState), srcEPStr,
                     request->timelineID, mTimelineID);

    if (mState == ICommonClock::STATE_MASTER) {
        // is this request related to this master's timeline?
        if (request->timelineID != ICommonClock::kInvalidTimelineID &&
            request->timelineID != mTimelineID)
            return true;

        WhoIsMasterResponsePacket pkt;
        pkt.initHeader(mTimelineID, mSyncGroupID);
        pkt.deviceID = mDeviceID;
        pkt.devicePriority = effectivePriority();

        mElectionLog.log("TXing WhoIs master resp to %s while in state %s.  "
                         "ourTID %016llx ourGID %016llx ourDID %016llx "
                         "ourPrio %u",
                         srcEPStr, stateToString(mState),
                         mTimelineID, mSyncGroupID,
                         pkt.deviceID, pkt.devicePriority);

        uint8_t buf[256];
        ssize_t bufSz = pkt.serializePacket(buf, sizeof(buf));
        if (bufSz < 0)
            return false;

        ssize_t sendBytes = sendto(
                mSocket, buf, bufSz, 0,
                reinterpret_cast<const sockaddr *>(&srcAddr),
                sizeof(srcAddr));
        if (sendBytes == -1) {
            ALOGE("%s:%d sendto failed", __PRETTY_FUNCTION__, __LINE__);
            return false;
        }
    } else if (mState == ICommonClock::STATE_RONIN) {
        // if we hear a WhoIsMaster request from another device following
        // the same timeline and that device wins arbitration, then we will stop
        // trying to elect ourselves master and will instead wait for an
        // announcement from the election winner
        if (request->timelineID != mTimelineID)
            return true;

        if (arbitrateMaster(request->senderDeviceID,
                            request->senderDevicePriority,
                            mDeviceID,
                            effectivePriority()))
            return becomeWaitForElection("would lose election");

        return true;
    } else if (mState == ICommonClock::STATE_INITIAL) {
        // If a group of devices booted simultaneously (e.g. after a power
        // outage) and all of them are in the initial state and there is no
        // master, then each device may time out and declare itself master at
        // the same time.  To avoid this, listen for
        // WhoIsMaster(InvalidTimeline) requests from peers.  If we would lose
        // arbitration against that peer, reset our timeout count so that the
        // peer has a chance to become master before we time out.
        if (request->timelineID == ICommonClock::kInvalidTimelineID &&
                arbitrateMaster(request->senderDeviceID,
                                request->senderDevicePriority,
                                mDeviceID,
                                effectivePriority())) {
            mInitial_WhoIsMasterRequestTimeouts = 0;
        }
    }

    return true;
}

bool CommonTimeServer::handleWhoIsMasterResponse(
        const WhoIsMasterResponsePacket* response,
        const sockaddr_storage& srcAddr) {
    // Skip our own messages which come back via broadcast loopback.
    if (response->deviceID == mDeviceID)
        return true;

    char srcEPStr[64];
    sockaddrToString(srcAddr, true, srcEPStr, sizeof(srcEPStr));
    mElectionLog.log("RXed WhoIs master response while in state %s.  "
                     "src %s respTID %016llx respDID %016llx respPrio %u "
                     "ourTID %016llx",
                     stateToString(mState), srcEPStr,
                     response->timelineID,
                     response->deviceID,
                     static_cast<uint32_t>(response->devicePriority),
                     mTimelineID);

    if (mState == ICommonClock::STATE_INITIAL || mState == ICommonClock::STATE_RONIN) {
        return becomeClient(srcAddr,
                            response->deviceID,
                            response->devicePriority,
                            response->timelineID,
                            "heard whois response");
    } else if (mState == ICommonClock::STATE_CLIENT) {
        // if we get multiple responses because there are multiple devices
        // who believe that they are master, then follow the master that
        // wins arbitration
        if (arbitrateMaster(response->deviceID,
                            response->devicePriority,
                            mClient_MasterDeviceID,
                            mClient_MasterDevicePriority)) {
            return becomeClient(srcAddr,
                                response->deviceID,
                                response->devicePriority,
                                response->timelineID,
                                "heard whois response");
        }
    }

    return true;
}

bool CommonTimeServer::handleSyncRequest(const SyncRequestPacket* request,
                                         const sockaddr_storage& srcAddr) {
    SyncResponsePacket pkt;
    pkt.initHeader(mTimelineID, mSyncGroupID);

    if ((mState == ICommonClock::STATE_MASTER) &&
        (mTimelineID == request->timelineID)) {
        int64_t rxLocalTime = mLastPacketRxLocalTime;
        int64_t rxCommonTime;

        // If we are master on an actual network and have actual clients, then
        // we are no longer low priority.
        setForceLowPriority(false);

        if (OK != mCommonClock.localToCommon(rxLocalTime, &rxCommonTime)) {
            return false;
        }

        int64_t txLocalTime = mLocalClock.getLocalTime();;
        int64_t txCommonTime;
        if (OK != mCommonClock.localToCommon(txLocalTime, &txCommonTime)) {
            return false;
        }

        pkt.nak = 0;
        pkt.clientTxLocalTime  = request->clientTxLocalTime;
        pkt.masterRxCommonTime = rxCommonTime;
        pkt.masterTxCommonTime = txCommonTime;
    } else {
        pkt.nak = 1;
        pkt.clientTxLocalTime  = 0;
        pkt.masterRxCommonTime = 0;
        pkt.masterTxCommonTime = 0;
    }

    uint8_t buf[256];
    ssize_t bufSz = pkt.serializePacket(buf, sizeof(buf));
    if (bufSz < 0)
        return false;

    ssize_t sendBytes = sendto(
            mSocket, &buf, bufSz, 0,
            reinterpret_cast<const sockaddr *>(&srcAddr),
            sizeof(srcAddr));
    if (sendBytes == -1) {
        ALOGE("%s:%d sendto failed", __PRETTY_FUNCTION__, __LINE__);
        return false;
    }

    return true;
}

bool CommonTimeServer::handleSyncResponse(
        const SyncResponsePacket* response,
        const sockaddr_storage& srcAddr) {
    if (mState != ICommonClock::STATE_CLIENT)
        return true;

    assert(mMasterEPValid);
    if (!sockaddrMatch(srcAddr, mMasterEP, true)) {
        char srcEP[64], expectedEP[64];
        sockaddrToString(srcAddr, true, srcEP, sizeof(srcEP));
        sockaddrToString(mMasterEP, true, expectedEP, sizeof(expectedEP));
        ALOGI("Dropping sync response from unexpected address."
             " Expected %s Got %s", expectedEP, srcEP);
        return true;
    }

    if (response->nak) {
        // if our master is no longer accepting requests, then we need to find
        // a new master
        return becomeRonin("master NAK'ed");
    }

    mClient_SyncRequestPending = 0;
    mClient_SyncRequestTimeouts = 0;
    mClient_PacketRTTLog.logRX(response->clientTxLocalTime,
                               mLastPacketRxLocalTime);

    bool result;
    if (!(mClient_SyncRespsRXedFromCurMaster++)) {
        // the first request/response exchange between a client and a master
        // may take unusually long due to ARP, so discard it.
        result = true;
    } else {
        int64_t clientTxLocalTime  = response->clientTxLocalTime;
        int64_t clientRxLocalTime  = mLastPacketRxLocalTime;
        int64_t masterTxCommonTime = response->masterTxCommonTime;
        int64_t masterRxCommonTime = response->masterRxCommonTime;

        int64_t rtt       = (clientRxLocalTime - clientTxLocalTime);
        int64_t avgLocal  = (clientTxLocalTime + clientRxLocalTime) >> 1;
        int64_t avgCommon = (masterTxCommonTime + masterRxCommonTime) >> 1;

        // if the RTT of the packet is significantly larger than the panic
        // threshold, we should simply discard it.  Its better to do nothing
        // than to take cues from a packet like that.
        int rttCommon = mCommonClock.localDurationToCommonDuration(rtt);
        if (rttCommon > (static_cast<int64_t>(mPanicThresholdUsec) * 
                         kRTTDiscardPanicThreshMultiplier)) {
            ALOGV("Dropping sync response with RTT of %lld uSec", rttCommon);
            mClient_ExpiredSyncRespsRXedFromCurMaster++;
            if (shouldPanicNotGettingGoodData())
                return becomeInitial("RX panic, no good data");
        } else {
            result = mClockRecovery.pushDisciplineEvent(avgLocal, avgCommon, rttCommon);
            mClient_LastGoodSyncRX = clientRxLocalTime;

            if (result) {
                // indicate to listeners that we've synced to the common timeline
                notifyClockSync();
            } else {
                ALOGE("Panic!  Observed clock sync error is too high to tolerate,"
                        " resetting state machine and starting over.");
                notifyClockSyncLoss();
                return becomeInitial("panic");
            }
        }
    }

    mCurTimeout.setTimeout(mSyncRequestIntervalMs);
    return result;
}

bool CommonTimeServer::handleMasterAnnouncement(
        const MasterAnnouncementPacket* packet,
        const sockaddr_storage& srcAddr) {
    uint64_t newDeviceID   = packet->deviceID;
    uint8_t  newDevicePrio = packet->devicePriority;
    uint64_t newTimelineID = packet->timelineID;

    // Skip our own messages which come back via broadcast loopback.
    if (newDeviceID == mDeviceID)
        return true;

    char srcEPStr[64];
    sockaddrToString(srcAddr, true, srcEPStr, sizeof(srcEPStr));
    mElectionLog.log("RXed master announcement while in state %s.  "
                     "src %s srcDevID %lld srcPrio %u srcTID %016llx",
                     stateToString(mState), srcEPStr,
                     newDeviceID, static_cast<uint32_t>(newDevicePrio),
                     newTimelineID);

    if (mState == ICommonClock::STATE_INITIAL ||
        mState == ICommonClock::STATE_RONIN ||
        mState == ICommonClock::STATE_WAIT_FOR_ELECTION) {
        // if we aren't currently following a master, then start following
        // this new master
        return becomeClient(srcAddr,
                            newDeviceID,
                            newDevicePrio,
                            newTimelineID,
                            "heard master announcement");
    } else if (mState == ICommonClock::STATE_CLIENT) {
        // if the new master wins arbitration against our current master,
        // then become a client of the new master
        if (arbitrateMaster(newDeviceID,
                            newDevicePrio,
                            mClient_MasterDeviceID,
                            mClient_MasterDevicePriority))
            return becomeClient(srcAddr,
                                newDeviceID,
                                newDevicePrio,
                                newTimelineID,
                                "heard master announcement");
    } else if (mState == ICommonClock::STATE_MASTER) {
        // two masters are competing - if the new one wins arbitration, then
        // cease acting as master
        if (arbitrateMaster(newDeviceID, newDevicePrio,
                            mDeviceID, effectivePriority()))
            return becomeClient(srcAddr, newDeviceID,
                                newDevicePrio, newTimelineID,
                                "heard master announcement");
    }

    return true;
}

bool CommonTimeServer::sendWhoIsMasterRequest() {
    assert(mState == ICommonClock::STATE_INITIAL || mState == ICommonClock::STATE_RONIN);

    // If we have no socket, then we must be in the unconfigured initial state.
    // Don't report any errors, just don't try to send the initial who-is-master
    // query.  Eventually, our network will either become configured, or we will
    // be forced into network-less master mode by higher level code.
    if (mSocket < 0) {
        assert(mState == ICommonClock::STATE_INITIAL);
        return true;
    }

    bool ret = false;
    WhoIsMasterRequestPacket pkt;
    pkt.initHeader(mSyncGroupID);
    pkt.senderDeviceID = mDeviceID;
    pkt.senderDevicePriority = effectivePriority();

    uint8_t buf[256];
    ssize_t bufSz = pkt.serializePacket(buf, sizeof(buf));
    if (bufSz >= 0) {
        char dstEPStr[64];
        sockaddrToString(mMasterElectionEP, true, dstEPStr, sizeof(dstEPStr));
        mElectionLog.log("TXing WhoIs master request to %s while in state %s.  "
                         "ourTID %016llx ourGID %016llx ourDID %016llx "
                         "ourPrio %u",
                         dstEPStr, stateToString(mState),
                         mTimelineID, mSyncGroupID,
                         pkt.senderDeviceID, pkt.senderDevicePriority);

        ssize_t sendBytes = sendto(
                mSocket, buf, bufSz, 0,
                reinterpret_cast<const sockaddr *>(&mMasterElectionEP),
                sizeof(mMasterElectionEP));
        if (sendBytes < 0)
            ALOGE("WhoIsMaster sendto failed (errno %d)", errno);
        ret = true;
    }

    if (mState == ICommonClock::STATE_INITIAL) {
        mCurTimeout.setTimeout(kInitial_WhoIsMasterTimeoutMs);
    } else {
        mCurTimeout.setTimeout(kRonin_WhoIsMasterTimeoutMs);
    }

    return ret;
}

bool CommonTimeServer::sendSyncRequest() {
    // If we are sending sync requests, then we must be in the client state and
    // we must have a socket (when we have no network, we are only supposed to
    // be in INITIAL or MASTER)
    assert(mState == ICommonClock::STATE_CLIENT);
    assert(mSocket >= 0);

    bool ret = false;
    SyncRequestPacket pkt;
    pkt.initHeader(mTimelineID, mSyncGroupID);
    pkt.clientTxLocalTime = mLocalClock.getLocalTime();

    if (!mClient_FirstSyncTX)
        mClient_FirstSyncTX = pkt.clientTxLocalTime;

    mClient_PacketRTTLog.logTX(pkt.clientTxLocalTime);

    uint8_t buf[256];
    ssize_t bufSz = pkt.serializePacket(buf, sizeof(buf));
    if (bufSz >= 0) {
        ssize_t sendBytes = sendto(
                mSocket, buf, bufSz, 0,
                reinterpret_cast<const sockaddr *>(&mMasterEP),
                sizeof(mMasterEP));
        if (sendBytes < 0)
            ALOGE("SyncRequest sendto failed (errno %d)", errno);
        ret = true;
    }

    mClient_SyncsSentToCurMaster++;
    mCurTimeout.setTimeout(mSyncRequestIntervalMs);
    mClient_SyncRequestPending = true;

    return ret;
}

bool CommonTimeServer::sendMasterAnnouncement() {
    bool ret = false;
    assert(mState == ICommonClock::STATE_MASTER);

    // If we are being asked to send a master announcement, but we have no
    // socket, we must be in network-less master mode.  Don't bother to send the
    // announcement, and don't bother to schedule a timeout.  When the network
    // comes up, the work thread will get poked and start the process of
    // figuring out who the current master should be.
    if (mSocket < 0) {
        mCurTimeout.setTimeout(kInfiniteTimeout);
        return true;
    }

    MasterAnnouncementPacket pkt;
    pkt.initHeader(mTimelineID, mSyncGroupID);
    pkt.deviceID = mDeviceID;
    pkt.devicePriority = effectivePriority();

    uint8_t buf[256];
    ssize_t bufSz = pkt.serializePacket(buf, sizeof(buf));
    if (bufSz >= 0) {
        char dstEPStr[64];
        sockaddrToString(mMasterElectionEP, true, dstEPStr, sizeof(dstEPStr));
        mElectionLog.log("TXing Master announcement to %s while in state %s.  "
                         "ourTID %016llx ourGID %016llx ourDID %016llx "
                         "ourPrio %u",
                         dstEPStr, stateToString(mState),
                         mTimelineID, mSyncGroupID,
                         pkt.deviceID, pkt.devicePriority);

        ssize_t sendBytes = sendto(
                mSocket, buf, bufSz, 0,
                reinterpret_cast<const sockaddr *>(&mMasterElectionEP),
                sizeof(mMasterElectionEP));
        if (sendBytes < 0)
            ALOGE("MasterAnnouncement sendto failed (errno %d)", errno);
        ret = true;
    }

    mCurTimeout.setTimeout(mMasterAnnounceIntervalMs);
    return ret;
}

bool CommonTimeServer::becomeClient(const sockaddr_storage& masterEP,
                                    uint64_t masterDeviceID,
                                    uint8_t  masterDevicePriority,
                                    uint64_t timelineID,
                                    const char* cause) {
    char newEPStr[64], oldEPStr[64];
    sockaddrToString(masterEP, true, newEPStr, sizeof(newEPStr));
    sockaddrToString(mMasterEP, mMasterEPValid, oldEPStr, sizeof(oldEPStr));

    mStateChangeLog.log(ANDROID_LOG_INFO, LOG_TAG,
            "%s --> CLIENT (%s) :%s"
            " OldMaster: %02x-%014llx::%016llx::%s"
            " NewMaster: %02x-%014llx::%016llx::%s",
            stateToString(mState), cause,
            (mTimelineID != timelineID) ? " (new timeline)" : "",
            mClient_MasterDevicePriority, mClient_MasterDeviceID,
            mTimelineID, oldEPStr,
            masterDevicePriority, masterDeviceID,
            timelineID, newEPStr);

    if (mTimelineID != timelineID) {
        // start following a new timeline
        mTimelineID = timelineID;
        mClockRecovery.reset(true, true);
        notifyClockSyncLoss();
    } else {
        // start following a new master on the existing timeline
        mClockRecovery.reset(false, true);
    }

    mMasterEP = masterEP;
    mMasterEPValid = true;

    // If we are on a real network as a client of a real master, then we should
    // no longer force low priority.  If our master disappears, we should have
    // the high priority bit set during the election to replace the master
    // because this group was a real group and not a singleton created in
    // networkless mode.
    setForceLowPriority(false);

    mClient_MasterDeviceID = masterDeviceID;
    mClient_MasterDevicePriority = masterDevicePriority;
    resetSyncStats();

    setState(ICommonClock::STATE_CLIENT);

    // add some jitter to when the various clients send their requests
    // in order to reduce the likelihood that a group of clients overload
    // the master after receiving a master announcement
    usleep((lrand48() % 100) * 1000);

    return sendSyncRequest();
}

bool CommonTimeServer::becomeMaster(const char* cause) {
    uint64_t oldTimelineID = mTimelineID;
    if (mTimelineID == ICommonClock::kInvalidTimelineID) {
        // this device has not been following any existing timeline,
        // so it will create a new timeline and declare itself master
        assert(!mCommonClock.isValid());

        // set the common time basis
        mCommonClock.setBasis(mLocalClock.getLocalTime(), 0);

        // assign an arbitrary timeline iD
        assignTimelineID();

        // notify listeners that we've created a common timeline
        notifyClockSync();
    }

    mStateChangeLog.log(ANDROID_LOG_INFO, LOG_TAG,
            "%s --> MASTER (%s) : %s timeline %016llx",
            stateToString(mState), cause,
            (oldTimelineID == mTimelineID) ? "taking ownership of"
                                           : "creating new",
            mTimelineID);

    memset(&mMasterEP, 0, sizeof(mMasterEP));
    mMasterEPValid = false;
    mClient_MasterDevicePriority = effectivePriority();
    mClient_MasterDeviceID = mDeviceID;
    mClockRecovery.reset(false, true);
    resetSyncStats();

    setState(ICommonClock::STATE_MASTER);
    return sendMasterAnnouncement();
}

bool CommonTimeServer::becomeRonin(const char* cause) {
    // If we were the client of a given timeline, but had never received even a
    // single time sync packet, then we transition back to Initial instead of
    // Ronin.  If we transition to Ronin and end up becoming the new Master, we
    // will be unable to service requests for other clients because we never
    // actually knew what time it was.  By going to initial, we ensure that
    // other clients who know what time it is, but would lose master arbitration
    // in the Ronin case, will step up and become the proper new master of the
    // old timeline.

    char oldEPStr[64];
    sockaddrToString(mMasterEP, mMasterEPValid, oldEPStr, sizeof(oldEPStr));
    memset(&mMasterEP, 0, sizeof(mMasterEP));
    mMasterEPValid = false;

    if (mCommonClock.isValid()) {
        mStateChangeLog.log(ANDROID_LOG_INFO, LOG_TAG,
             "%s --> RONIN (%s) : lost track of previously valid timeline "
             "%02x-%014llx::%016llx::%s (%d TXed %d RXed %d RXExpired)",
             stateToString(mState), cause,
             mClient_MasterDevicePriority, mClient_MasterDeviceID,
             mTimelineID, oldEPStr,
             mClient_SyncsSentToCurMaster,
             mClient_SyncRespsRXedFromCurMaster,
             mClient_ExpiredSyncRespsRXedFromCurMaster);

        mRonin_WhoIsMasterRequestTimeouts = 0;
        setState(ICommonClock::STATE_RONIN);
        return sendWhoIsMasterRequest();
    } else {
        mStateChangeLog.log(ANDROID_LOG_INFO, LOG_TAG,
             "%s --> INITIAL (%s) : never synced timeline "
             "%02x-%014llx::%016llx::%s (%d TXed %d RXed %d RXExpired)",
             stateToString(mState), cause,
             mClient_MasterDevicePriority, mClient_MasterDeviceID,
             mTimelineID, oldEPStr,
             mClient_SyncsSentToCurMaster,
             mClient_SyncRespsRXedFromCurMaster,
             mClient_ExpiredSyncRespsRXedFromCurMaster);

        return becomeInitial("ronin, no timeline");
    }
}

bool CommonTimeServer::becomeWaitForElection(const char* cause) {
    mStateChangeLog.log(ANDROID_LOG_INFO, LOG_TAG,
         "%s --> WAIT_FOR_ELECTION (%s) : dropping out of election,"
         " waiting %d mSec for completion.",
         stateToString(mState), cause, kWaitForElection_TimeoutMs);

    setState(ICommonClock::STATE_WAIT_FOR_ELECTION);
    mCurTimeout.setTimeout(kWaitForElection_TimeoutMs);
    return true;
}

bool CommonTimeServer::becomeInitial(const char* cause) {
    mStateChangeLog.log(ANDROID_LOG_INFO, LOG_TAG,
                        "Entering INITIAL (%s), total reset.",
                        cause);

    setState(ICommonClock::STATE_INITIAL);

    // reset clock recovery
    mClockRecovery.reset(true, true);

    // reset internal state bookkeeping.
    mCurTimeout.setTimeout(kInfiniteTimeout);
    memset(&mMasterEP, 0, sizeof(mMasterEP));
    mMasterEPValid = false;
    mLastPacketRxLocalTime = 0;
    mTimelineID = ICommonClock::kInvalidTimelineID;
    mClockSynced = false;
    mInitial_WhoIsMasterRequestTimeouts = 0;
    mClient_MasterDeviceID = 0;
    mClient_MasterDevicePriority = 0;
    mRonin_WhoIsMasterRequestTimeouts = 0;
    resetSyncStats();

    // send the first request to discover the master
    return sendWhoIsMasterRequest();
}

void CommonTimeServer::notifyClockSync() {
    if (!mClockSynced) {
        mClockSynced = true;
        mICommonClock->notifyOnTimelineChanged(mTimelineID);
    }
}

void CommonTimeServer::notifyClockSyncLoss() {
    if (mClockSynced) {
        mClockSynced = false;
        mICommonClock->notifyOnTimelineChanged(
                ICommonClock::kInvalidTimelineID);
    }
}

void CommonTimeServer::setState(ICommonClock::State s) {
    mState = s;
}

const char* CommonTimeServer::stateToString(ICommonClock::State s) {
    switch(s) {
        case ICommonClock::STATE_INITIAL:
            return "INITIAL";
        case ICommonClock::STATE_CLIENT:
            return "CLIENT";
        case ICommonClock::STATE_MASTER:
            return "MASTER";
        case ICommonClock::STATE_RONIN:
            return "RONIN";
        case ICommonClock::STATE_WAIT_FOR_ELECTION:
            return "WAIT_FOR_ELECTION";
        default:
            return "unknown";
    }
}

void CommonTimeServer::sockaddrToString(const sockaddr_storage& addr,
                                        bool addrValid,
                                        char* buf, size_t bufLen) {
    if (!bufLen || !buf)
        return;

    if (addrValid) {
        switch (addr.ss_family) {
            case AF_INET: {
                const struct sockaddr_in* sa =
                    reinterpret_cast<const struct sockaddr_in*>(&addr);
                unsigned long a = ntohl(sa->sin_addr.s_addr);
                uint16_t      p = ntohs(sa->sin_port);
                snprintf(buf, bufLen, "%lu.%lu.%lu.%lu:%hu",
                        ((a >> 24) & 0xFF), ((a >> 16) & 0xFF),
                        ((a >>  8) & 0xFF),  (a        & 0xFF), p);
            } break;

            case AF_INET6: {
                const struct sockaddr_in6* sa =
                    reinterpret_cast<const struct sockaddr_in6*>(&addr);
                const uint8_t* a = sa->sin6_addr.s6_addr;
                uint16_t       p = ntohs(sa->sin6_port);
                snprintf(buf, bufLen,
                        "%02X%02X:%02X%02X:%02X%02X:%02X%02X:"
                        "%02X%02X:%02X%02X:%02X%02X:%02X%02X port %hd",
                        a[0], a[1], a[ 2], a[ 3], a[ 4], a[ 5], a[ 6], a[ 7],
                        a[8], a[9], a[10], a[11], a[12], a[13], a[14], a[15],
                        p);
            } break;

            default:
                snprintf(buf, bufLen,
                         "<unknown sockaddr family %d>", addr.ss_family);
                break;
        }
    } else {
        snprintf(buf, bufLen, "<none>");
    }

    buf[bufLen - 1] = 0;
}

bool CommonTimeServer::sockaddrMatch(const sockaddr_storage& a1,
                                     const sockaddr_storage& a2,
                                     bool matchAddressOnly) {
    if (a1.ss_family != a2.ss_family)
        return false;

    switch (a1.ss_family) {
        case AF_INET: {
            const struct sockaddr_in* sa1 =
                reinterpret_cast<const struct sockaddr_in*>(&a1);
            const struct sockaddr_in* sa2 =
                reinterpret_cast<const struct sockaddr_in*>(&a2);

            if (sa1->sin_addr.s_addr != sa2->sin_addr.s_addr)
                return false;

            return (matchAddressOnly || (sa1->sin_port == sa2->sin_port));
        } break;

        case AF_INET6: {
            const struct sockaddr_in6* sa1 =
                reinterpret_cast<const struct sockaddr_in6*>(&a1);
            const struct sockaddr_in6* sa2 =
                reinterpret_cast<const struct sockaddr_in6*>(&a2);

            if (memcmp(&sa1->sin6_addr, &sa2->sin6_addr, sizeof(sa2->sin6_addr)))
                return false;

            return (matchAddressOnly || (sa1->sin6_port == sa2->sin6_port));
        } break;

        // Huh?  We don't deal in non-IPv[46] addresses.  Not sure how we got
        // here, but we don't know how to comapre these addresses and simply
        // default to a no-match decision.
        default: return false;
    }
}

bool CommonTimeServer::shouldPanicNotGettingGoodData() {
    if (mClient_FirstSyncTX) {
        int64_t now = mLocalClock.getLocalTime();
        int64_t delta = now - (mClient_LastGoodSyncRX
                             ? mClient_LastGoodSyncRX
                             : mClient_FirstSyncTX);
        int64_t deltaUsec = mCommonClock.localDurationToCommonDuration(delta);

        if (deltaUsec >= kNoGoodDataPanicThresholdUsec)
            return true;
    }

    return false;
}

void CommonTimeServer::PacketRTTLog::logTX(int64_t txTime) {
    txTimes[wrPtr] = txTime;
    rxTimes[wrPtr] = 0;
    wrPtr = (wrPtr + 1) % RTT_LOG_SIZE;
    if (!wrPtr)
        logFull = true;
}

void CommonTimeServer::PacketRTTLog::logRX(int64_t txTime, int64_t rxTime) {
    if (!logFull && !wrPtr)
        return;

    uint32_t i = logFull ? wrPtr : 0;
    do {
        if (txTimes[i] == txTime) {
            rxTimes[i] = rxTime;
            break;
        }
        i = (i + 1) % RTT_LOG_SIZE;
    } while (i != wrPtr);
}

}  // namespace android
