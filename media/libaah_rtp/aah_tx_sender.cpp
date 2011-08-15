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
#include <media/stagefright/foundation/ADebug.h>

#include <netinet/in.h>
#include <poll.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <unistd.h>

#include <media/stagefright/foundation/AMessage.h>
#include <utils/misc.h>

#include "aah_tx_player.h"
#include "aah_tx_sender.h"

namespace android {

const char* AAH_TXSender::kSendPacketIPAddr = "ipaddr";
const char* AAH_TXSender::kSendPacketPort = "port";
const char* AAH_TXSender::kSendPacketTRTPPacket = "trtp";

const int AAH_TXSender::kRetryTrimIntervalUs = 100000;
const int AAH_TXSender::kHeartbeatIntervalUs = 1000000;
const int AAH_TXSender::kRetryBufferCapacity = 100;

Mutex AAH_TXSender::sLock;
wp<AAH_TXSender> AAH_TXSender::sInstance;
uint32_t AAH_TXSender::sNextEpoch;
bool AAH_TXSender::sNextEpochValid = false;

AAH_TXSender::AAH_TXSender() : mSocket(-1) { }

sp<AAH_TXSender> AAH_TXSender::GetInstance() {
    Mutex::Autolock autoLock(sLock);

    sp<AAH_TXSender> sender = sInstance.promote();

    if (sender == NULL) {
        sender = new AAH_TXSender();
        if (sender == NULL) {
            return NULL;
        }

        sender->mLooper = new ALooper();
        if (sender->mLooper == NULL) {
            return NULL;
        }

        sender->mReflector = new AHandlerReflector<AAH_TXSender>(sender.get());
        if (sender->mReflector == NULL) {
            return NULL;
        }

        sender->mSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (sender->mSocket == -1) {
            LOGW("%s unable to create socket", __PRETTY_FUNCTION__);
            return NULL;
        }

        struct sockaddr_in bind_addr;
        memset(&bind_addr, 0, sizeof(bind_addr));
        bind_addr.sin_family = AF_INET;
        if (bind(sender->mSocket,
                 reinterpret_cast<const sockaddr*>(&bind_addr),
                 sizeof(bind_addr)) < 0) {
            LOGW("%s unable to bind socket (errno %d)",
                 __PRETTY_FUNCTION__, errno);
            return NULL;
        }

        sender->mRetryReceiver = new RetryReceiver(sender.get());
        if (sender->mRetryReceiver == NULL) {
            return NULL;
        }

        sender->mLooper->setName("AAH_TXSender");
        sender->mLooper->registerHandler(sender->mReflector);
        sender->mLooper->start(false, false, PRIORITY_AUDIO);

        if (sender->mRetryReceiver->run("AAH_TXSenderRetry", PRIORITY_AUDIO)
                != OK) {
            LOGW("%s unable to start retry thread", __PRETTY_FUNCTION__);
            return NULL;
        }

        sInstance = sender;
    }

    return sender;
}

AAH_TXSender::~AAH_TXSender() {
    mLooper->stop();
    mLooper->unregisterHandler(mReflector->id());

    if (mRetryReceiver != NULL) {
        mRetryReceiver->requestExit();
        mRetryReceiver->mWakeupEvent.setEvent();
        if (mRetryReceiver->requestExitAndWait() != OK) {
            LOGW("%s shutdown of retry receiver failed", __PRETTY_FUNCTION__);
        }
        mRetryReceiver->mSender = NULL;
        mRetryReceiver.clear();
    }

    if (mSocket != -1) {
        close(mSocket);
    }
}

// Return the next epoch number usable for a newly instantiated endpoint.
uint32_t AAH_TXSender::getNextEpoch() {
    Mutex::Autolock autoLock(sLock);

    if (sNextEpochValid) {
        sNextEpoch = (sNextEpoch + 1) & TRTPPacket::kTRTPEpochMask;
    } else {
        sNextEpoch = ns2ms(systemTime()) & TRTPPacket::kTRTPEpochMask;
        sNextEpochValid = true;
    }

    return sNextEpoch;
}

// Notify the sender that a player has started sending to this endpoint.
// Returns a program ID for use by the calling player.
uint16_t AAH_TXSender::registerEndpoint(const Endpoint& endpoint) {
    Mutex::Autolock lock(mEndpointLock);

    EndpointState* eps = mEndpointMap.valueFor(endpoint);
    if (eps) {
        eps->playerRefCount++;
    } else {
        eps = new EndpointState(getNextEpoch());
        mEndpointMap.add(endpoint, eps);
    }

    // if this is the first registered endpoint, then send a message to start
    // trimming retry buffers and a message to start sending heartbeats.
    if (mEndpointMap.size() == 1) {
        sp<AMessage> trimMessage = new AMessage(kWhatTrimRetryBuffers,
                                                handlerID());
        trimMessage->post(kRetryTrimIntervalUs);

        sp<AMessage> heartbeatMessage = new AMessage(kWhatSendHeartbeats,
                                                     handlerID());
        heartbeatMessage->post(kHeartbeatIntervalUs);
    }

    eps->nextProgramID++;
    return eps->nextProgramID;
}

// Notify the sender that a player has ceased sending to this endpoint.
// An endpoint's state can not be deleted until all of the endpoint's
// registered players have called unregisterEndpoint.
void AAH_TXSender::unregisterEndpoint(const Endpoint& endpoint) {
    Mutex::Autolock lock(mEndpointLock);

    EndpointState* eps = mEndpointMap.valueFor(endpoint);
    if (eps) {
        eps->playerRefCount--;
        CHECK(eps->playerRefCount >= 0);
    }
}

void AAH_TXSender::assignSeqNumber(const Endpoint& endpoint,
                                       const sp<TRTPPacket>& packet) {
    Mutex::Autolock lock(mEndpointLock);
    assignSeqNumber_l(endpoint, packet);
}

void AAH_TXSender::assignSeqNumber_l(const Endpoint& endpoint,
                                         const sp<TRTPPacket>& packet) {
    EndpointState* eps = mEndpointMap.valueFor(endpoint);
    if (!eps) {
        // the endpoint state has disappeared, so the player that sent this
        // packet must be dead.
        return;
    }
    packet->setEpoch(eps->epoch);
    packet->setSeqNumber(eps->trtpSeqNumber++);
}

void AAH_TXSender::onMessageReceived(const sp<AMessage>& msg) {
    switch (msg->what()) {
        case kWhatSendPacket:
            onSendPacket(msg);
            break;

        case kWhatTrimRetryBuffers:
            trimRetryBuffers();
            break;

        case kWhatSendHeartbeats:
            sendHeartbeats();
            break;

        default:
            TRESPASS();
            break;
    }
}

void AAH_TXSender::onSendPacket(const sp<AMessage>& msg) {
    LOGV("*** %s", __PRETTY_FUNCTION__);

    sp<RefBase> obj;
    CHECK(msg->findObject(kSendPacketTRTPPacket, &obj));
    sp<TRTPPacket> packet = static_cast<TRTPPacket*>(obj.get());

    uint32_t ipAddr;
    CHECK(msg->findInt32(kSendPacketIPAddr,
                         reinterpret_cast<int32_t*>(&ipAddr)));

    int32_t port32;
    CHECK(msg->findInt32(kSendPacketPort, &port32));
    uint16_t port = port32;

    doSendPacket(packet, ipAddr, port);

    addToRetryBuffer(Endpoint(ipAddr, port), packet);
}

void AAH_TXSender::doSendPacket(sp<TRTPPacket> packet,
                                uint32_t ipAddr,
                                uint16_t port) {
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = ipAddr;
    addr.sin_port = htons(port);

    ssize_t result = sendto(mSocket,
                            packet->getPacket(),
                            packet->getPacketLen(),
                            0,
                            (const struct sockaddr *) &addr,
                            sizeof(addr));
    if (result == -1) {
        LOGW("%s sendto failed", __PRETTY_FUNCTION__);
    }
}

void AAH_TXSender::addToRetryBuffer(const Endpoint& endpoint,
                                    const sp<TRTPPacket>& packet) {
    Mutex::Autolock lock(mEndpointLock);

    EndpointState* eps = mEndpointMap.valueFor(endpoint);
    if (!eps) {
        return;
    }

    addToRetryBuffer_l(eps, packet);
}

void AAH_TXSender::addToRetryBuffer_l(EndpointState* eps,
                                      const sp<TRTPPacket>& packet) {
    RetryBuffer& retry = eps->retry;
    retry.push_back(packet);

    LOGV("*** %s seq=%hu size=%d",
         __PRETTY_FUNCTION__,
         packet->getSeqNumber(),
         retry.size());
}

void AAH_TXSender::trimRetryBuffers() {
    LOGV("*** %s", __PRETTY_FUNCTION__);

    Mutex::Autolock lock(mEndpointLock);

    nsecs_t localTimeNow = systemTime();

    Vector<Endpoint> endpointsToRemove;

    for (size_t i = 0; i < mEndpointMap.size(); i++) {
        EndpointState* eps = mEndpointMap.editValueAt(i);
        RetryBuffer& retry = eps->retry;

        while (!retry.isEmpty()) {
            if (retry[0]->getExpireTime() < localTimeNow) {
                retry.pop_front();
            } else {
                break;
            }
        }

        LOGV("*** %s size=%d", __PRETTY_FUNCTION__, retry.size());

        if (retry.isEmpty() && eps->playerRefCount == 0) {
            endpointsToRemove.add(mEndpointMap.keyAt(i));
        }
    }

    // remove the state for any endpoints that are no longer in use
    for (size_t i = 0; i < endpointsToRemove.size(); i++) {
        Endpoint& e = endpointsToRemove.editItemAt(i);
        LOGD("*** %s removing endpoint addr=%08x", __PRETTY_FUNCTION__, e.addr);
        size_t index = mEndpointMap.indexOfKey(e);
        delete mEndpointMap.valueAt(index);
        mEndpointMap.removeItemsAt(index);
    }

    // schedule the next trim
    if (mEndpointMap.size()) {
        sp<AMessage> trimMessage = new AMessage(kWhatTrimRetryBuffers,
                                                handlerID());
        trimMessage->post(kRetryTrimIntervalUs);
    }
}

void AAH_TXSender::sendHeartbeats() {
    Mutex::Autolock lock(mEndpointLock);

    for (size_t i = 0; i < mEndpointMap.size(); i++) {
        EndpointState* eps = mEndpointMap.editValueAt(i);
        const Endpoint& ep = mEndpointMap.keyAt(i);

        sp<TRTPControlPacket> packet = new TRTPControlPacket();
        packet->setCommandID(TRTPControlPacket::kCommandNop);

        assignSeqNumber_l(ep, packet);
        packet->setExpireTime(systemTime() +
                              AAH_TXPlayer::kAAHRetryKeepAroundTimeNs);
        packet->pack();

        doSendPacket(packet, ep.addr, ep.port);
        addToRetryBuffer_l(eps, packet);
    }

    // schedule the next heartbeat
    if (mEndpointMap.size()) {
        sp<AMessage> heartbeatMessage = new AMessage(kWhatSendHeartbeats,
                                                     handlerID());
        heartbeatMessage->post(kHeartbeatIntervalUs);
    }
}

// Receiver

// initial 4-byte ID of a retry request packet
const uint32_t AAH_TXSender::RetryReceiver::kRetryRequestID = 'Treq';

// initial 4-byte ID of a retry NAK packet
const uint32_t AAH_TXSender::RetryReceiver::kRetryNakID = 'Tnak';

// initial 4-byte ID of a fast start request packet
const uint32_t AAH_TXSender::RetryReceiver::kFastStartRequestID = 'Tfst';

AAH_TXSender::RetryReceiver::RetryReceiver(AAH_TXSender* sender)
        : Thread(false),
    mSender(sender) {}

