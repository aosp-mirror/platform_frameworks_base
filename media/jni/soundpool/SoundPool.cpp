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
#define LOG_TAG "SoundPoolRestartThread"
#include <utils/Log.h>

// XXX needed for timing latency
#include <utils/Timers.h>

#include <sys/resource.h>
#include <media/AudioTrack.h>
#include <media/mediaplayer.h>

#include "SoundPool.h"
#include "SoundPoolThread.h"

namespace android
{

int kDefaultBufferCount = 4;
uint32_t kMaxSampleRate = 44100;

// handles restarting channels that have been stolen
class SoundPoolRestartThread
{
public:
    SoundPoolRestartThread() : mQuit(false) { createThread(beginThread, this); }
    void addToRestartList(SoundChannel* channel);
    void quit();

private:
    static int beginThread(void* arg);
    int run();

    Mutex                   mLock;
    Condition               mCondition;
    List<SoundChannel*>     mRestart;
    bool                    mQuit;
};

void SoundPoolRestartThread::addToRestartList(SoundChannel* channel)
{
    Mutex::Autolock lock(&mLock);
    mRestart.push_back(channel);
    mCondition.signal();
}

int SoundPoolRestartThread::beginThread(void* arg)
{
    SoundPoolRestartThread* thread = (SoundPoolRestartThread*)arg;
    return thread->run();
}

int SoundPoolRestartThread::run()
{
    mLock.lock();
    while (!mQuit) {
        mCondition.wait(mLock);
        LOGV("awake");
        if (mQuit) break;

        while (!mRestart.empty()) {
            SoundChannel* channel;
            LOGV("Getting channel from list");
            List<SoundChannel*>::iterator iter = mRestart.begin();
            channel = *iter;
            mRestart.erase(iter);
            if (channel) {
                SoundEvent* next = channel->nextEvent();
                if (next) {
                    LOGV("Starting stolen channel %d -> %d", channel->channelID(), next->mChannelID);
                    channel->play(next->mSample, next->mChannelID, next->mLeftVolume,
                            next->mRightVolume, next->mPriority, next->mLoop,
                            next->mRate);
                }
                else {
                    LOGW("stolen channel has no event");
                }
            }
            else {
                LOGW("no stolen channel to process");
            }
            if (mQuit) break;
        }
    }

    mRestart.clear();
    mCondition.signal();
    mLock.unlock();
    LOGV("goodbye");
    return 0;
}

void SoundPoolRestartThread::quit()
{
    mLock.lock();
    mQuit = true;
    mCondition.signal();
    mCondition.wait(mLock);
    LOGV("return from quit");
}

#undef LOG_TAG
#define LOG_TAG "SoundPool"

SoundPool::SoundPool(jobject soundPoolRef, int maxChannels, int streamType, int srcQuality) :
        mSoundPoolRef(soundPoolRef), mRestartThread(NULL), mDecodeThread(NULL),
        mChannelPool(NULL), mMaxChannels(maxChannels), mStreamType(streamType),
        mSrcQuality(srcQuality), mAllocated(0), mNextSampleID(0), mNextChannelID(0)
{
    LOGV("SoundPool constructor: maxChannels=%d, streamType=%d, srcQuality=%d",
            maxChannels, streamType, srcQuality);

    mChannelPool = new SoundChannel[maxChannels];
    for (int i = 0; i < maxChannels; ++i) {
        mChannelPool[i].init(this);
        mChannels.push_back(&mChannelPool[i]);
    }

    // start decode thread
    startThreads();
}

SoundPool::~SoundPool()
{
    LOGV("SoundPool destructor");
    mDecodeThread->quit();
    mRestartThread->quit();

    Mutex::Autolock lock(&mLock);
    mChannels.clear();
    if (mChannelPool)
        delete [] mChannelPool;

    // clean up samples
    LOGV("clear samples");
    mSamples.clear();

    if (mDecodeThread)
        delete mDecodeThread;
    if (mRestartThread)
        delete mRestartThread;
}

bool SoundPool::startThreads()
{
    if (mDecodeThread == NULL)
        mDecodeThread = new SoundPoolThread(this);
    if (mRestartThread == NULL)
        mRestartThread = new SoundPoolRestartThread();
    return (mDecodeThread && mRestartThread);
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
        if (mChannelPool[i].nextEvent()->mChannelID == channelID) {
            return &mChannelPool[i];
        }
    }
    return NULL;
}

