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

#ifndef __AAH_TX_SENDER_H__
#define __AAH_TX_SENDER_H__

#include <netinet/in.h>
#include <stdint.h>

#include <media/stagefright/foundation/ABase.h>
#include <utils/RefBase.h>
#include <utils/threads.h>
#include <utils/Vector.h>

#include "aah_tx_packet.h"
#include "utils.h"

#define IP_PRINTF_HELPER(a) ((a >> 24) & 0xFF), ((a >> 16) & 0xFF), \
                            ((a >>  8) & 0xFF),  (a        & 0xFF)

namespace android {

class AAH_TXPlayer;

template <typename T> class CircularBuffer {
  public:
    CircularBuffer(size_t capacity);
    ~CircularBuffer();
    void push_back(const T& item);;
    void pop_front();
    size_t size() const;
    bool isFull() const;
    bool isEmpty() const;
    const T& itemAt(size_t index) const;
    const T& operator[](size_t index) const;

  private:
    T* mBuffer;
    size_t mCapacity;
    size_t mHead;
    size_t mTail;
    size_t mFillCount;
};

struct RetryPacket {
    uint32_t id;
    // TODO: endpointIP and endpointPort are no longer needed now that we use a
    // dedicated send/C&C socket for each TX group.  Removing them is a protocol
    // breaking change; something which would be good to do before 1.0 ship so
    // we don't have to maintain backwards compatibility forever.
    uint32_t endpointIP;
    uint16_t endpointPort;
    uint16_t seqStart;
    uint16_t seqEnd;
} __attribute__((packed));

class AAH_TXGroup : public virtual RefBase {
  public:
    // Obtain the instance of the TXGroup whose command and control socket is
    // currently listening on the specified port.  Alternatively, if port is 0,
    // create a new TXGroup with an ephemerally bound command and control port.
    static sp<AAH_TXGroup> getGroup(uint16_t port,
                                    const sp<AAH_TXPlayer>& client);

    // Obtain the instance of the TXGroup whose multicast transmit target is
    // currently set to target, or NULL if no such group exists.  To create a
    // new transmit group with a new multicast target address, call getGroup(0)
    // followed by setMulticastTXTarget.
    static sp<AAH_TXGroup> getGroup(const struct sockaddr_in* target,
                                    const sp<AAH_TXPlayer>& client);

    // AAH_TXGroups successfully obtained using calls to getGroup will hold a
    // reference back to the client passed to getGroup.  When the client is
    // finished using the group, it must call unregisterClient to release this
    // reference.
    //
    // While there exists active clients of transmit group, the TX group will
    // periodically send heartbeat messages to receiver clients containing the
    // program IDs of the currently active TX Player clients so that receivers
    // have a chance to clean up orphaned programs in the case where all EOS
    // messages got dropped on the way to the receiver.
    //
    // Once all clients references have been released from a TX Group, the group
    // will linger in the system for a short period of time before finally
    // expiring and being cleaned up by the command and control thread.
    //
    // TODO : someday, expose the AAH_TXGroup as a top level object in the
    // android MediaAPIs so that applications may explicitly manage TX group
    // lifecycles instead of relying on this timeout/cleanup mechanism.
    void unregisterClient(const sp<AAH_TXPlayer>& client);


    // Fetch the UDP port on which this TXGroup is listening for command and
    // control messages.  No need to hold any locks for this, the port is
    // established as group is created and bound, and then never changed
    // afterwards.
    uint16_t getCmdAndControlPort() const { return mCmdAndControlPort; }

    // Assign a TRTP sequence number to the supplied packet and send it to all
    // registered clients.  Then place the packet into the RetryBuffer to
    // service future client retry requests.
    status_t sendPacket(const sp<TRTPPacket>& packet);

    // Sets the multicast transmit target for this TX Group.  Pass NULL to clear
    // the multicast transmit target and return to pure unicast mode.
    void setMulticastTXTarget(const struct sockaddr_in* target);

  protected:
    // TXGroups are ref counted objects; make sure that only RefBase can call
    // the destructor.
    ~AAH_TXGroup();

  private:
    // Definition of the singleton command and control receiver who will handle
    // requests from RX clients.  Requests include things like unicast group
    // management as well as retransmission requests.
    class CmdAndControlRXer : public Thread {
      public:
        CmdAndControlRXer();
        void wakeupThread();
        bool init();

      protected:
        virtual ~CmdAndControlRXer();

      private:
        virtual bool threadLoop();
        void clearWakeupEvent();

        static const int kMaxReceiverPacketLen;
        static const uint32_t kRetryRequestID;
        static const uint32_t kFastStartRequestID;
        static const uint32_t kRetryNakID;

        int mWakeupEventFD;

        // Timeout used to track when we need to trim all of the retry buffers.
        Timeout mTrimRetryTimeout;

        DISALLOW_EVIL_CONSTRUCTORS(CmdAndControlRXer);
    };

    // Small utility class used to keep track of our unicast clients and when
    // they are going to time out of the group if we don't keep receiving group
    // membership reports.
    class UnicastTarget : public RefBase {
      public:
        explicit UnicastTarget(const struct sockaddr_in& endpoint) {
            mGroupTimeout.setTimeout(kUnicastClientTimeoutMsec);
            mEndpoint = endpoint;
        }

        struct sockaddr_in mEndpoint;
        Timeout mGroupTimeout;

        DISALLOW_EVIL_CONSTRUCTORS(UnicastTarget);
    };

