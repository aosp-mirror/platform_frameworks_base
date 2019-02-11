/*
 * Copyright (C) 2007 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "SoundPool"

#include <chrono>
#include <inttypes.h>
#include <thread>
#include <utils/Log.h>

#define USE_SHARED_MEM_BUFFER

#include <media/AudioTrack.h>
#include "SoundPool.h"
#include "SoundPoolThread.h"
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>

namespace android
{

int kDefaultBufferCount = 4;
uint32_t kMaxSampleRate = 48000;
uint32_t kDefaultSampleRate = 44100;
uint32_t kDefaultFrameCount = 1200;
size_t kDefaultHeapSize = 1024 * 1024; // 1MB


SoundPool::SoundPool(int maxChannels, const audio_attributes_t* pAttributes)
{
    ALOGV("SoundPool constructor: maxChannels=%d, attr.usage=%d, attr.flags=0x%x, attr.tags=%s",
            maxChannels, pAttributes->usage, pAttributes->flags, pAttributes->tags);

    // check limits
    mMaxChannels = maxChannels;
    if (mMaxChannels < 1) {
        mMaxChannels = 1;
    }
    else if (mMaxChannels > 32) {
        mMaxChannels = 32;
    }
    ALOGW_IF(maxChannels != mMaxChannels, "App requested %d channels", maxChannels);

    mQuit = false;
    mMuted = false;
    mDecodeThread = 0;
    memcpy(&mAttributes, pAttributes, sizeof(audio_attributes_t));
    mAllocated = 0;
    mNextSampleID = 0;
    mNextChannelID = 0;

    mCallback = 0;
    mUserData = 0;

    mChannelPool = new SoundChannel[mMaxChannels];
    for (int i = 0; i < mMaxChannels; ++i) {
        mChannelPool[i].init(this);
        mChannels.push_back(&mChannelPool[i]);
    }

    // start decode thread
    startThreads();
}

SoundPool::~SoundPool()
{
    ALOGV("SoundPool destructor");
    mDecodeThread->quit();
    quit();

    Mutex::Autolock lock(&mLock);

    mChannels.clear();
    if (mChannelPool)
        delete [] mChannelPool;
    // clean up samples
    ALOGV("clear samples");
    mSamples.clear();

    if (mDecodeThread)
        delete mDecodeThread;
}

void SoundPool::addToRestartList(SoundChannel* channel)
{
    Mutex::Autolock lock(&mRestartLock);
    if (!mQuit) {
        mRestart.push_back(channel);
        mCondition.signal();
    }
}

void SoundPool::addToStopList(SoundChannel* channel)
{
    Mutex::Autolock lock(&mRestartLock);
    if (!mQuit) {
        mStop.push_back(channel);
        mCondition.signal();
    }
}

int SoundPool::beginThread(void* arg)
{
    SoundPool* p = (SoundPool*)arg;
    return p->run();
}

int SoundPool::run()
{
    mRestartLock.lock();
    while (!mQuit) {
        mCondition.wait(mRestartLock);
        ALOGV("awake");
        if (mQuit) break;

        while (!mStop.empty()) {
            SoundChannel* channel;
            ALOGV("Getting channel from stop list");
            List<SoundChannel* >::iterator iter = mStop.begin();
            channel = *iter;
            mStop.erase(iter);
            mRestartLock.unlock();
            if (channel != 0) {
                Mutex::Autolock lock(&mLock);
                channel->stop();
            }
            mRestartLock.lock();
            if (mQuit) break;
        }

        while (!mRestart.empty()) {
            SoundChannel* channel;
            ALOGV("Getting channel from list");
            List<SoundChannel*>::iterator iter = mRestart.begin();
            channel = *iter;
            mRestart.erase(iter);
            mRestartLock.unlock();
            if (channel != 0) {
                Mutex::Autolock lock(&mLock);
                channel->nextEvent();
            }
            mRestartLock.lock();
            if (mQuit) break;
        }
    }

    mStop.clear();
    mRestart.clear();
    mCondition.signal();
    mRestartLock.unlock();
    ALOGV("goodbye");
    return 0;
}

void SoundPool::quit()
{
    mRestartLock.lock();
    mQuit = true;
    mCondition.signal();
    mCondition.wait(mRestartLock);
    ALOGV("return from quit");
    mRestartLock.unlock();
}

bool SoundPool::startThreads()
{
    createThreadEtc(beginThread, this, "SoundPool");
    if (mDecodeThread == NULL)
        mDecodeThread = new SoundPoolThread(this);
    return mDecodeThread != NULL;
}

sp<Sample> SoundPool::findSample(int sampleID)
{
    Mutex::Autolock lock(&mLock);
    return findSample_l(sampleID);
}

sp<Sample> SoundPool::findSample_l(int sampleID)
{
    return mSamples.valueFor(sampleID);
}

SoundChannel* SoundPool::findChannel(int channelID)
{
    for (int i = 0; i < mMaxChannels; ++i) {
        if (mChannelPool[i].channelID() == channelID) {
            return &mChannelPool[i];
        }
    }
    return NULL;
}

SoundChannel* SoundPool::findNextChannel(int channelID)
{
    for (int i = 0; i < mMaxChannels; ++i) {
        if (mChannelPool[i].nextChannelID() == channelID) {
            return &mChannelPool[i];
        }
    }
    return NULL;
}

int SoundPool::load(int fd, int64_t offset, int64_t length, int priority __unused)
{
    ALOGV("load: fd=%d, offset=%" PRId64 ", length=%" PRId64 ", priority=%d",
            fd, offset, length, priority);
    int sampleID;
    {
        Mutex::Autolock lock(&mLock);
        sampleID = ++mNextSampleID;
        sp<Sample> sample = new Sample(sampleID, fd, offset, length);
        mSamples.add(sampleID, sample);
        sample->startLoad();
    }
    // mDecodeThread->loadSample() must be called outside of mLock.
    // mDecodeThread->loadSample() may block on mDecodeThread message queue space;
    // the message queue emptying may block on SoundPool::findSample().
    //
    // It theoretically possible that sample loads might decode out-of-order.
    mDecodeThread->loadSample(sampleID);
    return sampleID;
}

bool SoundPool::unload(int sampleID)
{
    ALOGV("unload: sampleID=%d", sampleID);
    Mutex::Autolock lock(&mLock);
    return mSamples.removeItem(sampleID) >= 0; // removeItem() returns index or BAD_VALUE
}

int SoundPool::play(int sampleID, float leftVolume, float rightVolume,
        int priority, int loop, float rate)
{
    ALOGV("play sampleID=%d, leftVolume=%f, rightVolume=%f, priority=%d, loop=%d, rate=%f",
            sampleID, leftVolume, rightVolume, priority, loop, rate);
    SoundChannel* channel;
    int channelID;

    Mutex::Autolock lock(&mLock);

    if (mQuit) {
        return 0;
    }
    // is sample ready?
    sp<Sample> sample(findSample_l(sampleID));
    if ((sample == 0) || (sample->state() != Sample::READY)) {
        ALOGW("  sample %d not READY", sampleID);
        return 0;
    }

    dump();

    // allocate a channel
    channel = allocateChannel_l(priority, sampleID);

    // no channel allocated - return 0
    if (!channel) {
        ALOGV("No channel allocated");
        return 0;
    }

    channelID = ++mNextChannelID;

    ALOGV("play channel %p state = %d", channel, channel->state());
    channel->play(sample, channelID, leftVolume, rightVolume, priority, loop, rate);
    return channelID;
}

SoundChannel* SoundPool::allocateChannel_l(int priority, int sampleID)
{
    List<SoundChannel*>::iterator iter;
    SoundChannel* channel = NULL;

    // check if channel for given sampleID still available
    if (!mChannels.empty()) {
        for (iter = mChannels.begin(); iter != mChannels.end(); ++iter) {
            if (sampleID == (*iter)->getPrevSampleID() && (*iter)->state() == SoundChannel::IDLE) {
                channel = *iter;
                mChannels.erase(iter);
                ALOGV("Allocated recycled channel for same sampleID");
                break;
            }
        }
    }

    // allocate any channel
    if (!channel && !mChannels.empty()) {
        iter = mChannels.begin();
        if (priority >= (*iter)->priority()) {
            channel = *iter;
            mChannels.erase(iter);
            ALOGV("Allocated active channel");
        }
    }

    // update priority and put it back in the list
    if (channel) {
        channel->setPriority(priority);
        for (iter = mChannels.begin(); iter != mChannels.end(); ++iter) {
            if (priority < (*iter)->priority()) {
                break;
            }
        }
        mChannels.insert(iter, channel);
    }
    return channel;
}

// move a channel from its current position to the front of the list
void SoundPool::moveToFront_l(SoundChannel* channel)
{
    for (List<SoundChannel*>::iterator iter = mChannels.begin(); iter != mChannels.end(); ++iter) {
        if (*iter == channel) {
            mChannels.erase(iter);
            mChannels.push_front(channel);
            break;
        }
    }
}

void SoundPool::pause(int channelID)
{
    ALOGV("pause(%d)", channelID);
    Mutex::Autolock lock(&mLock);
    SoundChannel* channel = findChannel(channelID);
    if (channel) {
        channel->pause();
    }
}

void SoundPool::autoPause()
{
    ALOGV("autoPause()");
    Mutex::Autolock lock(&mLock);
    for (int i = 0; i < mMaxChannels; ++i) {
        SoundChannel* channel = &mChannelPool[i];
        channel->autoPause();
    }
}

void SoundPool::resume(int channelID)
{
    ALOGV("resume(%d)", channelID);
    Mutex::Autolock lock(&mLock);
    SoundChannel* channel = findChannel(channelID);
    if (channel) {
        channel->resume();
    }
}

void SoundPool::mute(bool muting)
{
    ALOGV("mute(%d)", muting);
    Mutex::Autolock lock(&mLock);
    mMuted = muting;
    if (!mChannels.empty()) {
            for (List<SoundChannel*>::iterator iter = mChannels.begin();
                    iter != mChannels.end(); ++iter) {
                (*iter)->mute(muting);
            }
        }
}

void SoundPool::autoResume()
{
    ALOGV("autoResume()");
    Mutex::Autolock lock(&mLock);
    for (int i = 0; i < mMaxChannels; ++i) {
        SoundChannel* channel = &mChannelPool[i];
        channel->autoResume();
    }
}

void SoundPool::stop(int channelID)
{
    ALOGV("stop(%d)", channelID);
    Mutex::Autolock lock(&mLock);
    SoundChannel* channel = findChannel(channelID);
    if (channel) {
        channel->stop();
    } else {
        channel = findNextChannel(channelID);
        if (channel)
            channel->clearNextEvent();
    }
}

void SoundPool::setVolume(int channelID, float leftVolume, float rightVolume)
{
    Mutex::Autolock lock(&mLock);
    SoundChannel* channel = findChannel(channelID);
    if (channel) {
        channel->setVolume(leftVolume, rightVolume);
    }
}

void SoundPool::setPriority(int channelID, int priority)
{
    ALOGV("setPriority(%d, %d)", channelID, priority);
    Mutex::Autolock lock(&mLock);
    SoundChannel* channel = findChannel(channelID);
    if (channel) {
        channel->setPriority(priority);
    }
}

void SoundPool::setLoop(int channelID, int loop)
{
    ALOGV("setLoop(%d, %d)", channelID, loop);
    Mutex::Autolock lock(&mLock);
    SoundChannel* channel = findChannel(channelID);
    if (channel) {
        channel->setLoop(loop);
    }
}

void SoundPool::setRate(int channelID, float rate)
{
    ALOGV("setRate(%d, %f)", channelID, rate);
    Mutex::Autolock lock(&mLock);
    SoundChannel* channel = findChannel(channelID);
    if (channel) {
        channel->setRate(rate);
    }
}

// call with lock held
void SoundPool::done_l(SoundChannel* channel)
{
    ALOGV("done_l(%d)", channel->channelID());
    // if "stolen", play next event
    if (channel->nextChannelID() != 0) {
        ALOGV("add to restart list");
        addToRestartList(channel);
    }

    // return to idle state
    else {
        ALOGV("move to front");
        moveToFront_l(channel);
    }
}

void SoundPool::setCallback(SoundPoolCallback* callback, void* user)
{
    Mutex::Autolock lock(&mCallbackLock);
    mCallback = callback;
    mUserData = user;
}

void SoundPool::notify(SoundPoolEvent event)
{
    Mutex::Autolock lock(&mCallbackLock);
    if (mCallback != NULL) {
        mCallback(event, this, mUserData);
    }
}

void SoundPool::dump()
{
    for (int i = 0; i < mMaxChannels; ++i) {
        mChannelPool[i].dump();
    }
}


Sample::Sample(int sampleID, int fd, int64_t offset, int64_t length)
{
    init();
    mSampleID = sampleID;
    mFd = dup(fd);
    mOffset = offset;
    mLength = length;
    ALOGV("create sampleID=%d, fd=%d, offset=%" PRId64 " length=%" PRId64,
        mSampleID, mFd, mLength, mOffset);
}

void Sample::init()
{
    mSize = 0;
    mRefCount = 0;
    mSampleID = 0;
    mState = UNLOADED;
    mFd = -1;
    mOffset = 0;
    mLength = 0;
}

Sample::~Sample()
{
    ALOGV("Sample::destructor sampleID=%d, fd=%d", mSampleID, mFd);
    if (mFd > 0) {
        ALOGV("close(%d)", mFd);
        ::close(mFd);
    }
}

static status_t decode(int fd, int64_t offset, int64_t length,
        uint32_t *rate, int *numChannels, audio_format_t *audioFormat,
        audio_channel_mask_t *channelMask, sp<MemoryHeapBase> heap,
        size_t *memsize) {

    ALOGV("fd %d, offset %" PRId64 ", size %" PRId64, fd, offset, length);
    AMediaExtractor *ex = AMediaExtractor_new();
    status_t err = AMediaExtractor_setDataSourceFd(ex, fd, offset, length);

    if (err != AMEDIA_OK) {
        AMediaExtractor_delete(ex);
        return err;
    }

    *audioFormat = AUDIO_FORMAT_PCM_16_BIT;

    size_t numTracks = AMediaExtractor_getTrackCount(ex);
    for (size_t i = 0; i < numTracks; i++) {
        AMediaFormat *format = AMediaExtractor_getTrackFormat(ex, i);
        const char *mime;
        if (!AMediaFormat_getString(format, AMEDIAFORMAT_KEY_MIME, &mime)) {
            AMediaExtractor_delete(ex);
            AMediaFormat_delete(format);
            return UNKNOWN_ERROR;
        }
        if (strncmp(mime, "audio/", 6) == 0) {

            AMediaCodec *codec = AMediaCodec_createDecoderByType(mime);
            if (codec == NULL
                    || AMediaCodec_configure(codec, format,
                            NULL /* window */, NULL /* drm */, 0 /* flags */) != AMEDIA_OK
                    || AMediaCodec_start(codec) != AMEDIA_OK
                    || AMediaExtractor_selectTrack(ex, i) != AMEDIA_OK) {
                AMediaExtractor_delete(ex);
                AMediaCodec_delete(codec);
                AMediaFormat_delete(format);
                return UNKNOWN_ERROR;
            }

            bool sawInputEOS = false;
            bool sawOutputEOS = false;
            uint8_t* writePos = static_cast<uint8_t*>(heap->getBase());
            size_t available = heap->getSize();
            size_t written = 0;

            AMediaFormat_delete(format);
            format = AMediaCodec_getOutputFormat(codec);

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    ssize_t bufidx = AMediaCodec_dequeueInputBuffer(codec, 5000);
                    ALOGV("input buffer %zd", bufidx);
                    if (bufidx >= 0) {
                        size_t bufsize;
                        uint8_t *buf = AMediaCodec_getInputBuffer(codec, bufidx, &bufsize);
                        if (buf == nullptr) {
                            ALOGE("AMediaCodec_getInputBuffer returned nullptr, short decode");
                            break;
                        }
                        int sampleSize = AMediaExtractor_readSampleData(ex, buf, bufsize);
                        ALOGV("read %d", sampleSize);
                        if (sampleSize < 0) {
                            sampleSize = 0;
                            sawInputEOS = true;
                            ALOGV("EOS");
                        }
                        int64_t presentationTimeUs = AMediaExtractor_getSampleTime(ex);

                        media_status_t mstatus = AMediaCodec_queueInputBuffer(codec, bufidx,
                                0 /* offset */, sampleSize, presentationTimeUs,
                                sawInputEOS ? AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM : 0);
                        if (mstatus != AMEDIA_OK) {
                            // AMEDIA_ERROR_UNKNOWN == { -ERANGE -EINVAL -EACCES }
                            ALOGE("AMediaCodec_queueInputBuffer returned status %d, short decode",
                                    (int)mstatus);
                            break;
                        }
                        (void)AMediaExtractor_advance(ex);
                    }
                }

                AMediaCodecBufferInfo info;
                int status = AMediaCodec_dequeueOutputBuffer(codec, &info, 1);
                ALOGV("dequeueoutput returned: %d", status);
                if (status >= 0) {
                    if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                        ALOGV("output EOS");
                        sawOutputEOS = true;
                    }
                    ALOGV("got decoded buffer size %d", info.size);

                    uint8_t *buf = AMediaCodec_getOutputBuffer(codec, status, NULL /* out_size */);
                    if (buf == nullptr) {
                        ALOGE("AMediaCodec_getOutputBuffer returned nullptr, short decode");
                        break;
                    }
                    size_t dataSize = info.size;
                    if (dataSize > available) {
                        dataSize = available;
                    }
                    memcpy(writePos, buf + info.offset, dataSize);
                    writePos += dataSize;
                    written += dataSize;
                    available -= dataSize;
                    media_status_t mstatus = AMediaCodec_releaseOutputBuffer(
                            codec, status, false /* render */);
                    if (mstatus != AMEDIA_OK) {
                        // AMEDIA_ERROR_UNKNOWN == { -ERANGE -EINVAL -EACCES }
                        ALOGE("AMediaCodec_releaseOutputBuffer returned status %d, short decode",
                                (int)mstatus);
                        break;
                    }
                    if (available == 0) {
                        // there might be more data, but there's no space for it
                        sawOutputEOS = true;
                    }
                } else if (status == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
                    ALOGV("output buffers changed");
                } else if (status == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                    AMediaFormat_delete(format);
                    format = AMediaCodec_getOutputFormat(codec);
                    ALOGV("format changed to: %s", AMediaFormat_toString(format));
                } else if (status == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                    ALOGV("no output buffer right now");
                } else if (status <= AMEDIA_ERROR_BASE) {
                    ALOGE("decode error: %d", status);
                    break;
                } else {
                    ALOGV("unexpected info code: %d", status);
                }
            }

            (void)AMediaCodec_stop(codec);
            (void)AMediaCodec_delete(codec);
            (void)AMediaExtractor_delete(ex);
            if (!AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, (int32_t*) rate) ||
                    !AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, numChannels)) {
                (void)AMediaFormat_delete(format);
                return UNKNOWN_ERROR;
            }
            if (!AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_CHANNEL_MASK,
                    (int32_t*) channelMask)) {
                *channelMask = AUDIO_CHANNEL_NONE;
            }
            (void)AMediaFormat_delete(format);
            *memsize = written;
            return OK;
        }
        (void)AMediaFormat_delete(format);
    }
    (void)AMediaExtractor_delete(ex);
    return UNKNOWN_ERROR;
}

