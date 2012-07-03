/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/epoll.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <arpa/inet.h>
#include <netinet/in.h>

// #define LOG_NDEBUG 0
#define LOG_TAG "AudioGroup"
#include <cutils/atomic.h>
#include <cutils/properties.h>
#include <utils/Log.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/threads.h>
#include <utils/SystemClock.h>
#include <media/AudioSystem.h>
#include <media/AudioRecord.h>
#include <media/AudioTrack.h>
#include <media/mediarecorder.h>
#include <media/AudioEffect.h>
#include <audio_effects/effect_aec.h>
#include <system/audio.h>

#include "jni.h"
#include "JNIHelp.h"

#include "AudioCodec.h"
#include "EchoSuppressor.h"

extern int parse(JNIEnv *env, jstring jAddress, int port, sockaddr_storage *ss);

namespace {

using namespace android;

int gRandom = -1;

// We use a circular array to implement jitter buffer. The simplest way is doing
// a modulo operation on the index while accessing the array. However modulo can
// be expensive on some platforms, such as ARM. Thus we round up the size of the
// array to the nearest power of 2 and then use bitwise-and instead of modulo.
// Currently we make it 2048ms long and assume packet interval is 50ms or less.
// The first 100ms is the place where samples get mixed. The rest is the real
// jitter buffer. For a stream at 8000Hz it takes 32 kilobytes. These numbers
// are chosen by experiments and each of them can be adjusted as needed.

// Originally a stream does not send packets when it is receive-only or there is
// nothing to mix. However, this causes some problems with certain firewalls and
// proxies. A firewall might remove a port mapping when there is no outgoing
// packet for a preiod of time, and a proxy might wait for incoming packets from
// both sides before start forwarding. To solve these problems, we send out a
// silence packet on the stream for every second. It should be good enough to
// keep the stream alive with relatively low resources.

// Other notes:
// + We use elapsedRealtime() to get the time. Since we use 32bit variables
//   instead of 64bit ones, comparison must be done by subtraction.
// + Sampling rate must be multiple of 1000Hz, and packet length must be in
//   milliseconds. No floating points.
// + If we cannot get enough CPU, we drop samples and simulate packet loss.
// + Resampling is not done yet, so streams in one group must use the same rate.
//   For the first release only 8000Hz is supported.

#define BUFFER_SIZE     2048
#define HISTORY_SIZE    100
#define MEASURE_BASE    100
#define MEASURE_PERIOD  5000
#define DTMF_PERIOD     200

class AudioStream
{
public:
    AudioStream();
    ~AudioStream();
    bool set(int mode, int socket, sockaddr_storage *remote,
        AudioCodec *codec, int sampleRate, int sampleCount,
        int codecType, int dtmfType);

    void sendDtmf(int event);
    bool mix(int32_t *output, int head, int tail, int sampleRate);
    void encode(int tick, AudioStream *chain);
    void decode(int tick);

private:
    enum {
        NORMAL = 0,
        SEND_ONLY = 1,
        RECEIVE_ONLY = 2,
        LAST_MODE = 2,
    };

    int mMode;
    int mSocket;
    sockaddr_storage mRemote;
    AudioCodec *mCodec;
    uint32_t mCodecMagic;
    uint32_t mDtmfMagic;
    bool mFixRemote;

    int mTick;
    int mSampleRate;
    int mSampleCount;
    int mInterval;
    int mKeepAlive;

    int16_t *mBuffer;
    int mBufferMask;
    int mBufferHead;
    int mBufferTail;
    int mLatencyTimer;
    int mLatencyScore;

    uint16_t mSequence;
    uint32_t mTimestamp;
    uint32_t mSsrc;

    int mDtmfEvent;
    int mDtmfStart;

    AudioStream *mNext;

