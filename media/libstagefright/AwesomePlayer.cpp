/*
 * Copyright (C) 2009 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "AwesomePlayer"
#include <utils/Log.h>

#include "include/AwesomePlayer.h"
#include "include/Prefetcher.h"
#include "include/SoftwareRenderer.h"

#include <binder/IPCThreadState.h>
#include <media/stagefright/AudioPlayer.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXCodec.h>

namespace android {

struct AwesomeEvent : public TimedEventQueue::Event {
    AwesomeEvent(AwesomePlayer *player, int32_t code)
        : mPlayer(player),
          mCode(code) {
    }

protected:
    virtual ~AwesomeEvent() {}

    virtual void fire(TimedEventQueue *queue, int64_t /* now_us */) {
        mPlayer->onEvent(mCode);
    }

private:
    AwesomePlayer *mPlayer;
    int32_t mCode;

    AwesomeEvent(const AwesomeEvent &);
    AwesomeEvent &operator=(const AwesomeEvent &);
};

struct AwesomeRemoteRenderer : public AwesomeRenderer {
    AwesomeRemoteRenderer(const sp<IOMXRenderer> &target)
        : mTarget(target) {
    }

    virtual void render(MediaBuffer *buffer) {
        void *id;
        if (buffer->meta_data()->findPointer(kKeyBufferID, &id)) {
            mTarget->render((IOMX::buffer_id)id);
        }
    }

private:
    sp<IOMXRenderer> mTarget;

    AwesomeRemoteRenderer(const AwesomeRemoteRenderer &);
    AwesomeRemoteRenderer &operator=(const AwesomeRemoteRenderer &);
};

struct AwesomeLocalRenderer : public AwesomeRenderer {
    AwesomeLocalRenderer(
            OMX_COLOR_FORMATTYPE colorFormat,
            const sp<ISurface> &surface,
            size_t displayWidth, size_t displayHeight,
            size_t decodedWidth, size_t decodedHeight)
        : mTarget(new SoftwareRenderer(
                    colorFormat, surface, displayWidth, displayHeight,
                    decodedWidth, decodedHeight)) {
    }

    virtual void render(MediaBuffer *buffer) {
        mTarget->render(
                (const uint8_t *)buffer->data() + buffer->range_offset(),
                buffer->range_length(), NULL);
    }

protected:
    virtual ~AwesomeLocalRenderer() {
        delete mTarget;
        mTarget = NULL;
    }

private:
    SoftwareRenderer *mTarget;

    AwesomeLocalRenderer(const AwesomeLocalRenderer &);
    AwesomeLocalRenderer &operator=(const AwesomeLocalRenderer &);;
};

AwesomePlayer::AwesomePlayer()
    : mTimeSource(NULL),
      mAudioPlayer(NULL),
      mLastVideoBuffer(NULL),
      mVideoBuffer(NULL) {
    CHECK_EQ(mClient.connect(), OK);

    DataSource::RegisterDefaultSniffers();

    mVideoEvent = new AwesomeEvent(this, 0);
    mVideoEventPending = false;
    mStreamDoneEvent = new AwesomeEvent(this, 1);
    mStreamDoneEventPending = false;
    mBufferingEvent = new AwesomeEvent(this, 2);
    mBufferingEventPending = false;

    mQueue.start();

    reset();
}

AwesomePlayer::~AwesomePlayer() {
    mQueue.stop();

    reset();

    mClient.disconnect();
}

void AwesomePlayer::cancelPlayerEvents(bool keepBufferingGoing) {
    mQueue.cancelEvent(mVideoEvent->eventID());
    mVideoEventPending = false;
    mQueue.cancelEvent(mStreamDoneEvent->eventID());
    mStreamDoneEventPending = false;

    if (!keepBufferingGoing) {
        mQueue.cancelEvent(mBufferingEvent->eventID());
        mBufferingEventPending = false;
    }
}

void AwesomePlayer::setListener(const wp<MediaPlayerBase> &listener) {
    Mutex::Autolock autoLock(mLock);
    mListener = listener;
}