status_t Sample::doLoad()
{
    uint32_t sampleRate;
    int numChannels;
    audio_format_t format;
    audio_channel_mask_t channelMask;
    status_t status;
    mHeap = new MemoryHeapBase(kDefaultHeapSize);

    ALOGV("Start decode");
    status = decode(mFd, mOffset, mLength, &sampleRate, &numChannels, &format,
                    &channelMask, mHeap, &mSize);
    ALOGV("close(%d)", mFd);
    ::close(mFd);
    mFd = -1;
    if (status != NO_ERROR) {
        ALOGE("Unable to load sample");
        goto error;
    }
    ALOGV("pointer = %p, size = %zu, sampleRate = %u, numChannels = %d",
          mHeap->getBase(), mSize, sampleRate, numChannels);

    if (sampleRate > kMaxSampleRate) {
       ALOGE("Sample rate (%u) out of range", sampleRate);
       status = BAD_VALUE;
       goto error;
    }

    if ((numChannels < 1) || (numChannels > FCC_8)) {
        ALOGE("Sample channel count (%d) out of range", numChannels);
        status = BAD_VALUE;
        goto error;
    }

    mData = new MemoryBase(mHeap, 0, mSize);
    mSampleRate = sampleRate;
    mNumChannels = numChannels;
    mFormat = format;
    mChannelMask = channelMask;
    mState = READY;
    return NO_ERROR;

error:
    mHeap.clear();
    return status;
}