    friend class AudioGroup;
};

AudioStream::AudioStream()
{
    mSocket = -1;
    mCodec = NULL;
    mBuffer = NULL;
    mNext = NULL;
}

AudioStream::~AudioStream()
{
    close(mSocket);
    delete mCodec;
    delete [] mBuffer;
    ALOGD("stream[%d] is dead", mSocket);
}

bool AudioStream::set(int mode, int socket, sockaddr_storage *remote,
    AudioCodec *codec, int sampleRate, int sampleCount,
    int codecType, int dtmfType)
{
    if (mode < 0 || mode > LAST_MODE) {
        return false;
    }
    mMode = mode;

    mCodecMagic = (0x8000 | codecType) << 16;
    mDtmfMagic = (dtmfType == -1) ? 0 : (0x8000 | dtmfType) << 16;

    mTick = elapsedRealtime();
    mSampleRate = sampleRate / 1000;
    mSampleCount = sampleCount;
    mInterval = mSampleCount / mSampleRate;

    // Allocate jitter buffer.
    for (mBufferMask = 8; mBufferMask < mSampleRate; mBufferMask <<= 1);
    mBufferMask *= BUFFER_SIZE;
    mBuffer = new int16_t[mBufferMask];
    --mBufferMask;
    mBufferHead = 0;
    mBufferTail = 0;
    mLatencyTimer = 0;
    mLatencyScore = 0;

    // Initialize random bits.
    read(gRandom, &mSequence, sizeof(mSequence));
    read(gRandom, &mTimestamp, sizeof(mTimestamp));
    read(gRandom, &mSsrc, sizeof(mSsrc));

    mDtmfEvent = -1;
    mDtmfStart = 0;

    // Only take over these things when succeeded.
    mSocket = socket;
    if (codec) {
        mRemote = *remote;
        mCodec = codec;

        // Here we should never get an private address, but some buggy proxy
        // servers do give us one. To solve this, we replace the address when
        // the first time we successfully decode an incoming packet.
        mFixRemote = false;
        if (remote->ss_family == AF_INET) {
            unsigned char *address =
                (unsigned char *)&((sockaddr_in *)remote)->sin_addr;
            if (address[0] == 10 ||
                (address[0] == 172 && (address[1] >> 4) == 1) ||
                (address[0] == 192 && address[1] == 168)) {
                mFixRemote = true;
            }
        }
    }

    ALOGD("stream[%d] is configured as %s %dkHz %dms mode %d", mSocket,
        (codec ? codec->name : "RAW"), mSampleRate, mInterval, mMode);
    return true;
}

void AudioStream::sendDtmf(int event)
{
    if (mDtmfMagic != 0) {
        mDtmfEvent = event << 24;
        mDtmfStart = mTimestamp + mSampleCount;
    }
}

bool AudioStream::mix(int32_t *output, int head, int tail, int sampleRate)
{
    if (mMode == SEND_ONLY) {
        return false;
    }

    if (head - mBufferHead < 0) {
        head = mBufferHead;
    }
    if (tail - mBufferTail > 0) {
        tail = mBufferTail;
    }
    if (tail - head <= 0) {
        return false;
    }

    head *= mSampleRate;
    tail *= mSampleRate;

    if (sampleRate == mSampleRate) {
        for (int i = head; i - tail < 0; ++i) {
            output[i - head] += mBuffer[i & mBufferMask];
        }
    } else {
        // TODO: implement resampling.
        return false;
    }
    return true;
}

void AudioStream::encode(int tick, AudioStream *chain)
{
    if (tick - mTick >= mInterval) {
        // We just missed the train. Pretend that packets in between are lost.
        int skipped = (tick - mTick) / mInterval;
        mTick += skipped * mInterval;
        mSequence += skipped;
        mTimestamp += skipped * mSampleCount;
        ALOGV("stream[%d] skips %d packets", mSocket, skipped);
    }

    tick = mTick;
    mTick += mInterval;
    ++mSequence;
    mTimestamp += mSampleCount;

    // If there is an ongoing DTMF event, send it now.
    if (mMode != RECEIVE_ONLY && mDtmfEvent != -1) {
        int duration = mTimestamp - mDtmfStart;
        // Make sure duration is reasonable.
        if (duration >= 0 && duration < mSampleRate * DTMF_PERIOD) {
            duration += mSampleCount;
            int32_t buffer[4] = {
                htonl(mDtmfMagic | mSequence),
                htonl(mDtmfStart),
                mSsrc,
                htonl(mDtmfEvent | duration),
            };
            if (duration >= mSampleRate * DTMF_PERIOD) {
                buffer[3] |= htonl(1 << 23);
                mDtmfEvent = -1;
            }
            sendto(mSocket, buffer, sizeof(buffer), MSG_DONTWAIT,
                (sockaddr *)&mRemote, sizeof(mRemote));
            return;
        }
        mDtmfEvent = -1;
    }

    int32_t buffer[mSampleCount + 3];
    bool data = false;
    if (mMode != RECEIVE_ONLY) {
        // Mix all other streams.
        memset(buffer, 0, sizeof(buffer));
        while (chain) {
            if (chain != this) {
                data |= chain->mix(buffer, tick - mInterval, tick, mSampleRate);
            }
            chain = chain->mNext;
        }
    }

    int16_t samples[mSampleCount];
    if (data) {
        // Saturate into 16 bits.
        for (int i = 0; i < mSampleCount; ++i) {
            int32_t sample = buffer[i];
            if (sample < -32768) {
                sample = -32768;
            }
            if (sample > 32767) {
                sample = 32767;
            }
            samples[i] = sample;
        }
    } else {
        if ((mTick ^ mKeepAlive) >> 10 == 0) {
            return;
        }
        mKeepAlive = mTick;
        memset(samples, 0, sizeof(samples));

        if (mMode != RECEIVE_ONLY) {
            ALOGV("stream[%d] no data", mSocket);
        }
    }

    if (!mCodec) {
        // Special case for device stream.
        send(mSocket, samples, sizeof(samples), MSG_DONTWAIT);
        return;
    }

    // Cook the packet and send it out.
    buffer[0] = htonl(mCodecMagic | mSequence);
    buffer[1] = htonl(mTimestamp);
    buffer[2] = mSsrc;
    int length = mCodec->encode(&buffer[3], samples);
    if (length <= 0) {
        ALOGV("stream[%d] encoder error", mSocket);
        return;
    }
    sendto(mSocket, buffer, length + 12, MSG_DONTWAIT, (sockaddr *)&mRemote,
        sizeof(mRemote));
}

void AudioStream::decode(int tick)
{
    char c;
    if (mMode == SEND_ONLY) {
        recv(mSocket, &c, 1, MSG_DONTWAIT);
        return;
    }

    // Make sure mBufferHead and mBufferTail are reasonable.
    if ((unsigned int)(tick + BUFFER_SIZE - mBufferHead) > BUFFER_SIZE * 2) {
        mBufferHead = tick - HISTORY_SIZE;
        mBufferTail = mBufferHead;
    }

    if (tick - mBufferHead > HISTORY_SIZE) {
        // Throw away outdated samples.
        mBufferHead = tick - HISTORY_SIZE;
        if (mBufferTail - mBufferHead < 0) {
            mBufferTail = mBufferHead;
        }
    }

    // Adjust the jitter buffer if the latency keeps larger than the threshold
    // in the measurement period.
    int score = mBufferTail - tick - MEASURE_BASE;
    if (mLatencyScore > score || mLatencyScore <= 0) {
        mLatencyScore = score;
        mLatencyTimer = tick;
    } else if (tick - mLatencyTimer >= MEASURE_PERIOD) {
        ALOGV("stream[%d] reduces latency of %dms", mSocket, mLatencyScore);
        mBufferTail -= mLatencyScore;
        mLatencyScore = -1;
    }

    int count = (BUFFER_SIZE - (mBufferTail - mBufferHead)) * mSampleRate;
    if (count < mSampleCount) {
        // Buffer overflow. Drop the packet.
        ALOGV("stream[%d] buffer overflow", mSocket);
        recv(mSocket, &c, 1, MSG_DONTWAIT);
        return;
    }

    // Receive the packet and decode it.
    int16_t samples[count];
    if (!mCodec) {
        // Special case for device stream.
        count = recv(mSocket, samples, sizeof(samples),
            MSG_TRUNC | MSG_DONTWAIT) >> 1;
    } else {
        __attribute__((aligned(4))) uint8_t buffer[2048];
        sockaddr_storage remote;
        socklen_t addrlen = sizeof(remote);

        int length = recvfrom(mSocket, buffer, sizeof(buffer),
            MSG_TRUNC | MSG_DONTWAIT, (sockaddr *)&remote, &addrlen);

        // Do we need to check SSRC, sequence, and timestamp? They are not
        // reliable but at least they can be used to identify duplicates?
        if (length < 12 || length > (int)sizeof(buffer) ||
            (ntohl(*(uint32_t *)buffer) & 0xC07F0000) != mCodecMagic) {
            ALOGV("stream[%d] malformed packet", mSocket);
            return;
        }
        int offset = 12 + ((buffer[0] & 0x0F) << 2);
        if ((buffer[0] & 0x10) != 0) {
            offset += 4 + (ntohs(*(uint16_t *)&buffer[offset + 2]) << 2);
        }
        if ((buffer[0] & 0x20) != 0) {
            length -= buffer[length - 1];
        }
        length -= offset;
        if (length >= 0) {
            length = mCodec->decode(samples, count, &buffer[offset], length);
        }
        if (length > 0 && mFixRemote) {
            mRemote = remote;
            mFixRemote = false;
        }
        count = length;
    }
    if (count <= 0) {
        ALOGV("stream[%d] decoder error", mSocket);
        return;
    }

    if (tick - mBufferTail > 0) {
        // Buffer underrun. Reset the jitter buffer.
        ALOGV("stream[%d] buffer underrun", mSocket);
        if (mBufferTail - mBufferHead <= 0) {
            mBufferHead = tick + mInterval;
            mBufferTail = mBufferHead;
        } else {
            int tail = (tick + mInterval) * mSampleRate;
            for (int i = mBufferTail * mSampleRate; i - tail < 0; ++i) {
                mBuffer[i & mBufferMask] = 0;
            }
            mBufferTail = tick + mInterval;
        }
    }

    // Append to the jitter buffer.
    int tail = mBufferTail * mSampleRate;
    for (int i = 0; i < count; ++i) {
        mBuffer[tail & mBufferMask] = samples[i];
        ++tail;
    }
    mBufferTail += mInterval;
}

//------------------------------------------------------------------------------

class AudioGroup
{
public:
    AudioGroup();
    ~AudioGroup();
    bool set(int sampleRate, int sampleCount);

