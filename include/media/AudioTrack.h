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
#include <binder/IInterface.h>
#include <binder/IMemory.h>
#include <utils/threads.h>

namespace android {

// ----------------------------------------------------------------------------

class audio_track_cblk_t;

// ----------------------------------------------------------------------------

class AudioTrack : virtual public RefBase
{
public:
    enum channel_index {
        MONO   = 0,
        LEFT   = 0,
        RIGHT  = 1
    };

    /* Events used by AudioTrack callback function (audio_track_cblk_t).
     */
    enum event_type {
        EVENT_MORE_DATA = 0,        // Request to write more data to PCM buffer.
        EVENT_UNDERRUN = 1,         // PCM buffer underrun occured.
        EVENT_LOOP_END = 2,         // Sample loop end was reached; playback restarted from loop start if loop count was not 0.
        EVENT_MARKER = 3,           // Playback head is at the specified marker position (See setMarkerPosition()).
        EVENT_NEW_POS = 4,          // Playback head is at a new position (See setPositionUpdatePeriod()).
        EVENT_BUFFER_END = 5        // Playback head is at the end of the buffer.
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
        audio_format_t format; // but AUDIO_FORMAT_PCM_8_BIT -> AUDIO_FORMAT_PCM_16_BIT
        // accessed directly by WebKit ANP callback
        int         channelCount; // will be removed in the future, do not use
        size_t      frameCount;
        size_t      size;
        union {
            void*       raw;
            short*      i16;    // signed 16-bit
            int8_t*     i8;     // unsigned 8-bit, offset by 0x80
        };
    };


    /* As a convenience, if a callback is supplied, a handler thread
     * is automatically created with the appropriate priority. This thread
     * invokes the callback when a new buffer becomes available or an underrun condition occurs.
     * Parameters:
     *
     * event:   type of event notified (see enum AudioTrack::event_type).
     * user:    Pointer to context for use by the callback receiver.
     * info:    Pointer to optional parameter according to event type:
     *          - EVENT_MORE_DATA: pointer to AudioTrack::Buffer struct. The callback must not write
     *          more bytes than indicated by 'size' field and update 'size' if less bytes are
     *          written.
     *          - EVENT_UNDERRUN: unused.
     *          - EVENT_LOOP_END: pointer to an int indicating the number of loops remaining.
     *          - EVENT_MARKER: pointer to an uint32_t containing the marker position in frames.
     *          - EVENT_NEW_POS: pointer to an uint32_t containing the new position in frames.
     *          - EVENT_BUFFER_END: unused.
     */

    typedef void (*callback_t)(int event, void* user, void *info);

    /* Returns the minimum frame count required for the successful creation of
     * an AudioTrack object.
     * Returned status (from utils/Errors.h) can be:
     *  - NO_ERROR: successful operation
     *  - NO_INIT: audio server or audio hardware not initialized
     */

     static status_t getMinFrameCount(int* frameCount,
                                      audio_stream_type_t streamType = AUDIO_STREAM_DEFAULT,
                                      uint32_t sampleRate = 0);

    /* Constructs an uninitialized AudioTrack. No connection with
     * AudioFlinger takes place.
     */
                        AudioTrack();

    /* Creates an audio track and registers it with AudioFlinger.
     * Once created, the track needs to be started before it can be used.
     * Unspecified values are set to the audio hardware's current
     * values.
     *
     * Parameters:
     *
     * streamType:         Select the type of audio stream this track is attached to
     *                     (e.g. AUDIO_STREAM_MUSIC).
     * sampleRate:         Track sampling rate in Hz.
     * format:             Audio format (e.g AUDIO_FORMAT_PCM_16_BIT for signed
     *                     16 bits per sample).
     * channelMask:        Channel mask: see audio_channels_t.
     * frameCount:         Total size of track PCM buffer in frames. This defines the
     *                     latency of the track.
     * flags:              Reserved for future use.
     * cbf:                Callback function. If not null, this function is called periodically
     *                     to request new PCM data.
     * user:               Context for use by the callback receiver.
     * notificationFrames: The callback function is called each time notificationFrames PCM
     *                     frames have been consumed from track input buffer.
     * sessionId:          Specific session ID, or zero to use default.
     */