void SoundChannel::init(SoundPool* soundPool)
{
    mSoundPool = soundPool;
    mPrevSampleID = -1;
}

// call with sound pool lock held
void SoundChannel::play(const sp<Sample>& sample, int nextChannelID, float leftVolume,
        float rightVolume, int priority, int loop, float rate)
{
    sp<AudioTrack> oldTrack;
    sp<AudioTrack> newTrack;
    status_t status = NO_ERROR;

    { // scope for the lock
        Mutex::Autolock lock(&mLock);

        ALOGV("SoundChannel::play %p: sampleID=%d, channelID=%d, leftVolume=%f, rightVolume=%f,"
                " priority=%d, loop=%d, rate=%f",
                this, sample->sampleID(), nextChannelID, leftVolume, rightVolume,
                priority, loop, rate);

        // if not idle, this voice is being stolen
        if (mState != IDLE) {
            ALOGV("channel %d stolen - event queued for channel %d", channelID(), nextChannelID);
            mNextEvent.set(sample, nextChannelID, leftVolume, rightVolume, priority, loop, rate);
            stop_l();
            return;
        }

        // initialize track
        size_t afFrameCount;
        uint32_t afSampleRate;
        audio_stream_type_t streamType =
                AudioSystem::attributesToStreamType(*mSoundPool->attributes());
        if (AudioSystem::getOutputFrameCount(&afFrameCount, streamType) != NO_ERROR) {
            afFrameCount = kDefaultFrameCount;
        }
        if (AudioSystem::getOutputSamplingRate(&afSampleRate, streamType) != NO_ERROR) {
            afSampleRate = kDefaultSampleRate;
        }
        int numChannels = sample->numChannels();
        uint32_t sampleRate = uint32_t(float(sample->sampleRate()) * rate + 0.5);
        size_t frameCount = 0;

        if (loop) {
            const audio_format_t format = sample->format();
            const size_t frameSize = audio_is_linear_pcm(format)
                    ? numChannels * audio_bytes_per_sample(format) : 1;
            frameCount = sample->size() / frameSize;
        }

#ifndef USE_SHARED_MEM_BUFFER
        uint32_t totalFrames = (kDefaultBufferCount * afFrameCount * sampleRate) / afSampleRate;
        // Ensure minimum audio buffer size in case of short looped sample
        if(frameCount < totalFrames) {
            frameCount = totalFrames;
        }
#endif

        // check if the existing track has the same sample id.
        if (mAudioTrack != 0 && mPrevSampleID == sample->sampleID()) {
            // the sample rate may fail to change if the audio track is a fast track.
            if (mAudioTrack->setSampleRate(sampleRate) == NO_ERROR) {
                newTrack = mAudioTrack;
                ALOGV("reusing track %p for sample %d", mAudioTrack.get(), sample->sampleID());
            }
        }
        if (newTrack == 0) {
            // mToggle toggles each time a track is started on a given channel.
            // The toggle is concatenated with the SoundChannel address and passed to AudioTrack
            // as callback user data. This enables the detection of callbacks received from the old
            // audio track while the new one is being started and avoids processing them with
            // wrong audio audio buffer size  (mAudioBufferSize)
            unsigned long toggle = mToggle ^ 1;
            void *userData = (void *)((unsigned long)this | toggle);
            audio_channel_mask_t sampleChannelMask = sample->channelMask();
            // When sample contains a not none channel mask, use it as is.
            // Otherwise, use channel count to calculate channel mask.
            audio_channel_mask_t channelMask = sampleChannelMask != AUDIO_CHANNEL_NONE
                    ? sampleChannelMask : audio_channel_out_mask_from_count(numChannels);

            // do not create a new audio track if current track is compatible with sample parameters
    #ifdef USE_SHARED_MEM_BUFFER
            newTrack = new AudioTrack(streamType, sampleRate, sample->format(),
                    channelMask, sample->getIMemory(), AUDIO_OUTPUT_FLAG_FAST, callback, userData,
                    0 /*default notification frames*/, AUDIO_SESSION_ALLOCATE,
                    AudioTrack::TRANSFER_DEFAULT,
                    NULL /*offloadInfo*/, -1 /*uid*/, -1 /*pid*/, mSoundPool->attributes());
    #else
            uint32_t bufferFrames = (totalFrames + (kDefaultBufferCount - 1)) / kDefaultBufferCount;
            newTrack = new AudioTrack(streamType, sampleRate, sample->format(),
                    channelMask, frameCount, AUDIO_OUTPUT_FLAG_FAST, callback, userData,
                    bufferFrames, AUDIO_SESSION_ALLOCATE, AudioTrack::TRANSFER_DEFAULT,
                    NULL /*offloadInfo*/, -1 /*uid*/, -1 /*pid*/, mSoundPool->attributes());
    #endif
            oldTrack = mAudioTrack;
            status = newTrack->initCheck();
            if (status != NO_ERROR) {
                ALOGE("Error creating AudioTrack");
                // newTrack goes out of scope, so reference count drops to zero
                goto exit;
            }
            // From now on, AudioTrack callbacks received with previous toggle value will be ignored.
            mToggle = toggle;
            mAudioTrack = newTrack;
            ALOGV("using new track %p for sample %d", newTrack.get(), sample->sampleID());
        }
        if (mMuted) {
            newTrack->setVolume(0.0f, 0.0f);
        } else {
            newTrack->setVolume(leftVolume, rightVolume);
        }
        newTrack->setLoop(0, frameCount, loop);
        mPos = 0;
        mSample = sample;
        mChannelID = nextChannelID;
        mPriority = priority;
        mLoop = loop;
        mLeftVolume = leftVolume;
        mRightVolume = rightVolume;
        mNumChannels = numChannels;
        mRate = rate;
        clearNextEvent();
        mState = PLAYING;
        mAudioTrack->start();
        mAudioBufferSize = newTrack->frameCount()*newTrack->frameSize();
    }

exit:
    ALOGV("delete oldTrack %p", oldTrack.get());
    if (status != NO_ERROR) {
        mAudioTrack.clear();
    }
}

