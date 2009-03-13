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

#include <media/IAudioFlinger.h>
#include <media/IAudioFlingerClient.h>
#include <media/IAudioTrack.h>
#include <media/IAudioRecord.h>
#include <media/AudioTrack.h>

#include <utils/Atomic.h>
#include <utils/Errors.h>
#include <utils/threads.h>
#include <utils/MemoryDealer.h>
#include <utils/KeyedVector.h>
#include <utils/SortedVector.h>
#include <utils/Vector.h>

#include <hardware_legacy/AudioHardwareInterface.h>

#include "AudioBufferProvider.h"

namespace android {

class audio_track_cblk_t;
class AudioMixer;
class AudioBuffer;


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

    virtual     status_t    setStreamVolume(int stream, float value);
    virtual     status_t    setStreamMute(int stream, bool muted);

    virtual     float       streamVolume(int stream) const;
    virtual     bool        streamMute(int stream) const;

    virtual     status_t    setRouting(int mode, uint32_t routes, uint32_t mask);
    virtual     uint32_t    getRouting(int mode) const;

    virtual     status_t    setMode(int mode);
    virtual     int         getMode() const;

    virtual     status_t    setMicMute(bool state);
    virtual     bool        getMicMute() const;

    virtual     bool        isMusicActive() const;

    virtual     bool        isA2dpEnabled() const;

    virtual     status_t    setParameter(const char* key, const char* value);

    virtual     void        registerClient(const sp<IAudioFlingerClient>& client);
    
    virtual     size_t      getInputBufferSize(uint32_t sampleRate, int format, int channelCount);
    
    virtual     void        wakeUp()    { mWaitWorkCV.broadcast(); }
    
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
                                int streamType,
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
    
    void                    setOutput(int outputType);
    void                    doSetOutput(int outputType);

#ifdef WITH_A2DP
    void                    setA2dpEnabled_l(bool enable);
    void                    checkA2dpEnabledChange_l();
#endif
    static bool             streamForcedToSpeaker(int streamType);
    static bool             streamDisablesA2dp(int streamType);
    