                        AudioTrack( audio_stream_type_t streamType,
                                    uint32_t sampleRate  = 0,
                                    audio_format_t format = AUDIO_FORMAT_DEFAULT,
                                    int channelMask      = 0,
                                    int frameCount       = 0,
                                    uint32_t flags       = 0,
                                    callback_t cbf       = 0,
                                    void* user           = 0,
                                    int notificationFrames = 0,
                                    int sessionId = 0);

    /* Creates an audio track and registers it with AudioFlinger. With this constructor,
     * the PCM data to be rendered by AudioTrack is passed in a shared memory buffer
     * identified by the argument sharedBuffer. This prototype is for static buffer playback.
     * PCM data must be present in memory before the AudioTrack is started.
     * The write() and flush() methods are not supported in this case.
     * It is recommended to pass a callback function to be notified of playback end by an
     * EVENT_UNDERRUN event.
     */

                        AudioTrack( audio_stream_type_t streamType,
                                    uint32_t sampleRate = 0,
                                    audio_format_t format = AUDIO_FORMAT_DEFAULT,
                                    int channelMask     = 0,
                                    const sp<IMemory>& sharedBuffer = 0,
                                    uint32_t flags      = 0,
                                    callback_t cbf      = 0,
                                    void* user          = 0,
                                    int notificationFrames = 0,
                                    int sessionId = 0);

    /* Terminates the AudioTrack and unregisters it from AudioFlinger.
     * Also destroys all resources associated with the AudioTrack.
     */
                        ~AudioTrack();


    /* Initialize an uninitialized AudioTrack.
     * Returned status (from utils/Errors.h) can be:
     *  - NO_ERROR: successful initialization
     *  - INVALID_OPERATION: AudioTrack is already initialized
     *  - BAD_VALUE: invalid parameter (channels, format, sampleRate...)
     *  - NO_INIT: audio server or audio hardware not initialized
     * */
            status_t    set(audio_stream_type_t streamType = AUDIO_STREAM_DEFAULT,
                            uint32_t sampleRate = 0,
                            audio_format_t format = AUDIO_FORMAT_DEFAULT,
                            int channelMask     = 0,
                            int frameCount      = 0,
                            uint32_t flags      = 0,
                            callback_t cbf      = 0,
                            void* user          = 0,
                            int notificationFrames = 0,
                            const sp<IMemory>& sharedBuffer = 0,
                            bool threadCanCallJava = false,
                            int sessionId = 0);


    /* Result of constructing the AudioTrack. This must be checked
     * before using any AudioTrack API (except for set()), because using
     * an uninitialized AudioTrack produces undefined results.
     * See set() method above for possible return codes.
     */
            status_t    initCheck() const;

    /* Returns this track's estimated latency in milliseconds.
     * This includes the latency due to AudioTrack buffer size, AudioMixer (if any)
     * and audio hardware driver.
     */
            uint32_t     latency() const;

    /* getters, see constructor */

            audio_stream_type_t streamType() const;
            audio_format_t format() const;
            int         channelCount() const;
            uint32_t    frameCount() const;
            int         frameSize() const;
            sp<IMemory>& sharedBuffer();


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

    /* Flush a stopped track. All pending buffers are discarded.
     * This function has no effect if the track is not stopped.
     */
            void        flush();

    /* Pause a track. If set, the callback will cease being called and
     * obtainBuffer returns STOPPED. Note that obtainBuffer() still works
     * and will fill up buffers until the pool is exhausted.
     */
            void        pause();

    /* Mute or unmute this track.
     * While muted, the callback, if set, is still called.
     */
            void        mute(bool);
            bool        muted() const;

    /* Set volume for this track, mostly used for games' sound effects
     * left and right volumes. Levels must be >= 0.0 and <= 1.0.
     */
            status_t    setVolume(float left, float right);
            void        getVolume(float* left, float* right);

