/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef AUDIORECORD_H_
#define AUDIORECORD_H_

#include <stdint.h>
#include <sys/types.h>

#include <media/IAudioFlinger.h>
#include <media/IAudioRecord.h>
#include <media/AudioTrack.h>

#include <utils/RefBase.h>
#include <utils/Errors.h>
#include <utils/IInterface.h>
#include <utils/IMemory.h>
#include <utils/threads.h>


namespace android {

// ----------------------------------------------------------------------------

class AudioRecord
{
public: 
    
    enum stream_type {
        DEFAULT_INPUT   =-1,
        MIC_INPUT       = 0,
        NUM_STREAM_TYPES
    };
    
    static const int DEFAULT_SAMPLE_RATE = 8000; 
    
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

    /* These are static methods to control the system-wide AudioFlinger
     * only privileged processes can have access to them
     */

//    static status_t setMasterMute(bool mute);

    /* Returns AudioFlinger's frame count. AudioRecord's buffers will
     * be created with this size.
     */
    static  size_t      frameCount();

    /* As a convenience, if a callback is supplied, a handler thread
     * is automatically created with the appropriate priority. This thread
     * invokes the callback when a new buffer becomes availlable.
     */
    typedef bool (*callback_t)(void* user, const Buffer& info);

    /* Constructs an uninitialized AudioRecord. No connection with
     * AudioFlinger takes place.
     */
                        AudioRecord();
                        
    /* Creates an AudioRecord track and registers it with AudioFlinger.
     * Once created, the track needs to be started before it can be used.
     * Unspecified values are set to the audio hardware's current
     * values.
     */
     
                        AudioRecord(int streamType      = 0,
                                    uint32_t sampleRate = 0,
                                    int format          = 0,
                                    int channelCount    = 0,
                                    int bufferCount     = 0,
                                    uint32_t flags      = 0,
                                    callback_t cbf = 0, void* user = 0);


    /* Terminates the AudioRecord and unregisters it from AudioFlinger.
     * Also destroys all resources assotiated with the AudioRecord.
     */ 
                        ~AudioRecord();


    /* Initialize an uninitialized AudioRecord. */
            status_t    set(int streamType      = 0,
                            uint32_t sampleRate = 0,
                            int format          = 0,
                            int channelCount    = 0,
                            int bufferCount     = 0,
                            uint32_t flags      = 0,
                            callback_t cbf = 0, void* user = 0);
        

    /* Result of constructing the AudioRecord. This must be checked
     * before using any AudioRecord API (except for set()), using
     * an uninitialized AudioRecord prduces undefined results.
     */
            status_t    initCheck() const;

    /* Returns this track's latency in nanoseconds or framecount.
     * This only includes the latency due to the fill buffer size.
     * In particular, the hardware or driver latencies are not accounted.
     */
            nsecs_t     latency() const;

   /* getters, see constructor */ 
            
            uint32_t    sampleRate() const;
            int         format() const;
            int         channelCount() const;
            int         bufferCount() const;


    /* After it's created the track is not active. Call start() to
     * make it active. If set, the callback will start being called.
     */
            status_t    start();

    /* Stop a track. If set, the callback will cease being called and
     * obtainBuffer returns STOPPED. Note that obtainBuffer() still works
     * and will fill up buffers until the pool is exhausted.
     */
            status_t    stop();
            bool        stopped() const;

    /* get sample rate for this track
     */
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


    /* As a convenience we provide a read() interface to the audio buffer.
     * This is implemented on top of lockBuffer/unlockBuffer. 
     */
            ssize_t     read(void* buffer, size_t size);

private:
    /* copying audio tracks is not allowed */
                        AudioRecord(const AudioRecord& other);
            AudioRecord& operator = (const AudioRecord& other);

    /* a small internal class to handle the callback */
    class ClientRecordThread : public Thread
    {
    public:
        ClientRecordThread(AudioRecord& receiver);
    private:
        friend class AudioRecord;
        virtual bool        threadLoop();
        virtual status_t    readyToRun() { return NO_ERROR; }
        virtual void        onFirstRef() {}
        AudioRecord& mReceiver;
    };
    
            bool processAudioBuffer(const sp<ClientRecordThread>& thread);

    sp<IAudioFlinger>       mAudioFlinger;
    sp<IAudioRecord>        mAudioRecord;
    sp<IMemory>             mCblkMemory;
    sp<ClientRecordThread>  mClientRecordThread;
    Mutex                   mRecordThreadLock;
    
    uint32_t                mSampleRate;
    size_t                  mFrameCount;

    audio_track_cblk_t*     mCblk;
    uint8_t                 mFormat;
    uint8_t                 mBufferCount;
    uint8_t                 mChannelCount   : 4;
    uint8_t                 mReserved       : 3;
    status_t                mStatus;
    nsecs_t                 mLatency;

    volatile int32_t        mActive;

    callback_t              mCbf;
    void*                   mUserData;
    
    AudioRecord::Buffer      mAudioBuffer;
    size_t                  mPosition;

    uint32_t                mReservedFBC[4];
};

}; // namespace android

#endif /*AUDIORECORD_H_*/
