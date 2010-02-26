/* //device/include/server/AudioFlinger/AudioFlinger.h
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#ifndef ANDROID_AUDIO_FLINGER_H
#define ANDROID_AUDIO_FLINGER_H

#include <stdint.h>
#include <sys/types.h>
#include <limits.h>

#include <media/IAudioFlinger.h>
#include <media/IAudioFlingerClient.h>
#include <media/IAudioTrack.h>
#include <media/IAudioRecord.h>
#include <media/AudioTrack.h>

#include <utils/Atomic.h>
#include <utils/Errors.h>
#include <utils/threads.h>
#include <binder/MemoryDealer.h>
#include <utils/SortedVector.h>
#include <utils/Vector.h>

#include <hardware_legacy/AudioHardwareInterface.h>

#include "AudioBufferProvider.h"

namespace android {

class audio_track_cblk_t;
class AudioMixer;
class AudioBuffer;
class AudioResampler;


// ----------------------------------------------------------------------------

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))


// ----------------------------------------------------------------------------

static const nsecs_t kStandbyTimeInNsecs = seconds(3);

class AudioFlinger : public BnAudioFlinger, public IBinder::DeathRecipient
{
public:
    static void instantiate();

    virtual     status_t    dump(int fd, const Vector<String16>& args);

    // IAudioFlinger interface
    virtual sp<IAudioTrack> createTrack(
                                pid_t pid,
                                int streamType,
                                uint32_t sampleRate,
                                int format,
                                int channelCount,
                                int frameCount,
                                uint32_t flags,
                                const sp<IMemory>& sharedBuffer,
                                int output,
                                status_t *status);

    virtual     uint32_t    sampleRate(int output) const;
    virtual     int         channelCount(int output) const;
    virtual     int         format(int output) const;
    virtual     size_t      frameCount(int output) const;
    virtual     uint32_t    latency(int output) const;

    virtual     status_t    setMasterVolume(float value);
    virtual     status_t    setMasterMute(bool muted);

    virtual     float       masterVolume() const;
    virtual     bool        masterMute() const;

    virtual     status_t    setStreamVolume(int stream, float value, int output);
    virtual     status_t    setStreamMute(int stream, bool muted);

    virtual     float       streamVolume(int stream, int output) const;
    virtual     bool        streamMute(int stream) const;

    virtual     status_t    setMode(int mode);

    virtual     status_t    setMicMute(bool state);
    virtual     bool        getMicMute() const;

    virtual     bool        isStreamActive(int stream) const;

    virtual     status_t    setParameters(int ioHandle, const String8& keyValuePairs);
    virtual     String8     getParameters(int ioHandle, const String8& keys);

    virtual     void        registerClient(const sp<IAudioFlingerClient>& client);

    virtual     size_t      getInputBufferSize(uint32_t sampleRate, int format, int channelCount);
    virtual     unsigned int  getInputFramesLost(int ioHandle);

    virtual int openOutput(uint32_t *pDevices,
                                    uint32_t *pSamplingRate,
                                    uint32_t *pFormat,
                                    uint32_t *pChannels,
                                    uint32_t *pLatencyMs,
                                    uint32_t flags);

    virtual int openDuplicateOutput(int output1, int output2);

    virtual status_t closeOutput(int output);

    virtual status_t suspendOutput(int output);

    virtual status_t restoreOutput(int output);

    virtual int openInput(uint32_t *pDevices,
                            uint32_t *pSamplingRate,
                            uint32_t *pFormat,
                            uint32_t *pChannels,
                            uint32_t acoustics);

    virtual status_t closeInput(int input);

    virtual status_t setStreamOutput(uint32_t stream, int output);

    virtual status_t setVoiceVolume(float volume);

    virtual status_t getRenderPosition(uint32_t *halFrames, uint32_t *dspFrames, int output);

    // IBinder::DeathRecipient
    virtual     void        binderDied(const wp<IBinder>& who);

    enum hardware_call_state {
        AUDIO_HW_IDLE = 0,
        AUDIO_HW_INIT,
        AUDIO_HW_OUTPUT_OPEN,
        AUDIO_HW_OUTPUT_CLOSE,
        AUDIO_HW_INPUT_OPEN,
        AUDIO_HW_INPUT_CLOSE,
        AUDIO_HW_STANDBY,
        AUDIO_HW_SET_MASTER_VOLUME,
        AUDIO_HW_GET_ROUTING,
        AUDIO_HW_SET_ROUTING,
        AUDIO_HW_GET_MODE,
        AUDIO_HW_SET_MODE,
        AUDIO_HW_GET_MIC_MUTE,
        AUDIO_HW_SET_MIC_MUTE,
        AUDIO_SET_VOICE_VOLUME,
        AUDIO_SET_PARAMETER,
    };

    // record interface
    virtual sp<IAudioRecord> openRecord(
                                pid_t pid,
                                int input,
                                uint32_t sampleRate,
                                int format,
                                int channelCount,
                                int frameCount,
                                uint32_t flags,
                                status_t *status);

    virtual     status_t    onTransact(
                                uint32_t code,
                                const Parcel& data,
                                Parcel* reply,
                                uint32_t flags);

private:
                            AudioFlinger();
    virtual                 ~AudioFlinger();


    // Internal dump utilites.
    status_t dumpPermissionDenial(int fd, const Vector<String16>& args);
    status_t dumpClients(int fd, const Vector<String16>& args);
    status_t dumpInternals(int fd, const Vector<String16>& args);

    // --- Client ---
    class Client : public RefBase {
    public:
                            Client(const sp<AudioFlinger>& audioFlinger, pid_t pid);
        virtual             ~Client();
        const sp<MemoryDealer>&     heap() const;
        pid_t               pid() const { return mPid; }
        sp<AudioFlinger>    audioFlinger() { return mAudioFlinger; }

    private:
                            Client(const Client&);
                            Client& operator = (const Client&);
        sp<AudioFlinger>    mAudioFlinger;
        sp<MemoryDealer>    mMemoryDealer;
        pid_t               mPid;
    };


    class TrackHandle;
    class RecordHandle;
    class RecordThread;
    class PlaybackThread;
    class MixerThread;
    class DirectOutputThread;
    class DuplicatingThread;
    class Track;
    class RecordTrack;

    class ThreadBase : public Thread {
    public:
        ThreadBase (const sp<AudioFlinger>& audioFlinger, int id);
        virtual             ~ThreadBase();

        status_t dumpBase(int fd, const Vector<String16>& args);

        // base for record and playback
        class TrackBase : public AudioBufferProvider, public RefBase {

        public:
            enum track_state {
                IDLE,
                TERMINATED,
                STOPPED,
                RESUMING,
                ACTIVE,
                PAUSING,
                PAUSED
            };

            enum track_flags {
                STEPSERVER_FAILED = 0x01, //  StepServer could not acquire cblk->lock mutex
                SYSTEM_FLAGS_MASK = 0x0000ffffUL,
                // The upper 16 bits are used for track-specific flags.
            };

                                TrackBase(const wp<ThreadBase>& thread,
                                        const sp<Client>& client,
                                        uint32_t sampleRate,
                                        int format,
                                        int channelCount,
                                        int frameCount,
                                        uint32_t flags,
                                        const sp<IMemory>& sharedBuffer);
                                ~TrackBase();

            virtual status_t    start() = 0;
            virtual void        stop() = 0;
                    sp<IMemory> getCblk() const;
                    audio_track_cblk_t* cblk() const { return mCblk; }

        protected:
            friend class ThreadBase;
            friend class RecordHandle;
            friend class PlaybackThread;
            friend class RecordThread;
            friend class MixerThread;
            friend class DirectOutputThread;

                                TrackBase(const TrackBase&);
                                TrackBase& operator = (const TrackBase&);

            virtual status_t getNextBuffer(AudioBufferProvider::Buffer* buffer) = 0;
            virtual void releaseBuffer(AudioBufferProvider::Buffer* buffer);

            int format() const {
                return mFormat;
            }

            int channelCount() const ;

            int sampleRate() const;

            void* getBuffer(uint32_t offset, uint32_t frames) const;

            bool isStopped() const {
                return mState == STOPPED;
            }

            bool isTerminated() const {
                return mState == TERMINATED;
            }

            bool step();
            void reset();

            wp<ThreadBase>      mThread;
            sp<Client>          mClient;
            sp<IMemory>         mCblkMemory;
            audio_track_cblk_t* mCblk;
            void*               mBuffer;
            void*               mBufferEnd;
            uint32_t            mFrameCount;
            // we don't really need a lock for these
            int                 mState;
            int                 mClientTid;
            uint8_t             mFormat;
            uint32_t            mFlags;
        };

        class ConfigEvent {
        public:
            ConfigEvent() : mEvent(0), mParam(0) {}

            int mEvent;
            int mParam;
        };

                    uint32_t    sampleRate() const;
                    int         channelCount() const;
                    int         format() const;
                    size_t      frameCount() const;
                    void        wakeUp()    { mWaitWorkCV.broadcast(); }
                    void        exit();
        virtual     bool        checkForNewParameters_l() = 0;
        virtual     status_t    setParameters(const String8& keyValuePairs);
        virtual     String8     getParameters(const String8& keys) = 0;
        virtual     void        audioConfigChanged(int event, int param = 0) = 0;
                    void        sendConfigEvent(int event, int param = 0);
                    void        sendConfigEvent_l(int event, int param = 0);
                    void        processConfigEvents();
                    int         id() const { return mId;}
                    bool        standby() { return mStandby; }

        mutable     Mutex                   mLock;

    protected:

        friend class Track;
        friend class TrackBase;
        friend class PlaybackThread;
        friend class MixerThread;
        friend class DirectOutputThread;
        friend class DuplicatingThread;
        friend class RecordThread;
        friend class RecordTrack;

                    Condition               mWaitWorkCV;
                    sp<AudioFlinger>        mAudioFlinger;
                    uint32_t                mSampleRate;
                    size_t                  mFrameCount;
                    int                     mChannelCount;
                    int                     mFormat;
                    uint32_t                mFrameSize;
                    Condition               mParamCond;
                    Vector<String8>         mNewParameters;
                    status_t                mParamStatus;
                    Vector<ConfigEvent *>   mConfigEvents;
                    bool                    mStandby;
                    int                     mId;
                    bool                    mExiting;
    };

    // --- PlaybackThread ---
    class PlaybackThread : public ThreadBase {
    public:

        enum type {
            MIXER,
            DIRECT,
            DUPLICATING
        };

        enum mixer_state {
            MIXER_IDLE,
            MIXER_TRACKS_ENABLED,
            MIXER_TRACKS_READY
        };

        // playback track
        class Track : public TrackBase {
        public:
                                Track(  const wp<ThreadBase>& thread,
                                        const sp<Client>& client,
                                        int streamType,
                                        uint32_t sampleRate,
                                        int format,
                                        int channelCount,
                                        int frameCount,
                                        const sp<IMemory>& sharedBuffer);
                                ~Track();

                    void        dump(char* buffer, size_t size);
            virtual status_t    start();
            virtual void        stop();
                    void        pause();

                    void        flush();
                    void        destroy();
                    void        mute(bool);
                    void        setVolume(float left, float right);
                    int name() const {
                        return mName;
                    }

                    int type() const {
                        return mStreamType;
                    }


        protected:
            friend class ThreadBase;
            friend class AudioFlinger;
            friend class TrackHandle;
            friend class PlaybackThread;
            friend class MixerThread;
            friend class DirectOutputThread;

                                Track(const Track&);
                                Track& operator = (const Track&);

            virtual status_t getNextBuffer(AudioBufferProvider::Buffer* buffer);
            bool isMuted() { return mMute; }
            bool isPausing() const {
                return mState == PAUSING;
            }
            bool isPaused() const {
                return mState == PAUSED;
            }
            bool isReady() const;
            void setPaused() { mState = PAUSED; }
            void reset();

            bool isOutputTrack() const {
                return (mStreamType == AudioSystem::NUM_STREAM_TYPES);
            }

            // we don't really need a lock for these
            float               mVolume[2];
            volatile bool       mMute;
            // FILLED state is used for suppressing volume ramp at begin of playing
            enum {FS_FILLING, FS_FILLED, FS_ACTIVE};
            mutable uint8_t     mFillingUpStatus;
            int8_t              mRetryCount;
            sp<IMemory>         mSharedBuffer;
            bool                mResetDone;
            int                 mStreamType;
            int                 mName;
        };  // end of Track


        // playback track
        class OutputTrack : public Track {
        public:

            class Buffer: public AudioBufferProvider::Buffer {
            public:
                int16_t *mBuffer;
            };

                                OutputTrack(  const wp<ThreadBase>& thread,
                                        DuplicatingThread *sourceThread,
                                        uint32_t sampleRate,
                                        int format,
                                        int channelCount,
                                        int frameCount);
                                ~OutputTrack();

            virtual status_t    start();
            virtual void        stop();
                    bool        write(int16_t* data, uint32_t frames);
                    bool        bufferQueueEmpty() { return (mBufferQueue.size() == 0) ? true : false; }
                    bool        isActive() { return mActive; }
            wp<ThreadBase>&     thread()  { return mThread; }

        private:

            status_t            obtainBuffer(AudioBufferProvider::Buffer* buffer, uint32_t waitTimeMs);
            void                clearBufferQueue();

            // Maximum number of pending buffers allocated by OutputTrack::write()
            static const uint8_t kMaxOverFlowBuffers = 10;

            Vector < Buffer* >          mBufferQueue;
            AudioBufferProvider::Buffer mOutBuffer;
            bool                        mActive;
            DuplicatingThread*          mSourceThread;
        };  // end of OutputTrack

        PlaybackThread (const sp<AudioFlinger>& audioFlinger, AudioStreamOut* output, int id);
        virtual             ~PlaybackThread();

        virtual     status_t    dump(int fd, const Vector<String16>& args);

        // Thread virtuals
        virtual     status_t    readyToRun();
        virtual     void        onFirstRef();

        virtual     uint32_t    latency() const;

        virtual     status_t    setMasterVolume(float value);
        virtual     status_t    setMasterMute(bool muted);

        virtual     float       masterVolume() const;
        virtual     bool        masterMute() const;

        virtual     status_t    setStreamVolume(int stream, float value);
        virtual     status_t    setStreamMute(int stream, bool muted);

        virtual     float       streamVolume(int stream) const;
        virtual     bool        streamMute(int stream) const;

                    bool        isStreamActive(int stream) const;

                    sp<Track>   createTrack_l(
                                    const sp<AudioFlinger::Client>& client,
                                    int streamType,
                                    uint32_t sampleRate,
                                    int format,
                                    int channelCount,
                                    int frameCount,
                                    const sp<IMemory>& sharedBuffer,
                                    status_t *status);

                    AudioStreamOut* getOutput() { return mOutput; }

        virtual     int         type() const { return mType; }
                    void        suspend() { mSuspended++; }
                    void        restore() { if (mSuspended) mSuspended--; }
                    bool        isSuspended() { return (mSuspended != 0); }
        virtual     String8     getParameters(const String8& keys);
        virtual     void        audioConfigChanged(int event, int param = 0);
        virtual     status_t    getRenderPosition(uint32_t *halFrames, uint32_t *dspFrames);

        struct  stream_type_t {
            stream_type_t()
                :   volume(1.0f),
                    mute(false)
            {
            }
            float       volume;
            bool        mute;
        };

    protected:
        int                             mType;
        int16_t*                        mMixBuffer;
        int                             mSuspended;
        int                             mBytesWritten;
        bool                            mMasterMute;
        SortedVector< wp<Track> >       mActiveTracks;

        virtual int             getTrackName_l() = 0;
        virtual void            deleteTrackName_l(int name) = 0;
        virtual uint32_t        activeSleepTimeUs() = 0;
        virtual uint32_t        idleSleepTimeUs() = 0;

    private:

        friend class AudioFlinger;
        friend class OutputTrack;
        friend class Track;
        friend class TrackBase;
        friend class MixerThread;
        friend class DirectOutputThread;
        friend class DuplicatingThread;

        PlaybackThread(const Client&);
        PlaybackThread& operator = (const PlaybackThread&);

        status_t    addTrack_l(const sp<Track>& track);
        void        destroyTrack_l(const sp<Track>& track);

        void        readOutputParameters();

        virtual status_t    dumpInternals(int fd, const Vector<String16>& args);
        status_t    dumpTracks(int fd, const Vector<String16>& args);

        SortedVector< sp<Track> >       mTracks;
        // mStreamTypes[] uses 1 additionnal stream type internally for the OutputTrack used by DuplicatingThread
        stream_type_t                   mStreamTypes[AudioSystem::NUM_STREAM_TYPES + 1];
        AudioStreamOut*                 mOutput;
        float                           mMasterVolume;
        nsecs_t                         mLastWriteTime;
        int                             mNumWrites;
        int                             mNumDelayedWrites;
        bool                            mInWrite;
    };

    class MixerThread : public PlaybackThread {
    public:
        MixerThread (const sp<AudioFlinger>& audioFlinger, AudioStreamOut* output, int id);
        virtual             ~MixerThread();

        // Thread virtuals
        virtual     bool        threadLoop();

                    void        getTracks(SortedVector < sp<Track> >& tracks,
                                      SortedVector < wp<Track> >& activeTracks,
                                      int streamType);
                    void        putTracks(SortedVector < sp<Track> >& tracks,
                                      SortedVector < wp<Track> >& activeTracks);
        virtual     bool        checkForNewParameters_l();
        virtual     status_t    dumpInternals(int fd, const Vector<String16>& args);

    protected:
                    uint32_t    prepareTracks_l(const SortedVector< wp<Track> >& activeTracks, Vector< sp<Track> > *tracksToRemove);
        virtual     int         getTrackName_l();
        virtual     void        deleteTrackName_l(int name);
        virtual     uint32_t    activeSleepTimeUs();
        virtual     uint32_t    idleSleepTimeUs();

        AudioMixer*                     mAudioMixer;
    };

    class DirectOutputThread : public PlaybackThread {
    public:

        DirectOutputThread (const sp<AudioFlinger>& audioFlinger, AudioStreamOut* output, int id);
        ~DirectOutputThread();

        // Thread virtuals
        virtual     bool        threadLoop();

        virtual     bool        checkForNewParameters_l();

    protected:
        virtual     int         getTrackName_l();
        virtual     void        deleteTrackName_l(int name);
        virtual     uint32_t    activeSleepTimeUs();
        virtual     uint32_t    idleSleepTimeUs();

    private:
        float mLeftVolume;
        float mRightVolume;
    };

    class DuplicatingThread : public MixerThread {
    public:
        DuplicatingThread (const sp<AudioFlinger>& audioFlinger, MixerThread* mainThread, int id);
        ~DuplicatingThread();

        // Thread virtuals
        virtual     bool        threadLoop();
                    void        addOutputTrack(MixerThread* thread);
                    void        removeOutputTrack(MixerThread* thread);
                    uint32_t    waitTimeMs() { return mWaitTimeMs; }
    protected:
        virtual     uint32_t    activeSleepTimeUs();

    private:
                    bool        outputsReady(SortedVector< sp<OutputTrack> > &outputTracks);
                    void        updateWaitTime();

        SortedVector < sp<OutputTrack> >  mOutputTracks;
                    uint32_t    mWaitTimeMs;
    };

              PlaybackThread *checkPlaybackThread_l(int output) const;
              MixerThread *checkMixerThread_l(int output) const;
              RecordThread *checkRecordThread_l(int input) const;
              float streamVolumeInternal(int stream) const { return mStreamTypes[stream].volume; }
              void audioConfigChanged_l(int event, int ioHandle, void *param2);

    friend class AudioBuffer;

    class TrackHandle : public android::BnAudioTrack {
    public:
                            TrackHandle(const sp<PlaybackThread::Track>& track);
        virtual             ~TrackHandle();
        virtual status_t    start();
        virtual void        stop();
        virtual void        flush();
        virtual void        mute(bool);
        virtual void        pause();
        virtual void        setVolume(float left, float right);
        virtual sp<IMemory> getCblk() const;
        virtual status_t onTransact(
            uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags);
    private:
        sp<PlaybackThread::Track> mTrack;
    };

    friend class Client;
    friend class PlaybackThread::Track;


                void        removeClient_l(pid_t pid);


    // record thread
    class RecordThread : public ThreadBase, public AudioBufferProvider
    {
    public:

        // record track
        class RecordTrack : public TrackBase {
        public:
                                RecordTrack(const wp<ThreadBase>& thread,
                                        const sp<Client>& client,
                                        uint32_t sampleRate,
                                        int format,
                                        int channelCount,
                                        int frameCount,
                                        uint32_t flags);
                                ~RecordTrack();

            virtual status_t    start();
            virtual void        stop();

                    bool        overflow() { bool tmp = mOverflow; mOverflow = false; return tmp; }
                    bool        setOverflow() { bool tmp = mOverflow; mOverflow = true; return tmp; }

                    void        dump(char* buffer, size_t size);
        private:
            friend class AudioFlinger;
            friend class RecordThread;

                                RecordTrack(const RecordTrack&);
                                RecordTrack& operator = (const RecordTrack&);

            virtual status_t getNextBuffer(AudioBufferProvider::Buffer* buffer);

            bool                mOverflow;
        };


                RecordThread(const sp<AudioFlinger>& audioFlinger,
                        AudioStreamIn *input,
                        uint32_t sampleRate,
                        uint32_t channels,
                        int id);
                ~RecordThread();

        virtual bool        threadLoop();
        virtual status_t    readyToRun() { return NO_ERROR; }
        virtual void        onFirstRef();

                status_t    start(RecordTrack* recordTrack);
                void        stop(RecordTrack* recordTrack);
                status_t    dump(int fd, const Vector<String16>& args);
                AudioStreamIn* getInput() { return mInput; }

        virtual status_t    getNextBuffer(AudioBufferProvider::Buffer* buffer);
        virtual void        releaseBuffer(AudioBufferProvider::Buffer* buffer);
        virtual bool        checkForNewParameters_l();
        virtual String8     getParameters(const String8& keys);
        virtual void        audioConfigChanged(int event, int param = 0);
                void        readInputParameters();
        virtual unsigned int  getInputFramesLost();

    private:
                RecordThread();
                AudioStreamIn                       *mInput;
                sp<RecordTrack>                     mActiveTrack;
                Condition                           mStartStopCond;
                AudioResampler                      *mResampler;
                int32_t                             *mRsmpOutBuffer;
                int16_t                             *mRsmpInBuffer;
                size_t                              mRsmpInIndex;
                size_t                              mInputBytes;
                int                                 mReqChannelCount;
                uint32_t                            mReqSampleRate;
                ssize_t                             mBytesRead;
    };

    class RecordHandle : public android::BnAudioRecord {
    public:
        RecordHandle(const sp<RecordThread::RecordTrack>& recordTrack);
        virtual             ~RecordHandle();
        virtual status_t    start();
        virtual void        stop();
        virtual sp<IMemory> getCblk() const;
        virtual status_t onTransact(
            uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags);
    private:
        sp<RecordThread::RecordTrack> mRecordTrack;
    };

    friend class RecordThread;
    friend class PlaybackThread;


    mutable     Mutex                               mLock;

                DefaultKeyedVector< pid_t, wp<Client> >     mClients;

                mutable     Mutex                   mHardwareLock;
                AudioHardwareInterface*             mAudioHardware;
    mutable     int                                 mHardwareStatus;


                DefaultKeyedVector< int, sp<PlaybackThread> >  mPlaybackThreads;
                PlaybackThread::stream_type_t       mStreamTypes[AudioSystem::NUM_STREAM_TYPES];
                float                               mMasterVolume;
                bool                                mMasterMute;

                DefaultKeyedVector< int, sp<RecordThread> >    mRecordThreads;

                SortedVector< sp<IBinder> >         mNotificationClients;
                int                                 mNextThreadId;
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_AUDIO_FLINGER_H
