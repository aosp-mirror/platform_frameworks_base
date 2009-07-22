//#define LOG_NDEBUG 0
#define LOG_TAG "StagefrightPlayer"
#include <utils/Log.h>

#include "StagefrightPlayer.h"
#include <media/stagefright/MediaPlayerImpl.h>

namespace android {

StagefrightPlayer::StagefrightPlayer()
    : mPlayer(NULL) {
    LOGV("StagefrightPlayer");
}

StagefrightPlayer::~StagefrightPlayer() {
    LOGV("~StagefrightPlayer");
    reset();
    LOGV("~StagefrightPlayer done.");
}

status_t StagefrightPlayer::initCheck() {
    LOGV("initCheck");
    return OK;
}

status_t StagefrightPlayer::setDataSource(const char *url) {
    LOGV("setDataSource('%s')", url);

    reset();
    mPlayer = new MediaPlayerImpl(url);

    status_t err = mPlayer->initCheck();
    if (err != OK) {
        delete mPlayer;
        mPlayer = NULL;
    } else {
        mPlayer->setAudioSink(mAudioSink);
    }

    return err;
}

status_t StagefrightPlayer::setDataSource(int fd, int64_t offset, int64_t length) {
    LOGV("setDataSource(%d, %lld, %lld)", fd, offset, length);

    reset();
    mPlayer = new MediaPlayerImpl(fd, offset, length);

    status_t err = mPlayer->initCheck();
    if (err != OK) {
        delete mPlayer;
        mPlayer = NULL;
    } else {
        mPlayer->setAudioSink(mAudioSink);
    }

    return err;
}

status_t StagefrightPlayer::setVideoSurface(const sp<ISurface> &surface) {
    LOGV("setVideoSurface");

    if (mPlayer == NULL) {
        return NO_INIT;
    }

    mPlayer->setISurface(surface);

    return OK;
}

status_t StagefrightPlayer::prepare() {
    LOGV("prepare");

    if (mPlayer == NULL) {
        return NO_INIT;
    }

    sendEvent(
            MEDIA_SET_VIDEO_SIZE,
            mPlayer->getWidth(), mPlayer->getHeight());

    return OK;
}

status_t StagefrightPlayer::prepareAsync() {
    LOGV("prepareAsync");

    status_t err = prepare();

    if (err != OK) {
        return err;
    }

    sendEvent(MEDIA_PREPARED);

    return OK;
}

status_t StagefrightPlayer::start() {
    LOGV("start");

    if (mPlayer == NULL) {
        return NO_INIT;
    }

    mPlayer->play();

    return OK;
}

status_t StagefrightPlayer::stop() {
    LOGV("stop");

    if (mPlayer == NULL) {
        return NO_INIT;
    }

    reset();

    return OK;
}

status_t StagefrightPlayer::pause() {
    LOGV("pause");

    if (mPlayer == NULL) {
        return NO_INIT;
    }

    mPlayer->pause();

    return OK;
}

bool StagefrightPlayer::isPlaying() {
    LOGV("isPlaying");
    return mPlayer != NULL && mPlayer->isPlaying();
}

status_t StagefrightPlayer::seekTo(int msec) {
    LOGV("seekTo");

    if (mPlayer == NULL) {
        return NO_INIT;
    }

    status_t err = mPlayer->seekTo((int64_t)msec * 1000);

    sendEvent(MEDIA_SEEK_COMPLETE);

    return err;
}

status_t StagefrightPlayer::getCurrentPosition(int *msec) {
    LOGV("getCurrentPosition");

    if (mPlayer == NULL) {
        return NO_INIT;
    }

    *msec = mPlayer->getPosition() / 1000;
    return OK;
}

status_t StagefrightPlayer::getDuration(int *msec) {
    LOGV("getDuration");

    if (mPlayer == NULL) {
        return NO_INIT;
    }

    *msec = mPlayer->getDuration() / 1000;
    return OK;
}

status_t StagefrightPlayer::reset() {
    LOGV("reset");

    delete mPlayer;
    mPlayer = NULL;

    return OK;
}

status_t StagefrightPlayer::setLooping(int loop) {
    LOGV("setLooping");
    return UNKNOWN_ERROR;
}

player_type StagefrightPlayer::playerType() {
    LOGV("playerType");
    return STAGEFRIGHT_PLAYER;
}

status_t StagefrightPlayer::invoke(const Parcel &request, Parcel *reply) {
    return INVALID_OPERATION;
}

void StagefrightPlayer::setAudioSink(const sp<AudioSink> &audioSink) {
    MediaPlayerInterface::setAudioSink(audioSink);

    if (mPlayer != NULL) {
        mPlayer->setAudioSink(audioSink);
    }
}

}  // namespace android