status_t AwesomePlayer::setDataSource(
        const char *uri, const KeyedVector<String8, String8> *headers) {
    Mutex::Autolock autoLock(mLock);

    reset_l();

    sp<DataSource> dataSource = DataSource::CreateFromURI(uri, headers);

    if (dataSource == NULL) {
        return UNKNOWN_ERROR;
    }

    sp<MediaExtractor> extractor = MediaExtractor::Create(dataSource);

    if (extractor == NULL) {
        return UNKNOWN_ERROR;
    }

    if (dataSource->flags() & DataSource::kWantsPrefetching) {
        mPrefetcher = new Prefetcher;
    }

    return setDataSource_l(extractor);
}

status_t AwesomePlayer::setDataSource(
        int fd, int64_t offset, int64_t length) {
    Mutex::Autolock autoLock(mLock);

    reset_l();

    sp<DataSource> source = new FileSource(fd, offset, length);

    status_t err = source->initCheck();

    if (err != OK) {
        return err;
    }

    sp<MediaExtractor> extractor = MediaExtractor::Create(source);

    if (extractor == NULL) {
        return UNKNOWN_ERROR;
    }

    return setDataSource_l(extractor);
}

status_t AwesomePlayer::setDataSource_l(const sp<MediaExtractor> &extractor) {
    bool haveAudio = false;
    bool haveVideo = false;
    for (size_t i = 0; i < extractor->countTracks(); ++i) {
        sp<MetaData> meta = extractor->getTrackMetaData(i);

        const char *mime;
        CHECK(meta->findCString(kKeyMIMEType, &mime));

        if (!haveVideo && !strncasecmp(mime, "video/", 6)) {
            if (setVideoSource(extractor->getTrack(i)) == OK) {
                haveVideo = true;
            }
        } else if (!haveAudio && !strncasecmp(mime, "audio/", 6)) {
            if (setAudioSource(extractor->getTrack(i)) == OK) {
                haveAudio = true;
            }
        }

        if (haveAudio && haveVideo) {
            break;
        }
    }

    return !haveAudio && !haveVideo ? UNKNOWN_ERROR : OK;
}

void AwesomePlayer::reset() {
    Mutex::Autolock autoLock(mLock);
    reset_l();
}

void AwesomePlayer::reset_l() {
    cancelPlayerEvents();

    mVideoRenderer.clear();

    if (mLastVideoBuffer) {
        mLastVideoBuffer->release();
        mLastVideoBuffer = NULL;
    }

    if (mVideoBuffer) {
        mVideoBuffer->release();
        mVideoBuffer = NULL;
    }

    if (mVideoSource != NULL) {
        mVideoSource->stop();

        // The following hack is necessary to ensure that the OMX
        // component is completely released by the time we may try
        // to instantiate it again.
        wp<MediaSource> tmp = mVideoSource;
        mVideoSource.clear();
        while (tmp.promote() != NULL) {
            usleep(1000);
        }
        IPCThreadState::self()->flushCommands();
    }

    mAudioSource.clear();

    if (mTimeSource != mAudioPlayer) {
        delete mTimeSource;
    }
    mTimeSource = NULL;

    delete mAudioPlayer;
    mAudioPlayer = NULL;

    mDurationUs = -1;
    mFlags = 0;
    mVideoWidth = mVideoHeight = -1;
    mTimeSourceDeltaUs = 0;
    mVideoTimeUs = 0;

    mSeeking = false;
    mSeekTimeUs = 0;

    mPrefetcher.clear();
}

// static
void AwesomePlayer::AudioNotify(void *_me, int what) {
    AwesomePlayer *me = (AwesomePlayer *)_me;

    Mutex::Autolock autoLock(me->mLock);

    switch (what) {
        case AudioPlayer::REACHED_EOS:
            me->postStreamDoneEvent_l();
            break;

        case AudioPlayer::SEEK_COMPLETE:
        {
            me->notifyListener_l(MEDIA_SEEK_COMPLETE);
            break;
        }

        default:
            CHECK(!"should not be here.");
            break;
    }
}

void AwesomePlayer::notifyListener_l(int msg, int ext1) {
    if (mListener != NULL) {
        sp<MediaPlayerBase> listener = mListener.promote();

        if (listener != NULL) {
            listener->sendEvent(msg, ext1);
        }
    }
}