    /* Set the send level for this track. An auxiliary effect should be attached
     * to the track with attachEffect(). Level must be >= 0.0 and <= 1.0.
     */
            status_t    setAuxEffectSendLevel(float level);
            void        getAuxEffectSendLevel(float* level);

    /* Set sample rate for this track, mostly used for games' sound effects
     */
            status_t    setSampleRate(int sampleRate);
            uint32_t    getSampleRate();

    /* Enables looping and sets the start and end points of looping.
     *
     * Parameters:
     *
     * loopStart:   loop start expressed as the number of PCM frames played since AudioTrack start.
     * loopEnd:     loop end expressed as the number of PCM frames played since AudioTrack start.
     * loopCount:   number of loops to execute. Calling setLoop() with loopCount == 0 cancels any
     *              pending or active loop. loopCount = -1 means infinite looping.
     *
     * For proper operation the following condition must be respected:
     *          (loopEnd-loopStart) <= framecount()
     */
            status_t    setLoop(uint32_t loopStart, uint32_t loopEnd, int loopCount);
            status_t    getLoop(uint32_t *loopStart, uint32_t *loopEnd, int *loopCount);

    /* Sets marker position. When playback reaches the number of frames specified, a callback with
     * event type EVENT_MARKER is called. Calling setMarkerPosition with marker == 0 cancels marker
     * notification callback.
     * If the AudioTrack has been opened with no callback function associated, the operation will fail.
     *
     * Parameters:
     *
     * marker:   marker position expressed in frames.
     *
     * Returned status (from utils/Errors.h) can be:
     *  - NO_ERROR: successful operation
     *  - INVALID_OPERATION: the AudioTrack has no callback installed.
     */
            status_t    setMarkerPosition(uint32_t marker);
            status_t    getMarkerPosition(uint32_t *marker);


    /* Sets position update period. Every time the number of frames specified has been played,
     * a callback with event type EVENT_NEW_POS is called.
     * Calling setPositionUpdatePeriod with updatePeriod == 0 cancels new position notification
     * callback.
     * If the AudioTrack has been opened with no callback function associated, the operation will fail.
     *
     * Parameters:
     *
     * updatePeriod:  position update notification period expressed in frames.
     *
     * Returned status (from utils/Errors.h) can be:
     *  - NO_ERROR: successful operation
     *  - INVALID_OPERATION: the AudioTrack has no callback installed.
     */
            status_t    setPositionUpdatePeriod(uint32_t updatePeriod);
            status_t    getPositionUpdatePeriod(uint32_t *updatePeriod);

    /* Sets playback head position within AudioTrack buffer. The new position is specified
     * in number of frames.
     * This method must be called with the AudioTrack in paused or stopped state.
     * Note that the actual position set is <position> modulo the AudioTrack buffer size in frames.
     * Therefore using this method makes sense only when playing a "static" audio buffer
     * as opposed to streaming.
     * The getPosition() method on the other hand returns the total number of frames played since
     * playback start.
     *
     * Parameters:
     *
     * position:  New playback head position within AudioTrack buffer.
     *
     * Returned status (from utils/Errors.h) can be:
     *  - NO_ERROR: successful operation
     *  - INVALID_OPERATION: the AudioTrack is not stopped.
     *  - BAD_VALUE: The specified position is beyond the number of frames present in AudioTrack buffer
     */
            status_t    setPosition(uint32_t position);
            status_t    getPosition(uint32_t *position);

    /* Forces AudioTrack buffer full condition. When playing a static buffer, this method avoids
     * rewriting the buffer before restarting playback after a stop.
     * This method must be called with the AudioTrack in paused or stopped state.
     *
     * Returned status (from utils/Errors.h) can be:
     *  - NO_ERROR: successful operation
     *  - INVALID_OPERATION: the AudioTrack is not stopped.
     */
            status_t    reload();

