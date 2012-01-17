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

namespace android {

static const int IDLE_PRIORITY = -1;

// forward declarations
class SoundEvent;
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
    enum MessageType { INVALID, SAMPLE_LOADED };
};

// callback function prototype
typedef void SoundPoolCallback(SoundPoolEvent event, SoundPool* soundPool, void* user);

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
    audio_format_t format() { return mFormat; }
    size_t size() { return mSize; }
    int state() { return mState; }
    uint8_t* data() { return static_cast<uint8_t*>(mData->pointer()); }
    status_t doLoad();
    void startLoad() { mState = LOADING; }
    sp<IMemory> getIMemory() { return mData; }

    // hack
    void init(int numChannels, int sampleRate, audio_format_t format, size_t size, sp<IMemory> data ) {
        mNumChannels = numChannels; mSampleRate = sampleRate; mFormat = format; mSize = size; mData = data; }

private:
    void init();

    size_t              mSize;
    volatile int32_t    mRefCount;
    uint16_t            mSampleID;
    uint16_t            mSampleRate;
    uint8_t             mState : 3;
    uint8_t             mNumChannels : 2;
    audio_format_t      mFormat;
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
    SoundEvent() : mChannelID(0), mLeftVolume(0), mRightVolume(0),
            mPriority(IDLE_PRIORITY), mLoop(0), mRate(0) {}
    void set(const sp<Sample>& sample, int channelID, float leftVolume,
            float rightVolume, int priority, int loop, float rate);
    sp<Sample>      sample() { return mSample; }
    int             channelID() { return mChannelID; }
    float           leftVolume() { return mLeftVolume; }
    float           rightVolume() { return mRightVolume; }
    int             priority() { return mPriority; }
    int             loop() { return mLoop; }
    float           rate() { return mRate; }
    void            clear() { mChannelID = 0; mSample.clear(); }

protected:
    sp<Sample>      mSample;
    int             mChannelID;
    float           mLeftVolume;
    float           mRightVolume;
    int             mPriority;
    int             mLoop;
    float           mRate;
};

// for channels aka AudioTracks
class SoundChannel : public SoundEvent {
public:
    enum state { IDLE, RESUMING, STOPPING, PAUSED, PLAYING };
    SoundChannel() : mAudioTrack(0), mState(IDLE), mNumChannels(1),
            mPos(0), mToggle(0), mAutoPaused(false) {}
    ~SoundChannel();
    void init(SoundPool* soundPool);
    void play(const sp<Sample>& sample, int channelID, float leftVolume, float rightVolume,
            int priority, int loop, float rate);
    void setVolume_l(float leftVolume, float rightVolume);
    void setVolume(float leftVolume, float rightVolume);
    void stop_l();
    void stop();
    void pause();
    void autoPause();
    void resume();
    void autoResume();
    void setRate(float rate);
    int state() { return mState; }
    void setPriority(int priority) { mPriority = priority; }
    void setLoop(int loop);
    int numChannels() { return mNumChannels; }
    void clearNextEvent() { mNextEvent.clear(); }
    void nextEvent();
    int nextChannelID() { return mNextEvent.channelID(); }
    void dump();

private:
    static void callback(int event, void* user, void *info);
    void process(int event, void *info, unsigned long toggle);
    bool doStop_l();

    SoundPool*          mSoundPool;
    AudioTrack*         mAudioTrack;
    SoundEvent          mNextEvent;
    Mutex               mLock;
    int                 mState;
    int                 mNumChannels;
    int                 mPos;
    int                 mAudioBufferSize;
    unsigned long       mToggle;
    bool                mAutoPaused;
};

// application object for managing a pool of sounds
class SoundPool {
    friend class SoundPoolThread;
    friend class SoundChannel;
public:
    SoundPool(int maxChannels, audio_stream_type_t streamType, int srcQuality);
    ~SoundPool();
    int load(const char* url, int priority);
    int load(int fd, int64_t offset, int64_t length, int priority);
    bool unload(int sampleID);
    int play(int sampleID, float leftVolume, float rightVolume, int priority,
            int loop, float rate);
    void pause(int channelID);
    void autoPause();
    void resume(int channelID);
    void autoResume();
    void stop(int channelID);
    void setVolume(int channelID, float leftVolume, float rightVolume);
    void setPriority(int channelID, int priority);
    void setLoop(int channelID, int loop);
    void setRate(int channelID, float rate);
    audio_stream_type_t streamType() const { return mStreamType; }
    int srcQuality() const { return mSrcQuality; }

    // called from SoundPoolThread
    void sampleLoaded(int sampleID);

    // called from AudioTrack thread
    void done_l(SoundChannel* channel);

    // callback function
    void setCallback(SoundPoolCallback* callback, void* user);
    void* getUserData() { return mUserData; }

private:
    SoundPool() {} // no default constructor
    bool startThreads();
    void doLoad(sp<Sample>& sample);
    sp<Sample> findSample(int sampleID) { return mSamples.valueFor(sampleID); }
    SoundChannel* findChannel (int channelID);
    SoundChannel* findNextChannel (int channelID);
    SoundChannel* allocateChannel_l(int priority);
    void moveToFront_l(SoundChannel* channel);
    void notify(SoundPoolEvent event);
    void dump();

    // restart thread
    void addToRestartList(SoundChannel* channel);
    void addToStopList(SoundChannel* channel);
    static int beginThread(void* arg);
    int run();
    void quit();

    Mutex                   mLock;
    Mutex                   mRestartLock;
    Condition               mCondition;
    SoundPoolThread*        mDecodeThread;
    SoundChannel*           mChannelPool;
    List<SoundChannel*>     mChannels;
    List<SoundChannel*>     mRestart;
    List<SoundChannel*>     mStop;
    DefaultKeyedVector< int, sp<Sample> >   mSamples;
    int                     mMaxChannels;
    audio_stream_type_t     mStreamType;
    int                     mSrcQuality;
    int                     mAllocated;
    int                     mNextSampleID;
    int                     mNextChannelID;
    bool                    mQuit;

    // callback
    Mutex                   mCallbackLock;
    SoundPoolCallback*      mCallback;
    void*                   mUserData;
};

} // end namespace android

#endif /*SOUNDPOOL_H_*/
