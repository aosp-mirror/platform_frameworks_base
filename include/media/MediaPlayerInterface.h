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

#ifndef ANDROID_MEDIAPLAYERINTERFACE_H
#define ANDROID_MEDIAPLAYERINTERFACE_H

#ifdef __cplusplus

#include <sys/types.h>
#include <utils/Errors.h>
#include <utils/KeyedVector.h>
#include <utils/String8.h>
#include <utils/RefBase.h>

#include <media/mediaplayer.h>
#include <media/AudioSystem.h>
#include <media/Metadata.h>

namespace android {

class Parcel;
class Surface;
class ISurfaceTexture;

template<typename T> class SortedVector;

enum player_type {
    PV_PLAYER = 1,
    SONIVOX_PLAYER = 2,
    STAGEFRIGHT_PLAYER = 3,
    NU_PLAYER = 4,
    // Test players are available only in the 'test' and 'eng' builds.
    // The shared library with the test player is passed passed as an
    // argument to the 'test:' url in the setDataSource call.
    TEST_PLAYER = 5,
};


#define DEFAULT_AUDIOSINK_BUFFERCOUNT 4
#define DEFAULT_AUDIOSINK_BUFFERSIZE 1200
#define DEFAULT_AUDIOSINK_SAMPLERATE 44100


// callback mechanism for passing messages to MediaPlayer object
typedef void (*notify_callback_f)(void* cookie,
        int msg, int ext1, int ext2, const Parcel *obj);

// abstract base class - use MediaPlayerInterface
class MediaPlayerBase : public RefBase
{
public:
    // AudioSink: abstraction layer for audio output
    class AudioSink : public RefBase {
    public:
        // Callback returns the number of bytes actually written to the buffer.
        typedef size_t (*AudioCallback)(
                AudioSink *audioSink, void *buffer, size_t size, void *cookie);

        virtual             ~AudioSink() {}
        virtual bool        ready() const = 0; // audio output is open and ready
        virtual bool        realtime() const = 0; // audio output is real-time output
        virtual ssize_t     bufferSize() const = 0;
        virtual ssize_t     frameCount() const = 0;
        virtual ssize_t     channelCount() const = 0;
        virtual ssize_t     frameSize() const = 0;
        virtual uint32_t    latency() const = 0;
        virtual float       msecsPerFrame() const = 0;
        virtual status_t    getPosition(uint32_t *position) = 0;
        virtual int         getSessionId() = 0;

        // If no callback is specified, use the "write" API below to submit
        // audio data.
        virtual status_t    open(
                uint32_t sampleRate, int channelCount,
                int format=AUDIO_FORMAT_PCM_16_BIT,
                int bufferCount=DEFAULT_AUDIOSINK_BUFFERCOUNT,
                AudioCallback cb = NULL,
                void *cookie = NULL) = 0;

        virtual void        start() = 0;
        virtual ssize_t     write(const void* buffer, size_t size) = 0;
        virtual void        stop() = 0;
        virtual void        flush() = 0;
        virtual void        pause() = 0;
        virtual void        close() = 0;
    };

                        MediaPlayerBase() : mCookie(0), mNotify(0) {}
    virtual             ~MediaPlayerBase() {}
    virtual status_t    initCheck() = 0;
    virtual bool        hardwareOutput() = 0;

    virtual status_t    setUID(uid_t uid) {
        return INVALID_OPERATION;
    }

    virtual status_t    setDataSource(
            const char *url,
            const KeyedVector<String8, String8> *headers = NULL) = 0;

    virtual status_t    setDataSource(int fd, int64_t offset, int64_t length) = 0;

    virtual status_t    setDataSource(const sp<IStreamSource> &source) {
        return INVALID_OPERATION;
    }

    // pass the buffered ISurfaceTexture to the media player service
    virtual status_t    setVideoSurfaceTexture(
                                const sp<ISurfaceTexture>& surfaceTexture) = 0;

    virtual status_t    prepare() = 0;
    virtual status_t    prepareAsync() = 0;
    virtual status_t    start() = 0;
    virtual status_t    stop() = 0;
    virtual status_t    pause() = 0;
    virtual bool        isPlaying() = 0;
    virtual status_t    seekTo(int msec) = 0;
    virtual status_t    getCurrentPosition(int *msec) = 0;
    virtual status_t    getDuration(int *msec) = 0;
    virtual status_t    reset() = 0;
    virtual status_t    setLooping(int loop) = 0;
    virtual player_type playerType() = 0;
    virtual status_t    setParameter(int key, const Parcel &request) = 0;
    virtual status_t    getParameter(int key, Parcel *reply) = 0;

    // Invoke a generic method on the player by using opaque parcels
    // for the request and reply.
    //
    // @param request Parcel that is positioned at the start of the
    //                data sent by the java layer.
    // @param[out] reply Parcel to hold the reply data. Cannot be null.
    // @return OK if the call was successful.
    virtual status_t    invoke(const Parcel& request, Parcel *reply) = 0;

    // The Client in the MetadataPlayerService calls this method on
    // the native player to retrieve all or a subset of metadata.
    //
    // @param ids SortedList of metadata ID to be fetch. If empty, all
    //            the known metadata should be returned.
    // @param[inout] records Parcel where the player appends its metadata.
    // @return OK if the call was successful.
    virtual status_t    getMetadata(const media::Metadata::Filter& ids,
                                    Parcel *records) {
        return INVALID_OPERATION;
    };

    void        setNotifyCallback(
            void* cookie, notify_callback_f notifyFunc) {
        Mutex::Autolock autoLock(mNotifyLock);
        mCookie = cookie; mNotify = notifyFunc;
    }

    void        sendEvent(int msg, int ext1=0, int ext2=0,
                          const Parcel *obj=NULL) {
        Mutex::Autolock autoLock(mNotifyLock);
        if (mNotify) mNotify(mCookie, msg, ext1, ext2, obj);
    }

    virtual status_t dump(int fd, const Vector<String16> &args) const {
        return INVALID_OPERATION;
    }

private:
    friend class MediaPlayerService;

    Mutex               mNotifyLock;
    void*               mCookie;
    notify_callback_f   mNotify;
};

// Implement this class for media players that use the AudioFlinger software mixer
class MediaPlayerInterface : public MediaPlayerBase
{
public:
    virtual             ~MediaPlayerInterface() { }
    virtual bool        hardwareOutput() { return false; }
    virtual void        setAudioSink(const sp<AudioSink>& audioSink) { mAudioSink = audioSink; }
protected:
    sp<AudioSink>       mAudioSink;
};

// Implement this class for media players that output audio directly to hardware
class MediaPlayerHWInterface : public MediaPlayerBase
{
public:
    virtual             ~MediaPlayerHWInterface() {}
    virtual bool        hardwareOutput() { return true; }
    virtual status_t    setVolume(float leftVolume, float rightVolume) = 0;
    virtual status_t    setAudioStreamType(int streamType) = 0;
};

}; // namespace android

#endif // __cplusplus


#endif // ANDROID_MEDIAPLAYERINTERFACE_H