    bool setMode(int mode);
    bool sendDtmf(int event);
    bool add(AudioStream *stream);
    bool remove(AudioStream *stream);
    bool platformHasAec() { return mPlatformHasAec; }

private:
    enum {
        ON_HOLD = 0,
        MUTED = 1,
        NORMAL = 2,
        ECHO_SUPPRESSION = 3,
        LAST_MODE = 3,
    };

    bool checkPlatformAec();

    AudioStream *mChain;
    int mEventQueue;
    volatile int mDtmfEvent;

    int mMode;
    int mSampleRate;
    int mSampleCount;
    int mDeviceSocket;
    bool mPlatformHasAec;

    class NetworkThread : public Thread
    {
    public:
        NetworkThread(AudioGroup *group) : Thread(false), mGroup(group) {}

        bool start()
        {
            if (run("Network", ANDROID_PRIORITY_AUDIO) != NO_ERROR) {
                ALOGE("cannot start network thread");
                return false;
            }
            return true;
        }

    private:
        AudioGroup *mGroup;
        bool threadLoop();
    };
    sp<NetworkThread> mNetworkThread;

    class DeviceThread : public Thread
    {
    public:
        DeviceThread(AudioGroup *group) : Thread(false), mGroup(group) {}

        bool start()
        {
            if (run("Device", ANDROID_PRIORITY_AUDIO) != NO_ERROR) {
                ALOGE("cannot start device thread");
                return false;
            }
            return true;
        }