int SoundPool::load(const char* path, int priority)
{
    LOGV("load: path=%s, priority=%d", path, priority);
    Mutex::Autolock lock(&mLock);
    sp<Sample> sample = new Sample(++mNextSampleID, path);
    mSamples.add(sample->sampleID(), sample);
    doLoad(sample);
    return sample->sampleID();
}

int SoundPool::load(int fd, int64_t offset, int64_t length, int priority)
{
    LOGV("load: fd=%d, offset=%lld, length=%lld, priority=%d",
            fd, offset, length, priority);
    Mutex::Autolock lock(&mLock);
    sp<Sample> sample = new Sample(++mNextSampleID, fd, offset, length);
    mSamples.add(sample->sampleID(), sample);
    doLoad(sample);
    return sample->sampleID();
}

void SoundPool::doLoad(sp<Sample>& sample)
{
    LOGV("doLoad: loading sample sampleID=%d", sample->sampleID());
    sample->startLoad();
    mDecodeThread->loadSample(sample->sampleID());
}

bool SoundPool::unload(int sampleID)
{
    LOGV("unload: sampleID=%d", sampleID);
    Mutex::Autolock lock(&mLock);
    return mSamples.removeItem(sampleID);
}

int SoundPool::play(int sampleID, float leftVolume, float rightVolume,
        int priority, int loop, float rate)
{
    LOGV("sampleID=%d, leftVolume=%f, rightVolume=%f, priority=%d, loop=%d, rate=%f",
            sampleID, leftVolume, rightVolume, priority, loop, rate);
    Mutex::Autolock lock(&mLock);

    // is sample ready?
    sp<Sample> sample = findSample(sampleID);
    if ((sample == 0) || (sample->state() != Sample::READY)) {
        LOGW("  sample %d not READY", sampleID);
        return 0;
    }

    dump();

    // allocate a channel
    SoundChannel* channel = allocateChannel(priority);

    // no channel allocated - return 0
    if (!channel) {
        LOGV("No channel allocated");
        return 0;
    }

    int channelID = ++mNextChannelID;
    LOGV("channel state = %d", channel->state());

    // idle: start playback
    if (channel->state() == SoundChannel::IDLE) {
        LOGV("idle channel - starting playback");
        channel->play(sample, channelID, leftVolume, rightVolume, priority,
                loop, rate);
    }

    // stolen: stop, save event data, and let service thread restart it
    else {
        LOGV("channel %d stolen - event queued for channel %d", channel->channelID(), channelID);
        channel->stop();
        channel->setNextEvent(new SoundEvent(sample, channelID, leftVolume,
                rightVolume, priority, loop, rate));
    }

    return channelID;
}