void SoundChannel::nextEvent()
{
    sp<Sample> sample;
    int nextChannelID;
    float leftVolume;
    float rightVolume;
    int priority;
    int loop;
    float rate;

    // check for valid event
    {
        Mutex::Autolock lock(&mLock);
        nextChannelID = mNextEvent.channelID();
        if (nextChannelID  == 0) {
            ALOGV("stolen channel has no event");
            return;
        }

        sample = mNextEvent.sample();
        leftVolume = mNextEvent.leftVolume();
        rightVolume = mNextEvent.rightVolume();
        priority = mNextEvent.priority();
        loop = mNextEvent.loop();
        rate = mNextEvent.rate();
    }

    ALOGV("Starting stolen channel %d -> %d", channelID(), nextChannelID);
    play(sample, nextChannelID, leftVolume, rightVolume, priority, loop, rate);
}

void SoundChannel::callback(int event, void* user, void *info)
{
    SoundChannel* channel = static_cast<SoundChannel*>((void *)((unsigned long)user & ~1));

    channel->process(event, info, (unsigned long)user & 1);
}

void SoundChannel::process(int event, void *info, unsigned long toggle)
{
    //ALOGV("process(%d)", mChannelID);

    Mutex::Autolock lock(&mLock);

    AudioTrack::Buffer* b = NULL;
    if (event == AudioTrack::EVENT_MORE_DATA) {
       b = static_cast<AudioTrack::Buffer *>(info);
    }

    if (mToggle != toggle) {
        ALOGV("process wrong toggle %p channel %d", this, mChannelID);
        if (b != NULL) {
            b->size = 0;
        }
        return;
    }

    sp<Sample> sample = mSample;

//    ALOGV("SoundChannel::process event %d", event);

    if (event == AudioTrack::EVENT_MORE_DATA) {

        // check for stop state
        if (b->size == 0) return;

        if (mState == IDLE) {
            b->size = 0;
            return;
        }

        if (sample != 0) {
            // fill buffer
            uint8_t* q = (uint8_t*) b->i8;
            size_t count = 0;

            if (mPos < (int)sample->size()) {
                uint8_t* p = sample->data() + mPos;
                count = sample->size() - mPos;
                if (count > b->size) {
                    count = b->size;
                }
                memcpy(q, p, count);
//              ALOGV("fill: q=%p, p=%p, mPos=%u, b->size=%u, count=%d", q, p, mPos, b->size,
//                      count);
            } else if (mPos < mAudioBufferSize) {
                count = mAudioBufferSize - mPos;
                if (count > b->size) {
                    count = b->size;
                }
                memset(q, 0, count);
//              ALOGV("fill extra: q=%p, mPos=%u, b->size=%u, count=%d", q, mPos, b->size, count);
            }

            mPos += count;
            b->size = count;
            //ALOGV("buffer=%p, [0]=%d", b->i16, b->i16[0]);
        }
    } else if (event == AudioTrack::EVENT_UNDERRUN || event == AudioTrack::EVENT_BUFFER_END) {
        ALOGV("process %p channel %d event %s",
              this, mChannelID, (event == AudioTrack::EVENT_UNDERRUN) ? "UNDERRUN" :
                      "BUFFER_END");
        mSoundPool->addToStopList(this);
    } else if (event == AudioTrack::EVENT_LOOP_END) {
        ALOGV("End loop %p channel %d", this, mChannelID);
    } else if (event == AudioTrack::EVENT_NEW_IAUDIOTRACK) {
        ALOGV("process %p channel %d NEW_IAUDIOTRACK", this, mChannelID);
    } else {
        ALOGW("SoundChannel::process unexpected event %d", event);
    }
}