    private:
        AudioGroup *mGroup;
        bool threadLoop();
    };
    sp<DeviceThread> mDeviceThread;
};

AudioGroup::AudioGroup()
{
    mMode = ON_HOLD;
    mChain = NULL;
    mEventQueue = -1;
    mDtmfEvent = -1;
    mDeviceSocket = -1;
    mNetworkThread = new NetworkThread(this);
    mDeviceThread = new DeviceThread(this);
    mPlatformHasAec = checkPlatformAec();
}

AudioGroup::~AudioGroup()
{
    mNetworkThread->requestExitAndWait();
    mDeviceThread->requestExitAndWait();
    close(mEventQueue);
    close(mDeviceSocket);
    while (mChain) {
        AudioStream *next = mChain->mNext;
        delete mChain;
        mChain = next;
    }
    ALOGD("group[%d] is dead", mDeviceSocket);
}

bool AudioGroup::set(int sampleRate, int sampleCount)
{
    mEventQueue = epoll_create(2);
    if (mEventQueue == -1) {
        ALOGE("epoll_create: %s", strerror(errno));
        return false;
    }

    mSampleRate = sampleRate;
    mSampleCount = sampleCount;

    // Create device socket.
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_DGRAM, 0, pair)) {
        ALOGE("socketpair: %s", strerror(errno));
        return false;
    }
    mDeviceSocket = pair[0];

    // Create device stream.
    mChain = new AudioStream;
    if (!mChain->set(AudioStream::NORMAL, pair[1], NULL, NULL,
        sampleRate, sampleCount, -1, -1)) {
        close(pair[1]);
        ALOGE("cannot initialize device stream");
        return false;
    }

    // Give device socket a reasonable timeout.
    timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 1000 * sampleCount / sampleRate * 500;
    if (setsockopt(pair[0], SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv))) {
        ALOGE("setsockopt: %s", strerror(errno));
        return false;
    }

    // Add device stream into event queue.
    epoll_event event;
    event.events = EPOLLIN;
    event.data.ptr = mChain;
    if (epoll_ctl(mEventQueue, EPOLL_CTL_ADD, pair[1], &event)) {
        ALOGE("epoll_ctl: %s", strerror(errno));
        return false;
    }

    // Anything else?
    ALOGD("stream[%d] joins group[%d]", pair[1], pair[0]);
    return true;
}

