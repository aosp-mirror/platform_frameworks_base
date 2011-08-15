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

#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/foundation/AHandlerReflector.h>
#include <utils/RefBase.h>
#include <utils/threads.h>

#include "aah_tx_packet.h"
#include "pipe_event.h"

namespace android {

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

class AAH_TXSender : public virtual RefBase {
  public:
    ~AAH_TXSender();

    static sp<AAH_TXSender> GetInstance();

    ALooper::handler_id handlerID() { return mReflector->id(); }

    // an IP address and port
    struct Endpoint {
        Endpoint();
        Endpoint(uint32_t a, uint16_t p);
        bool operator<(const Endpoint& other) const;

        uint32_t addr;
        uint16_t port;
    };

    uint16_t registerEndpoint(const Endpoint& endpoint);
    void unregisterEndpoint(const Endpoint& endpoint);
    void assignSeqNumber(const Endpoint& endpoint,
                         const sp<TRTPPacket>& packet);

    enum {
        kWhatSendPacket,
        kWhatTrimRetryBuffers,
        kWhatSendHeartbeats,
    };

    // fields for SendPacket messages
    static const char* kSendPacketIPAddr;
    static const char* kSendPacketPort;
    static const char* kSendPacketTRTPPacket;

  private:
    AAH_TXSender();

    static Mutex sLock;
    static wp<AAH_TXSender> sInstance;
    static uint32_t sNextEpoch;
    static bool sNextEpochValid;

    static uint32_t getNextEpoch();

    typedef CircularBuffer<sp<TRTPPacket> > RetryBuffer;

    // state maintained on a per-endpoint basis
    struct EndpointState {
        EndpointState(uint32_t epoch);
        RetryBuffer retry;
        int playerRefCount;
        uint16_t trtpSeqNumber;
        uint16_t nextProgramID;
        uint32_t epoch;
    };

    friend class AHandlerReflector<AAH_TXSender>;
    void onMessageReceived(const sp<AMessage>& msg);
    void onSendPacket(const sp<AMessage>& msg);
    void doSendPacket(sp<TRTPPacket> packet, uint32_t ipAddr, uint16_t port);
    void addToRetryBuffer(const Endpoint& endpoint,
                          const sp<TRTPPacket>& packet);
    void addToRetryBuffer_l(EndpointState* eps,
                            const sp<TRTPPacket>& packet);
    void trimRetryBuffers();
    void sendHeartbeats();
    void assignSeqNumber_l(const Endpoint& endpoint,
                           const sp<TRTPPacket>& packet);
    sp<ALooper> mLooper;
    sp<AHandlerReflector<AAH_TXSender> > mReflector;

    int mSocket;

    DefaultKeyedVector<Endpoint, EndpointState*> mEndpointMap;
    Mutex mEndpointLock;

    static const int kRetryTrimIntervalUs;
    static const int kHeartbeatIntervalUs;
    static const int kRetryBufferCapacity;

    class RetryReceiver : public Thread {
      private:
        friend class AAH_TXSender;

        RetryReceiver(AAH_TXSender* sender);
        virtual ~RetryReceiver();
        virtual bool threadLoop();
        void handleRetryRequest();

        static const int kMaxReceiverPacketLen;
        static const uint32_t kRetryRequestID;
        static const uint32_t kFastStartRequestID;
        static const uint32_t kRetryNakID;

        AAH_TXSender* mSender;
        PipeEvent mWakeupEvent;
    };

    sp<RetryReceiver> mRetryReceiver;
};

struct RetryPacket {
    uint32_t id;
    uint32_t endpointIP;
    uint16_t endpointPort;
    uint16_t seqStart;
    uint16_t seqEnd;
} __attribute__((packed));

}  // namespace android

#endif  // __AAH_TX_SENDER_H__