// call with lock held
bool SoundChannel::doStop_l()
{
    if (mState != IDLE) {
        setVolume_l(0, 0);
        ALOGV("stop");
        // Since we're forcibly halting the previously playing content,
        // we sleep here to ensure the volume is ramped down before we stop the track.
        // Ideally the sleep time is the mixer period, or an approximation thereof
        // (Fast vs Normal tracks are different).
        // TODO: consider pausing instead of stop here.
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        mAudioTrack->stop();
        mPrevSampleID = mSample->sampleID();
        mSample.clear();
        mState = IDLE;
        mPriority = IDLE_PRIORITY;
        return true;
    }
    return false;
}

// call with lock held and sound pool lock held
void SoundChannel::stop_l()
{
    if (doStop_l()) {
        mSoundPool->done_l(this);
    }
}

// call with sound pool lock held
void SoundChannel::stop()
{
    bool stopped;
    {
        Mutex::Autolock lock(&mLock);
        stopped = doStop_l();
    }

    if (stopped) {
        mSoundPool->done_l(this);
    }
}

//FIXME: Pause is a little broken right now
void SoundChannel::pause()
{
    Mutex::Autolock lock(&mLock);
    if (mState == PLAYING) {
        ALOGV("pause track");
        mState = PAUSED;
        mAudioTrack->pause();
    }
}