bool AudioGroup::setMode(int mode)
{
    if (mode < 0 || mode > LAST_MODE) {
        return false;
    }
    // FIXME: temporary code to overcome echo and mic gain issues on herring and tuna boards.
    // Must be modified/removed when the root cause of the issue is fixed in the hardware or
    // driver
    char value[PROPERTY_VALUE_MAX];
    property_get("ro.product.board", value, "");
    if (mode == NORMAL &&
            (!strcmp(value, "herring") || !strcmp(value, "tuna"))) {
        mode = ECHO_SUPPRESSION;
    }
    if (mMode == mode) {
        return true;
    }

    mDeviceThread->requestExitAndWait();
    ALOGD("group[%d] switches from mode %d to %d", mDeviceSocket, mMode, mode);
    mMode = mode;
    return (mode == ON_HOLD) || mDeviceThread->start();
}

bool AudioGroup::sendDtmf(int event)
{
    if (event < 0 || event > 15) {
        return false;
    }

    // DTMF is rarely used, so we try to make it as lightweight as possible.
    // Using volatile might be dodgy, but using a pipe or pthread primitives
    // or stop-set-restart threads seems too heavy. Will investigate later.
    timespec ts;
    ts.tv_sec = 0;
    ts.tv_nsec = 100000000;
    for (int i = 0; mDtmfEvent != -1 && i < 20; ++i) {
        nanosleep(&ts, NULL);
    }
    if (mDtmfEvent != -1) {
        return false;
    }
    mDtmfEvent = event;
    nanosleep(&ts, NULL);
    return true;
}

bool AudioGroup::add(AudioStream *stream)
{
    mNetworkThread->requestExitAndWait();

    epoll_event event;
    event.events = EPOLLIN;
    event.data.ptr = stream;
    if (epoll_ctl(mEventQueue, EPOLL_CTL_ADD, stream->mSocket, &event)) {
        ALOGE("epoll_ctl: %s", strerror(errno));
        return false;
    }

    stream->mNext = mChain->mNext;
    mChain->mNext = stream;
    if (!mNetworkThread->start()) {
        // Only take over the stream when succeeded.
        mChain->mNext = stream->mNext;
        return false;
    }

    ALOGD("stream[%d] joins group[%d]", stream->mSocket, mDeviceSocket);
    return true;
}

