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
#include <utils/Log.h>

//#define USE_SHARED_MEM_BUFFER

// XXX needed for timing latency
#include <utils/Timers.h>

#include <media/AudioTrack.h>
#include <media/mediaplayer.h>

#include <system/audio.h>

#include "SoundPool.h"
#include "SoundPoolThread.h"

namespace android
{

int kDefaultBufferCount = 4;
uint32_t kMaxSampleRate = 48000;
uint32_t kDefaultSampleRate = 44100;
uint32_t kDefaultFrameCount = 1200;

SoundPool::SoundPool(int maxChannels, int streamType, int srcQuality)
{
    ALOGV("SoundPool constructor: maxChannels=%d, streamType=%d, srcQuality=%d",
            maxChannels, streamType, srcQuality);

    // check limits
    mMaxChannels = maxChannels;
    if (mMaxChannels < 1) {
        mMaxChannels = 1;
    }
    else if (mMaxChannels > 32) {
        mMaxChannels = 32;
    }
    LOGW_IF(maxChannels != mMaxChannels, "App requested %d channels", maxChannels);

    mQuit = false;
    mDecodeThread = 0;
    mStreamType = streamType;
    mSrcQuality = srcQuality;
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

int SoundPool::load(const char* path, int priority)
{
    ALOGV("load: path=%s, priority=%d", path, priority);
    Mutex::Autolock lock(&mLock);
    sp<Sample> sample = new Sample(++mNextSampleID, path);
    mSamples.add(sample->sampleID(), sample);
    doLoad(sample);
    return sample->sampleID();
}

int SoundPool::load(int fd, int64_t offset, int64_t length, int priority)
{
    ALOGV("load: fd=%d, offset=%lld, length=%lld, priority=%d",
            fd, offset, length, priority);
    Mutex::Autolock lock(&mLock);
    sp<Sample> sample = new Sample(++mNextSampleID, fd, offset, length);
    mSamples.add(sample->sampleID(), sample);
    doLoad(sample);
    return sample->sampleID();
}

void SoundPool::doLoad(sp<Sample>& sample)
{
    ALOGV("doLoad: loading sample sampleID=%d", sample->sampleID());
    sample->startLoad();
    mDecodeThread->loadSample(sample->sampleID());
}

bool SoundPool::unload(int sampleID)
{
    ALOGV("unload: sampleID=%d", sampleID);
    Mutex::Autolock lock(&mLock);
    return mSamples.removeItem(sampleID);
}

int SoundPool::play(int sampleID, float leftVolume, float rightVolume,
        int priority, int loop, float rate)
{
    ALOGV("play sampleID=%d, leftVolume=%f, rightVolume=%f, priority=%d, loop=%d, rate=%f",
            sampleID, leftVolume, rightVolume, priority, loop, rate);
    sp<Sample> sample;
    SoundChannel* channel;
    int channelID;

    Mutex::Autolock lock(&mLock);

    if (mQuit) {
        return 0;
    }
    // is sample ready?
    sample = findSample(sampleID);
    if ((sample == 0) || (sample->state() != Sample::READY)) {
        LOGW("  sample %d not READY", sampleID);
        return 0;
    }

    dump();

    // allocate a channel
    channel = allocateChannel_l(priority);

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

SoundChannel* SoundPool::allocateChannel_l(int priority)
{
    List<SoundChannel*>::iterator iter;
    SoundChannel* channel = NULL;

    // allocate a channel
    if (!mChannels.empty()) {
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


Sample::Sample(int sampleID, const char* url)
{
    init();
    mSampleID = sampleID;
    mUrl = strdup(url);
    ALOGV("create sampleID=%d, url=%s", mSampleID, mUrl);
}

Sample::Sample(int sampleID, int fd, int64_t offset, int64_t length)
{
    init();
    mSampleID = sampleID;
    mFd = dup(fd);
    mOffset = offset;
    mLength = length;
    ALOGV("create sampleID=%d, fd=%d, offset=%lld, length=%lld", mSampleID, mFd, mLength, mOffset);
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
    ALOGV("Sample::destructor sampleID=%d, fd=%d", mSampleID, mFd);
    if (mFd > 0) {
        ALOGV("close(%d)", mFd);
        ::close(mFd);
    }
    mData.clear();
    delete mUrl;
}

status_t Sample::doLoad()
{
    uint32_t sampleRate;
    int numChannels;
    int format;
    sp<IMemory> p;
    ALOGV("Start decode");
    if (mUrl) {
        p = MediaPlayer::decode(mUrl, &sampleRate, &numChannels, &format);
    } else {
        p = MediaPlayer::decode(mFd, mOffset, mLength, &sampleRate, &numChannels, &format);
        ALOGV("close(%d)", mFd);
        ::close(mFd);
        mFd = -1;
    }
    if (p == 0) {
        LOGE("Unable to load sample: %s", mUrl);
        return -1;
    }
    ALOGV("pointer = %p, size = %u, sampleRate = %u, numChannels = %d",
            p->pointer(), p->size(), sampleRate, numChannels);

    if (sampleRate > kMaxSampleRate) {
       LOGE("Sample rate (%u) out of range", sampleRate);
       return - 1;
    }

    if ((numChannels < 1) || (numChannels > 2)) {
        LOGE("Sample channel count (%d) out of range", numChannels);
        return - 1;
    }

    //_dumpBuffer(p->pointer(), p->size());
    uint8_t* q = static_cast<uint8_t*>(p->pointer()) + p->size() - 10;
    //_dumpBuffer(q, 10, 10, false);

    mData = p;
    mSize = p->size();
    mSampleRate = sampleRate;
    mNumChannels = numChannels;
    mFormat = format;
    mState = READY;
    return 0;
}


void SoundChannel::init(SoundPool* soundPool)
{
    mSoundPool = soundPool;
}

// call with sound pool lock held
void SoundChannel::play(const sp<Sample>& sample, int nextChannelID, float leftVolume,
        float rightVolume, int priority, int loop, float rate)
{
    AudioTrack* oldTrack;
    AudioTrack* newTrack;
    status_t status;

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
        int afFrameCount;
        int afSampleRate;
        int streamType = mSoundPool->streamType();
        if (AudioSystem::getOutputFrameCount(&afFrameCount, streamType) != NO_ERROR) {
            afFrameCount = kDefaultFrameCount;
        }
        if (AudioSystem::getOutputSamplingRate(&afSampleRate, streamType) != NO_ERROR) {
            afSampleRate = kDefaultSampleRate;
        }
        int numChannels = sample->numChannels();
        uint32_t sampleRate = uint32_t(float(sample->sampleRate()) * rate + 0.5);
        uint32_t totalFrames = (kDefaultBufferCount * afFrameCount * sampleRate) / afSampleRate;
        uint32_t bufferFrames = (totalFrames + (kDefaultBufferCount - 1)) / kDefaultBufferCount;
        uint32_t frameCount = 0;

        if (loop) {
            frameCount = sample->size()/numChannels/
                ((sample->format() == AUDIO_FORMAT_PCM_16_BIT) ? sizeof(int16_t) : sizeof(uint8_t));
        }

#ifndef USE_SHARED_MEM_BUFFER
        // Ensure minimum audio buffer size in case of short looped sample
        if(frameCount < totalFrames) {
            frameCount = totalFrames;
        }
#endif

        // mToggle toggles each time a track is started on a given channel.
        // The toggle is concatenated with the SoundChannel address and passed to AudioTrack
        // as callback user data. This enables the detection of callbacks received from the old
        // audio track while the new one is being started and avoids processing them with
        // wrong audio audio buffer size  (mAudioBufferSize)
        unsigned long toggle = mToggle ^ 1;
        void *userData = (void *)((unsigned long)this | toggle);
        uint32_t channels = (numChannels == 2) ?
                AUDIO_CHANNEL_OUT_STEREO : AUDIO_CHANNEL_OUT_MONO;

        // do not create a new audio track if current track is compatible with sample parameters
#ifdef USE_SHARED_MEM_BUFFER
        newTrack = new AudioTrack(streamType, sampleRate, sample->format(),
                channels, sample->getIMemory(), 0, callback, userData);
#else
        newTrack = new AudioTrack(streamType, sampleRate, sample->format(),
                channels, frameCount, 0, callback, userData, bufferFrames);
#endif
        oldTrack = mAudioTrack;
        status = newTrack->initCheck();
        if (status != NO_ERROR) {
            LOGE("Error creating AudioTrack");
            goto exit;
        }
        ALOGV("setVolume %p", newTrack);
        newTrack->setVolume(leftVolume, rightVolume);
        newTrack->setLoop(0, frameCount, loop);

        // From now on, AudioTrack callbacks recevieved with previous toggle value will be ignored.
        mToggle = toggle;
        mAudioTrack = newTrack;
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
    ALOGV("delete oldTrack %p", oldTrack);
    delete oldTrack;
    if (status != NO_ERROR) {
        delete newTrack;
        mAudioTrack = NULL;
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
//              ALOGV("fill: q=%p, p=%p, mPos=%u, b->size=%u, count=%d", q, p, mPos, b->size, count);
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
    } else if (event == AudioTrack::EVENT_UNDERRUN) {
        ALOGV("process %p channel %d EVENT_UNDERRUN", this, mChannelID);
        mSoundPool->addToStopList(this);
    } else if (event == AudioTrack::EVENT_LOOP_END) {
        ALOGV("End loop %p channel %d count %d", this, mChannelID, *(int *)info);
    }
}


// call with lock held
bool SoundChannel::doStop_l()
{
    if (mState != IDLE) {
        setVolume_l(0, 0);
        ALOGV("stop");
        mAudioTrack->stop();
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
    if (mAudioTrack != 0 && mSample.get() != 0) {
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
    if (mAudioTrack != 0) mAudioTrack->setVolume(leftVolume, rightVolume);
}

void SoundChannel::setVolume(float leftVolume, float rightVolume)
{
    Mutex::Autolock lock(&mLock);
    setVolume_l(leftVolume, rightVolume);
}

void SoundChannel::setLoop(int loop)
{
    Mutex::Autolock lock(&mLock);
    if (mAudioTrack != 0 && mSample.get() != 0) {
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
    delete mAudioTrack;
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