void AwesomePlayer::onBufferingUpdate() {
    Mutex::Autolock autoLock(mLock);
    mBufferingEventPending = false;

    if (mDurationUs >= 0) {
        int64_t cachedDurationUs = mPrefetcher->getCachedDurationUs();
        int64_t positionUs = 0;
        if (mVideoRenderer != NULL) {
            positionUs = mVideoTimeUs;
        } else if (mAudioPlayer != NULL) {
            positionUs = mAudioPlayer->getMediaTimeUs();
        }

        cachedDurationUs += positionUs;

        double percentage = (double)cachedDurationUs / mDurationUs;
        notifyListener_l(MEDIA_BUFFERING_UPDATE, percentage * 100.0);

        postBufferingEvent_l();
    }
}

void AwesomePlayer::onStreamDone() {
    // Posted whenever any stream finishes playing.

    Mutex::Autolock autoLock(mLock);
    mStreamDoneEventPending = false;

    if (mFlags & LOOPING) {
        seekTo_l(0);

        if (mVideoRenderer != NULL) {
            postVideoEvent_l();
        }
    } else {
        notifyListener_l(MEDIA_PLAYBACK_COMPLETE);

        pause_l();
    }
}

status_t AwesomePlayer::play() {
    Mutex::Autolock autoLock(mLock);

    if (mFlags & PLAYING) {
        return OK;
    }

    mFlags |= PLAYING;
    mFlags |= FIRST_FRAME;

    bool deferredAudioSeek = false;

    if (mAudioSource != NULL) {
        if (mAudioPlayer == NULL) {
            if (mAudioSink != NULL) {
                mAudioPlayer = new AudioPlayer(mAudioSink);

                mAudioPlayer->setListenerCallback(
                        &AwesomePlayer::AudioNotify, this);

                mAudioPlayer->setSource(mAudioSource);
                status_t err = mAudioPlayer->start();

                if (err != OK) {
                    delete mAudioPlayer;
                    mAudioPlayer = NULL;

                    mFlags &= ~(PLAYING | FIRST_FRAME);

                    return err;
                }

                delete mTimeSource;
                mTimeSource = mAudioPlayer;

                deferredAudioSeek = true;
            }
        } else {
            mAudioPlayer->resume();
        }
    }

    if (mTimeSource == NULL && mAudioPlayer == NULL) {
        mTimeSource = new SystemTimeSource;
    }

    if (mVideoSource != NULL) {
        if (mVideoRenderer == NULL) {
            initRenderer_l();
        }

        if (mVideoRenderer != NULL) {
            // Kick off video playback
            postVideoEvent_l();
        }
    }

    if (deferredAudioSeek) {
        // If there was a seek request while we were paused
        // and we're just starting up again, honor the request now.
        seekAudioIfNecessary_l();
    }

    postBufferingEvent_l();

    return OK;
}

void AwesomePlayer::initRenderer_l() {
    if (mISurface != NULL) {
        sp<MetaData> meta = mVideoSource->getFormat();

        int32_t format;
        const char *component;
        int32_t decodedWidth, decodedHeight;
        CHECK(meta->findInt32(kKeyColorFormat, &format));
        CHECK(meta->findCString(kKeyDecoderComponent, &component));
        CHECK(meta->findInt32(kKeyWidth, &decodedWidth));
        CHECK(meta->findInt32(kKeyHeight, &decodedHeight));

        mVideoRenderer.clear();

        // Must ensure that mVideoRenderer's destructor is actually executed
        // before creating a new one.
        IPCThreadState::self()->flushCommands();

        if (!strncmp("OMX.", component, 4)) {
            // Our OMX codecs allocate buffers on the media_server side
            // therefore they require a remote IOMXRenderer that knows how
            // to display them.
            mVideoRenderer = new AwesomeRemoteRenderer(
                mClient.interface()->createRenderer(
                        mISurface, component,
                        (OMX_COLOR_FORMATTYPE)format,
                        decodedWidth, decodedHeight,
                        mVideoWidth, mVideoHeight));
        } else {
            // Other decoders are instantiated locally and as a consequence
            // allocate their buffers in local address space.
            mVideoRenderer = new AwesomeLocalRenderer(
                (OMX_COLOR_FORMATTYPE)format,
                mISurface,
                mVideoWidth, mVideoHeight,
                decodedWidth, decodedHeight);
        }
    }
}

status_t AwesomePlayer::pause() {
    Mutex::Autolock autoLock(mLock);
    return pause_l();
}

status_t AwesomePlayer::pause_l() {
    if (!(mFlags & PLAYING)) {
        return OK;
    }

    cancelPlayerEvents(true /* keepBufferingGoing */);

    if (mAudioPlayer != NULL) {
        mAudioPlayer->pause();
    }

    mFlags &= ~PLAYING;

    return OK;
}