bool AudioGroup::remove(AudioStream *stream)
{
    mNetworkThread->requestExitAndWait();

    for (AudioStream *chain = mChain; chain->mNext; chain = chain->mNext) {
        if (chain->mNext == stream) {
            if (epoll_ctl(mEventQueue, EPOLL_CTL_DEL, stream->mSocket, NULL)) {
                ALOGE("epoll_ctl: %s", strerror(errno));
                return false;
            }
            chain->mNext = stream->mNext;
            ALOGD("stream[%d] leaves group[%d]", stream->mSocket, mDeviceSocket);
            delete stream;
            break;
        }
    }

    // Do not start network thread if there is only one stream.
    if (!mChain->mNext || !mNetworkThread->start()) {
        return false;
    }
    return true;
}

bool AudioGroup::NetworkThread::threadLoop()
{
    AudioStream *chain = mGroup->mChain;
    int tick = elapsedRealtime();
    int deadline = tick + 10;
    int count = 0;

    for (AudioStream *stream = chain; stream; stream = stream->mNext) {
        if (tick - stream->mTick >= 0) {
            stream->encode(tick, chain);
        }
        if (deadline - stream->mTick > 0) {
            deadline = stream->mTick;
        }
        ++count;
    }

    int event = mGroup->mDtmfEvent;
    if (event != -1) {
        for (AudioStream *stream = chain; stream; stream = stream->mNext) {
            stream->sendDtmf(event);
        }
        mGroup->mDtmfEvent = -1;
    }

    deadline -= tick;
    if (deadline < 1) {
        deadline = 1;
    }

    epoll_event events[count];
    count = epoll_wait(mGroup->mEventQueue, events, count, deadline);
    if (count == -1) {
        ALOGE("epoll_wait: %s", strerror(errno));
        return false;
    }
    for (int i = 0; i < count; ++i) {
        ((AudioStream *)events[i].data.ptr)->decode(tick);
    }

    return true;
}

bool AudioGroup::checkPlatformAec()
{
    effect_descriptor_t fxDesc;
    uint32_t numFx;

    if (AudioEffect::queryNumberEffects(&numFx) != NO_ERROR) {
        return false;
    }
    for (uint32_t i = 0; i < numFx; i++) {
        if (AudioEffect::queryEffect(i, &fxDesc) != NO_ERROR) {
            continue;
        }
        if (memcmp(&fxDesc.type, FX_IID_AEC, sizeof(effect_uuid_t)) == 0) {
            return true;
        }
    }
    return false;
}