    // Management of forced route to speaker for certain track types.
    enum force_speaker_command {
        ACTIVE_TRACK_ADDED = 0,
        ACTIVE_TRACK_REMOVED,
        CHECK_ROUTE_RESTORE_TIME,
        FORCE_ROUTE_RESTORE
    };
    void                    handleForcedSpeakerRoute(int command);
#ifdef WITH_A2DP
    void                    handleStreamDisablesA2dp_l(int command);
#endif

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
    private:
                            Client(const Client&);
                            Client& operator = (const Client&);
        sp<AudioFlinger>    mAudioFlinger;
        sp<MemoryDealer>    mMemoryDealer;
        pid_t               mPid;
    };


    class TrackHandle;
    class RecordHandle;
    class AudioRecordThread;

    
    // --- MixerThread ---
    class MixerThread : public Thread {
    public:
        
        // --- Track ---

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

                                TrackBase(const sp<MixerThread>& mixerThread,
                                        const sp<Client>& client,
                                        int streamType,
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

        protected:
            friend class MixerThread;
            friend class RecordHandle;
            friend class AudioRecordThread;

                                TrackBase(const TrackBase&);
                                TrackBase& operator = (const TrackBase&);

            virtual status_t getNextBuffer(AudioBufferProvider::Buffer* buffer) = 0;
            virtual void releaseBuffer(AudioBufferProvider::Buffer* buffer);

            audio_track_cblk_t* cblk() const {
                return mCblk;
            }

            int type() const {
                return mStreamType;
            }

            int format() const {
                return mFormat;
            }

            int channelCount() const ;

            int sampleRate() const;

            void* getBuffer(uint32_t offset, uint32_t frames) const;

            int name() const {
                return mName;
            }

            bool isStopped() const {
                return mState == STOPPED;
            }

            bool isTerminated() const {
                return mState == TERMINATED;
            }

            bool step();
            void reset();

            sp<MixerThread>     mMixerThread;
            sp<Client>          mClient;
            sp<IMemory>         mCblkMemory;
            audio_track_cblk_t* mCblk;
            int                 mStreamType;
            void*               mBuffer;
            void*               mBufferEnd;
            uint32_t            mFrameCount;
            int                 mName;
            // we don't really need a lock for these
            int                 mState;
            int                 mClientTid;
            uint8_t             mFormat;
            uint32_t            mFlags;
        };

        // playback track
        class Track : public TrackBase {
        public:
                                Track(  const sp<MixerThread>& mixerThread,
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

        protected:
            friend class MixerThread;
            friend class AudioFlinger;
            friend class AudioFlinger::TrackHandle;

                                Track(const Track&);
                                Track& operator = (const Track&);

            virtual status_t getNextBuffer(AudioBufferProvider::Buffer* buffer);

            bool isMuted() const {
                return (mMute || mMixerThread->mStreamTypes[mStreamType].mute);
            }

            bool isPausing() const {
                return mState == PAUSING;
            }

            bool isPaused() const {
                return mState == PAUSED;
            }

            bool isReady() const;

            void setPaused() { mState = PAUSED; }
            void reset();

            // we don't really need a lock for these
            float               mVolume[2];
            volatile bool       mMute;
            // FILLED state is used for suppressing volume ramp at begin of playing
            enum {FS_FILLING, FS_FILLED, FS_ACTIVE};
            mutable uint8_t     mFillingUpStatus;
            int8_t              mRetryCount;
            sp<IMemory>         mSharedBuffer;
            bool                mResetDone;
        };  // end of Track

        // record track
        class RecordTrack : public TrackBase {
        public:
                                RecordTrack(const sp<MixerThread>& mixerThread,
                                        const sp<Client>& client,
                                        int streamType,
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

        private:
            friend class AudioFlinger;
            friend class AudioFlinger::RecordHandle;
            friend class AudioFlinger::AudioRecordThread;
            friend class MixerThread;

                                RecordTrack(const Track&);
                                RecordTrack& operator = (const Track&);

            virtual status_t getNextBuffer(AudioBufferProvider::Buffer* buffer);

            bool                mOverflow;
        };

        // playback track
        class OutputTrack : public Track {
        public:
            
            class Buffer: public AudioBufferProvider::Buffer {
            public:
                int16_t *mBuffer;
            };
            
                                OutputTrack(  const sp<MixerThread>& mixerThread,
                                        uint32_t sampleRate,
                                        int format,
                                        int channelCount,
                                        int frameCount);
                                ~OutputTrack();

            virtual status_t    start();
            virtual void        stop();
                    void        write(int16_t* data, uint32_t frames);
                    bool        bufferQueueEmpty() { return (mBufferQueue.size() == 0) ? true : false; }

        private:

            status_t            obtainBuffer(AudioBufferProvider::Buffer* buffer);
            void                clearBufferQueue();
            
            sp<MixerThread>             mOutputMixerThread;
            Vector < Buffer* >          mBufferQueue;
            AudioBufferProvider::Buffer mOutBuffer;
            uint32_t                    mFramesWritten;
            
         };  // end of OutputTrack

        MixerThread (const sp<AudioFlinger>& audioFlinger, AudioStreamOut* output, int outputType);
        virtual             ~MixerThread();

        virtual     status_t    dump(int fd, const Vector<String16>& args);

        // Thread virtuals
        virtual     bool        threadLoop();
        virtual     status_t    readyToRun();
        virtual     void        onFirstRef();

        virtual     uint32_t    sampleRate() const;
        virtual     int         channelCount() const;
        virtual     int         format() const;
        virtual     size_t      frameCount() const;
        virtual     uint32_t    latency() const;

        virtual     status_t    setMasterVolume(float value);
        virtual     status_t    setMasterMute(bool muted);

        virtual     float       masterVolume() const;
        virtual     bool        masterMute() const;

        virtual     status_t    setStreamVolume(int stream, float value);
        virtual     status_t    setStreamMute(int stream, bool muted);

        virtual     float       streamVolume(int stream) const;
        virtual     bool        streamMute(int stream) const;

                    bool        isMusicActive() const;
        
                    
                    sp<Track>   createTrack_l(
                                    const sp<AudioFlinger::Client>& client,
                                    int streamType,
                                    uint32_t sampleRate,
                                    int format,
                                    int channelCount,
                                    int frameCount,
                                    const sp<IMemory>& sharedBuffer,
                                    status_t *status);
                    
                    void        getTracks_l(SortedVector < sp<Track> >& tracks,
                                          SortedVector < wp<Track> >& activeTracks);
                    void        putTracks_l(SortedVector < sp<Track> >& tracks,
                                          SortedVector < wp<Track> >& activeTracks);
                    void        setOuputTrack(OutputTrack *track) { mOutputTrack = track; }
                    
        struct  stream_type_t {
            stream_type_t()
                :   volume(1.0f),
                    mute(false)
            {
            }
            float       volume;
            bool        mute;
        };

    private:


        friend class AudioFlinger;
        friend class Track;
        friend class TrackBase;
        friend class RecordTrack;
        
        MixerThread(const Client&);
        MixerThread& operator = (const MixerThread&);
  
        status_t    addTrack_l(const sp<Track>& track);
        void        removeTrack_l(wp<Track> track, int name);
        void        destroyTrack_l(const sp<Track>& track);
        int         getTrackName_l();
        void        deleteTrackName_l(int name);
        void        addActiveTrack_l(const wp<Track>& t);
        void        removeActiveTrack_l(const wp<Track>& t);
        size_t      getOutputFrameCount();

        status_t    dumpInternals(int fd, const Vector<String16>& args);
        status_t    dumpTracks(int fd, const Vector<String16>& args);
        
        sp<AudioFlinger>                mAudioFlinger;       
        SortedVector< wp<Track> >       mActiveTracks;
        SortedVector< sp<Track> >       mTracks;
        stream_type_t                   mStreamTypes[AudioSystem::NUM_STREAM_TYPES];
        AudioMixer*                     mAudioMixer;
        AudioStreamOut*                 mOutput;
        int                             mOutputType;
        uint32_t                        mSampleRate;
        size_t                          mFrameCount;
        int                             mChannelCount;
        int                             mFormat;
        int16_t*                        mMixBuffer;
        float                           mMasterVolume;
        bool                            mMasterMute;
        nsecs_t                         mLastWriteTime;
        int                             mNumWrites;
        int                             mNumDelayedWrites;
        bool                            mStandby;
        bool                            mInWrite;
        sp <OutputTrack>                mOutputTrack;
    };

    
    friend class AudioBuffer;

    class TrackHandle : public android::BnAudioTrack {
    public:
                            TrackHandle(const sp<MixerThread::Track>& track);
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
        sp<MixerThread::Track> mTrack;
    };

    friend class Client;
    friend class MixerThread::Track;


                void        removeClient(pid_t pid);



    class RecordHandle : public android::BnAudioRecord {
    public:
        RecordHandle(const sp<MixerThread::RecordTrack>& recordTrack);
        virtual             ~RecordHandle();
        virtual status_t    start();
        virtual void        stop();
        virtual sp<IMemory> getCblk() const;
        virtual status_t onTransact(
            uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags);
    private:
        sp<MixerThread::RecordTrack> mRecordTrack;
    };

    // record thread
    class AudioRecordThread : public Thread
    {
    public:
        AudioRecordThread(AudioHardwareInterface* audioHardware, const sp<AudioFlinger>& audioFlinger);
        virtual             ~AudioRecordThread();
        virtual bool        threadLoop();
        virtual status_t    readyToRun() { return NO_ERROR; }
        virtual void        onFirstRef() {}

                status_t    start(MixerThread::RecordTrack* recordTrack);
                void        stop(MixerThread::RecordTrack* recordTrack);
                void        exit();
                status_t    dump(int fd, const Vector<String16>& args);

    private:
                AudioRecordThread();
                AudioHardwareInterface              *mAudioHardware;
                sp<AudioFlinger>                    mAudioFlinger;
                sp<MixerThread::RecordTrack>        mRecordTrack;
                Mutex                               mLock;
                Condition                           mWaitWorkCV;
                Condition                           mStopped;
                volatile bool                       mActive;
                status_t                            mStartStatus;
    };

    friend class AudioRecordThread;
    friend class MixerThread;

                status_t    startRecord(MixerThread::RecordTrack* recordTrack);
                void        stopRecord(MixerThread::RecordTrack* recordTrack);

    mutable     Mutex                               mHardwareLock;
    mutable     Mutex                               mLock;
    mutable     Condition                           mWaitWorkCV;

                DefaultKeyedVector< pid_t, wp<Client> >     mClients;

                sp<MixerThread>                     mA2dpMixerThread;
                sp<MixerThread>                     mHardwareMixerThread;
                AudioHardwareInterface*             mAudioHardware;
                AudioHardwareInterface*             mA2dpAudioInterface;
                sp<AudioRecordThread>               mAudioRecordThread;
                bool                                mA2dpEnabled;
                bool                                mNotifyA2dpChange;
    mutable     int                                 mHardwareStatus;
                SortedVector< wp<IBinder> >         mNotificationClients;
                int                                 mForcedSpeakerCount;
                int                                 mA2dpDisableCount;

                // true if A2DP should resume when mA2dpDisableCount returns to zero
                bool                                mA2dpSuppressed;
                uint32_t                            mSavedRoute;
                uint32_t                            mForcedRoute;
                nsecs_t                             mRouteRestoreTime;
                bool                                mMusicMuteSaved;
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_AUDIO_FLINGER_H