    AAH_TXSender::RetryReceiver::~RetryReceiver() {
        mWakeupEvent.clearPendingEvents();
    }

// Returns true if val is within the interval bounded inclusively by
// start and end.  Also handles the case where there is a rollover of the
// range between start and end.
template <typename T>
static inline bool withinIntervalWithRollover(T val, T start, T end) {
    return ((start <= end && val >= start && val <= end) ||
            (start > end && (val >= start || val <= end)));
}

bool AAH_TXSender::RetryReceiver::threadLoop() {
    struct pollfd pollFds[2];
    pollFds[0].fd = mSender->mSocket;
    pollFds[0].events = POLLIN;
    pollFds[0].revents = 0;
    pollFds[1].fd = mWakeupEvent.getWakeupHandle();
    pollFds[1].events = POLLIN;
    pollFds[1].revents = 0;

    int pollResult = poll(pollFds, NELEM(pollFds), -1);
    if (pollResult == -1) {
        LOGE("%s poll failed", __PRETTY_FUNCTION__);
        return false;
    }

    if (exitPending()) {
        LOGI("*** %s exiting", __PRETTY_FUNCTION__);
        return false;
    }

    if (pollFds[0].revents) {
        handleRetryRequest();
    }

    return true;
}

void AAH_TXSender::RetryReceiver::handleRetryRequest() {
    LOGV("*** RX %s start", __PRETTY_FUNCTION__);

    RetryPacket request;
    struct sockaddr requestSrcAddr;
    socklen_t requestSrcAddrLen = sizeof(requestSrcAddr);

    ssize_t result = recvfrom(mSender->mSocket, &request, sizeof(request), 0,
                              &requestSrcAddr, &requestSrcAddrLen);
    if (result == -1) {
        LOGE("%s recvfrom failed, errno=%d", __PRETTY_FUNCTION__, errno);
        return;
    }

    if (static_cast<size_t>(result) < sizeof(RetryPacket)) {
        LOGW("%s short packet received", __PRETTY_FUNCTION__);
        return;
    }

    uint32_t host_request_id = ntohl(request.id);
    if ((host_request_id != kRetryRequestID) &&
        (host_request_id != kFastStartRequestID)) {
        LOGW("%s received retry request with bogus ID (%08x)",
                __PRETTY_FUNCTION__, host_request_id);
        return;
    }

    Endpoint endpoint(request.endpointIP, ntohs(request.endpointPort));

    Mutex::Autolock lock(mSender->mEndpointLock);

    EndpointState* eps = mSender->mEndpointMap.valueFor(endpoint);

    if (eps == NULL || eps->retry.isEmpty()) {
        // we have no retry buffer or an empty retry buffer for this endpoint,
        // so NAK the entire request
        RetryPacket nak = request;
        nak.id = htonl(kRetryNakID);
        result = sendto(mSender->mSocket, &nak, sizeof(nak), 0,
                        &requestSrcAddr, requestSrcAddrLen);
        if (result == -1) {
            LOGW("%s sendto failed", __PRETTY_FUNCTION__);
        }
        return;
    }

    RetryBuffer& retry = eps->retry;

    uint16_t startSeq = ntohs(request.seqStart);
    uint16_t endSeq = ntohs(request.seqEnd);

    uint16_t retryFirstSeq = retry[0]->getSeqNumber();
    uint16_t retryLastSeq = retry[retry.size() - 1]->getSeqNumber();

    // If this is a fast start, then force the start of the retry to match the
    // start of the retransmit ring buffer (unless the end of the retransmit
    // ring buffer is already past the point of fast start)
    if ((host_request_id == kFastStartRequestID) &&
        !((startSeq - retryFirstSeq) & 0x8000)) {
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
        RetryPacket nak = request;
        nak.id = htonl(kRetryNakID);
        result = sendto(mSender->mSocket, &nak, sizeof(nak), 0,
                        &requestSrcAddr, requestSrcAddrLen);
        if (result == -1) {
            LOGW("%s sendto failed", __PRETTY_FUNCTION__);
        }
        return;
    }

    if (startIndex == -1) {
        // NAK a subrange at the front of the request range
        RetryPacket nak = request;
        nak.id = htonl(kRetryNakID);
        nak.seqEnd = htons(retryFirstSeq - 1);
        result = sendto(mSender->mSocket, &nak, sizeof(nak), 0,
                        &requestSrcAddr, requestSrcAddrLen);
        if (result == -1) {
            LOGW("%s sendto failed", __PRETTY_FUNCTION__);
        }

        startIndex = 0;
    } else if (endIndex == -1) {
        // NAK a subrange at the back of the request range
        RetryPacket nak = request;
        nak.id = htonl(kRetryNakID);
        nak.seqStart = htons(retryLastSeq + 1);
        result = sendto(mSender->mSocket, &nak, sizeof(nak), 0,
                        &requestSrcAddr, requestSrcAddrLen);
        if (result == -1) {
            LOGW("%s sendto failed", __PRETTY_FUNCTION__);
        }

        endIndex = retry.size() - 1;
    }

    // send the retry packets
    for (int i = startIndex; i <= endIndex; i++) {
        const sp<TRTPPacket>& replyPacket = retry[i];

        result = sendto(mSender->mSocket,
                        replyPacket->getPacket(),
                        replyPacket->getPacketLen(),
                        0,
                        &requestSrcAddr,
                        requestSrcAddrLen);

        if (result == -1) {
            LOGW("%s sendto failed", __PRETTY_FUNCTION__);
        }
    }
}

// Endpoint

AAH_TXSender::Endpoint::Endpoint()
        : addr(0)
        , port(0) { }

AAH_TXSender::Endpoint::Endpoint(uint32_t a, uint16_t p)
        : addr(a)
        , port(p) {}

bool AAH_TXSender::Endpoint::operator<(const Endpoint& other) const {
    return ((addr < other.addr) ||
            (addr == other.addr && port < other.port));
}

// EndpointState

AAH_TXSender::EndpointState::EndpointState(uint32_t _epoch)
    : retry(kRetryBufferCapacity)
    , playerRefCount(1)
    , trtpSeqNumber(0)
    , nextProgramID(0)
    , epoch(_epoch) { }

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
