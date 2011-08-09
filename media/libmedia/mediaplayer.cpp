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

#include <binder/IServiceManager.h>
#include <binder/IPCThreadState.h>

#include <gui/SurfaceTextureClient.h>

#include <media/mediaplayer.h>
#include <media/AudioTrack.h>

#include <surfaceflinger/Surface.h>

#include <binder/MemoryBase.h>

#include <utils/KeyedVector.h>
#include <utils/String8.h>

#include <system/audio.h>
#include <system/window.h>

namespace android {

MediaPlayer::MediaPlayer()
{
    LOGV("constructor");
    mListener = NULL;
    mCookie = NULL;
    mDuration = -1;
    mStreamType = AUDIO_STREAM_MUSIC;
    mCurrentPosition = -1;
    mSeekPosition = -1;
    mCurrentState = MEDIA_PLAYER_IDLE;
    mPrepareSync = false;
    mPrepareStatus = NO_ERROR;
    mLoop = false;
    mLeftVolume = mRightVolume = 1.0;
    mVideoWidth = mVideoHeight = 0;
    mLockThreadId = 0;
    mAudioSessionId = AudioSystem::newAudioSessionId();
    AudioSystem::acquireAudioSessionId(mAudioSessionId);
    mSendLevel = 0;
}

MediaPlayer::~MediaPlayer()
{
    LOGV("destructor");
    AudioSystem::releaseAudioSessionId(mAudioSessionId);
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
    }

    disconnectNativeWindow();
}

// always call with lock held
void MediaPlayer::clear_l()
{
    mDuration = -1;
    mCurrentPosition = -1;
    mSeekPosition = -1;
    mVideoWidth = mVideoHeight = 0;
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

        if ( !( (mCurrentState & MEDIA_PLAYER_IDLE) ||
                (mCurrentState == MEDIA_PLAYER_STATE_ERROR ) ) ) {
            LOGE("setDataSource called in state %d", mCurrentState);
            return INVALID_OPERATION;
        }

        clear_l();
        p = mPlayer;
        mPlayer = player;
        if (player != 0) {
            mCurrentState = MEDIA_PLAYER_INITIALIZED;
            err = NO_ERROR;
        } else {
            LOGE("Unable to to create media player");
        }
    }

    if (p != 0) {
        p->disconnect();
    }

    return err;
}

