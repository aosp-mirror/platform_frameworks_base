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

#define LOG_TAG "LibAAH_RTP"
#include <utils/Log.h>

#include <netinet/in.h>
#include <poll.h>
#include <stdint.h>
#include <sys/eventfd.h>
#include <sys/socket.h>

#include <cutils/atomic.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/Utils.h>

#include "aah_tx_group.h"
#include "aah_tx_player.h"
#include "utils.h"

//#define DROP_PACKET_TEST
#ifdef DROP_PACKET_TEST
static android::Mutex sDropTestLock;
static bool           sDropTestSeeded = false;
static uint32_t       sDropTestDropNextN = 0;
const static uint32_t kDropTestGrpSize = 5;
const static double   kDropTestTXDropProb = 0.25;
const static double   kDropTestRXDropProb = 0.35;

static void droptest_seed_rng_l() {
    if (!sDropTestSeeded) {
        int64_t now = systemTime();
        long seed = static_cast<long>((now >> 32)) |
                    static_cast<long>(now);
        srand48(seed);
        LOGI("AAH TX Drop Test enabled... seed is 0x%08lx", seed);
        sDropTestSeeded = true;
    }
}

static ssize_t droptest_sendto(int sockfd, const void *buf,
                               size_t len, int flags,
                               const struct sockaddr *dest_addr,
                               socklen_t addrlen) {
    android::Mutex::Autolock lock(sDropTestLock);

    droptest_seed_rng_l();

    if ((!sDropTestDropNextN) && (drand48() <= kDropTestTXDropProb)) {
        sDropTestDropNextN = (static_cast<uint32_t>(lrand48()) %
                              kDropTestGrpSize) + 1;
        LOGI("AAH Drop Test dropping next %u TX packets",
             sDropTestDropNextN);
    }

    if (!sDropTestDropNextN) {
        return sendto(sockfd, buf, len, flags, dest_addr, addrlen);
    } else {
        --sDropTestDropNextN;
        return len;
    }
}
#define sendto droptest_sendto

static ssize_t droptest_recvfrom(int sockfd, void *buf,
                                 size_t len, int flags,
                                 struct sockaddr *src_addr,
                                 socklen_t *addrlen) {
    android::Mutex::Autolock lock(sDropTestLock);

    droptest_seed_rng_l();

    while (1) {
        ssize_t ret_val = recvfrom(sockfd, buf, len, flags, src_addr, addrlen);

        // If we receive an error (most likely EAGAIN) or our random roll tells
        // us to not drop the packet, just get out.  Otherwise, pretend that we
        // never received this packet and loop around to try again.
        if ((ret_val < 0) || (drand48() > kDropTestRXDropProb)) {
            return ret_val;
        }

        LOGI("AAH Drop Test dropping RXed packet of length %ld", ret_val);
    }
}
#define recvfrom droptest_recvfrom
#endif