bool AudioGroup::DeviceThread::threadLoop()
{
    int mode = mGroup->mMode;
    int sampleRate = mGroup->mSampleRate;
    int sampleCount = mGroup->mSampleCount;
    int deviceSocket = mGroup->mDeviceSocket;

    // Find out the frame count for AudioTrack and AudioRecord.
    int output = 0;
    int input = 0;
    if (AudioTrack::getMinFrameCount(&output, AUDIO_STREAM_VOICE_CALL,
        sampleRate) != NO_ERROR || output <= 0 ||
        AudioRecord::getMinFrameCount(&input, sampleRate,
        AUDIO_FORMAT_PCM_16_BIT, AUDIO_CHANNEL_IN_MONO) != NO_ERROR || input <= 0) {
        ALOGE("cannot compute frame count");
        return false;
    }
    ALOGD("reported frame count: output %d, input %d", output, input);

    if (output < sampleCount * 2) {
        output = sampleCount * 2;
    }
    if (input < sampleCount * 2) {
        input = sampleCount * 2;
    }
    ALOGD("adjusted frame count: output %d, input %d", output, input);

    // Initialize AudioTrack and AudioRecord.
    AudioTrack track;
    AudioRecord record;
    if (track.set(AUDIO_STREAM_VOICE_CALL, sampleRate, AUDIO_FORMAT_PCM_16_BIT,
                AUDIO_CHANNEL_OUT_MONO, output) != NO_ERROR ||
            record.set(AUDIO_SOURCE_VOICE_COMMUNICATION, sampleRate, AUDIO_FORMAT_PCM_16_BIT,
                AUDIO_CHANNEL_IN_MONO, input) != NO_ERROR) {
        ALOGE("cannot initialize audio device");
        return false;
    }
    ALOGD("latency: output %d, input %d", track.latency(), record.latency());

    // Give device socket a reasonable buffer size.
    setsockopt(deviceSocket, SOL_SOCKET, SO_RCVBUF, &output, sizeof(output));
    setsockopt(deviceSocket, SOL_SOCKET, SO_SNDBUF, &output, sizeof(output));

    // Drain device socket.
    char c;
    while (recv(deviceSocket, &c, 1, MSG_DONTWAIT) == 1);

    // check if platform supports echo cancellation and do not active local echo suppression in
    // this case
    EchoSuppressor *echo = NULL;
    AudioEffect *aec = NULL;
    if (mode == ECHO_SUPPRESSION) {
        if (mGroup->platformHasAec()) {
            aec = new AudioEffect(FX_IID_AEC,
                                    NULL,
                                    0,
                                    0,
                                    0,
                                    record.getSessionId(),
                                    record.getInput());
            status_t status = aec->initCheck();
            if (status == NO_ERROR || status == ALREADY_EXISTS) {
                aec->setEnabled(true);
            } else {
                delete aec;
                aec = NULL;
            }
        }
        // Create local echo suppressor if platform AEC cannot be used.
        if (aec == NULL) {
             echo = new EchoSuppressor(sampleCount,
                                       (track.latency() + record.latency()) * sampleRate / 1000);
        }
    }
    // Start AudioRecord before AudioTrack. This prevents AudioTrack from being
    // disabled due to buffer underrun while waiting for AudioRecord.
    if (mode != MUTED) {
        record.start();
        int16_t one;
        record.read(&one, sizeof(one));
    }
    track.start();

    while (!exitPending()) {
        int16_t output[sampleCount];
        if (recv(deviceSocket, output, sizeof(output), 0) <= 0) {
            memset(output, 0, sizeof(output));
        }

        int16_t input[sampleCount];
        int toWrite = sampleCount;
        int toRead = (mode == MUTED) ? 0 : sampleCount;
        int chances = 100;

        while (--chances > 0 && (toWrite > 0 || toRead > 0)) {
            if (toWrite > 0) {
                AudioTrack::Buffer buffer;
                buffer.frameCount = toWrite;

                status_t status = track.obtainBuffer(&buffer, 1);
                if (status == NO_ERROR) {
                    int offset = sampleCount - toWrite;
                    memcpy(buffer.i8, &output[offset], buffer.size);
                    toWrite -= buffer.frameCount;
                    track.releaseBuffer(&buffer);
                } else if (status != TIMED_OUT && status != WOULD_BLOCK) {
                    ALOGE("cannot write to AudioTrack");
                    goto exit;
                }
            }

            if (toRead > 0) {
                AudioRecord::Buffer buffer;
                buffer.frameCount = toRead;

                status_t status = record.obtainBuffer(&buffer, 1);
                if (status == NO_ERROR) {
                    int offset = sampleCount - toRead;
                    memcpy(&input[offset], buffer.i8, buffer.size);
                    toRead -= buffer.frameCount;
                    record.releaseBuffer(&buffer);
                } else if (status != TIMED_OUT && status != WOULD_BLOCK) {
                    ALOGE("cannot read from AudioRecord");
                    goto exit;
                }
            }
        }

        if (chances <= 0) {
            ALOGW("device loop timeout");
            while (recv(deviceSocket, &c, 1, MSG_DONTWAIT) == 1);
        }

        if (mode != MUTED) {
            if (echo != NULL) {
                ALOGV("echo->run()");
                echo->run(output, input);
            }
            send(deviceSocket, input, sizeof(input), MSG_DONTWAIT);
        }
    }

exit:
    delete echo;
    delete aec;
    return true;
}

//------------------------------------------------------------------------------

static jfieldID gNative;
static jfieldID gMode;