    /* Returns a handle on the audio output used by this AudioTrack.
     *
     * Parameters:
     *  none.
     *
     * Returned value:
     *  handle on audio hardware output
     */
            audio_io_handle_t    getOutput();

    /* Returns the unique session ID associated with this track.
     *
     * Parameters:
     *  none.
     *
     * Returned value:
     *  AudioTrack session ID.
     */
            int    getSessionId();

    /* Attach track auxiliary output to specified effect. Use effectId = 0
     * to detach track from effect.
     *
     * Parameters:
     *
     * effectId:  effectId obtained from AudioEffect::id().
     *
     * Returned status (from utils/Errors.h) can be:
     *  - NO_ERROR: successful operation
     *  - INVALID_OPERATION: the effect is not an auxiliary effect.
     *  - BAD_VALUE: The specified effect ID is invalid
     */
            status_t    attachAuxEffect(int effectId);

    /* Obtains a buffer of "frameCount" frames. The buffer must be
     * filled entirely. If the track is stopped, obtainBuffer() returns
     * STOPPED instead of NO_ERROR as long as there are buffers available,
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

            status_t    obtainBuffer(Buffer* audioBuffer, int32_t waitCount);
            void        releaseBuffer(Buffer* audioBuffer);

    /* As a convenience we provide a write() interface to the audio buffer.
     * This is implemented on top of lockBuffer/unlockBuffer. For best
     * performance use callbacks. Return actual number of bytes written.
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
        AudioTrackThread(AudioTrack& receiver, bool bCanCallJava = false);
    private:
        friend class AudioTrack;
        virtual bool        threadLoop();
        virtual status_t    readyToRun();
        virtual void        onFirstRef();
        AudioTrack& mReceiver;
        Mutex       mLock;
    };

            bool processAudioBuffer(const sp<AudioTrackThread>& thread);
            status_t createTrack_l(audio_stream_type_t streamType,
                                 uint32_t sampleRate,
                                 audio_format_t format,
                                 uint32_t channelMask,
                                 int frameCount,
                                 uint32_t flags,
                                 const sp<IMemory>& sharedBuffer,
                                 audio_io_handle_t output,
                                 bool enforceFrameCount);
            void flush_l();
            status_t setLoop_l(uint32_t loopStart, uint32_t loopEnd, int loopCount);
            audio_io_handle_t getOutput_l();
            status_t restoreTrack_l(audio_track_cblk_t*& cblk, bool fromStart);
            bool stopped_l() const { return !mActive; }

    sp<IAudioTrack>         mAudioTrack;
    sp<IMemory>             mCblkMemory;
    sp<AudioTrackThread>    mAudioTrackThread;

    float                   mVolume[2];
    float                   mSendLevel;
    uint32_t                mFrameCount;

    audio_track_cblk_t*     mCblk;
    audio_format_t          mFormat;
    audio_stream_type_t     mStreamType;
    uint8_t                 mChannelCount;
    uint8_t                 mMuted;
    uint8_t                 mReserved;
    uint32_t                mChannelMask;
    status_t                mStatus;
    uint32_t                mLatency;

    bool                    mActive;                // protected by mLock

    callback_t              mCbf;
    void*                   mUserData;
    uint32_t                mNotificationFramesReq; // requested number of frames between each notification callback
    uint32_t                mNotificationFramesAct; // actual number of frames between each notification callback
    sp<IMemory>             mSharedBuffer;
    int                     mLoopCount;
    uint32_t                mRemainingFrames;
    uint32_t                mMarkerPosition;
    bool                    mMarkerReached;
    uint32_t                mNewPosition;
    uint32_t                mUpdatePeriod;
    bool                    mFlushed; // FIXME will be made obsolete by making flush() synchronous
    uint32_t                mFlags;
    int                     mSessionId;
    int                     mAuxEffectId;
    mutable Mutex           mLock;
    status_t                mRestoreStatus;
    int                     mPreviousPriority;          // before start()
    int                     mPreviousSchedulingGroup;
};


}; // namespace android

#endif // ANDROID_AUDIOTRACK_H