SoundChannel* SoundPool::allocateChannel(int priority)
{
    List<SoundChannel*>::iterator iter;
    SoundChannel* channel = NULL;

    // allocate a channel
    if (!mChannels.empty()) {
        iter = mChannels.begin();
        if (priority >= (*iter)->priority()) {
            channel = *iter;
            mChannels.erase(iter);
            LOGV("Allocated active channel");
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
void SoundPool::moveToFront(List<SoundChannel*>& list, SoundChannel* channel)
{
    for (List<SoundChannel*>::iterator iter = list.begin(); iter != list.end(); ++iter) {
        if (*iter == channel) {
            list.erase(iter);
            list.push_front(channel);
            break;
        }
    }
}

void SoundPool::pause(int channelID)
{
    LOGV("pause(%d)", channelID);
    Mutex::Autolock lock(&mLock);
    SoundChannel* channel = findChannel(channelID);
    if (channel) {
        channel->pause();
    }
}

void SoundPool::resume(int channelID)
{
    LOGV("resume(%d)", channelID);
    Mutex::Autolock lock(&mLock);
    SoundChannel* channel = findChannel(channelID);
    if (channel) {
        channel->resume();
    }
}

void SoundPool::stop(int channelID)
{
    LOGV("stop(%d)", channelID);
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
    LOGV("setVolume(%d, %f, %f)", channelID, leftVolume, rightVolume);
    Mutex::Autolock lock(&mLock);
    SoundChannel* channel = findChannel(channelID);
    if (channel) {
        channel->setVolume(leftVolume, rightVolume);
    }
}

void SoundPool::setPriority(int channelID, int priority)
{
    LOGV("setPriority(%d, %d)", channelID, priority);
    Mutex::Autolock lock(&mLock);
    SoundChannel* channel = findChannel(channelID);
    if (channel) {
        channel->setPriority(priority);
    }
}

void SoundPool::setLoop(int channelID, int loop)
{
    LOGV("setLoop(%d, %d)", channelID, loop);
    Mutex::Autolock lock(&mLock);
    SoundChannel* channel = findChannel(channelID);
    if (channel) {
        channel->setLoop(loop);
    }
}

void SoundPool::setRate(int channelID, float rate)
{
    LOGV("setRate(%d, %f)", channelID, rate);
    Mutex::Autolock lock(&mLock);
    SoundChannel* channel = findChannel(channelID);
    if (channel) {
        channel->setRate(rate);
    }
}

void SoundPool::done(SoundChannel* channel)
{
    LOGV("done(%d)", channel->channelID());

    // if "stolen", play next event
    SoundEvent* next = channel->nextEvent();
    if (next) {
        LOGV("add to restart list");
        mRestartThread->addToRestartList(channel);
    }

    // return to idle state
    else {
        LOGV("move to front");
        moveToFront(mChannels, channel);
    }
}

void SoundPool::dump()
{
    for (int i = 0; i < mMaxChannels; ++i) {
        mChannelPool[i].dump();
    }
}

Sample::Sample(int sampleID, const char* url)
{
    init();
    mSampleID = sampleID;
    mUrl = strdup(url);
    LOGV("create sampleID=%d, url=%s", mSampleID, mUrl);
}

Sample::Sample(int sampleID, int fd, int64_t offset, int64_t length)
{
    init();
    mSampleID = sampleID;
    mFd = dup(fd);
    mOffset = offset;
    mLength = length;
    LOGV("create sampleID=%d, fd=%d, offset=%lld, length=%lld", mSampleID, mFd, mLength, mOffset);
}

void Sample::init()
{
    mData = 0;
    mSize = 0;
    mRefCount = 0;
    mSampleID = 0;
    mState = UNLOADED;
    mFd = -1;
    mOffset = 0;
    mLength = 0;
    mUrl = 0;
}

Sample::~Sample()
{
    LOGV("Sample::destructor sampleID=%d, fd=%d", mSampleID, mFd);
    if (mFd > 0) {
        LOGV("close(%d)", mFd);
        ::close(mFd);
    }
    mData.clear();
    delete mUrl;
}

// TODO: Remove after debug is complete
#if 0
static void _dumpBuffer(void* buffer, size_t bufferSize, size_t dumpSize=10, bool zeroCheck=true)
{
    int16_t* p = static_cast<int16_t*>(buffer);
    if (zeroCheck) {
        for (size_t i = 0; i < bufferSize / 2; i++) {
            if (*p != 0) {
                goto Dump;
            }
        }
        LOGV("Sample data is all zeroes");
        return;
    }

Dump:
    LOGV("Sample Data");
    while (--dumpSize) {
        LOGV(" %04x", *p++);
    }
}
#endif

void Sample::doLoad()
{
    uint32_t sampleRate;
    int numChannels;
    sp<IMemory> p;
    LOGV("Start decode");
    if (mUrl) {
        p = MediaPlayer::decode(mUrl, &sampleRate, &numChannels);
    } else {
        p = MediaPlayer::decode(mFd, mOffset, mLength, &sampleRate, &numChannels);
        LOGV("close(%d)", mFd);
        ::close(mFd);
        mFd = -1;
    }
    if (p == 0) {
        LOGE("Unable to load sample: %s", mUrl);
        return;
    }
    LOGV("pointer = %p, size = %u, sampleRate = %u, numChannels = %d",
            p->pointer(), p->size(), sampleRate, numChannels);

    if (sampleRate > kMaxSampleRate) {
       LOGE("Sample rate (%u) out of range", sampleRate);
       return;
    }

    if ((numChannels < 1) || (numChannels > 2)) {
        LOGE("Sample channel count (%d) out of range", numChannels);
        return;
    }

    //_dumpBuffer(p->pointer(), p->size());
    uint8_t* q = static_cast<uint8_t*>(p->pointer()) + p->size() - 10;
    //_dumpBuffer(q, 10, 10, false);

    mData = p;
    mSize = p->size();
    mSampleRate = sampleRate;
    mNumChannels = numChannels;
    mState = READY;
}

void SoundChannel::init(SoundPool* soundPool)
{
    mSoundPool = soundPool;
}

void SoundChannel::deleteTrack() {
    LOGV("delete track");
    delete mAudioTrack;
    mAudioTrack = 0;
    mState = IDLE;
    return;
}

void SoundChannel::play(const sp<Sample>& sample, int channelID, float leftVolume,
        float rightVolume, int priority, int loop, float rate)
{
    Mutex::Autolock lock(&mLock);
    mSample = sample;
    mChannelID = channelID;
    mPriority = priority;
    mLoop = loop;
    doPlay(leftVolume, rightVolume, rate);
}

// must call with mutex held
void SoundChannel::doPlay(float leftVolume, float rightVolume, float rate)
{
    LOGV("SoundChannel::doPlay: sampleID=%d, channelID=%d, leftVolume=%f, rightVolume=%f, priority=%d, loop=%d, rate=%f",
            mSample->sampleID(), mChannelID, leftVolume, rightVolume, mPriority, mLoop, rate);
    deleteTrack();
    mNumChannels = mSample->numChannels();
    clearNextEvent();
    mPos = 0;

    // initialize track
    uint32_t sampleRate = uint32_t(float(mSample->sampleRate()) * rate + 0.5);
    LOGV("play: channelID=%d, sampleRate=%d\n", mChannelID, sampleRate);    // create track

    mAudioTrack = new AudioTrack(mSoundPool->streamType(), sampleRate, AudioSystem::PCM_16_BIT,
            mSample->numChannels(), kDefaultBufferCount, 0, callback, this);
    if (mAudioTrack->initCheck() != NO_ERROR) {
        LOGE("Error creating AudioTrack");
        deleteTrack();
        return;
    }
    mLeftVolume = leftVolume;
    mRightVolume = rightVolume;
    mAudioTrack->setVolume(leftVolume, rightVolume);

    // start playback
    mState = PLAYING;
    LOGV("play: start track");
    mAudioTrack->start();
}

void SoundChannel::callback(void* user, const AudioTrack::Buffer& b)
{
    SoundChannel* channel = static_cast<SoundChannel*>(user);
    channel->process(b);
}

void SoundChannel::process(const AudioTrack::Buffer& b)
{
    //LOGV("process(%d)", mChannelID);
    bool more = true;
    sp<Sample> sample = mSample;

    // check for stop state
    if (sample != 0) {

        // fill buffer
        uint8_t* q = (uint8_t*) b.i8;
        uint8_t* p = sample->data() + mPos;
        size_t count = sample->size() - mPos;
        if (count > b.size) {
            //LOGV("fill: q=%p, p=%p, mPos=%u, b.size=%u", q, p, mPos, b.size);
            memcpy(q, p, b.size);
            mPos += b.size;
        }

        // not enough samples to fill buffer
        else {
            //LOGV("partial: q=%p, p=%p, mPos=%u, count=%u", q, p, mPos, count);
            memcpy(q, p, count);
            size_t left = b.size - count;
            q += count;

            // loop sample
            while (left && mLoop) {
                if (mLoop > 0) {
                    mLoop--;
                }
                count = left > sample->size() ? sample->size() : left;
                //LOGV("loop: q=%p, p=%p, count=%u, mLoop=%d", p, q, count, mLoop);
                memcpy(q, sample->data(), count);
                q += count;
                mPos = count;
                left -= count;

                // done filling buffer?
                if ((mLoop == 0) && (count == sample->size())) {
                    more = false;
                }
            }

            // end of sample: zero-fill and stop track
            if (left) {
                //LOGV("zero-fill: q=%p, left=%u", q, left);
                memset(q, 0, left);
                more = false;
            }
        }

        //LOGV("buffer=%p, [0]=%d", b.i16, b.i16[0]);
    }

    // clean up
    Mutex::Autolock lock(&mLock);
    if (!more || (mState == STOPPING) || (mState == PAUSED)) {
        LOGV("stopping track");
        mAudioTrack->stop();
        if (more && (mState == PAUSED)) {
            LOGV("volume to zero");
            mAudioTrack->setVolume(0,0);
        } else {
            mSample.clear();
            mState = IDLE;
            mPriority = IDLE_PRIORITY;
            mSoundPool->done(this);
        }
    }
}

void SoundChannel::stop()
{
    Mutex::Autolock lock(&mLock);
    if (mState != IDLE) {
        setVolume(0, 0);
        LOGV("stop");
        mState = STOPPING;
    }
}

//FIXME: Pause is a little broken right now
void SoundChannel::pause()
{
    Mutex::Autolock lock(&mLock);
    if (mState == PLAYING) {
        LOGV("pause track");
        mState = PAUSED;
    }
}

void SoundChannel::resume()
{
    Mutex::Autolock lock(&mLock);
    if (mState == PAUSED) {
        LOGV("resume track");
        mState = PLAYING;
        mAudioTrack->setVolume(mLeftVolume, mRightVolume);
        mAudioTrack->start();
    }
}

void SoundChannel::setRate(float rate)
{
    uint32_t sampleRate = uint32_t(float(mSample->sampleRate()) * rate + 0.5);
    mAudioTrack->setSampleRate(sampleRate);
}

void SoundChannel::setVolume(float leftVolume, float rightVolume)
{
    Mutex::Autolock lock(&mLock);
    mLeftVolume = leftVolume;
    mRightVolume = rightVolume;
    if (mAudioTrack != 0) mAudioTrack->setVolume(leftVolume, rightVolume);
}

SoundChannel::~SoundChannel()
{
    LOGV("SoundChannel destructor");
    if (mAudioTrack) {
        LOGV("stop track");
        mAudioTrack->stop();
        delete mAudioTrack;
    }
    clearNextEvent();
    mSample.clear();
}

// always call with lock held
void SoundChannel::clearNextEvent()
{
    if (mNextEvent) {
        mNextEvent->mSample.clear();
        delete mNextEvent;
        mNextEvent = NULL;
    }
}

void SoundChannel::setNextEvent(SoundEvent* nextEvent)
{
    Mutex::Autolock lock(&mLock);
    clearNextEvent();
    mNextEvent = nextEvent;
}

void SoundChannel::dump()
{
    LOGV("mState = %d mChannelID=%d, mNumChannels=%d, mPos = %d, mPriority=%d, mLoop=%d",
            mState, mChannelID, mNumChannels, mPos, mPriority, mLoop);
}

} // end namespace android