    // A circular buffer of TRTPPackets is a retry buffer for a TX Group.
    typedef CircularBuffer< sp<TRTPPacket> > RetryBuffer;

    /***************************************************************************
     *
     * Static shared state as well as static methods.
     *
     **************************************************************************/

    // Our lock for serializing access to shared state.
    static Mutex sLock;

    // The list of currently active TX groups.
    static Vector < sp<AAH_TXGroup> > sActiveTXGroups;

    // The singleton command and control receiver thread.
    static sp<CmdAndControlRXer> mCmdAndControlRXer;

    // State used to generate unique TRTP epochs.
    static uint32_t sNextEpoch;
    static bool sNextEpochValid;
    static uint32_t getNextEpoch();

    /***************************************************************************
     *
     * Private methods.
     *
     **************************************************************************/

    // Private constructor.  Use the static getGroup to obtain an AAH_TXGroup
    // instance.
    AAH_TXGroup();

    // Used by the static getGroup methods when they need to create a brand new
    // TXGroup.
    bool bindSocket();

    // Locked version of sendPacket.
    status_t sendPacket_l(const sp<TRTPPacket>& packet);

    // Send a payload to a specific target address.
    status_t sendToTarget_l(const struct sockaddr_in* target,
                            const uint8_t* payload,
                            size_t length);

    // Register a player client to this TX Group and reset its cleanup timer.
    bool registerClient(const sp<AAH_TXPlayer>& client);

    // Obtain a new program ID for a newly registered client.
    uint8_t getNewProgramID();

    // Test used by the C&C thread to see if its time to expire and cleanup a
    // client.
    bool shouldExpire();

    // Trim expired packets from this instance's retry buffer.
    void trimRetryBuffer();

    // Send a heartbeat to this instance's clients to let them know that we are
    // still alive if its been a while since we sent any traffic to them.
    void sendHeartbeatIfNeeded();

    // Handle any pending command and control requests waiting in this TX
    // group's socket.
    void handleRequests();

    // Handlers for individual command and control request types.
    void handleRetryRequest(const uint8_t* req,
                            const struct sockaddr_in* src_addr,
                            bool isFastStart);

    void handleJoinGroup(const struct sockaddr_in* src_addr);

    void handleLeaveGroup(const struct sockaddr_in* src_addr);

    /***************************************************************************
     *
     * Private member variables
     *
     **************************************************************************/

    // Lock we use to serialize access to instance variables.
    Mutex mLock;

    // The list of packets we hold for servicing retry requests.
    RetryBuffer mRetryBuffer;

    // The current set of active TX Player clients using this TX group.
    Vector< sp<AAH_TXPlayer> > mActiveClients;

    // The sequence number to assign to the next transmitted TRTP packet.
    uint16_t mTRTPSeqNumber;

    // The program ID to assign to the next TXPlayer client.  Program IDs are
    // actually unsigned 16 bit ints, but android's atomic inc routine operates
    // on ints, not shorts.  Its not a problem, we just mask out the lower 16
    // bits and call it good.
    int mNextProgramID;

    // The TRTP Epoch assigned to this transmit group.
    uint32_t mEpoch;

    // The socket we use to send packet and receive command and control
    // requests.
    int mSocket;

    // The UDP port to which our socket is bound (in host order).
    uint16_t mCmdAndControlPort;

    // The multicast target to send traffic to (if valid) For sanity's sake,
    // TXGroups are not allowed to have multiple multicast targets.
    struct sockaddr_in mMulticastTarget;
    bool mMulticastTargetValid;

    // The list of unicast client targets to send traffic to.
    //
    // TODO: right now, N for this list is expected to be small (think 1-3), and
    // is capped at something reasonable (16 right now).  If we ever need to go
    // much beyond that, we should seriously consider switching this to
    // something with O(ln) lookup time indexed by client's endpoint so we can
    // efficiently handle the regular group membership reports we need to
    // process from each client.
    Vector< sp<UnicastTarget> > mUnicastTargets;

    // Timeout used to track when the next heartbeat should be sent.
    Timeout mHeartbeatTimeout;

    // Timeout used to determine when to clean up this TXGroup after it no
    // longer has any TXPlayer clients.
    Timeout mCleanupTimeout;

    /***************************************************************************
     *
     * Class wide constants.
     *
     **************************************************************************/
    static const int kRetryTrimIntervalMsec;
    static const int kHeartbeatIntervalMsec;
    static const int kTXGroupLingerTimeMsec;
    static const int kUnicastClientTimeoutMsec;

    static const size_t kRetryBufferCapacity;
    static const size_t kMaxAllowedUnicastTargets;
    static const size_t kInitialUnicastTargetCapacity;
    static const size_t kMaxAllowedTXGroups;
    static const size_t kInitialActiveTXGroupsCapacity;
    static const size_t kMaxAllowedPlayerClients;
    static const size_t kInitialPlayerClientCapacity;

    static const uint32_t kCNC_RetryRequestID;
    static const uint32_t kCNC_FastStartRequestID;
    static const uint32_t kCNC_NakRetryRequestID;
    static const uint32_t kCNC_JoinGroupID;
    static const uint32_t kCNC_LeaveGroupID;
    static const uint32_t kCNC_NakJoinGroupID;

    DISALLOW_EVIL_CONSTRUCTORS(AAH_TXGroup);
};

}  // namespace android

#endif  // __AAH_TX_SENDER_H__