void SoundChannel::autoPause()
{
    Mutex::Autolock lock(&mLock);
    if (mState == PLAYING) {
        ALOGV("pause track");
        mState = PAUSED;
        mAutoPaused = true;
        mAudioTrack->pause();
    }
}

void SoundChannel::resume()
{
    Mutex::Autolock lock(&mLock);
    if (mState == PAUSED) {
        ALOGV("resume track");
        mState = PLAYING;
        mAutoPaused = false;
        mAudioTrack->start();
    }
}

void SoundChannel::autoResume()
{
    Mutex::Autolock lock(&mLock);
    if (mAutoPaused && (mState == PAUSED)) {
        ALOGV("resume track");
        mState = PLAYING;
        mAutoPaused = false;
        mAudioTrack->start();
    }
}

void SoundChannel::setRate(float rate)
{
    Mutex::Autolock lock(&mLock);
    if (mAudioTrack != NULL && mSample != 0) {
        uint32_t sampleRate = uint32_t(float(mSample->sampleRate()) * rate + 0.5);
        mAudioTrack->setSampleRate(sampleRate);
        mRate = rate;
    }
}

// call with lock held
void SoundChannel::setVolume_l(float leftVolume, float rightVolume)
{
    mLeftVolume = leftVolume;
    mRightVolume = rightVolume;
    if (mAudioTrack != NULL && !mMuted)
        mAudioTrack->setVolume(leftVolume, rightVolume);
}

