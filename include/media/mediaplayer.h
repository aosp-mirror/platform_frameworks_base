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

#include <binder/IMemory.h>
#include <media/IMediaPlayerClient.h>
#include <media/IMediaPlayer.h>
#include <media/IMediaDeathNotifier.h>

#include <utils/KeyedVector.h>
#include <utils/String8.h>

class ANativeWindow;

namespace android {

class Surface;
class ISurfaceTexture;

enum media_event_type {
    MEDIA_NOP               = 0, // interface test message
    MEDIA_PREPARED          = 1,
    MEDIA_PLAYBACK_COMPLETE = 2,
    MEDIA_BUFFERING_UPDATE  = 3,
    MEDIA_SEEK_COMPLETE     = 4,
    MEDIA_SET_VIDEO_SIZE    = 5,
    MEDIA_TIMED_TEXT        = 99,
    MEDIA_ERROR             = 100,
    MEDIA_INFO              = 200,
};

// Generic error codes for the media player framework.  Errors are fatal, the
// playback must abort.
//
// Errors are communicated back to the client using the
// MediaPlayerListener::notify method defined below.
// In this situation, 'notify' is invoked with the following:
//   'msg' is set to MEDIA_ERROR.
//   'ext1' should be a value from the enum media_error_type.
//   'ext2' contains an implementation dependant error code to provide
//          more details. Should default to 0 when not used.
//
// The codes are distributed as follow:
//   0xx: Reserved
//   1xx: Android Player errors. Something went wrong inside the MediaPlayer.
//   2xx: Media errors (e.g Codec not supported). There is a problem with the
//        media itself.
//   3xx: Runtime errors. Some extraordinary condition arose making the playback
//        impossible.
//
enum media_error_type {
    // 0xx
    MEDIA_ERROR_UNKNOWN = 1,
    // 1xx
    MEDIA_ERROR_SERVER_DIED = 100,
    // 2xx
    MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK = 200,
    // 3xx
};


// Info and warning codes for the media player framework.  These are non fatal,
// the playback is going on but there might be some user visible issues.
//
// Info and warning messages are communicated back to the client using the
// MediaPlayerListener::notify method defined below.  In this situation,
// 'notify' is invoked with the following:
//   'msg' is set to MEDIA_INFO.
//   'ext1' should be a value from the enum media_info_type.
//   'ext2' contains an implementation dependant info code to provide
//          more details. Should default to 0 when not used.
//
// The codes are distributed as follow:
//   0xx: Reserved
//   7xx: Android Player info/warning (e.g player lagging behind.)
//   8xx: Media info/warning (e.g media badly interleaved.)
//
enum media_info_type {
    // 0xx
    MEDIA_INFO_UNKNOWN = 1,
    // 7xx
    // The video is too complex for the decoder: it can't decode frames fast
    // enough. Possibly only the audio plays fine at this stage.
    MEDIA_INFO_VIDEO_TRACK_LAGGING = 700,
    // MediaPlayer is temporarily pausing playback internally in order to
    // buffer more data.
    MEDIA_INFO_BUFFERING_START = 701,
    // MediaPlayer is resuming playback after filling buffers.
    MEDIA_INFO_BUFFERING_END = 702,
    // Bandwidth in recent past
    MEDIA_INFO_NETWORK_BANDWIDTH = 703,

    // 8xx
    // Bad interleaving means that a media has been improperly interleaved or not
    // interleaved at all, e.g has all the video samples first then all the audio
    // ones. Video is playing but a lot of disk seek may be happening.
    MEDIA_INFO_BAD_INTERLEAVING = 800,
    // The media is not seekable (e.g live stream).
    MEDIA_INFO_NOT_SEEKABLE = 801,
    // New media metadata is available.
    MEDIA_INFO_METADATA_UPDATE = 802,
};



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

// Keep KEY_PARAMETER_* in sync with MediaPlayer.java.
// The same enum space is used for both set and get, in case there are future keys that
// can be both set and get.  But as of now, all parameters are either set only or get only.
enum media_parameter_keys {
    KEY_PARAMETER_TIMED_TEXT_TRACK_INDEX = 1000,                // set only
    KEY_PARAMETER_TIMED_TEXT_ADD_OUT_OF_BAND_SOURCE = 1001,     // set only

    // Streaming/buffering parameters
    KEY_PARAMETER_CACHE_STAT_COLLECT_FREQ_MS = 1100,            // set only

    // Return a Parcel containing a single int, which is the channel count of the
    // audio track, or zero for error (e.g. no audio track) or unknown.
    KEY_PARAMETER_AUDIO_CHANNEL_COUNT = 1200,                   // get only

};

// ----------------------------------------------------------------------------
// ref-counted object for callbacks
class MediaPlayerListener: virtual public RefBase
{
public:
    virtual void notify(int msg, int ext1, int ext2, const Parcel *obj) = 0;
};

class MediaPlayer : public BnMediaPlayerClient,
                    public virtual IMediaDeathNotifier
{
public:
    MediaPlayer();
    ~MediaPlayer();
            void            died();
            void            disconnect();

            status_t        setDataSource(
                    const char *url,
                    const KeyedVector<String8, String8> *headers);

            status_t        setDataSource(int fd, int64_t offset, int64_t length);
            status_t        setVideoSurface(const sp<Surface>& surface);
            status_t        setVideoSurfaceTexture(
                                    const sp<ISurfaceTexture>& surfaceTexture);
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
            bool            isLooping();
            status_t        setVolume(float leftVolume, float rightVolume);
            void            notify(int msg, int ext1, int ext2, const Parcel *obj = NULL);
    static  sp<IMemory>     decode(const char* url, uint32_t *pSampleRate, int* pNumChannels, int* pFormat);
    static  sp<IMemory>     decode(int fd, int64_t offset, int64_t length, uint32_t *pSampleRate, int* pNumChannels, int* pFormat);
            status_t        invoke(const Parcel& request, Parcel *reply);
            status_t        setMetadataFilter(const Parcel& filter);
            status_t        getMetadata(bool update_only, bool apply_filter, Parcel *metadata);
            status_t        setAudioSessionId(int sessionId);
            int             getAudioSessionId();
            status_t        setAuxEffectSendLevel(float level);
            status_t        attachAuxEffect(int effectId);
            status_t        setParameter(int key, const Parcel& request);
            status_t        getParameter(int key, Parcel* reply);

private:
            void            clear_l();
            status_t        seekTo_l(int msec);
            status_t        prepareAsync_l();
            status_t        getDuration_l(int *msec);
            status_t        setDataSource(const sp<IMediaPlayer>& player);
            void            disconnectNativeWindow();
            status_t        reset_l();

    sp<IMediaPlayer>            mPlayer;
    thread_id_t                 mLockThreadId;
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
    int                         mVideoWidth;
    int                         mVideoHeight;
    int                         mAudioSessionId;
    float                       mSendLevel;
    sp<ANativeWindow>           mConnectedWindow;
    sp<IBinder>                 mConnectedWindowBinder;
};

}; // namespace android

#endif // ANDROID_MEDIAPLAYER_H