int add(JNIEnv *env, jobject thiz, jint mode,
    jint socket, jstring jRemoteAddress, jint remotePort,
    jstring jCodecSpec, jint dtmfType)
{
    AudioCodec *codec = NULL;
    AudioStream *stream = NULL;
    AudioGroup *group = NULL;

    // Sanity check.
    sockaddr_storage remote;
    if (parse(env, jRemoteAddress, remotePort, &remote) < 0) {
        // Exception already thrown.
        return 0;
    }
    if (!jCodecSpec) {
        jniThrowNullPointerException(env, "codecSpec");
        return 0;
    }
    const char *codecSpec = env->GetStringUTFChars(jCodecSpec, NULL);
    if (!codecSpec) {
        // Exception already thrown.
        return 0;
    }
    socket = dup(socket);
    if (socket == -1) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "cannot get stream socket");
        return 0;
    }

    // Create audio codec.
    int codecType = -1;
    char codecName[16];
    int sampleRate = -1;
    sscanf(codecSpec, "%d %15[^/]%*c%d", &codecType, codecName, &sampleRate);
    codec = newAudioCodec(codecName);
    int sampleCount = (codec ? codec->set(sampleRate, codecSpec) : -1);
    env->ReleaseStringUTFChars(jCodecSpec, codecSpec);
    if (sampleCount <= 0) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "cannot initialize audio codec");
        goto error;
    }

    // Create audio stream.
    stream = new AudioStream;
    if (!stream->set(mode, socket, &remote, codec, sampleRate, sampleCount,
        codecType, dtmfType)) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "cannot initialize audio stream");
        goto error;
    }
    socket = -1;
    codec = NULL;

    // Create audio group.
    group = (AudioGroup *)env->GetIntField(thiz, gNative);
    if (!group) {
        int mode = env->GetIntField(thiz, gMode);
        group = new AudioGroup;
        if (!group->set(8000, 256) || !group->setMode(mode)) {
            jniThrowException(env, "java/lang/IllegalStateException",
                "cannot initialize audio group");
            goto error;
        }
    }

    // Add audio stream into audio group.
    if (!group->add(stream)) {
        jniThrowException(env, "java/lang/IllegalStateException",
            "cannot add audio stream");
        goto error;
    }

    // Succeed.
    env->SetIntField(thiz, gNative, (int)group);
    return (int)stream;

error:
    delete group;
    delete stream;
    delete codec;
    close(socket);
    env->SetIntField(thiz, gNative, 0);
    return 0;
}

void remove(JNIEnv *env, jobject thiz, jint stream)
{
    AudioGroup *group = (AudioGroup *)env->GetIntField(thiz, gNative);
    if (group) {
        if (!stream || !group->remove((AudioStream *)stream)) {
            delete group;
            env->SetIntField(thiz, gNative, 0);
        }
    }
}

void setMode(JNIEnv *env, jobject thiz, jint mode)
{
    AudioGroup *group = (AudioGroup *)env->GetIntField(thiz, gNative);
    if (group && !group->setMode(mode)) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
    }
}

void sendDtmf(JNIEnv *env, jobject thiz, jint event)
{
    AudioGroup *group = (AudioGroup *)env->GetIntField(thiz, gNative);
    if (group && !group->sendDtmf(event)) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
    }
}

JNINativeMethod gMethods[] = {
    {"nativeAdd", "(IILjava/lang/String;ILjava/lang/String;I)I", (void *)add},
    {"nativeRemove", "(I)V", (void *)remove},
    {"nativeSetMode", "(I)V", (void *)setMode},
    {"nativeSendDtmf", "(I)V", (void *)sendDtmf},
};

} // namespace

int registerAudioGroup(JNIEnv *env)
{
    gRandom = open("/dev/urandom", O_RDONLY);
    if (gRandom == -1) {
        ALOGE("urandom: %s", strerror(errno));
        return -1;
    }

    jclass clazz;
    if ((clazz = env->FindClass("android/net/rtp/AudioGroup")) == NULL ||
        (gNative = env->GetFieldID(clazz, "mNative", "I")) == NULL ||
        (gMode = env->GetFieldID(clazz, "mMode", "I")) == NULL ||
        env->RegisterNatives(clazz, gMethods, NELEM(gMethods)) < 0) {
        ALOGE("JNI registration failed");
        return -1;
    }
    return 0;
}
