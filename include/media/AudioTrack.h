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

#ifndef ANDROID_AUDIOTRACK_H
#define ANDROID_AUDIOTRACK_H

#include <stdint.h>
#include <sys/types.h>

#include <media/IAudioFlinger.h>
#include <media/IAudioTrack.h>
#include <media/AudioSystem.h>

#include <utils/RefBase.h>
#include <utils/Errors.h>
#include <utils/IInterface.h>
#include <utils/IMemory.h>
#include <utils/threads.h>


namespace android {

// ----------------------------------------------------------------------------

class audio_track_cblk_t;

// ----------------------------------------------------------------------------

class AudioTrack
{
public: 

    enum stream_type {
        DEFAULT     =-1,
        VOICE_CALL  = 0,
        SYSTEM      = 1,
        RING        = 2,
        MUSIC       = 3,
        ALARM       = 4,
        NUM_STREAM_TYPES
    };

    enum channel_index {
        MONO   = 0,
        LEFT   = 0,
        RIGHT  = 1
    };

    /* Create Buffer on the stack and pass it to obtainBuffer()
     * and releaseBuffer().
     */

    class Buffer
    {
    public:
        enum {
            MUTE    = 0x00000001
        };
        uint32_t    flags;
        int         channelCount;
        int         format;
        size_t      frameCount;
        size_t      size;
        union {
            void*       raw;
            short*      i16;
            int8_t*     i8;
        };
    };

    /* Returns AudioFlinger's frame count. AudioTrack's buffers will
     * be created with this size.
     */
    static  size_t      frameCount();

    /* As a convenience, if a callback is supplied, a handler thread
     * is automatically created with the appropriate priority. This thread
     * invokes the callback when a new buffer becomes availlable.
     */
    typedef void (*callback_t)(void* user, const Buffer& info);

    /* Constructs an uninitialized AudioTrack. No connection with
     * AudioFlinger takes place.
     */
                        AudioTrack();
                        
    /* Creates an audio track and registers it with AudioFlinger.
     * Once created, the track needs to be started before it can be used.
     * Unspecified values are set to the audio hardware's current
     * values.
     */
     
                        AudioTrack( int streamType,
                                    uint32_t sampleRate = 0,
                                    int format          = 0,
                                    int channelCount    = 0,
                                    int bufferCount     = 0,
                                    uint32_t flags      = 0,
                                    callback_t cbf = 0, void* user = 0);


    /* Terminates the AudioTrack and unregisters it from AudioFlinger.
     * Also destroys all resources assotiated with the AudioTrack.
     */ 
                        ~AudioTrack();


    /* Initialize an uninitialized AudioTrack. */
            status_t    set(int streamType      =-1,
                            uint32_t sampleRate = 0,
                            int format          = 0,
                            int channelCount    = 0,
                            int bufferCount     = 0,
                            uint32_t flags      = 0,
                            callback_t cbf = 0, void* user = 0);
        

    /* Result of constructing the AudioTrack. This must be checked
     * before using any AudioTrack API (except for set()), using
     * an uninitialized AudoiTrack prduces undefined results.
     */
            status_t    initCheck() const;

    /* Returns this track's latency in nanoseconds or framecount.
     * This only includes the latency due to the fill buffer size.
     * In particular, the hardware or driver latencies are not accounted.
     */
            nsecs_t     latency() const;

   /* getters, see constructor */ 
            
            int         streamType() const;
            uint32_t    sampleRate() const;
            int         format() const;
            int         channelCount() const;
            int         bufferCount() const;


    /* After it's created the track is not active. Call start() to
     * make it active. If set, the callback will start being called.
     */
            void        start();

    /* Stop a track. If set, the callback will cease being called and
     * obtainBuffer returns STOPPED. Note that obtainBuffer() still works
     * and will fill up buffers until the pool is exhausted.
     */
            void        stop();
            bool        stopped() const;

    /* flush a stopped track. All pending buffers are discarded.
     * This function has no effect if the track is not stoped.
     */
            void        flush();

    /* Pause a track. If set, the callback will cease being called and
     * obtainBuffer returns STOPPED. Note that obtainBuffer() still works
     * and will fill up buffers until the pool is exhausted.
     */
            void        pause();

    /* mute or unmutes this track.
     * While mutted, the callback, if set, is still called.
     */
            void        mute(bool);
            bool        muted() const;


    /* set volume for this track, mostly used for games' sound effects
     */
            void        setVolume(float left, float right);
            void        getVolume(float* left, float* right);

    /* set sample rate for this track, mostly used for games' sound effects
     */
            void        setSampleRate(int sampleRate);
            uint32_t    getSampleRate();

    /* obtains a buffer of "frameCount" frames. The buffer must be
     * filled entirely. If the track is stopped, obtainBuffer() returns
     * STOPPED instead of NO_ERROR as long as there are buffers availlable,
     * at which point NO_MORE_BUFFERS is returned.
     * Buffers will be returned until the pool (buffercount())
     * is exhausted, at which point obtainBuffer() will either block 
     * or return WOULD_BLOCK depending on the value of the "blocking"
     * parameter. 
     */
     
        enum {
            NO_MORE_BUFFERS = 0x80000001,
            STOPPED = 1
        };
     
            status_t    obtainBuffer(Buffer* audioBuffer, bool blocking);
            void        releaseBuffer(Buffer* audioBuffer);


    /* As a convenience we provide a write() interface to the audio buffer.
     * This is implemented on top of lockBuffer/unlockBuffer. For best
     * performance
     * 
     */
            ssize_t     write(const void* buffer, size_t size);
            
    /*
     * Dumps the state of an audio track.
     */
            status_t dump(int fd, const Vector<String16>& args) const;

private:
    /* copying audio tracks is not allowed */
                        AudioTrack(const AudioTrack& other);
            AudioTrack& operator = (const AudioTrack& other);

    /* a small internal class to handle the callback */
    class AudioTrackThread : public Thread
    {
    public:
        AudioTrackThread(AudioTrack& receiver);
    private:
        friend class AudioTrack;
        virtual bool        threadLoop();
        virtual status_t    readyToRun();
        virtual void        onFirstRef();
        AudioTrack& mReceiver;
        Mutex       mLock;
    };
    
            bool processAudioBuffer(const sp<AudioTrackThread>& thread);

    sp<IAudioFlinger>       mAudioFlinger;
    sp<IAudioTrack>         mAudioTrack;
    sp<IMemory>             mCblkMemory;
    sp<AudioTrackThread>    mAudioTrackThread;
    
    float                   mVolume[2];
    uint32_t                mSampleRate;
    size_t                  mFrameCount;

    audio_track_cblk_t*     mCblk;
    uint8_t                 mStreamType;
    uint8_t                 mFormat;
    uint8_t                 mBufferCount;
    uint8_t                 mChannelCount   : 4;
    uint8_t                 mMuted          : 1;
    uint8_t                 mReserved       : 2;
    status_t                mStatus;
    nsecs_t                 mLatency;

    volatile int32_t        mActive;

    callback_t              mCbf;
    void*                   mUserData;
    
    AudioTrack::Buffer      mAudioBuffer;
    size_t                  mPosition;

    uint32_t                mReservedFBC[4];
};


}; // namespace android

#endif // ANDROID_AUDIOTRACK_H
