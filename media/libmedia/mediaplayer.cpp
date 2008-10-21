/* mediaplayer.cpp
**
** Copyright 2006, The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaPlayer"
#include <utils/Log.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>

#include <utils/IServiceManager.h>
#include <utils/IPCThreadState.h>

#include <media/mediaplayer.h>
#include <libsonivox/eas.h>

#include <utils/MemoryBase.h>

namespace android {

// client singleton for binder interface to service
Mutex MediaPlayer::mServiceLock;
sp<IMediaPlayerService> MediaPlayer::mMediaPlayerService;
sp<MediaPlayer::DeathNotifier> MediaPlayer::mDeathNotifier;

// establish binder interface to service
const sp<IMediaPlayerService>& MediaPlayer::getMediaPlayerService()
{
    Mutex::Autolock _l(mServiceLock);
    if (mMediaPlayerService.get() == 0) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder;
        do {
            binder = sm->getService(String16("media.player"));
            if (binder != 0)
                break;
            LOGW("MediaPlayerService not published, waiting...");
            usleep(500000); // 0.5 s
        } while(true);
        if (mDeathNotifier == NULL) {
            mDeathNotifier = new DeathNotifier();
        }
        binder->linkToDeath(mDeathNotifier);
        mMediaPlayerService = interface_cast<IMediaPlayerService>(binder);
    }
    LOGE_IF(mMediaPlayerService==0, "no MediaPlayerService!?");
    return mMediaPlayerService;
}

MediaPlayer::MediaPlayer()
{
    LOGV("constructor");
    mListener = NULL;
    mCookie = NULL;
    mDuration = -1;
    mStreamType = AudioTrack::MUSIC;
    mCurrentPosition = -1;
    mSeekPosition = -1;
    mCurrentState = MEDIA_PLAYER_IDLE;
    mPrepareSync = false;
    mPrepareStatus = NO_ERROR;
    mLoop = false;
    mLeftVolume = mRightVolume = 1.0;
}

MediaPlayer::~MediaPlayer()
{
    LOGV("destructor");
    disconnect();
    IPCThreadState::self()->flushCommands();
}

void MediaPlayer::disconnect()
{
    LOGV("disconnect");
    sp<IMediaPlayer> p;
    {
        Mutex::Autolock _l(mLock);
        p = mPlayer;
        mPlayer.clear();
    }

    if (p != 0) {
        p->disconnect();
        p->asBinder()->unlinkToDeath(this);
    }
}

// always call with lock held
void MediaPlayer::clear_l()
{
    mDuration = -1;
    mCurrentPosition = -1;
    mSeekPosition = -1;
}

status_t MediaPlayer::setListener(const sp<MediaPlayerListener>& listener)
{
    LOGV("setListener");
    Mutex::Autolock _l(mLock);
    mListener = listener;
    return NO_ERROR;
}


status_t MediaPlayer::setDataSource(const sp<IMediaPlayer>& player)
{
    status_t err = UNKNOWN_ERROR;
    sp<IMediaPlayer> p;
    { // scope for the lock
        Mutex::Autolock _l(mLock);

        if ( !( mCurrentState & ( MEDIA_PLAYER_IDLE | MEDIA_PLAYER_STATE_ERROR ) ) ) {
            LOGE("setDataSource called in state %d", mCurrentState);
            return INVALID_OPERATION;
        }

        clear_l();
        p = mPlayer;
        mPlayer = player;
        if (player != 0) {
            mCurrentState = MEDIA_PLAYER_INITIALIZED;
            player->asBinder()->linkToDeath(this);
            err = NO_ERROR;
        } else {
            LOGE("Unable to to create media player");
        }
    }

    if (p != 0) {
        p->disconnect();
        p->asBinder()->unlinkToDeath(this);
    }
    return err;
}

status_t MediaPlayer::setDataSource(const char *url)
{
    LOGV("setDataSource(%s)", url);
    status_t err = UNKNOWN_ERROR;
    if (url != NULL) {
        const sp<IMediaPlayerService>& service(getMediaPlayerService());
        if (service != 0) {
            sp<IMediaPlayer> player(service->create(getpid(), this, url));
            err = setDataSource(player);
        }
    }
    return err;
}

status_t MediaPlayer::setDataSource(int fd, int64_t offset, int64_t length)
{
    LOGV("setDataSource(%d, %lld, %lld)", fd, offset, length);
    status_t err = UNKNOWN_ERROR;
    const sp<IMediaPlayerService>& service(getMediaPlayerService());
    if (service != 0) {
        sp<IMediaPlayer> player(service->create(getpid(), this, fd, offset, length));
        err = setDataSource(player);
    }
    return err;
}

status_t MediaPlayer::setVideoSurface(const sp<Surface>& surface)
{
    LOGV("setVideoSurface");
    Mutex::Autolock _l(mLock);
    if (mPlayer == 0) return UNKNOWN_ERROR;
    return  mPlayer->setVideoSurface(surface->getISurface());
}

// must call with lock held
status_t MediaPlayer::prepareAsync_l()
{
    if ( (mPlayer != 0) && ( mCurrentState & ( MEDIA_PLAYER_INITIALIZED | MEDIA_PLAYER_STOPPED) ) ) {
        mPlayer->setAudioStreamType(mStreamType);
        mCurrentState = MEDIA_PLAYER_PREPARING;
        return mPlayer->prepareAsync();
    }
    LOGE("prepareAsync called in state %d", mCurrentState);
    return INVALID_OPERATION;
}

status_t MediaPlayer::prepare()
{
    LOGV("prepare");
    Mutex::Autolock _l(mLock);
    if (mPrepareSync) return UNKNOWN_ERROR;
    mPrepareSync = true;
    status_t ret = prepareAsync_l();
    if (ret != NO_ERROR) return ret;

    if (mPrepareSync) {
        mSignal.wait(mLock);  // wait for prepare done
        mPrepareSync = false;
    }
    LOGV("prepare complete - status=%d", mPrepareStatus);
    return mPrepareStatus;
}

status_t MediaPlayer::prepareAsync()
{
    LOGV("prepareAsync");
    Mutex::Autolock _l(mLock);
    return prepareAsync_l();
}

status_t MediaPlayer::start()
{
    LOGV("start");
    Mutex::Autolock _l(mLock);
    if (mCurrentState & MEDIA_PLAYER_STARTED)
        return NO_ERROR;
    if ( (mPlayer != 0) && ( mCurrentState & ( MEDIA_PLAYER_PREPARED |
                    MEDIA_PLAYER_PLAYBACK_COMPLETE | MEDIA_PLAYER_PAUSED ) ) ) {
        mPlayer->setLooping(mLoop);
        mPlayer->setVolume(mLeftVolume, mRightVolume);
        mCurrentState = MEDIA_PLAYER_STARTED;
        status_t ret = mPlayer->start();
        if (ret != NO_ERROR) {
            mCurrentState = MEDIA_PLAYER_STATE_ERROR;
            ret = UNKNOWN_ERROR;
        } else {
            if (mCurrentState == MEDIA_PLAYER_PLAYBACK_COMPLETE) {
                LOGV("playback completed immediately following start()");
            }
        }
        return ret;
    }
    LOGE("start called in state %d", mCurrentState);
    return INVALID_OPERATION;
}

status_t MediaPlayer::stop()
{
    LOGV("stop");
    Mutex::Autolock _l(mLock);
    if (mCurrentState & MEDIA_PLAYER_STOPPED) return NO_ERROR;
    if ( (mPlayer != 0) && ( mCurrentState & ( MEDIA_PLAYER_STARTED | MEDIA_PLAYER_PREPARED |
                    MEDIA_PLAYER_PAUSED | MEDIA_PLAYER_PLAYBACK_COMPLETE ) ) ) {
        status_t ret = mPlayer->stop();
        if (ret != NO_ERROR) {
            mCurrentState = MEDIA_PLAYER_STATE_ERROR;
            ret = UNKNOWN_ERROR;
        } else {
            mCurrentState = MEDIA_PLAYER_STOPPED;
        }
        return ret;
    }
    LOGE("stop called in state %d", mCurrentState);
    return INVALID_OPERATION;
}

status_t MediaPlayer::pause()
{
    LOGV("pause");
    Mutex::Autolock _l(mLock);
    if (mCurrentState & MEDIA_PLAYER_PAUSED)
        return NO_ERROR;
    if ((mPlayer != 0) && (mCurrentState & MEDIA_PLAYER_STARTED)) {
        status_t ret = mPlayer->pause();
        if (ret != NO_ERROR) {
            mCurrentState = MEDIA_PLAYER_STATE_ERROR;
            ret = UNKNOWN_ERROR;
        } else {
            mCurrentState = MEDIA_PLAYER_PAUSED;
        }
        return ret;
    }
    LOGE("pause called in state %d", mCurrentState);
    return INVALID_OPERATION;
}

bool MediaPlayer::isPlaying()
{
    Mutex::Autolock _l(mLock);
    if (mPlayer != 0) {
        bool temp = false;
        mPlayer->isPlaying(&temp);
        LOGV("isPlaying: %d", temp);
        if ((mCurrentState & MEDIA_PLAYER_STARTED) && ! temp) {
            LOGE("internal/external state mismatch corrected");
            mCurrentState = MEDIA_PLAYER_PAUSED;
        }
        return temp;
    }
    LOGV("isPlaying: no active player");
    return false;
}

status_t MediaPlayer::getVideoWidth(int *w)
{
    LOGV("getVideoWidth");
    Mutex::Autolock _l(mLock);
    if (mPlayer != 0) {
        int h;
        return mPlayer->getVideoSize(w, &h);
    }
    return INVALID_OPERATION;
}

status_t MediaPlayer::getVideoHeight(int *h)
{
    LOGV("getVideoHeight");
    Mutex::Autolock _l(mLock);
    if (mPlayer != 0) {
        int w;
        return mPlayer->getVideoSize(&w, h);
    }
    return INVALID_OPERATION;
}

status_t MediaPlayer::getCurrentPosition(int *msec)
{
    LOGV("getCurrentPosition");
    Mutex::Autolock _l(mLock);
    if (mPlayer != 0) {
        if (mCurrentPosition >= 0) {
            LOGV("Using cached seek position: %d", mCurrentPosition);
            *msec = mCurrentPosition;
            return NO_ERROR;
        }
        return mPlayer->getCurrentPosition(msec);
    }
    return INVALID_OPERATION;
}

status_t MediaPlayer::getDuration_l(int *msec)
{
    LOGV("getDuration");
    bool isValidState = (mCurrentState & (MEDIA_PLAYER_PREPARED | MEDIA_PLAYER_STARTED | MEDIA_PLAYER_PAUSED | MEDIA_PLAYER_STOPPED | MEDIA_PLAYER_PLAYBACK_COMPLETE));
    if (mPlayer != 0 && isValidState) {
        status_t ret = NO_ERROR;
        if (mDuration <= 0)
            ret = mPlayer->getDuration(&mDuration);
        if (msec)
            *msec = mDuration;
        return ret;
    }
    LOGE("Attempt to call getDuration without a valid mediaplayer");
    return INVALID_OPERATION;
}

status_t MediaPlayer::getDuration(int *msec)
{
    Mutex::Autolock _l(mLock);
    return getDuration_l(msec);
}

status_t MediaPlayer::seekTo_l(int msec)
{
    LOGV("seekTo %d", msec);
    if ((mPlayer != 0) && ( mCurrentState & ( MEDIA_PLAYER_STARTED | MEDIA_PLAYER_PREPARED | MEDIA_PLAYER_PAUSED |  MEDIA_PLAYER_PLAYBACK_COMPLETE) ) ) {
        if ( msec < 0 ) {
            LOGW("Attempt to seek to invalid position: %d", msec);
            msec = 0;
        } else if ((mDuration > 0) && (msec > mDuration)) {
            LOGW("Attempt to seek to past end of file: request = %d, EOF = %d", msec, mDuration);
            msec = mDuration;
        }
        // cache duration
        mCurrentPosition = msec;
        if (mSeekPosition < 0) {
            getDuration_l(NULL);
            mSeekPosition = msec;
            return mPlayer->seekTo(msec);
        }
        else {
            LOGV("Seek in progress - queue up seekTo[%d]", msec);
            return NO_ERROR;
        }
    }
    LOGE("Attempt to perform seekTo in wrong state: mPlayer=%p, mCurrentState=%u", mPlayer.get(), mCurrentState);
    return INVALID_OPERATION;
}

status_t MediaPlayer::seekTo(int msec)
{
    Mutex::Autolock _l(mLock);
    return seekTo_l(msec);
}

status_t MediaPlayer::reset()
{
    LOGV("reset");
    Mutex::Autolock _l(mLock);
    mLoop = false;
    if (mCurrentState == MEDIA_PLAYER_IDLE) return NO_ERROR;
    mPrepareSync = false;
    if (mPlayer != 0) {
        status_t ret = mPlayer->reset();
        if (ret != NO_ERROR) {
            mCurrentState = MEDIA_PLAYER_STATE_ERROR;
            ret = UNKNOWN_ERROR;
        } else {
            mCurrentState = MEDIA_PLAYER_IDLE;
        }
        return ret;
    }
    clear_l();
    return NO_ERROR;
}

status_t MediaPlayer::setAudioStreamType(int type)
{
    LOGV("MediaPlayer::setAudioStreamType");
    Mutex::Autolock _l(mLock);
    if (mStreamType == type) return NO_ERROR;
    if (mCurrentState & ( MEDIA_PLAYER_PREPARED | MEDIA_PLAYER_STARTED |
                MEDIA_PLAYER_PAUSED | MEDIA_PLAYER_PLAYBACK_COMPLETE ) ) {
        // Can't change the stream type after prepare
        LOGE("setAudioStream called in state %d", mCurrentState);
        return INVALID_OPERATION;
    }
    // cache
    mStreamType = type;
    return OK;
}

status_t MediaPlayer::setLooping(int loop)
{
    LOGV("MediaPlayer::setLooping");
    Mutex::Autolock _l(mLock);
    mLoop = (loop != 0);
    if (mPlayer != 0) {
        return mPlayer->setLooping(loop);
    }
    return OK;
}

status_t MediaPlayer::setVolume(float leftVolume, float rightVolume)
{
    LOGV("MediaPlayer::setVolume(%f, %f)", leftVolume, rightVolume);
    Mutex::Autolock _l(mLock);
    mLeftVolume = leftVolume;
    mRightVolume = rightVolume;
    if (mPlayer != 0) {
        return mPlayer->setVolume(leftVolume, rightVolume);
    }
    return OK;
}

void MediaPlayer::notify(int msg, int ext1, int ext2)
{
    LOGV("message received msg=%d, ext1=%d, ext2=%d", msg, ext1, ext2);
    bool send = true;

    // TODO: In the future, we might be on the same thread if the app is
    // running in the same process as the media server. In that case,
    // this will deadlock.
    mLock.lock();
    if (mPlayer == 0) {
        LOGV("notify(%d, %d, %d) callback on disconnected mediaplayer", msg, ext1, ext2);
        return;
    }

    switch (msg) {
    case MEDIA_NOP: // interface test message
        break;
    case MEDIA_PREPARED:
        LOGV("prepared");
        mCurrentState = MEDIA_PLAYER_PREPARED;
        if (mPrepareSync) {
            LOGV("signal application thread");
            mPrepareSync = false;
            mPrepareStatus = NO_ERROR;
            mSignal.signal();
        }
        break;
    case MEDIA_PLAYBACK_COMPLETE:
        LOGV("playback complete");
        if (!mLoop) {
            mCurrentState = MEDIA_PLAYER_PLAYBACK_COMPLETE;
        }
        break;
    case MEDIA_ERROR:
        LOGV("error (%d, %d)", ext1, ext2);
        mCurrentState = MEDIA_PLAYER_STATE_ERROR;
        if (mPrepareSync)
        {
            LOGV("signal application thread");
            mPrepareSync = false;
            mPrepareStatus = ext1;
            mSignal.signal();
            send = false;
        }
        break;
    case MEDIA_SEEK_COMPLETE:
        LOGV("Received seek complete");
        if (mSeekPosition != mCurrentPosition) {
            LOGV("Executing queued seekTo(%d)", mSeekPosition);
            mSeekPosition = -1;
            seekTo_l(mCurrentPosition);
        }
        else {
            LOGV("All seeks complete - return to regularly scheduled program");
            mCurrentPosition = mSeekPosition = -1;
        }
        break;
    case MEDIA_BUFFERING_UPDATE:
        LOGV("buffering %d", ext1);
        break;
    default:
        LOGV("unrecognized message: (%d, %d, %d)", msg, ext1, ext2);
        break;
    }

    sp<MediaPlayerListener> listener = mListener;
    mLock.unlock();

    // this prevents re-entrant calls into client code
    if ((listener != 0) && send) {
        Mutex::Autolock _l(mNotifyLock);
        LOGV("callback application");
        listener->notify(msg, ext1, ext2);
        LOGV("back from callback");
    }
}

void MediaPlayer::binderDied(const wp<IBinder>& who) {
    LOGW("IMediaplayer died");
    notify(MEDIA_ERROR, MEDIA_ERROR_SERVER_DIED, 0);
}

void MediaPlayer::DeathNotifier::binderDied(const wp<IBinder>& who) {
    Mutex::Autolock _l(MediaPlayer::mServiceLock);
    MediaPlayer::mMediaPlayerService.clear();
    LOGW("MediaPlayer server died!");
}

MediaPlayer::DeathNotifier::~DeathNotifier()
{
    Mutex::Autolock _l(mServiceLock);
    if (mMediaPlayerService != 0) {
        mMediaPlayerService->asBinder()->unlinkToDeath(this);
    }
}

/*static*/ sp<IMemory> MediaPlayer::decode(const char* url, uint32_t *pSampleRate, int* pNumChannels)
{
    LOGV("decode(%s)", url);
    sp<IMemory> p;
    const sp<IMediaPlayerService>& service = getMediaPlayerService();
    if (service != 0) {
        p = mMediaPlayerService->decode(url, pSampleRate, pNumChannels);
    } else {
        LOGE("Unable to locate media service");
    }
    return p;

}

/*static*/ sp<IMemory> MediaPlayer::decode(int fd, int64_t offset, int64_t length, uint32_t *pSampleRate, int* pNumChannels)
{
    LOGV("decode(%d, %lld, %lld)", fd, offset, length);
    sp<IMemory> p;
    const sp<IMediaPlayerService>& service = getMediaPlayerService();
    if (service != 0) {
        p = mMediaPlayerService->decode(fd, offset, length, pSampleRate, pNumChannels);
    } else {
        LOGE("Unable to locate media service");
    }
    return p;

}

}; // namespace android