bool AwesomePlayer::isPlaying() const {
    Mutex::Autolock autoLock(mLock);

    return mFlags & PLAYING;
}

void AwesomePlayer::setISurface(const sp<ISurface> &isurface) {
    Mutex::Autolock autoLock(mLock);

    mISurface = isurface;
}

void AwesomePlayer::setAudioSink(
        const sp<MediaPlayerBase::AudioSink> &audioSink) {
    Mutex::Autolock autoLock(mLock);

    mAudioSink = audioSink;
}

status_t AwesomePlayer::setLooping(bool shouldLoop) {
    Mutex::Autolock autoLock(mLock);

    mFlags = mFlags & ~LOOPING;

    if (shouldLoop) {
        mFlags |= LOOPING;
    }

    return OK;
}

status_t AwesomePlayer::getDuration(int64_t *durationUs) {
    Mutex::Autolock autoLock(mLock);

    if (mDurationUs < 0) {
        return UNKNOWN_ERROR;
    }

    *durationUs = mDurationUs;

    return OK;
}

status_t AwesomePlayer::getPosition(int64_t *positionUs) {
    Mutex::Autolock autoLock(mLock);

    if (mVideoRenderer != NULL) {
        *positionUs = mVideoTimeUs;
    } else if (mAudioPlayer != NULL) {
        *positionUs = mAudioPlayer->getMediaTimeUs();
    } else {
        *positionUs = 0;
    }

    return OK;
}

status_t AwesomePlayer::seekTo(int64_t timeUs) {
    Mutex::Autolock autoLock(mLock);
    return seekTo_l(timeUs);
}

status_t AwesomePlayer::seekTo_l(int64_t timeUs) {
    mSeeking = true;
    mSeekTimeUs = timeUs;

    seekAudioIfNecessary_l();

    return OK;
}

void AwesomePlayer::seekAudioIfNecessary_l() {
    if (mSeeking && mVideoRenderer == NULL && mAudioPlayer != NULL) {
        mAudioPlayer->seekTo(mSeekTimeUs);

        mSeeking = false;
    }
}

status_t AwesomePlayer::getVideoDimensions(
        int32_t *width, int32_t *height) const {
    Mutex::Autolock autoLock(mLock);

    if (mVideoWidth < 0 || mVideoHeight < 0) {
        return UNKNOWN_ERROR;
    }

    *width = mVideoWidth;
    *height = mVideoHeight;

    return OK;
}

status_t AwesomePlayer::setAudioSource(sp<MediaSource> source) {
    if (source == NULL) {
        return UNKNOWN_ERROR;
    }

    if (mPrefetcher != NULL) {
        source = mPrefetcher->addSource(source);
    }

    sp<MetaData> meta = source->getFormat();

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_RAW)) {
        mAudioSource = source;
    } else {
        mAudioSource = OMXCodec::Create(
                mClient.interface(), source->getFormat(),
                false, // createEncoder
                source);
    }

    if (mAudioSource != NULL) {
        int64_t durationUs;
        if (source->getFormat()->findInt64(kKeyDuration, &durationUs)) {
            if (mDurationUs < 0 || durationUs > mDurationUs) {
                mDurationUs = durationUs;
            }
        }
    }

    return mAudioSource != NULL ? OK : UNKNOWN_ERROR;
}

status_t AwesomePlayer::setVideoSource(sp<MediaSource> source) {
    if (source == NULL) {
        return UNKNOWN_ERROR;
    }

    if (mPrefetcher != NULL) {
        source = mPrefetcher->addSource(source);
    }

    mVideoSource = OMXCodec::Create(
            mClient.interface(), source->getFormat(),
            false, // createEncoder
            source);

    if (mVideoSource != NULL) {
        int64_t durationUs;
        if (source->getFormat()->findInt64(kKeyDuration, &durationUs)) {
            if (mDurationUs < 0 || durationUs > mDurationUs) {
                mDurationUs = durationUs;
            }
        }

        CHECK(source->getFormat()->findInt32(kKeyWidth, &mVideoWidth));
        CHECK(source->getFormat()->findInt32(kKeyHeight, &mVideoHeight));

        mVideoSource->start();
    }

    return mVideoSource != NULL ? OK : UNKNOWN_ERROR;
}

