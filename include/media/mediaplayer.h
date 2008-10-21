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

#ifndef ANDROID_MEDIAPLAYER_H
#define ANDROID_MEDIAPLAYER_H

#include <ui/Surface.h>
#include <media/AudioTrack.h>
#include <media/IMediaPlayerClient.h>
#include <media/IMediaPlayer.h>
#include <media/IMediaPlayerService.h>

namespace android {

enum media_event_type {
    MEDIA_NOP               = 0, // interface test message
    MEDIA_PREPARED          = 1,
    MEDIA_PLAYBACK_COMPLETE = 2,
    MEDIA_BUFFERING_UPDATE  = 3,
    MEDIA_SEEK_COMPLETE     = 4,
    MEDIA_ERROR             = 100,
};

typedef int media_error_type;
const media_error_type MEDIA_ERROR_UNKNOWN = 1;
const media_error_type MEDIA_ERROR_SERVER_DIED = 100;

enum media_player_states {
    MEDIA_PLAYER_STATE_ERROR        = 0,
    MEDIA_PLAYER_IDLE               = 1 << 0,
    MEDIA_PLAYER_INITIALIZED        = 1 << 1,
    MEDIA_PLAYER_PREPARING          = 1 << 2,
    MEDIA_PLAYER_PREPARED           = 1 << 3,
    MEDIA_PLAYER_STARTED            = 1 << 4,
    MEDIA_PLAYER_PAUSED             = 1 << 5,
    MEDIA_PLAYER_STOPPED            = 1 << 6,
    MEDIA_PLAYER_PLAYBACK_COMPLETE  = 1 << 7
};

// ----------------------------------------------------------------------------
// ref-counted object for callbacks
class MediaPlayerListener: public RefBase
{
public:
    virtual void notify(int msg, int ext1, int ext2) = 0;
};

class MediaPlayer : public BnMediaPlayerClient, public IBinder::DeathRecipient
{
public:
    MediaPlayer();
    ~MediaPlayer();

            void            disconnect();
            status_t        setDataSource(const char *url);
            status_t        setDataSource(int fd, int64_t offset, int64_t length);
            status_t        setVideoSurface(const sp<Surface>& surface);
            status_t        setListener(const sp<MediaPlayerListener>& listener);
            status_t        prepare();
            status_t        prepareAsync();
            status_t        start();
            status_t        stop();
            status_t        pause();
            bool            isPlaying();
            status_t        getVideoWidth(int *w);
            status_t        getVideoHeight(int *h);
            status_t        seekTo(int msec);
            status_t        getCurrentPosition(int *msec);
            status_t        getDuration(int *msec);
            status_t        reset();
            status_t        setAudioStreamType(int type);
            status_t        setLooping(int loop);
            status_t        setVolume(float leftVolume, float rightVolume);
            void            notify(int msg, int ext1, int ext2);
    static  sp<IMemory>     decode(const char* url, uint32_t *pSampleRate, int* pNumChannels);
    static  sp<IMemory>     decode(int fd, int64_t offset, int64_t length, uint32_t *pSampleRate, int* pNumChannels);

private:
            void            clear_l();
            status_t        seekTo_l(int msec);
            status_t        prepareAsync_l();
            status_t        getDuration_l(int *msec);
            status_t        setDataSource(const sp<IMediaPlayer>& player);

    static const sp<IMediaPlayerService>& getMediaPlayerService();
    virtual void binderDied(const wp<IBinder>& who);

    class DeathNotifier: public IBinder::DeathRecipient
    {
    public:
                DeathNotifier() {}
        virtual ~DeathNotifier();

        virtual void binderDied(const wp<IBinder>& who);
    };

    static sp<DeathNotifier> mDeathNotifier;

    sp<IMediaPlayer>            mPlayer;
    Mutex                       mLock;
    Mutex                       mNotifyLock;
    Condition                   mSignal;
    sp<MediaPlayerListener>     mListener;
    void*                       mCookie;
    media_player_states         mCurrentState;
    int                         mDuration;
    int                         mCurrentPosition;
    int                         mSeekPosition;
    bool                        mPrepareSync;
    status_t                    mPrepareStatus;
    int                         mStreamType;
    bool                        mLoop;
    float                       mLeftVolume;
    float                       mRightVolume;

    friend class DeathNotifier;

    static  Mutex               mServiceLock;
    static  sp<IMediaPlayerService>    mMediaPlayerService;
};

}; // namespace android

#endif // ANDROID_MEDIAPLAYER_H