void SoundChannel::setVolume(float leftVolume, float rightVolume)
{
    Mutex::Autolock lock(&mLock);
    setVolume_l(leftVolume, rightVolume);
}

void SoundChannel::mute(bool muting)
{
    Mutex::Autolock lock(&mLock);
    mMuted = muting;
    if (mAudioTrack != NULL) {
        if (mMuted) {
            mAudioTrack->setVolume(0.0f, 0.0f);
        } else {
            mAudioTrack->setVolume(mLeftVolume, mRightVolume);
        }
    }
}

void SoundChannel::setLoop(int loop)
{
    Mutex::Autolock lock(&mLock);
    if (mAudioTrack != NULL && mSample != 0) {
        uint32_t loopEnd = mSample->size()/mNumChannels/
            ((mSample->format() == AUDIO_FORMAT_PCM_16_BIT) ? sizeof(int16_t) : sizeof(uint8_t));
        mAudioTrack->setLoop(0, loopEnd, loop);
        mLoop = loop;
    }
}

SoundChannel::~SoundChannel()
{
    ALOGV("SoundChannel destructor %p", this);
    {
        Mutex::Autolock lock(&mLock);
        clearNextEvent();
        doStop_l();
    }
    // do not call AudioTrack destructor with mLock held as it will wait for the AudioTrack
    // callback thread to exit which may need to execute process() and acquire the mLock.
    mAudioTrack.clear();
}

void SoundChannel::dump()
{
    ALOGV("mState = %d mChannelID=%d, mNumChannels=%d, mPos = %d, mPriority=%d, mLoop=%d",
            mState, mChannelID, mNumChannels, mPos, mPriority, mLoop);
}

void SoundEvent::set(const sp<Sample>& sample, int channelID, float leftVolume,
            float rightVolume, int priority, int loop, float rate)
{
    mSample = sample;
    mChannelID = channelID;
    mLeftVolume = leftVolume;
    mRightVolume = rightVolume;
    mPriority = priority;
    mLoop = loop;
    mRate =rate;
}

} // end namespace android