status_t MediaPlayer::setDataSource(
        const char *url, const KeyedVector<String8, String8> *headers)
{
    LOGV("setDataSource(%s)", url);
    status_t err = BAD_VALUE;
    if (url != NULL) {
        const sp<IMediaPlayerService>& service(getMediaPlayerService());
        if (service != 0) {
            sp<IMediaPlayer> player(
                    service->create(getpid(), this, url, headers, mAudioSessionId));
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
        sp<IMediaPlayer> player(service->create(getpid(), this, fd, offset, length, mAudioSessionId));
        err = setDataSource(player);
    }
    return err;
}

status_t MediaPlayer::invoke(const Parcel& request, Parcel *reply)
{
    Mutex::Autolock _l(mLock);
    const bool hasBeenInitialized =
            (mCurrentState != MEDIA_PLAYER_STATE_ERROR) &&
            ((mCurrentState & MEDIA_PLAYER_IDLE) != MEDIA_PLAYER_IDLE);
    if ((mPlayer != NULL) && hasBeenInitialized) {
         LOGV("invoke %d", request.dataSize());
         return  mPlayer->invoke(request, reply);
    }
    LOGE("invoke failed: wrong state %X", mCurrentState);
    return INVALID_OPERATION;
}

status_t MediaPlayer::setMetadataFilter(const Parcel& filter)
{
    LOGD("setMetadataFilter");
    Mutex::Autolock lock(mLock);
    if (mPlayer == NULL) {
        return NO_INIT;
    }
    return mPlayer->setMetadataFilter(filter);
}

status_t MediaPlayer::getMetadata(bool update_only, bool apply_filter, Parcel *metadata)
{
    LOGD("getMetadata");
    Mutex::Autolock lock(mLock);
    if (mPlayer == NULL) {
        return NO_INIT;
    }
    return mPlayer->getMetadata(update_only, apply_filter, metadata);
}

void MediaPlayer::disconnectNativeWindow() {
    if (mConnectedWindow != NULL) {
        status_t err = native_window_api_disconnect(mConnectedWindow.get(),
                NATIVE_WINDOW_API_MEDIA);

        if (err != OK) {
            LOGW("native_window_api_disconnect returned an error: %s (%d)",
                    strerror(-err), err);
        }
    }
    mConnectedWindow.clear();
}

status_t MediaPlayer::setVideoSurface(const sp<Surface>& surface)
{
    LOGV("setVideoSurface");
    Mutex::Autolock _l(mLock);
    if (mPlayer == 0) return NO_INIT;

    sp<IBinder> binder(surface == NULL ? NULL : surface->asBinder());
    if (mConnectedWindowBinder == binder) {
        return OK;
    }

    if (surface != NULL) {
        status_t err = native_window_api_connect(surface.get(),
                NATIVE_WINDOW_API_MEDIA);

        if (err != OK) {
            LOGE("setVideoSurface failed: %d", err);
            // Note that we must do the reset before disconnecting from the ANW.
            // Otherwise queue/dequeue calls could be made on the disconnected
            // ANW, which may result in errors.
            reset_l();

            disconnectNativeWindow();

            return err;
        }
    }

    // Note that we must set the player's new surface before disconnecting the
    // old one.  Otherwise queue/dequeue calls could be made on the disconnected
    // ANW, which may result in errors.
    status_t err = mPlayer->setVideoSurface(surface);

    disconnectNativeWindow();

    mConnectedWindow = surface;

    if (err == OK) {
        mConnectedWindowBinder = binder;
    } else {
        disconnectNativeWindow();
    }

    return err;
}

status_t MediaPlayer::setVideoSurfaceTexture(
        const sp<ISurfaceTexture>& surfaceTexture)
{
    LOGV("setVideoSurfaceTexture");
    Mutex::Autolock _l(mLock);
    if (mPlayer == 0) return NO_INIT;

    sp<IBinder> binder(surfaceTexture == NULL ? NULL :
            surfaceTexture->asBinder());
    if (mConnectedWindowBinder == binder) {
        return OK;
    }

    sp<ANativeWindow> anw;
    if (surfaceTexture != NULL) {
        anw = new SurfaceTextureClient(surfaceTexture);
        status_t err = native_window_api_connect(anw.get(),
                NATIVE_WINDOW_API_MEDIA);

        if (err != OK) {
            LOGE("setVideoSurfaceTexture failed: %d", err);
            // Note that we must do the reset before disconnecting from the ANW.
            // Otherwise queue/dequeue calls could be made on the disconnected
            // ANW, which may result in errors.
            reset_l();

            disconnectNativeWindow();

            return err;
        }
    }

    // Note that we must set the player's new SurfaceTexture before
    // disconnecting the old one.  Otherwise queue/dequeue calls could be made
    // on the disconnected ANW, which may result in errors.
    status_t err = mPlayer->setVideoSurfaceTexture(surfaceTexture);

    disconnectNativeWindow();

    mConnectedWindow = anw;

    if (err == OK) {
        mConnectedWindowBinder = binder;
    } else {
        disconnectNativeWindow();
    }

    return err;
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

// TODO: In case of error, prepareAsync provides the caller with 2 error codes,
// one defined in the Android framework and one provided by the implementation
// that generated the error. The sync version of prepare returns only 1 error
// code.
status_t MediaPlayer::prepare()
{
    LOGV("prepare");
    Mutex::Autolock _l(mLock);
    mLockThreadId = getThreadId();
    if (mPrepareSync) {
        mLockThreadId = 0;
        return -EALREADY;
    }
    mPrepareSync = true;
    status_t ret = prepareAsync_l();
    if (ret != NO_ERROR) {
        mLockThreadId = 0;
        return ret;
    }

    if (mPrepareSync) {
        mSignal.wait(mLock);  // wait for prepare done
        mPrepareSync = false;
    }
    LOGV("prepare complete - status=%d", mPrepareStatus);
    mLockThreadId = 0;
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
        mPlayer->setAuxEffectSendLevel(mSendLevel);
        mCurrentState = MEDIA_PLAYER_STARTED;
        status_t ret = mPlayer->start();
        if (ret != NO_ERROR) {
            mCurrentState = MEDIA_PLAYER_STATE_ERROR;
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
    if (mCurrentState & (MEDIA_PLAYER_PAUSED|MEDIA_PLAYER_PLAYBACK_COMPLETE))
        return NO_ERROR;
    if ((mPlayer != 0) && (mCurrentState & MEDIA_PLAYER_STARTED)) {
        status_t ret = mPlayer->pause();
        if (ret != NO_ERROR) {
            mCurrentState = MEDIA_PLAYER_STATE_ERROR;
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
    if (mPlayer == 0) return INVALID_OPERATION;
    *w = mVideoWidth;
    return NO_ERROR;
}

status_t MediaPlayer::getVideoHeight(int *h)
{
    LOGV("getVideoHeight");
    Mutex::Autolock _l(mLock);
    if (mPlayer == 0) return INVALID_OPERATION;
    *h = mVideoHeight;
    return NO_ERROR;
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
    mLockThreadId = getThreadId();
    Mutex::Autolock _l(mLock);
    status_t result = seekTo_l(msec);
    mLockThreadId = 0;

    return result;
}

status_t MediaPlayer::reset_l()
{
    mLoop = false;
    if (mCurrentState == MEDIA_PLAYER_IDLE) return NO_ERROR;
    mPrepareSync = false;
    if (mPlayer != 0) {
        status_t ret = mPlayer->reset();
        if (ret != NO_ERROR) {
            LOGE("reset() failed with return code (%d)", ret);
            mCurrentState = MEDIA_PLAYER_STATE_ERROR;
        } else {
            mCurrentState = MEDIA_PLAYER_IDLE;
        }
        // setDataSource has to be called again to create a
        // new mediaplayer.
        mPlayer = 0;
        return ret;
    }
    clear_l();
    return NO_ERROR;
}

status_t MediaPlayer::reset()
{
    LOGV("reset");
    Mutex::Autolock _l(mLock);
    return reset_l();
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

bool MediaPlayer::isLooping() {
    LOGV("isLooping");
    Mutex::Autolock _l(mLock);
    if (mPlayer != 0) {
        return mLoop;
    }
    LOGV("isLooping: no active player");
    return false;
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

status_t MediaPlayer::setAudioSessionId(int sessionId)
{
    LOGV("MediaPlayer::setAudioSessionId(%d)", sessionId);
    Mutex::Autolock _l(mLock);
    if (!(mCurrentState & MEDIA_PLAYER_IDLE)) {
        LOGE("setAudioSessionId called in state %d", mCurrentState);
        return INVALID_OPERATION;
    }
    if (sessionId < 0) {
        return BAD_VALUE;
    }
    if (sessionId != mAudioSessionId) {
      AudioSystem::releaseAudioSessionId(mAudioSessionId);
      AudioSystem::acquireAudioSessionId(sessionId);
      mAudioSessionId = sessionId;
    }
    return NO_ERROR;
}

int MediaPlayer::getAudioSessionId()
{
    Mutex::Autolock _l(mLock);
    return mAudioSessionId;
}

status_t MediaPlayer::setAuxEffectSendLevel(float level)
{
    LOGV("MediaPlayer::setAuxEffectSendLevel(%f)", level);
    Mutex::Autolock _l(mLock);
    mSendLevel = level;
    if (mPlayer != 0) {
        return mPlayer->setAuxEffectSendLevel(level);
    }
    return OK;
}

status_t MediaPlayer::attachAuxEffect(int effectId)
{
    LOGV("MediaPlayer::attachAuxEffect(%d)", effectId);
    Mutex::Autolock _l(mLock);
    if (mPlayer == 0 ||
        (mCurrentState & MEDIA_PLAYER_IDLE) ||
        (mCurrentState == MEDIA_PLAYER_STATE_ERROR )) {
        LOGE("attachAuxEffect called in state %d", mCurrentState);
        return INVALID_OPERATION;
    }

    return mPlayer->attachAuxEffect(effectId);
}

status_t MediaPlayer::setParameter(int key, const Parcel& request)
{
    LOGV("MediaPlayer::setParameter(%d)", key);
    Mutex::Autolock _l(mLock);
    if (mPlayer != NULL) {
        return  mPlayer->setParameter(key, request);
    }
    LOGV("setParameter: no active player");
    return INVALID_OPERATION;
}

status_t MediaPlayer::getParameter(int key, Parcel *reply)
{
    LOGV("MediaPlayer::getParameter(%d)", key);
    Mutex::Autolock _l(mLock);
    if (mPlayer != NULL) {
         return  mPlayer->getParameter(key, reply);
    }
    LOGV("getParameter: no active player");
    return INVALID_OPERATION;
}

void MediaPlayer::notify(int msg, int ext1, int ext2, const Parcel *obj)
{
    LOGV("message received msg=%d, ext1=%d, ext2=%d", msg, ext1, ext2);
    bool send = true;
    bool locked = false;

    // TODO: In the future, we might be on the same thread if the app is
    // running in the same process as the media server. In that case,
    // this will deadlock.
    //
    // The threadId hack below works around this for the care of prepare
    // and seekTo within the same process.
    // FIXME: Remember, this is a hack, it's not even a hack that is applied
    // consistently for all use-cases, this needs to be revisited.
     if (mLockThreadId != getThreadId()) {
        mLock.lock();
        locked = true;
    }

    // Allows calls from JNI in idle state to notify errors
    if (!(msg == MEDIA_ERROR && mCurrentState == MEDIA_PLAYER_IDLE) && mPlayer == 0) {
        LOGV("notify(%d, %d, %d) callback on disconnected mediaplayer", msg, ext1, ext2);
        if (locked) mLock.unlock();   // release the lock when done.
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
        if (mCurrentState == MEDIA_PLAYER_IDLE) {
            LOGE("playback complete in idle state");
        }
        if (!mLoop) {
            mCurrentState = MEDIA_PLAYER_PLAYBACK_COMPLETE;
        }
        break;
    case MEDIA_ERROR:
        // Always log errors.
        // ext1: Media framework error code.
        // ext2: Implementation dependant error code.
        LOGE("error (%d, %d)", ext1, ext2);
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
    case MEDIA_INFO:
        // ext1: Media framework error code.
        // ext2: Implementation dependant error code.
        if (ext1 != MEDIA_INFO_VIDEO_TRACK_LAGGING) {
            LOGW("info/warning (%d, %d)", ext1, ext2);
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
    case MEDIA_SET_VIDEO_SIZE:
        LOGV("New video size %d x %d", ext1, ext2);
        mVideoWidth = ext1;
        mVideoHeight = ext2;
        break;
    case MEDIA_TIMED_TEXT:
        LOGV("Received timed text message");
        break;
    default:
        LOGV("unrecognized message: (%d, %d, %d)", msg, ext1, ext2);
        break;
    }

    sp<MediaPlayerListener> listener = mListener;
    if (locked) mLock.unlock();

    // this prevents re-entrant calls into client code
    if ((listener != 0) && send) {
        Mutex::Autolock _l(mNotifyLock);
        LOGV("callback application");
        listener->notify(msg, ext1, ext2, obj);
        LOGV("back from callback");
    }
}

/*static*/ sp<IMemory> MediaPlayer::decode(const char* url, uint32_t *pSampleRate, int* pNumChannels, int* pFormat)
{
    LOGV("decode(%s)", url);
    sp<IMemory> p;
    const sp<IMediaPlayerService>& service = getMediaPlayerService();
    if (service != 0) {
        p = service->decode(url, pSampleRate, pNumChannels, pFormat);
    } else {
        LOGE("Unable to locate media service");
    }
    return p;

}

void MediaPlayer::died()
{
    LOGV("died");
    notify(MEDIA_ERROR, MEDIA_ERROR_SERVER_DIED, 0);
}

/*static*/ sp<IMemory> MediaPlayer::decode(int fd, int64_t offset, int64_t length, uint32_t *pSampleRate, int* pNumChannels, int* pFormat)
{
    LOGV("decode(%d, %lld, %lld)", fd, offset, length);
    sp<IMemory> p;
    const sp<IMediaPlayerService>& service = getMediaPlayerService();
    if (service != 0) {
        p = service->decode(fd, offset, length, pSampleRate, pNumChannels, pFormat);
    } else {
        LOGE("Unable to locate media service");
    }
    return p;

}

}; // namespace android
