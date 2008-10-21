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

#ifndef SOUNDPOOL_H_
#define SOUNDPOOL_H_

#include <utils/threads.h>
#include <utils/List.h>
#include <utils/Vector.h>
#include <utils/KeyedVector.h>
#include <media/AudioTrack.h>
#include <cutils/atomic.h>

#include <nativehelper/jni.h>

namespace android {

static const int IDLE_PRIORITY = -1;

// forward declarations
class SoundEvent;
class SoundPoolRestartThread;
class SoundPoolThread;
class SoundPool;

// for queued events
class SoundPoolEvent {
public:
    SoundPoolEvent(int msg, int arg1=0, int arg2=0) :
        mMsg(msg), mArg1(arg1), mArg2(arg2) {}
    int         mMsg;
    int         mArg1;
    int         mArg2;
};

// JNI for calling back Java SoundPool object
extern void android_soundpool_SoundPool_notify(jobject ref, const SoundPoolEvent *event);

// tracks samples used by application
class Sample  : public RefBase {
public:
    enum sample_state { UNLOADED, LOADING, READY, UNLOADING };
    Sample(int sampleID, const char* url);
    Sample(int sampleID, int fd, int64_t offset, int64_t length);
    ~Sample();
    int sampleID() { return mSampleID; }
    int numChannels() { return mNumChannels; }
    int sampleRate() { return mSampleRate; }
    size_t size() { return mSize; }
    int state() { return mState; }
    uint8_t* data() { return static_cast<uint8_t*>(mData->pointer()); }
    void doLoad();
    void startLoad() { mState = LOADING; }

    // hack
    void init(int numChannels, int sampleRate, size_t size, sp<IMemory> data ) {
        mNumChannels = numChannels; mSampleRate = sampleRate; mSize = size; mData = data; }

private:
    void init();

    size_t              mSize;
    volatile int32_t    mRefCount;
    uint16_t            mSampleID;
    uint16_t            mSampleRate;
    uint8_t             mState : 3;
    uint8_t             mNumChannels : 2;
    int                 mFd;
    int64_t             mOffset;
    int64_t             mLength;
    char*               mUrl;
    sp<IMemory>         mData;
};

// stores pending events for stolen channels
class SoundEvent
{
public:
    SoundEvent(const sp<Sample>& sample, int channelID, float leftVolume,
            float rightVolume, int priority, int loop, float rate) :
                mSample(sample), mChannelID(channelID), mLeftVolume(leftVolume),
                mRightVolume(rightVolume), mPriority(priority), mLoop(loop),
                mRate(rate) {}
    sp<Sample>      mSample;
    int             mChannelID;
    float           mLeftVolume;
    float           mRightVolume;
    int             mPriority;
    int             mLoop;
    float           mRate;
};

// for channels aka AudioTracks
class SoundChannel {
public:
    enum state { IDLE, RESUMING, STOPPING, PAUSED, PLAYING };
    SoundChannel() : mAudioTrack(0), mNextEvent(0), mChannelID(0), mState(IDLE),
            mNumChannels(1), mPos(0), mPriority(IDLE_PRIORITY), mLoop(0) {}
    ~SoundChannel();
    void init(SoundPool* soundPool);
    void deleteTrack();
    void play(const sp<Sample>& sample, int channelID, float leftVolume, float rightVolume,
            int priority, int loop, float rate);
    void doPlay(float leftVolume, float rightVolume, float rate);
    void setVolume(float leftVolume, float rightVolume);
    void stop();
    void pause();
    void resume();
    void setRate(float rate);
    int channelID() { return mChannelID; }
    int state() { return mState; }
    int priority() { return mPriority; }
    void setPriority(int priority) { mPriority = priority; }
    void setLoop(int loop) { mLoop = loop; }
    int numChannels() { return mNumChannels; }
    SoundEvent* nextEvent() { return mNextEvent; }
    void clearNextEvent();
    void setNextEvent(SoundEvent* nextEvent);
    void dump();

private:
    static void callback(void* user, const AudioTrack::Buffer& info);
    void process(const AudioTrack::Buffer& b);

    SoundPool*          mSoundPool;
    AudioTrack*         mAudioTrack;
    sp<Sample>          mSample;
    SoundEvent*         mNextEvent;
    Mutex               mLock;
    int                 mChannelID;
    int                 mState;
    int                 mNumChannels;
    int                 mPos;
    int                 mPriority;
    int                 mLoop;
    float               mLeftVolume;
    float               mRightVolume;
};

// application object for managing a pool of sounds
class SoundPool {
    friend class SoundPoolThread;
    friend class SoundChannel;
public:
    SoundPool(jobject soundPoolRef, int maxChannels, int streamType, int srcQuality);
    ~SoundPool();
    int load(const char* url, int priority);
    int load(int fd, int64_t offset, int64_t length, int priority);
    bool unload(int sampleID);
    int play(int sampleID, float leftVolume, float rightVolume, int priority,
            int loop, float rate);
    void pause(int channelID);
    void resume(int channelID);
    void stop(int channelID);
    void setVolume(int channelID, float leftVolume, float rightVolume);
    void setPriority(int channelID, int priority);
    void setLoop(int channelID, int loop);
    void setRate(int channelID, float rate);
    int streamType() const { return mStreamType; }
    int srcQuality() const { return mSrcQuality; }

    // called from SoundPoolThread
    void sampleLoaded(int sampleID);

    // called from AudioTrack thread
    void done(SoundChannel* channel);

private:
    SoundPool() {} // no default constructor
    bool startThreads();
    void doLoad(sp<Sample>& sample);
    inline void notify(const SoundPoolEvent* event) {
        android_soundpool_SoundPool_notify(mSoundPoolRef, event);
    }
    sp<Sample> findSample(int sampleID) { return mSamples.valueFor(sampleID); }
    SoundChannel* findChannel (int channelID);
    SoundChannel* findNextChannel (int channelID);
    SoundChannel* allocateChannel(int priority);
    void moveToFront(List<SoundChannel*>& list, SoundChannel* channel);
    void dump();

    jobject                 mSoundPoolRef;
    Mutex                   mLock;
    SoundPoolRestartThread* mRestartThread;
    SoundPoolThread*        mDecodeThread;
    SoundChannel*           mChannelPool;
    List<SoundChannel*>     mChannels;
    DefaultKeyedVector< int, sp<Sample> >   mSamples;
    int                     mMaxChannels;
    int                     mStreamType;
    int                     mSrcQuality;
    int                     mAllocated;
    int                     mNextSampleID;
    int                     mNextChannelID;
};

} // end namespace android

#endif /*SOUNDPOOL_H_*/