namespace android {

const int AAH_TXGroup::kRetryTrimIntervalMsec = 100;
const int AAH_TXGroup::kHeartbeatIntervalMsec = 500;
const int AAH_TXGroup::kTXGroupLingerTimeMsec = 10000;
const int AAH_TXGroup::kUnicastClientTimeoutMsec = 5000;

const size_t AAH_TXGroup::kRetryBufferCapacity = 100;
const size_t AAH_TXGroup::kMaxAllowedUnicastTargets = 16;
const size_t AAH_TXGroup::kInitialUnicastTargetCapacity = 4;
const size_t AAH_TXGroup::kMaxAllowedTXGroups = 8;
const size_t AAH_TXGroup::kInitialActiveTXGroupsCapacity = 4;
const size_t AAH_TXGroup::kMaxAllowedPlayerClients = 4;
const size_t AAH_TXGroup::kInitialPlayerClientCapacity = 2;

Mutex                               AAH_TXGroup::sLock;
Vector < sp<AAH_TXGroup> >          AAH_TXGroup::sActiveTXGroups;
sp<AAH_TXGroup::CmdAndControlRXer>  AAH_TXGroup::mCmdAndControlRXer;
uint32_t                            AAH_TXGroup::sNextEpoch;
bool                                AAH_TXGroup::sNextEpochValid = false;

AAH_TXGroup::AAH_TXGroup()
    : mRetryBuffer(kRetryBufferCapacity)
{
    // Initialize members with no constructor to sensible defaults.
    mTRTPSeqNumber = 0;
    mNextProgramID = 1;
    mEpoch = getNextEpoch();
    mMulticastTargetValid = false;
    mSocket = -1;
    mCmdAndControlPort = 0;

    mUnicastTargets.setCapacity(kInitialUnicastTargetCapacity);
    mActiveClients.setCapacity(kInitialPlayerClientCapacity);
    mHeartbeatTimeout.setTimeout(kHeartbeatIntervalMsec);
}

AAH_TXGroup::~AAH_TXGroup() {
    CHECK(mActiveClients.size() == 0);

    if (mSocket >= 0) {
        ::close(mSocket);
        mSocket = -1;
    }
}

bool AAH_TXGroup::bindSocket() {
    bool ret_val = false;

    // Create a UDP socket to use for TXing as well as command and control
    // RXing.
    mSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (mSocket < 0) {
        LOGE("Failed to create socket for AAH_TXGroup (errno = %d)", errno);
        goto bailout;
    }

    // Bind the socket we will use to an ephemeral UDP port.
    struct sockaddr_in bind_addr;
    memset(&bind_addr, 0, sizeof(bind_addr));
    bind_addr.sin_family = AF_INET;
    if (bind(mSocket,
             reinterpret_cast<const sockaddr*>(&bind_addr),
             sizeof(bind_addr)) < 0) {
        LOGE("Failed to bind socket for AAH_TXGroup (errno = %d)", errno);
        goto bailout;
    }

    // Fetch the port number that we just bound to so it can be handed out to
    // unicast clients by higher level code (if needed).
    int res;
    socklen_t socklen;
    res = getsockname(mSocket,
                      reinterpret_cast<sockaddr*>(&bind_addr),
                      &socklen);
    if (res) {
        LOGE("Failed to fetch bound port number for AAH_TXGroup (errno = %d)",
             errno);
        goto bailout;
    }

    // Set non-blocking operation
    long flags;
    flags = fcntl(mSocket, F_GETFL);
    res   = fcntl(mSocket, F_SETFL, flags | O_NONBLOCK);
    if (res < 0) {
        LOGE("Failed to set socket (%d) to non-blocking mode (errno %d)",
             mSocket, errno);
        goto bailout;
    }

    // Increase our transmit buffer size.
    int buf_size;
    buf_size = 1 << 17;   // 128k
    res = setsockopt(mSocket, SOL_SOCKET, SO_SNDBUF, &buf_size,
                     sizeof(buf_size));
    if (res < 0) {
        LOGW("Failed to increase socket buffer size to %d.  (errno %d)",
             buf_size, errno);
    }

    socklen_t opt_size;
    opt_size = sizeof(buf_size);
    buf_size = 0;
    res = getsockopt(mSocket, SOL_SOCKET, SO_SNDBUF, &buf_size, &opt_size);
    if (res < 0) {
        LOGW("Failed to increase socket buffer size to %d.  (errno %d)",
             buf_size, errno);
    } else {
        LOGD("TX socket buffer size is now %d bytes",  buf_size);
    }

    // Success!  Stash the bound port in host order and get out.
    mCmdAndControlPort = ntohs(bind_addr.sin_port);
    ret_val = true;

bailout:
    if (!ret_val && (mSocket >= 0)) {
        ::close(mSocket);
        mSocket = -1;
    }

    return ret_val;
}

sp<AAH_TXGroup> AAH_TXGroup::getGroup(uint16_t port,
                                      const sp<AAH_TXPlayer>& client) {
    sp<AAH_TXGroup> ret_val;

    // If port is non-zero, we are creating a new group.  Otherwise, we are
    // searching for an existing group.
    if (port) {
        // Hold our lock while we search the active group list for an active
        // group with the same cmd and ctrl port.
        Mutex::Autolock lock(sLock);

        for (size_t i = 0; i < sActiveTXGroups.size(); ++i) {
            if (port == sActiveTXGroups[i]->getCmdAndControlPort()) {
                ret_val = sActiveTXGroups[i];

                if (!ret_val->registerClient(client)) {
                    // No need to log an error, registerClient has already done
                    // so for us.
                    ret_val = NULL;
                }

                break;
            }
        }
    } else {
        // Looks like we are trying to create a new group.  Make sure we have
        // not maxed out our TX Group budget before proceeding.
        { // Explicit scope for lock pattern.
            Mutex::Autolock lock(sLock);
            if (sActiveTXGroups.size() >= kMaxAllowedTXGroups) {
                LOGE("Cannot create new transmit group.  %d/%d groups are"
                     " already active.",
                     sActiveTXGroups.size(), kMaxAllowedTXGroups);
                goto bailout;
            }
        }

        // Try to create and initialize our transmit group before attempting to
        // add it to the active group list.
        ret_val = new AAH_TXGroup();

        if (ret_val == NULL) {
            LOGE("Failed to allocate AAH_TXGroup");
            goto bailout;
        }

        if (!ret_val->bindSocket()) {
            // No need to log an error, bindSocket has already done so for us.
            goto bailout;
        }

        { // Explicit scope for lock pattern.
            Mutex::Autolock lock(sLock);

            // Attempt to allocate and start our command and control work thread
            // if we have not already done so.
            if (mCmdAndControlRXer == NULL) {
                status_t res;

                mCmdAndControlRXer = new CmdAndControlRXer();
                if (mCmdAndControlRXer == NULL) {
                    LOGE("Failed to allocate singleton command and control"
                         " thread");
                    goto bailout;
                }

                if (!mCmdAndControlRXer->init()) {
                    // No need to log an error, init should have already done so
                    // for us.
                    mCmdAndControlRXer = NULL;
                    goto bailout;
                }

                res = mCmdAndControlRXer->run("AAH_TXGroup", PRIORITY_AUDIO);
                if (OK != res) {
                    LOGE("Failed to start singleton command and control thread"
                         " (res = %d", res);
                    mCmdAndControlRXer = NULL;
                    goto bailout;
                }
            }

            // Register the client with the newly created group.
            if (!ret_val->registerClient(client)) {
                // No need to log an error, registerClient has already done so
                // for us.
                goto bailout;
            }

            // Make sure we are at least at minimum capacity in the
            // ActiveTXGroups vector.
            if (sActiveTXGroups.capacity() < kInitialActiveTXGroupsCapacity) {
                sActiveTXGroups.setCapacity(kInitialActiveTXGroupsCapacity);
            }

            // Add ourselves to the list of active TXGroups.
            if (sActiveTXGroups.add(ret_val) < 0) {
                LOGE("Failed to add new TX Group to Active Group list");
                ret_val->unregisterClient(client);
                goto bailout;
            }

            LOGI("Created TX Group with C&C Port %hu.  %d/%d groups now"
                 " active.", ret_val->getCmdAndControlPort(),
                 sActiveTXGroups.size(), kMaxAllowedTXGroups);
        }

        // Finally, poke the command and control thread so we are certain it
        // knows about the new group we just made.
        mCmdAndControlRXer->wakeupThread();
    }

    return ret_val;

bailout:
    return sp<AAH_TXGroup>(NULL);
}

sp<AAH_TXGroup> AAH_TXGroup::getGroup(const struct sockaddr_in* target,
                                      const sp<AAH_TXPlayer>& client) {
    // Hold the static lock while we search for a TX Group which has the
    // multicast target passed to us.
    Mutex::Autolock lock(sLock);
    sp<AAH_TXGroup> ret_val;

    CHECK(NULL != target);

    for (size_t i = 0; i < sActiveTXGroups.size(); ++i) {
        Mutex::Autolock instance_lock(sActiveTXGroups[i]->mLock);
        if (sActiveTXGroups[i]->mMulticastTargetValid &&
            matchSockaddrs(&sActiveTXGroups[i]->mMulticastTarget, target)) {
            ret_val = sActiveTXGroups[i];
            break;
        }
    }

    if (ret_val != NULL) {
        if (!ret_val->registerClient(client)) {
            // No need to log an error, registerClient has already done so for
            // us.
            ret_val = NULL;
        }
    }

    return ret_val;
}

void AAH_TXGroup::unregisterClient(const sp<AAH_TXPlayer>& client) {
    Mutex::Autolock lock(mLock);

    LOGI("TXPlayer leaving TXGroup listening on C&C port %hu",
         mCmdAndControlPort);

    bool found_it = false;
    for (size_t i = 0; i < mActiveClients.size(); ++i) {
        if (mActiveClients[i].get() == client.get()) {
            found_it = true;
            mActiveClients.removeAt(i);
            break;
        }
    }
    CHECK(found_it);

    if (!mActiveClients.size()) {
        mCleanupTimeout.setTimeout(kTXGroupLingerTimeMsec);
    }
}

bool AAH_TXGroup::registerClient(const sp<AAH_TXPlayer>& client) {
    // ASSERT holding sLock
    Mutex::Autolock lock(mLock);

    CHECK(client != NULL);

    // Check the client limit.
    if (mActiveClients.size() >= kMaxAllowedPlayerClients) {
        LOGE("Cannot register new client with C&C group listening on port %hu."
             "  %d/%d clients are already active", mCmdAndControlPort,
             mActiveClients.size(), kMaxAllowedPlayerClients);
        return false;
    }

    // Try to add the client to the list.
    if (mActiveClients.add(client) < 0) {
        LOGE("Failed to register new client with C&C group listening on port"
             " %hu.  %d/%d clients are currently active", mCmdAndControlPort,
             mActiveClients.size(), kMaxAllowedPlayerClients);
        return false;
    }

    // Assign our new client's program ID, cancel the cleanup timeout and get
    // out.
    client->setProgramID(getNewProgramID());
    mCleanupTimeout.setTimeout(-1);
    return true;
}

bool AAH_TXGroup::shouldExpire() {
    Mutex::Autolock lock(mLock);

    if (mActiveClients.size()) {
        return false;
    }

    if (mCleanupTimeout.msecTillTimeout()) {
        return false;
    }

    return true;
}

uint8_t AAH_TXGroup::getNewProgramID() {
    uint8_t tmp;
    do {
        tmp = static_cast<uint8_t>(android_atomic_inc(&mNextProgramID) & 0x1F);
    } while(!tmp);
    return tmp;
}

status_t AAH_TXGroup::sendPacket(const sp<TRTPPacket>& packet) {
    Mutex::Autolock lock(mLock);
    return sendPacket_l(packet);
}

status_t AAH_TXGroup::sendPacket_l(const sp<TRTPPacket>& packet) {
    CHECK(packet != NULL);
    CHECK(!packet->isPacked());

    // assign the packet's sequence number and expiration time, then pack it for
    // transmission.
    packet->setEpoch(mEpoch);
    packet->setSeqNumber(mTRTPSeqNumber++);
    packet->setExpireTime(systemTime() +
                          AAH_TXPlayer::kAAHRetryKeepAroundTimeNs);
    packet->pack();

    // add the packet to the retry buffer
    mRetryBuffer.push_back(packet);

    // get the payload pointer and length of the packet.
    const uint8_t* payload = packet->getPacket();
    size_t         length  = packet->getPacketLen();

    // send the packet to the multicast target, if valid.
    if (mMulticastTargetValid) {
        sendToTarget_l(&mMulticastTarget, payload, length);
    }

    // send the packet to each of the current unicast targets if they have not
    // timed out of the group due to a lack of group membership reports.  If the
    // target has timed out, remove it from the UnicastTargets vector instead of
    // sending it more data.
    nsecs_t now = systemTime();
    for (size_t i = 0; i < mUnicastTargets.size(); ) {
        const sp<UnicastTarget>& tgt = mUnicastTargets[i];

        if (tgt->mGroupTimeout.msecTillTimeout(now)) {
            sendToTarget_l(&tgt->mEndpoint, payload, length);
            ++i;
        } else {
            uint32_t addr = ntohl(tgt->mEndpoint.sin_addr.s_addr);
            uint16_t port = ntohs(tgt->mEndpoint.sin_port);

            mUnicastTargets.removeAt(i);

            LOGI("TXGroup on port %hu removing client at %d.%d.%d.%d:%hu due to"
                 " timeout.  Now serving %d/%d unicast clients.",
                 mCmdAndControlPort, IP_PRINTF_HELPER(addr), port,
                 mUnicastTargets.size(), kMaxAllowedUnicastTargets);
        }
    }

    // Done; see comments sendToTarget discussing error handling behavior
    return OK;
}

status_t AAH_TXGroup::sendToTarget_l(const struct sockaddr_in* target,
                                     const uint8_t* payload,
                                     size_t length) {
    CHECK(target  != NULL);
    CHECK(payload != NULL);
    CHECK(length  != 0);

    ssize_t result = sendto(mSocket, payload, length, 0,
                            reinterpret_cast<const struct sockaddr *>(target),
                            sizeof(*target));

    // TODO: need to decide what the proper thing to do is in case of transmit
    // errors.  TX errors could be caused by many things.  If we are in the
    // middle of an interface flap, its probably a transient condition and we
    // should just try to ride it out (probably with some impact on remote
    // presentation, but at least we will recover).  OTOH, if its because
    // something has gone horribly wrong at the IP stack level and our socket is
    // dead, then we really should signal and error and shut down the
    // transmitter trying to send this packet.
    //
    // Overflow is another situation to consider.  If we are transmitting high
    // enough bitrate content to enough unicast targets, and our connection
    // speed is limited (think 802.11b trained down to a 1Mbps link), then we
    // are in trouble.  Our TX buffer is bound to overflow, but its tough to
    // tell if the condition is transient or not.  If its due to an extremely
    // limited connection rate or because of a congested local network, the
    // problem might go away after a short while.  If its because we are simply
    // trying to send too much to too many targets, then the problem is not
    // going to go away unless we take action.  We could remove unicast targets
    // from the list in order to keep the others functioning instead of
    // stuttering, but that might be difficult to explain to the user.  We could
    // bubble and error up to the player who sent this packet and let them shut
    // down, but that might be a difficult thing for the application level to
    // handle right now.
    //
    // After discussing with high level folks for a while, the decision for now
    // is to log a warning and then ignore the condition.  Eventually, we will
    // need to revisit this decision.
    if (result < 0) {
        uint32_t addr = ntohl(target->sin_addr.s_addr);
        uint16_t port = ntohs(target->sin_port);

        switch (errno) {
        case EAGAIN:
            LOGW("TX socket buffer overflowing while attempting to send to"
                 " %d.%d.%d.%d:%hu.  We currently have %d unicast client%s and"
                 " %d multicast client%s",
                 IP_PRINTF_HELPER(addr), port,
                 mUnicastTargets.size(),
                (mUnicastTargets.size() == 1) ? "" : "s",
                 mMulticastTargetValid ? 1 : 0,
                 mMulticastTargetValid ? "" : "s");
            break;
        default:
            LOGW("TX error (%d) while attempting to send to %d.%d.%d.%d:%hu.",
                 errno, IP_PRINTF_HELPER(addr), port);
            break;
        }
    }

    return OK;
}

void AAH_TXGroup::setMulticastTXTarget(const struct sockaddr_in* target) {
    Mutex::Autolock lock(mLock);

    if (NULL == target) {
        memset(&mMulticastTarget, 0, sizeof(mMulticastTarget));
        mMulticastTargetValid = false;
    } else {
        memcpy(&mMulticastTarget, target, sizeof(mMulticastTarget));
        mMulticastTargetValid = true;
    }
}

// Return the next epoch number usable for a newly instantiated transmit group.
uint32_t AAH_TXGroup::getNextEpoch() {
    Mutex::Autolock autoLock(sLock);

    if (sNextEpochValid) {
        sNextEpoch = (sNextEpoch + 1) & TRTPPacket::kTRTPEpochMask;
    } else {
        sNextEpoch = ns2ms(systemTime()) & TRTPPacket::kTRTPEpochMask;
        sNextEpochValid = true;
    }

    return sNextEpoch;
}

void AAH_TXGroup::trimRetryBuffer() {
    Mutex::Autolock lock(mLock);

    nsecs_t now = systemTime();
    while (mRetryBuffer.size() && (mRetryBuffer[0]->getExpireTime() < now)) {
        mRetryBuffer.pop_front();
    }
}

void AAH_TXGroup::sendHeartbeatIfNeeded() {
    Mutex::Autolock lock(mLock);

    if (!mHeartbeatTimeout.msecTillTimeout()) {
        sp<TRTPActiveProgramUpdatePacket> packet =
            new TRTPActiveProgramUpdatePacket();

        if (packet != NULL) {
            for (size_t i = 0; i < mActiveClients.size(); ++i) {
                packet->pushProgramID(mActiveClients[i]->getProgramID());
            }

            sendPacket_l(packet);
        } else {
            LOGE("Failed to allocate TRTP packet for heartbeat on TX Group with"
                 " C&C port %hu", mCmdAndControlPort);
        }

        // reset our heartbeat timer.
        mHeartbeatTimeout.setTimeout(kHeartbeatIntervalMsec);
    }
}

void AAH_TXGroup::handleRequests() {
    // No need to grab the lock yet.  For now, we are only going to be
    // interacting with our socket, and we know the socket cannot go away until
    // destruction time.

    while (true) {
        struct sockaddr_in srcAddr;
        socklen_t srcAddrLen = sizeof(srcAddr);
        uint8_t request[sizeof(struct RetryPacket)];
        ssize_t rx_amt;

        memset(&srcAddr, 0, sizeof(srcAddr));
        rx_amt = recvfrom(mSocket,
                          request, sizeof(request),
                          MSG_TRUNC,
                          reinterpret_cast<struct sockaddr*>(&srcAddr),
                          &srcAddrLen);
        if (rx_amt < 0) {
            // We encountered an error during receive.  This is normal provided
            // that errno is EAGAIN (meaning we have processed all of the
            // packets waiting in the socket).  Any other error should be
            // logged.  One way or the other, we are done here and should get
            // out.
            if (errno != EAGAIN) {
                LOGE("Error reading from socket(%d) for TX group listening on"
                     " UDP port %hu", mSocket, mCmdAndControlPort);
            }
            break;
        }

        // Sanity check that this request came from an IPv4 client.
        if (srcAddr.sin_family != AF_INET) {
            LOGD("C&C request source address family (%d) is not IPv4 (%d)."
                 "  (len = %ld)", srcAddr.sin_family, AF_INET, rx_amt);
            continue;
        }

        // Someone sent us a packet larger than the largest message we were ever
        // expecting.  It cannot be valid, so just ignore it.
        uint32_t addr = ntohl(srcAddr.sin_addr.s_addr);
        uint16_t port = ntohs(srcAddr.sin_port);
        if (static_cast<size_t>(rx_amt) > sizeof(request)) {
            LOGD("C&C request packet from %d.%d.%d.%d:%hu too long (%ld) to be"
                 " real.", IP_PRINTF_HELPER(addr), port, rx_amt);
            continue;
        }

        // Parse the packet.  Start by trying to figure out what type of request
        // this is.  All requests should begin with a 4 byte tag which IDs the
        // type of request this is.
        if (rx_amt < 4) {
            LOGD("C&C request packet from %d.%d.%d.%d:%hu too short to contain"
                 " ID. (len = %ld)", IP_PRINTF_HELPER(addr), port, rx_amt);
            continue;
        }

        uint32_t id = U32_AT(request);
        size_t minSize = 0;
        switch(id) {
            case TRTPPacket::kCNC_RetryRequestID:
            case TRTPPacket::kCNC_FastStartRequestID:
                minSize = sizeof(RetryPacket);
                break;
            case TRTPPacket::kCNC_JoinGroupID:
            case TRTPPacket::kCNC_LeaveGroupID:
                minSize = sizeof(uint32_t);
                break;
        }

        if (static_cast<size_t>(rx_amt) < minSize) {
            LOGD("C&C request packet from %d.%d.%d.%d:%hu too short to contain"
                 " payload. (len = %ld, minSize = %d)",
                 IP_PRINTF_HELPER(addr), port, rx_amt, minSize);
            continue;
        }

        switch(id) {
            case TRTPPacket::kCNC_RetryRequestID:
                handleRetryRequest(request, &srcAddr, false);
                break;

            case TRTPPacket::kCNC_FastStartRequestID:
                handleRetryRequest(request, &srcAddr, true);
                break;

            case TRTPPacket::kCNC_JoinGroupID:
                handleJoinGroup(&srcAddr);
                break;

            case TRTPPacket::kCNC_LeaveGroupID:
                handleLeaveGroup(&srcAddr);
                break;

            default:
                LOGD("Unrecognized C&C request with id %08x from"
                     " %d.%d.%d.%d:%hu", id, IP_PRINTF_HELPER(addr), port);
                continue;
        }
    }
}

// Returns true if val is within the interval bounded inclusively by
// start and end.  Also handles the case where there is a rollover of the
// range between start and end.
template <typename T>
static inline bool withinIntervalWithRollover(T val, T start, T end) {
    return ((start <= end &&  val >= start && val <= end) ||
            (start  > end && (val >= start || val <= end)));
}

void AAH_TXGroup::handleRetryRequest(const uint8_t* req,
                                     const struct sockaddr_in* src_addr,
                                     bool isFastStart) {
    Mutex::Autolock lock(mLock);
    CHECK(NULL != src_addr);

    const RetryPacket* req_overlay =
        reinterpret_cast<const RetryPacket*>(req);
    const struct sockaddr* src =
        reinterpret_cast<const struct sockaddr*>(src_addr);
    uint32_t addr = ntohl(src_addr->sin_addr.s_addr);
    uint16_t port = ntohs(src_addr->sin_port);

    if (mRetryBuffer.isEmpty()) {
        // we have an empty retry buffer for this group, so NAK the entire
        // request
        RetryPacket nak = *req_overlay;
        nak.id = htonl(TRTPPacket::kCNC_NakRetryRequestID);

        if (sendto(mSocket, &nak, sizeof(nak), 0,
                   src, sizeof(*src_addr)) < 0) {
            LOGD("Failed to send retry NAK to %d.%d.%d.%d:%hu.  "
                 "(socket %d, errno %d, empty retry buffer)",
                 IP_PRINTF_HELPER(addr), port, mSocket, errno);
        }
        return;
    }

    size_t   retry_sz      = mRetryBuffer.size();
    uint16_t startSeq      = ntohs(req_overlay->seqStart);
    uint16_t endSeq        = ntohs(req_overlay->seqEnd);
    uint16_t retryFirstSeq = mRetryBuffer[0]->getSeqNumber();
    uint16_t retryLastSeq  = mRetryBuffer[retry_sz - 1]->getSeqNumber();

    // If this is a fast start, then force the start of the retry to match the
    // start of the retransmit ring buffer (unless the end of the retransmit
    // ring buffer is already past the point of fast start)
    if (isFastStart && !((startSeq - retryFirstSeq) & 0x8000)) {
        startSeq = retryFirstSeq;
    }

    int startIndex;
    if (withinIntervalWithRollover(startSeq, retryFirstSeq, retryLastSeq)) {
        startIndex = static_cast<uint16_t>(startSeq - retryFirstSeq);
    } else {
        startIndex = -1;
    }

    int endIndex;
    if (withinIntervalWithRollover(endSeq, retryFirstSeq, retryLastSeq)) {
        endIndex = static_cast<uint16_t>(endSeq - retryFirstSeq);
    } else {
        endIndex = -1;
    }

    if (startIndex == -1 && endIndex == -1) {
        // no part of the request range is found in the retry buffer
        RetryPacket nak = *req_overlay;
        nak.id = htonl(TRTPPacket::kCNC_NakRetryRequestID);

        if (sendto(mSocket, &nak, sizeof(nak), 0,
                   src, sizeof(*src_addr)) < 0) {
            LOGD("Failed to send retry NAK to %d.%d.%d.%d:%hu.  "
                 "(socket %d, errno %d, missing requested packets)",
                 IP_PRINTF_HELPER(addr), port, mSocket, errno);
        }
        return;
    }

    if (startIndex == -1) {
        // NAK a subrange at the front of the request range
        RetryPacket nak = *req_overlay;
        nak.id = htonl(TRTPPacket::kCNC_NakRetryRequestID);
        nak.seqEnd = htons(retryFirstSeq - 1);

        if (sendto(mSocket, &nak, sizeof(nak), 0,
                   src, sizeof(*src_addr)) < 0) {
            LOGD("Failed to send retry NAK to %d.%d.%d.%d:%hu.  "
                 "(socket %d, errno %d, missing front end)",
                 IP_PRINTF_HELPER(addr), port, mSocket, errno);
        }

        startIndex = 0;
    } else if (endIndex == -1) {
        // NAK a subrange at the back of the request range
        RetryPacket nak = *req_overlay;
        nak.id = htonl(TRTPPacket::kCNC_NakRetryRequestID);
        nak.seqStart = htons(retryLastSeq + 1);

        if (sendto(mSocket, &nak, sizeof(nak), 0,
                   src, sizeof(*src_addr)) < 0) {
            LOGD("Failed to send retry NAK to %d.%d.%d.%d:%hu.  "
                 "(socket %d, errno %d, missing back end)",
                 IP_PRINTF_HELPER(addr), port, mSocket, errno);
        }

        endIndex = retry_sz - 1;
    }

    // send the retry packets
    for (int i = startIndex; i <= endIndex; i++) {
        const sp<TRTPPacket>& replyPacket = mRetryBuffer[i];
        CHECK(replyPacket != NULL);

        if (sendto(mSocket,
                   replyPacket->getPacket(),
                   replyPacket->getPacketLen(),
                   0, src, sizeof(*src_addr)) < 0) {
            LOGD("Failed to send seq #%hu to %d.%d.%d.%d:%hu.  "
                 "(socket %d, errno %d)",
                 replyPacket->getSeqNumber(),
                 IP_PRINTF_HELPER(addr),
                 port, mSocket, errno);
        }
    }
}

void AAH_TXGroup::handleJoinGroup(const struct sockaddr_in* src_addr) {
    Mutex::Autolock lock(mLock);
    CHECK(src_addr != NULL);

    const struct sockaddr* src =
        reinterpret_cast<const struct sockaddr*>(src_addr);
    uint32_t addr = ntohl(src_addr->sin_addr.s_addr);
    uint16_t port = ntohs(src_addr->sin_port);

    // Looks like we just got a group membership report.  Start by checking to
    // see if this client is already in the list of unicast clients.  If it is,
    // just reset its group membership expiration timer and get out.
    for (size_t i = 0; i < mUnicastTargets.size(); ++i) {
        const sp<UnicastTarget>& tgt = mUnicastTargets[i];
        if (matchSockaddrs(src_addr, &tgt->mEndpoint)) {
            tgt->mGroupTimeout.setTimeout(kUnicastClientTimeoutMsec);
            return;
        }
    }

    // Looks like we have a new client.  Check to see if we have room to add it
    // before proceeding.  If not, send a NAK back so it knows to signal an
    // error to its application level.
    if (mUnicastTargets.size() >= kMaxAllowedUnicastTargets) {
        uint32_t nak_payload = htonl(TRTPPacket::kCNC_NakJoinGroupID);

        if (sendto(mSocket, &nak_payload, sizeof(nak_payload),
                   0, src, sizeof(*src_addr)) < 0) {
            LOGD("TXGroup on port %hu failed to NAK group join to"
                 " %d.%d.%d.%d:%hu.  (socket %d, errno %d, too many clients)",
                 mCmdAndControlPort, IP_PRINTF_HELPER(addr),
                 port, mSocket, errno);
        }

        return;
    }

    // Try to make a new client record and add him to the list of unicast
    // clients.  If we fail to make the new client, or fail to add it to the
    // list, send a NAK back to the client so it knows to signal an error to its
    // application level.
    sp<UnicastTarget> ut = new UnicastTarget(*src_addr);
    if ((ut == NULL) || (mUnicastTargets.add(ut) < 0)) {
        uint32_t nak_payload = htonl(TRTPPacket::kCNC_NakJoinGroupID);

        if (sendto(mSocket, &nak_payload, sizeof(nak_payload),
                   0, src, sizeof(*src_addr)) < 0) {
            LOGD("TXGroup on port %hu failed to NAK group join to"
                 " %d.%d.%d.%d:%hu.  (socket %d, errno %d, failed alloc)",
                 mCmdAndControlPort, IP_PRINTF_HELPER(addr),
                 port, mSocket, errno);
        }

        return;
    }

    // Looks good, log the fact that we have a new client and we should be done.
    LOGI("TXGroup on port %hu added new client at %d.%d.%d.%d:%hu.  "
         "Now serving %d/%d unicast clients.",
         mCmdAndControlPort, IP_PRINTF_HELPER(addr), port,
         mUnicastTargets.size(), kMaxAllowedUnicastTargets);
}

void AAH_TXGroup::handleLeaveGroup(const struct sockaddr_in* src_addr) {
    CHECK(src_addr != NULL);

    // Looks like we have a client who wants to leave the group.  Try to find
    // and remove them from the UnicastTargets vector.  Don't freak out if we
    // don't find the client on the list.  Its generally good practice for
    // clients to double or triple tap their leave message as they are shutting
    // down to minimize the chance that we will need to time the client out in
    // the case of packet loss.
    for (size_t i = 0; i < mUnicastTargets.size(); ++i) {
        const sp<UnicastTarget>& tgt = mUnicastTargets[i];
        if (matchSockaddrs(src_addr, &tgt->mEndpoint)) {
            uint32_t addr = ntohl(src_addr->sin_addr.s_addr);
            uint16_t port = ntohs(src_addr->sin_port);

            mUnicastTargets.removeAt(i);

            LOGI("TXGroup on port %hu removing client at %d.%d.%d.%d:%hu due to"
                 " leave request.  Now serving %d/%d unicast clients.",
                 mCmdAndControlPort, IP_PRINTF_HELPER(addr), port,
                 mUnicastTargets.size(), kMaxAllowedUnicastTargets);

            return;
        }
    }
}

AAH_TXGroup::CmdAndControlRXer::CmdAndControlRXer() {
    mWakeupEventFD = -1;
}

AAH_TXGroup::CmdAndControlRXer::~CmdAndControlRXer() {
    if (mWakeupEventFD >= 0) {
        ::close(mWakeupEventFD);
    }
}

bool AAH_TXGroup::CmdAndControlRXer::init() {
    CHECK(mWakeupEventFD < 0);
    mTrimRetryTimeout.setTimeout(AAH_TXGroup::kRetryTrimIntervalMsec);
    mWakeupEventFD = eventfd(0, EFD_NONBLOCK);
    return (mWakeupEventFD >= 0);
}

void AAH_TXGroup::CmdAndControlRXer::wakeupThread() {
    if (mWakeupEventFD >= 0) {
        uint64_t tmp = 1;
        ::write(mWakeupEventFD, &tmp, sizeof(tmp));
    }
}

void AAH_TXGroup::CmdAndControlRXer::clearWakeupEvent() {
    if (mWakeupEventFD >= 0) {
        uint64_t tmp;
        ::read(mWakeupEventFD, &tmp, sizeof(tmp));
    }
}

bool AAH_TXGroup::CmdAndControlRXer::threadLoop() {
    // Implementation for main command and control receiver thread.  Its primary
    // job is to service command and control requests from clients.  These
    // include servicing resend requests for clients who missed packets, and
    // managing TX group membership for unicast clients.  In addition, the
    // command and control receiver thread handles expiration and cleanup of
    // idle transmit groups.

    // Step 1: Obtain the static lock.
    bool ret_val = false;
    sLock.lock();

    // Step 2: Setup our poll structs to listen for our wakeup event as well as
    // for events on the sockets for all of the transmit groups we are currently
    // maintaining.  Keep an array of pointers to the TX Groups we are listening
    // to in the same order that we setup their sockets in the pollfd array so
    // it will be easy to map from a signalled pollfd back to a specific TX
    // group.  We don't need to actually hold any reference to the TX Group
    // because a ref is already being held by the sActiveTXGroups vector.  Right
    // now, the only way to be removed from the vector is to have no active TX
    // Player clients and then expire due to timeout, a process which is managed
    // by this thread.
    //
    // Finally, set up a timeout equal to the minimum timeout across all of our
    // timeout events (things like heartbeat service, retry buffer trimming, tx
    // group expiration, and so on)

    struct pollfd pollFds[kMaxAllowedTXGroups + 1];
    AAH_TXGroup* txGroups[kMaxAllowedTXGroups];
    nfds_t evtCount = 1;
    int tmp, nextTimeout = -1;
    nsecs_t now = systemTime();

    // Start with our wakeup event.
    pollFds[0].fd = mWakeupEventFD;
    pollFds[0].events = POLLIN;
    pollFds[0].revents = 0;

    if (sActiveTXGroups.size()) {
        for (size_t i = 0; i < sActiveTXGroups.size(); ++i, ++evtCount) {
            txGroups[i] = sActiveTXGroups[i].get();
            CHECK(txGroups[i] != NULL);

            pollFds[evtCount].fd = txGroups[i]->mSocket;
            pollFds[evtCount].events = POLLIN;
            pollFds[evtCount].revents = 0;

            // Check the heartbeat timeout for this group.
            tmp = txGroups[i]->mHeartbeatTimeout.msecTillTimeout(now);
            nextTimeout = minTimeout(nextTimeout, tmp);

            // Check the cleanup timeout for this group.
            tmp = txGroups[i]->mCleanupTimeout.msecTillTimeout(now);
            nextTimeout = minTimeout(nextTimeout, tmp);
        }

        // Take into account the common trim timeout.
        tmp = mTrimRetryTimeout.msecTillTimeout(now);
        nextTimeout = minTimeout(nextTimeout, tmp);
    }

    // Step 3: OK - time to wait for there to be something to do.  Release our
    // lock and call poll.  Reacquire the lock when we are done waiting, then
    // figure out what needs to be done.
    sLock.unlock();
    int pollRes = poll(pollFds, evtCount, nextTimeout);
    sLock.lock();

    // Step 4: Time to figure out what work needs to be done.  Start by checking
    // to see if an exit has been requested.  If so, just get out immediately.
    if (exitPending()) {
        LOGI("C&C RX thread exiting");
        goto bailout;
    }

    // Was there an error while polling?  If so, consider it to be fatal and get
    // out.
    if (pollRes < 0) {
        LOGE("C&C RX thread encountered fatal error while polling (errno = %d)",
             errno);
        goto bailout;
    }

    // clear the wakeup event if needed.
    if (pollFds[0].revents)
        clearWakeupEvent();

    // Handle any pending C&C requests and heartbeat timeouts.  Also, trim the
    // retry buffers if its time to do so.
    bool timeToTrim;
    timeToTrim = !mTrimRetryTimeout.msecTillTimeout();
    for (size_t i = 1; i < evtCount; ++i) {
        AAH_TXGroup* group = txGroups[i - 1];
        if (0 != pollFds[i].revents) {
            group->handleRequests();
        }

        group->sendHeartbeatIfNeeded();

        if (timeToTrim) {
            group->trimRetryBuffer();
        }
    }

    // If we just trimmed, reset our trim timer.
    if (timeToTrim) {
        mTrimRetryTimeout.setTimeout(AAH_TXGroup::kRetryTrimIntervalMsec);
    }

    // Finally, cleanup any expired TX groups.
    for (size_t i = 0; i < sActiveTXGroups.size(); ) {
        if (sActiveTXGroups[i]->shouldExpire()) {
            LOGI("Expiring TX Group with C&C Port %hu.  %d/%d groups now"
                 " active.", sActiveTXGroups[i]->getCmdAndControlPort(),
                 sActiveTXGroups.size() - 1, kMaxAllowedTXGroups);
            sActiveTXGroups.removeAt(i);
        } else {
            ++i;
        }
    }

    ret_val = true;
bailout:
    sLock.unlock();
    return ret_val;
}

// CircularBuffer

template <typename T>
CircularBuffer<T>::CircularBuffer(size_t capacity)
        : mCapacity(capacity)
        , mHead(0)
        , mTail(0)
        , mFillCount(0) {
    mBuffer = new T[capacity];
}

template <typename T>
CircularBuffer<T>::~CircularBuffer() {
    delete [] mBuffer;
}

template <typename T>
void CircularBuffer<T>::push_back(const T& item) {
    if (this->isFull()) {
        this->pop_front();
    }
    mBuffer[mHead] = item;
    mHead = (mHead + 1) % mCapacity;
    mFillCount++;
}

template <typename T>
void CircularBuffer<T>::pop_front() {
    CHECK(!isEmpty());
    mBuffer[mTail] = T();
    mTail = (mTail + 1) % mCapacity;
    mFillCount--;
}

template <typename T>
size_t CircularBuffer<T>::size() const {
    return mFillCount;
}

template <typename T>
bool CircularBuffer<T>::isFull() const {
    return (mFillCount == mCapacity);
}

template <typename T>
bool CircularBuffer<T>::isEmpty() const {
    return (mFillCount == 0);
}

template <typename T>
const T& CircularBuffer<T>::itemAt(size_t index) const {
    CHECK(index < mFillCount);
    return mBuffer[(mTail + index) % mCapacity];
}

template <typename T>
const T& CircularBuffer<T>::operator[](size_t index) const {
    return itemAt(index);
}

}  // namespace android