void AwesomePlayer::onEvent(int32_t code) {
    if (code == 1) {
        onStreamDone();
        return;
    } else if (code == 2) {
        onBufferingUpdate();
        return;
    }

    Mutex::Autolock autoLock(mLock);

    mVideoEventPending = false;

    if (mSeeking) {
        if (mLastVideoBuffer) {
            mLastVideoBuffer->release();
            mLastVideoBuffer = NULL;
        }

        if (mVideoBuffer) {
            mVideoBuffer->release();
            mVideoBuffer = NULL;
        }
    }

    if (!mVideoBuffer) {
        MediaSource::ReadOptions options;
        if (mSeeking) {
            LOGV("seeking to %lld us (%.2f secs)", mSeekTimeUs, mSeekTimeUs / 1E6);

            options.setSeekTo(mSeekTimeUs);
        }
        for (;;) {
            status_t err = mVideoSource->read(&mVideoBuffer, &options);
            options.clearSeekTo();

            if (err != OK) {
                CHECK_EQ(mVideoBuffer, NULL);

                if (err == INFO_FORMAT_CHANGED) {
                    LOGV("VideoSource signalled format change.");

                    initRenderer_l();
                    continue;
                }

                postStreamDoneEvent_l();
                return;
            }

            if (mVideoBuffer->range_length() == 0) {
                // Some decoders, notably the PV AVC software decoder
                // return spurious empty buffers that we just want to ignore.

                mVideoBuffer->release();
                mVideoBuffer = NULL;
                continue;
            }

            break;
        }
    }

    int64_t timeUs;
    CHECK(mVideoBuffer->meta_data()->findInt64(kKeyTime, &timeUs));

    mVideoTimeUs = timeUs;

    if (mSeeking) {
        if (mAudioPlayer != NULL) {
            LOGV("seeking audio to %lld us (%.2f secs).", timeUs, timeUs / 1E6);

            mAudioPlayer->seekTo(timeUs);
        } else {
            // If we're playing video only, report seek complete now,
            // otherwise audio player will notify us later.
            notifyListener_l(MEDIA_SEEK_COMPLETE);
        }

        mFlags |= FIRST_FRAME;
        mSeeking = false;
    }

    if (mFlags & FIRST_FRAME) {
        mFlags &= ~FIRST_FRAME;

        mTimeSourceDeltaUs = mTimeSource->getRealTimeUs() - timeUs;
    }

    int64_t realTimeUs, mediaTimeUs;
    if (mAudioPlayer != NULL
        && mAudioPlayer->getMediaTimeMapping(&realTimeUs, &mediaTimeUs)) {
        mTimeSourceDeltaUs = realTimeUs - mediaTimeUs;
    }

    int64_t nowUs = mTimeSource->getRealTimeUs() - mTimeSourceDeltaUs;

    int64_t latenessUs = nowUs - timeUs;

    if (latenessUs > 40000) {
        // We're more than 40ms late.
        LOGI("we're late by %lld us (%.2f secs)", latenessUs, latenessUs / 1E6);

        mVideoBuffer->release();
        mVideoBuffer = NULL;

        postVideoEvent_l();
        return;
    }

    if (latenessUs < -10000) {
        // We're more than 10ms early.

        postVideoEvent_l(10000);
        return;
    }

    mVideoRenderer->render(mVideoBuffer);

    if (mLastVideoBuffer) {
        mLastVideoBuffer->release();
        mLastVideoBuffer = NULL;
    }
    mLastVideoBuffer = mVideoBuffer;
    mVideoBuffer = NULL;

    postVideoEvent_l();
}

void AwesomePlayer::postVideoEvent_l(int64_t delayUs) {
    if (mVideoEventPending) {
        return;
    }

    mVideoEventPending = true;
    mQueue.postEventWithDelay(mVideoEvent, delayUs < 0 ? 10000 : delayUs);
}

void AwesomePlayer::postStreamDoneEvent_l() {
    if (mStreamDoneEventPending) {
        return;
    }
    mStreamDoneEventPending = true;
    mQueue.postEvent(mStreamDoneEvent);
}

void AwesomePlayer::postBufferingEvent_l() {
    if (mPrefetcher == NULL) {
        return;
    }

    if (mBufferingEventPending) {
        return;
    }
    mBufferingEventPending = true;
    mQueue.postEventWithDelay(mBufferingEvent, 1000000ll);
}

}  // namespace android

