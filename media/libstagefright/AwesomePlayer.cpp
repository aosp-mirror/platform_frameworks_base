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

#include <dlfcn.h>

#include "include/ARTSPController.h"
#include "include/AwesomePlayer.h"
#include "include/SoftwareRenderer.h"
#include "include/NuCachedSource2.h"
#include "include/ThrottledSource.h"
#include "include/MPEG2TSExtractor.h"

#include "ARTPSession.h"
#include "APacketSource.h"
#include "ASessionDescription.h"
#include "UDPPusher.h"

#include <binder/IPCThreadState.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/AudioPlayer.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXCodec.h>

#include <surfaceflinger/Surface.h>

#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/foundation/AMessage.h>
#include "include/LiveSession.h"

#define USE_SURFACE_ALLOC 1
#define FRAME_DROP_FREQ 0

namespace android {

static int64_t kLowWaterMarkUs = 2000000ll;  // 2secs
static int64_t kHighWaterMarkUs = 10000000ll;  // 10secs
static const size_t kLowWaterMarkBytes = 40000;
static const size_t kHighWaterMarkBytes = 200000;

struct AwesomeEvent : public TimedEventQueue::Event {
    AwesomeEvent(
            AwesomePlayer *player,
            void (AwesomePlayer::*method)())
        : mPlayer(player),
          mMethod(method) {
    }

protected:
    virtual ~AwesomeEvent() {}

    virtual void fire(TimedEventQueue *queue, int64_t /* now_us */) {
        (mPlayer->*mMethod)();
    }

private:
    AwesomePlayer *mPlayer;
    void (AwesomePlayer::*mMethod)();

    AwesomeEvent(const AwesomeEvent &);
    AwesomeEvent &operator=(const AwesomeEvent &);
};

struct AwesomeLocalRenderer : public AwesomeRenderer {
    AwesomeLocalRenderer(
            const sp<Surface> &surface, const sp<MetaData> &meta)
        : mTarget(new SoftwareRenderer(surface, meta)) {
    }

    virtual void render(MediaBuffer *buffer) {
        render((const uint8_t *)buffer->data() + buffer->range_offset(),
               buffer->range_length());
    }

    void render(const void *data, size_t size) {
        mTarget->render(data, size, NULL);
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

struct AwesomeNativeWindowRenderer : public AwesomeRenderer {
    AwesomeNativeWindowRenderer(
            const sp<ANativeWindow> &nativeWindow,
            int32_t rotationDegrees)
        : mNativeWindow(nativeWindow) {
        applyRotation(rotationDegrees);
    }

    virtual void render(MediaBuffer *buffer) {
        status_t err = mNativeWindow->queueBuffer(
                mNativeWindow.get(), buffer->graphicBuffer().get());
        if (err != 0) {
            LOGE("queueBuffer failed with error %s (%d)", strerror(-err),
                    -err);
            return;
        }

        sp<MetaData> metaData = buffer->meta_data();
        metaData->setInt32(kKeyRendered, 1);
    }

protected:
    virtual ~AwesomeNativeWindowRenderer() {}

private:
    sp<ANativeWindow> mNativeWindow;

    void applyRotation(int32_t rotationDegrees) {
        uint32_t transform;
        switch (rotationDegrees) {
            case 0: transform = 0; break;
            case 90: transform = HAL_TRANSFORM_ROT_90; break;
            case 180: transform = HAL_TRANSFORM_ROT_180; break;
            case 270: transform = HAL_TRANSFORM_ROT_270; break;
            default: transform = 0; break;
        }

        if (transform) {
            CHECK_EQ(0, native_window_set_buffers_transform(
                        mNativeWindow.get(), transform));
        }
    }

    AwesomeNativeWindowRenderer(const AwesomeNativeWindowRenderer &);
    AwesomeNativeWindowRenderer &operator=(
            const AwesomeNativeWindowRenderer &);
};

////////////////////////////////////////////////////////////////////////////////

AwesomePlayer::AwesomePlayer()
    : mQueueStarted(false),
      mTimeSource(NULL),
      mVideoRendererIsPreview(false),
      mAudioPlayer(NULL),
      mDisplayWidth(0),
      mDisplayHeight(0),
      mFlags(0),
      mExtractorFlags(0),
      mVideoBuffer(NULL),
      mDecryptHandle(NULL) {
    CHECK_EQ(mClient.connect(), (status_t)OK);

    DataSource::RegisterDefaultSniffers();

    mVideoEvent = new AwesomeEvent(this, &AwesomePlayer::onVideoEvent);
    mVideoEventPending = false;
    mStreamDoneEvent = new AwesomeEvent(this, &AwesomePlayer::onStreamDone);
    mStreamDoneEventPending = false;
    mBufferingEvent = new AwesomeEvent(this, &AwesomePlayer::onBufferingUpdate);
    mBufferingEventPending = false;
    mVideoLagEvent = new AwesomeEvent(this, &AwesomePlayer::onVideoLagUpdate);
    mVideoEventPending = false;

    mCheckAudioStatusEvent = new AwesomeEvent(
            this, &AwesomePlayer::onCheckAudioStatus);

    mAudioStatusEventPending = false;

    reset();
}

AwesomePlayer::~AwesomePlayer() {
    if (mQueueStarted) {
        mQueue.stop();
    }

    reset();

    mClient.disconnect();
}

void AwesomePlayer::cancelPlayerEvents(bool keepBufferingGoing) {
    mQueue.cancelEvent(mVideoEvent->eventID());
    mVideoEventPending = false;
    mQueue.cancelEvent(mStreamDoneEvent->eventID());
    mStreamDoneEventPending = false;
    mQueue.cancelEvent(mCheckAudioStatusEvent->eventID());
    mAudioStatusEventPending = false;
    mQueue.cancelEvent(mVideoLagEvent->eventID());
    mVideoLagEventPending = false;

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
    return setDataSource_l(uri, headers);
}

status_t AwesomePlayer::setDataSource_l(
        const char *uri, const KeyedVector<String8, String8> *headers) {
    reset_l();

    mUri = uri;

    if (!strncmp("http://", uri, 7)) {
        // Hack to support http live.

        size_t len = strlen(uri);
        if (!strcasecmp(&uri[len - 5], ".m3u8")
                || strstr(&uri[7], "m3u8") != NULL) {
            mUri = "httplive://";
            mUri.append(&uri[7]);
        }
    }

    if (headers) {
        mUriHeaders = *headers;
    }

    // The actual work will be done during preparation in the call to
    // ::finishSetDataSource_l to avoid blocking the calling thread in
    // setDataSource for any significant time.

    return OK;
}

status_t AwesomePlayer::setDataSource(
        int fd, int64_t offset, int64_t length) {
    Mutex::Autolock autoLock(mLock);

    reset_l();

    sp<DataSource> dataSource = new FileSource(fd, offset, length);

    status_t err = dataSource->initCheck();

    if (err != OK) {
        return err;
    }

    mFileSource = dataSource;

    return setDataSource_l(dataSource);
}

status_t AwesomePlayer::setDataSource(const sp<IStreamSource> &source) {
    return INVALID_OPERATION;
}

status_t AwesomePlayer::setDataSource_l(
        const sp<DataSource> &dataSource) {
    sp<MediaExtractor> extractor = MediaExtractor::Create(dataSource);

    if (extractor == NULL) {
        return UNKNOWN_ERROR;
    }

    dataSource->getDrmInfo(&mDecryptHandle, &mDrmManagerClient);
    if (mDecryptHandle != NULL
            && RightsStatus::RIGHTS_VALID != mDecryptHandle->status) {
        notifyListener_l(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, ERROR_NO_LICENSE);
    }

    return setDataSource_l(extractor);
}

status_t AwesomePlayer::setDataSource_l(const sp<MediaExtractor> &extractor) {
    // Attempt to approximate overall stream bitrate by summing all
    // tracks' individual bitrates, if not all of them advertise bitrate,
    // we have to fail.

    int64_t totalBitRate = 0;

    for (size_t i = 0; i < extractor->countTracks(); ++i) {
        sp<MetaData> meta = extractor->getTrackMetaData(i);

        int32_t bitrate;
        if (!meta->findInt32(kKeyBitRate, &bitrate)) {
            totalBitRate = -1;
            break;
        }

        totalBitRate += bitrate;
    }

    mBitrate = totalBitRate;

    LOGV("mBitrate = %lld bits/sec", mBitrate);

    bool haveAudio = false;
    bool haveVideo = false;
    for (size_t i = 0; i < extractor->countTracks(); ++i) {
        sp<MetaData> meta = extractor->getTrackMetaData(i);

        const char *mime;
        CHECK(meta->findCString(kKeyMIMEType, &mime));

        if (!haveVideo && !strncasecmp(mime, "video/", 6)) {
            setVideoSource(extractor->getTrack(i));
            haveVideo = true;

            // Set the presentation/display size
            int32_t displayWidth, displayHeight;
            bool success = meta->findInt32(kKeyDisplayWidth, &displayWidth);
            if (success) {
                success = meta->findInt32(kKeyDisplayHeight, &displayHeight);
            }
            if (success) {
                mDisplayWidth = displayWidth;
                mDisplayHeight = displayHeight;
            }

        } else if (!haveAudio && !strncasecmp(mime, "audio/", 6)) {
            setAudioSource(extractor->getTrack(i));
            haveAudio = true;

            if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_VORBIS)) {
                // Only do this for vorbis audio, none of the other audio
                // formats even support this ringtone specific hack and
                // retrieving the metadata on some extractors may turn out
                // to be very expensive.
                sp<MetaData> fileMeta = extractor->getMetaData();
                int32_t loop;
                if (fileMeta != NULL
                        && fileMeta->findInt32(kKeyAutoLoop, &loop) && loop != 0) {
                    mFlags |= AUTO_LOOPING;
                }
            }
        }

        if (haveAudio && haveVideo) {
            break;
        }
    }

    if (!haveAudio && !haveVideo) {
        return UNKNOWN_ERROR;
    }

    mExtractorFlags = extractor->flags();

    return OK;
}

void AwesomePlayer::reset() {
    Mutex::Autolock autoLock(mLock);
    reset_l();
}

void AwesomePlayer::reset_l() {
    mDisplayWidth = 0;
    mDisplayHeight = 0;

    if (mDecryptHandle != NULL) {
            mDrmManagerClient->setPlaybackStatus(mDecryptHandle,
                    Playback::STOP, 0);
            mDrmManagerClient->closeDecryptSession(mDecryptHandle);
            mDecryptHandle = NULL;
            mDrmManagerClient = NULL;
    }

    if (mFlags & PREPARING) {
        mFlags |= PREPARE_CANCELLED;
        if (mConnectingDataSource != NULL) {
            LOGI("interrupting the connection process");
            mConnectingDataSource->disconnect();
        }

        if (mFlags & PREPARING_CONNECTED) {
            // We are basically done preparing, we're just buffering
            // enough data to start playback, we can safely interrupt that.
            finishAsyncPrepare_l();
        }
    }

    while (mFlags & PREPARING) {
        mPreparedCondition.wait(mLock);
    }

    cancelPlayerEvents();

    mCachedSource.clear();
    mAudioTrack.clear();
    mVideoTrack.clear();

    // Shutdown audio first, so that the respone to the reset request
    // appears to happen instantaneously as far as the user is concerned
    // If we did this later, audio would continue playing while we
    // shutdown the video-related resources and the player appear to
    // not be as responsive to a reset request.
    if (mAudioPlayer == NULL && mAudioSource != NULL) {
        // If we had an audio player, it would have effectively
        // taken possession of the audio source and stopped it when
        // _it_ is stopped. Otherwise this is still our responsibility.
        mAudioSource->stop();
    }
    mAudioSource.clear();

    mTimeSource = NULL;

    delete mAudioPlayer;
    mAudioPlayer = NULL;

    mVideoRenderer.clear();

    if (mVideoBuffer) {
        mVideoBuffer->release();
        mVideoBuffer = NULL;
    }

    if (mRTSPController != NULL) {
        mRTSPController->disconnect();
        mRTSPController.clear();
    }

    if (mLiveSession != NULL) {
        mLiveSession->disconnect();
        mLiveSession.clear();
    }

    mRTPPusher.clear();
    mRTCPPusher.clear();
    mRTPSession.clear();

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

    mDurationUs = -1;
    mFlags = 0;
    mExtractorFlags = 0;
    mTimeSourceDeltaUs = 0;
    mVideoTimeUs = 0;

    mSeeking = NO_SEEK;
    mSeekNotificationSent = false;
    mSeekTimeUs = 0;

    mUri.setTo("");
    mUriHeaders.clear();

    mFileSource.clear();

    mBitrate = -1;
}

void AwesomePlayer::notifyListener_l(int msg, int ext1, int ext2) {
    if (mListener != NULL) {
        sp<MediaPlayerBase> listener = mListener.promote();

        if (listener != NULL) {
            listener->sendEvent(msg, ext1, ext2);
        }
    }
}

bool AwesomePlayer::getBitrate(int64_t *bitrate) {
    off64_t size;
    if (mDurationUs >= 0 && mCachedSource != NULL
            && mCachedSource->getSize(&size) == OK) {
        *bitrate = size * 8000000ll / mDurationUs;  // in bits/sec
        return true;
    }

    if (mBitrate >= 0) {
        *bitrate = mBitrate;
        return true;
    }

    *bitrate = 0;

    return false;
}

// Returns true iff cached duration is available/applicable.
bool AwesomePlayer::getCachedDuration_l(int64_t *durationUs, bool *eos) {
    int64_t bitrate;

    if (mRTSPController != NULL) {
        *durationUs = mRTSPController->getQueueDurationUs(eos);
        return true;
    } else if (mCachedSource != NULL && getBitrate(&bitrate)) {
        status_t finalStatus;
        size_t cachedDataRemaining = mCachedSource->approxDataRemaining(&finalStatus);
        *durationUs = cachedDataRemaining * 8000000ll / bitrate;
        *eos = (finalStatus != OK);
        return true;
    }

    return false;
}

void AwesomePlayer::ensureCacheIsFetching_l() {
    if (mCachedSource != NULL) {
        mCachedSource->resumeFetchingIfNecessary();
    }
}

void AwesomePlayer::onVideoLagUpdate() {
    Mutex::Autolock autoLock(mLock);
    if (!mVideoLagEventPending) {
        return;
    }
    mVideoLagEventPending = false;

    int64_t audioTimeUs = mAudioPlayer->getMediaTimeUs();
    int64_t videoLateByUs = audioTimeUs - mVideoTimeUs;

    if (videoLateByUs > 300000ll) {
        LOGV("video late by %lld ms.", videoLateByUs / 1000ll);

        notifyListener_l(
                MEDIA_INFO,
                MEDIA_INFO_VIDEO_TRACK_LAGGING,
                videoLateByUs / 1000ll);
    }

    postVideoLagEvent_l();
}

void AwesomePlayer::onBufferingUpdate() {
    Mutex::Autolock autoLock(mLock);
    if (!mBufferingEventPending) {
        return;
    }
    mBufferingEventPending = false;

    if (mCachedSource != NULL) {
        status_t finalStatus;
        size_t cachedDataRemaining = mCachedSource->approxDataRemaining(&finalStatus);
        bool eos = (finalStatus != OK);

        if (eos) {
            if (finalStatus == ERROR_END_OF_STREAM) {
                notifyListener_l(MEDIA_BUFFERING_UPDATE, 100);
            }
            if (mFlags & PREPARING) {
                LOGV("cache has reached EOS, prepare is done.");
                finishAsyncPrepare_l();
            }
        } else {
            int64_t bitrate;
            if (getBitrate(&bitrate)) {
                size_t cachedSize = mCachedSource->cachedSize();
                int64_t cachedDurationUs = cachedSize * 8000000ll / bitrate;

                int percentage = 100.0 * (double)cachedDurationUs / mDurationUs;
                if (percentage > 100) {
                    percentage = 100;
                }

                notifyListener_l(MEDIA_BUFFERING_UPDATE, percentage);
            } else {
                // We don't know the bitrate of the stream, use absolute size
                // limits to maintain the cache.

                if ((mFlags & PLAYING) && !eos
                        && (cachedDataRemaining < kLowWaterMarkBytes)) {
                    LOGI("cache is running low (< %d) , pausing.",
                         kLowWaterMarkBytes);
                    mFlags |= CACHE_UNDERRUN;
                    pause_l();
                    ensureCacheIsFetching_l();
                    notifyListener_l(MEDIA_INFO, MEDIA_INFO_BUFFERING_START);
                } else if (eos || cachedDataRemaining > kHighWaterMarkBytes) {
                    if (mFlags & CACHE_UNDERRUN) {
                        LOGI("cache has filled up (> %d), resuming.",
                             kHighWaterMarkBytes);
                        mFlags &= ~CACHE_UNDERRUN;
                        play_l();
                        notifyListener_l(MEDIA_INFO, MEDIA_INFO_BUFFERING_END);
                    } else if (mFlags & PREPARING) {
                        LOGV("cache has filled up (> %d), prepare is done",
                             kHighWaterMarkBytes);
                        finishAsyncPrepare_l();
                    }
                }
            }
        }
    }

    int64_t cachedDurationUs;
    bool eos;
    if (getCachedDuration_l(&cachedDurationUs, &eos)) {
        LOGV("cachedDurationUs = %.2f secs, eos=%d",
             cachedDurationUs / 1E6, eos);

        if ((mFlags & PLAYING) && !eos
                && (cachedDurationUs < kLowWaterMarkUs)) {
            LOGI("cache is running low (%.2f secs) , pausing.",
                 cachedDurationUs / 1E6);
            mFlags |= CACHE_UNDERRUN;
            pause_l();
            ensureCacheIsFetching_l();
            notifyListener_l(MEDIA_INFO, MEDIA_INFO_BUFFERING_START);
        } else if (eos || cachedDurationUs > kHighWaterMarkUs) {
            if (mFlags & CACHE_UNDERRUN) {
                LOGI("cache has filled up (%.2f secs), resuming.",
                     cachedDurationUs / 1E6);
                mFlags &= ~CACHE_UNDERRUN;
                play_l();
                notifyListener_l(MEDIA_INFO, MEDIA_INFO_BUFFERING_END);
            } else if (mFlags & PREPARING) {
                LOGV("cache has filled up (%.2f secs), prepare is done",
                     cachedDurationUs / 1E6);
                finishAsyncPrepare_l();
            }
        }
    }

    postBufferingEvent_l();
}

void AwesomePlayer::partial_reset_l() {
    // Only reset the video renderer and shut down the video decoder.
    // Then instantiate a new video decoder and resume video playback.

    mVideoRenderer.clear();

    if (mVideoBuffer) {
        mVideoBuffer->release();
        mVideoBuffer = NULL;
    }

    {
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

    CHECK_EQ((status_t)OK,
             initVideoDecoder(OMXCodec::kIgnoreCodecSpecificData));
}

void AwesomePlayer::onStreamDone() {
    // Posted whenever any stream finishes playing.

    Mutex::Autolock autoLock(mLock);
    if (!mStreamDoneEventPending) {
        return;
    }
    mStreamDoneEventPending = false;

    if (mStreamDoneStatus == INFO_DISCONTINUITY) {
        // This special status is returned because an http live stream's
        // video stream switched to a different bandwidth at this point
        // and future data may have been encoded using different parameters.
        // This requires us to shutdown the video decoder and reinstantiate
        // a fresh one.

        LOGV("INFO_DISCONTINUITY");

        CHECK(mVideoSource != NULL);

        partial_reset_l();
        postVideoEvent_l();
        return;
    } else if (mStreamDoneStatus != ERROR_END_OF_STREAM) {
        LOGV("MEDIA_ERROR %d", mStreamDoneStatus);

        notifyListener_l(
                MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, mStreamDoneStatus);

        pause_l(true /* at eos */);

        mFlags |= AT_EOS;
        return;
    }

    const bool allDone =
        (mVideoSource == NULL || (mFlags & VIDEO_AT_EOS))
            && (mAudioSource == NULL || (mFlags & AUDIO_AT_EOS));

    if (!allDone) {
        return;
    }

    if (mFlags & (LOOPING | AUTO_LOOPING)) {
        seekTo_l(0);

        if (mVideoSource != NULL) {
            postVideoEvent_l();
        }
    } else {
        LOGV("MEDIA_PLAYBACK_COMPLETE");
        notifyListener_l(MEDIA_PLAYBACK_COMPLETE);

        pause_l(true /* at eos */);

        mFlags |= AT_EOS;
    }
}

status_t AwesomePlayer::play() {
    Mutex::Autolock autoLock(mLock);

    mFlags &= ~CACHE_UNDERRUN;

    return play_l();
}

status_t AwesomePlayer::play_l() {
    if (mFlags & PLAYING) {
        return OK;
    }

    if (!(mFlags & PREPARED)) {
        status_t err = prepare_l();

        if (err != OK) {
            return err;
        }
    }

    mFlags |= PLAYING;
    mFlags |= FIRST_FRAME;

    bool deferredAudioSeek = false;

    if (mDecryptHandle != NULL) {
        int64_t position;
        getPosition(&position);
        mDrmManagerClient->setPlaybackStatus(mDecryptHandle,
                Playback::START, position / 1000);
    }

    if (mAudioSource != NULL) {
        if (mAudioPlayer == NULL) {
            if (mAudioSink != NULL) {
                mAudioPlayer = new AudioPlayer(mAudioSink, this);
                mAudioPlayer->setSource(mAudioSource);

                mTimeSource = mAudioPlayer;

                deferredAudioSeek = true;

                mWatchForAudioSeekComplete = false;
                mWatchForAudioEOS = true;
            }
        }

        CHECK(!(mFlags & AUDIO_RUNNING));

        if (mVideoSource == NULL) {
            status_t err = startAudioPlayer_l();

            if (err != OK) {
                delete mAudioPlayer;
                mAudioPlayer = NULL;

                mFlags &= ~(PLAYING | FIRST_FRAME);

                if (mDecryptHandle != NULL) {
                    mDrmManagerClient->setPlaybackStatus(
                            mDecryptHandle, Playback::STOP, 0);
                }

                return err;
            }
        }
    }

    if (mTimeSource == NULL && mAudioPlayer == NULL) {
        mTimeSource = &mSystemTimeSource;
    }

    if (mVideoSource != NULL) {
        // Kick off video playback
        postVideoEvent_l();

        if (mAudioSource != NULL && mVideoSource != NULL) {
            postVideoLagEvent_l();
        }
    }

    if (deferredAudioSeek) {
        // If there was a seek request while we were paused
        // and we're just starting up again, honor the request now.
        seekAudioIfNecessary_l();
    }

    if (mFlags & AT_EOS) {
        // Legacy behaviour, if a stream finishes playing and then
        // is started again, we play from the start...
        seekTo_l(0);
    }

    return OK;
}

status_t AwesomePlayer::startAudioPlayer_l() {
    CHECK(!(mFlags & AUDIO_RUNNING));

    if (mAudioSource == NULL || mAudioPlayer == NULL) {
        return OK;
    }

    if (!(mFlags & AUDIOPLAYER_STARTED)) {
        mFlags |= AUDIOPLAYER_STARTED;

        // We've already started the MediaSource in order to enable
        // the prefetcher to read its data.
        status_t err = mAudioPlayer->start(
                true /* sourceAlreadyStarted */);

        if (err != OK) {
            notifyListener_l(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
            return err;
        }
    } else {
        mAudioPlayer->resume();
    }

    mFlags |= AUDIO_RUNNING;

    mWatchForAudioEOS = true;

    return OK;
}

void AwesomePlayer::notifyVideoSize_l() {
    sp<MetaData> meta = mVideoSource->getFormat();

    int32_t cropLeft, cropTop, cropRight, cropBottom;
    if (!meta->findRect(
                kKeyCropRect, &cropLeft, &cropTop, &cropRight, &cropBottom)) {
        int32_t width, height;
        CHECK(meta->findInt32(kKeyWidth, &width));
        CHECK(meta->findInt32(kKeyHeight, &height));

        cropLeft = cropTop = 0;
        cropRight = width - 1;
        cropBottom = height - 1;

        LOGV("got dimensions only %d x %d", width, height);
    } else {
        LOGV("got crop rect %d, %d, %d, %d",
             cropLeft, cropTop, cropRight, cropBottom);
    }

    int32_t usableWidth = cropRight - cropLeft + 1;
    int32_t usableHeight = cropBottom - cropTop + 1;
    if (mDisplayWidth != 0) {
        usableWidth = mDisplayWidth;
    }
    if (mDisplayHeight != 0) {
        usableHeight = mDisplayHeight;
    }

    int32_t rotationDegrees;
    if (!mVideoTrack->getFormat()->findInt32(
                kKeyRotation, &rotationDegrees)) {
        rotationDegrees = 0;
    }

    if (rotationDegrees == 90 || rotationDegrees == 270) {
        notifyListener_l(
                MEDIA_SET_VIDEO_SIZE, usableHeight, usableWidth);
    } else {
        notifyListener_l(
                MEDIA_SET_VIDEO_SIZE, usableWidth, usableHeight);
    }
}

void AwesomePlayer::initRenderer_l() {
    if (mSurface == NULL) {
        return;
    }

    sp<MetaData> meta = mVideoSource->getFormat();

    int32_t format;
    const char *component;
    int32_t decodedWidth, decodedHeight;
    CHECK(meta->findInt32(kKeyColorFormat, &format));
    CHECK(meta->findCString(kKeyDecoderComponent, &component));
    CHECK(meta->findInt32(kKeyWidth, &decodedWidth));
    CHECK(meta->findInt32(kKeyHeight, &decodedHeight));

    int32_t rotationDegrees;
    if (!mVideoTrack->getFormat()->findInt32(
                kKeyRotation, &rotationDegrees)) {
        rotationDegrees = 0;
    }

    mVideoRenderer.clear();

    // Must ensure that mVideoRenderer's destructor is actually executed
    // before creating a new one.
    IPCThreadState::self()->flushCommands();

    if (USE_SURFACE_ALLOC && strncmp(component, "OMX.", 4) == 0) {
        // Hardware decoders avoid the CPU color conversion by decoding
        // directly to ANativeBuffers, so we must use a renderer that
        // just pushes those buffers to the ANativeWindow.
        mVideoRenderer =
            new AwesomeNativeWindowRenderer(mSurface, rotationDegrees);
    } else {
        // Other decoders are instantiated locally and as a consequence
        // allocate their buffers in local address space.  This renderer
        // then performs a color conversion and copy to get the data
        // into the ANativeBuffer.
        mVideoRenderer = new AwesomeLocalRenderer(mSurface, meta);
    }
}

status_t AwesomePlayer::pause() {
    Mutex::Autolock autoLock(mLock);

    mFlags &= ~CACHE_UNDERRUN;

    return pause_l();
}

status_t AwesomePlayer::pause_l(bool at_eos) {
    if (!(mFlags & PLAYING)) {
        return OK;
    }

    cancelPlayerEvents(true /* keepBufferingGoing */);

    if (mAudioPlayer != NULL && (mFlags & AUDIO_RUNNING)) {
        if (at_eos) {
            // If we played the audio stream to completion we
            // want to make sure that all samples remaining in the audio
            // track's queue are played out.
            mAudioPlayer->pause(true /* playPendingSamples */);
        } else {
            mAudioPlayer->pause();
        }

        mFlags &= ~AUDIO_RUNNING;
    }

    mFlags &= ~PLAYING;

    if (mDecryptHandle != NULL) {
        mDrmManagerClient->setPlaybackStatus(mDecryptHandle,
                Playback::PAUSE, 0);
    }

    return OK;
}

bool AwesomePlayer::isPlaying() const {
    return (mFlags & PLAYING) || (mFlags & CACHE_UNDERRUN);
}

void AwesomePlayer::setSurface(const sp<Surface> &surface) {
    Mutex::Autolock autoLock(mLock);

    mSurface = surface;
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
    Mutex::Autolock autoLock(mMiscStateLock);

    if (mDurationUs < 0) {
        return UNKNOWN_ERROR;
    }

    *durationUs = mDurationUs;

    return OK;
}

status_t AwesomePlayer::getPosition(int64_t *positionUs) {
    if (mRTSPController != NULL) {
        *positionUs = mRTSPController->getNormalPlayTimeUs();
    }
    else if (mSeeking != NO_SEEK) {
        *positionUs = mSeekTimeUs;
    } else if (mVideoSource != NULL) {
        Mutex::Autolock autoLock(mMiscStateLock);
        *positionUs = mVideoTimeUs;
    } else if (mAudioPlayer != NULL) {
        *positionUs = mAudioPlayer->getMediaTimeUs();
    } else {
        *positionUs = 0;
    }

    return OK;
}

status_t AwesomePlayer::seekTo(int64_t timeUs) {
    if (mExtractorFlags & MediaExtractor::CAN_SEEK) {
        Mutex::Autolock autoLock(mLock);
        return seekTo_l(timeUs);
    }

    return OK;
}

// static
void AwesomePlayer::OnRTSPSeekDoneWrapper(void *cookie) {
    static_cast<AwesomePlayer *>(cookie)->onRTSPSeekDone();
}

void AwesomePlayer::onRTSPSeekDone() {
    notifyListener_l(MEDIA_SEEK_COMPLETE);
    mSeekNotificationSent = true;
}

status_t AwesomePlayer::seekTo_l(int64_t timeUs) {
    if (mRTSPController != NULL) {
        mRTSPController->seekAsync(timeUs, OnRTSPSeekDoneWrapper, this);
        return OK;
    }

    if (mFlags & CACHE_UNDERRUN) {
        mFlags &= ~CACHE_UNDERRUN;
        play_l();
    }

    mSeeking = SEEK;
    mSeekNotificationSent = false;
    mSeekTimeUs = timeUs;
    mFlags &= ~(AT_EOS | AUDIO_AT_EOS | VIDEO_AT_EOS);

    seekAudioIfNecessary_l();

    if (!(mFlags & PLAYING)) {
        LOGV("seeking while paused, sending SEEK_COMPLETE notification"
             " immediately.");

        notifyListener_l(MEDIA_SEEK_COMPLETE);
        mSeekNotificationSent = true;
    }

    return OK;
}

void AwesomePlayer::seekAudioIfNecessary_l() {
    if (mSeeking != NO_SEEK && mVideoSource == NULL && mAudioPlayer != NULL) {
        mAudioPlayer->seekTo(mSeekTimeUs);

        mWatchForAudioSeekComplete = true;
        mWatchForAudioEOS = true;
        mSeekNotificationSent = false;

        if (mDecryptHandle != NULL) {
            mDrmManagerClient->setPlaybackStatus(mDecryptHandle,
                    Playback::PAUSE, 0);
            mDrmManagerClient->setPlaybackStatus(mDecryptHandle,
                    Playback::START, mSeekTimeUs / 1000);
        }
    }
}

void AwesomePlayer::setAudioSource(sp<MediaSource> source) {
    CHECK(source != NULL);

    mAudioTrack = source;
}

status_t AwesomePlayer::initAudioDecoder() {
    sp<MetaData> meta = mAudioTrack->getFormat();

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_RAW)) {
        mAudioSource = mAudioTrack;
    } else {
        mAudioSource = OMXCodec::Create(
                mClient.interface(), mAudioTrack->getFormat(),
                false, // createEncoder
                mAudioTrack);
    }

    if (mAudioSource != NULL) {
        int64_t durationUs;
        if (mAudioTrack->getFormat()->findInt64(kKeyDuration, &durationUs)) {
            Mutex::Autolock autoLock(mMiscStateLock);
            if (mDurationUs < 0 || durationUs > mDurationUs) {
                mDurationUs = durationUs;
            }
        }

        status_t err = mAudioSource->start();

        if (err != OK) {
            mAudioSource.clear();
            return err;
        }
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_QCELP)) {
        // For legacy reasons we're simply going to ignore the absence
        // of an audio decoder for QCELP instead of aborting playback
        // altogether.
        return OK;
    }

    return mAudioSource != NULL ? OK : UNKNOWN_ERROR;
}

void AwesomePlayer::setVideoSource(sp<MediaSource> source) {
    CHECK(source != NULL);

    mVideoTrack = source;
}

status_t AwesomePlayer::initVideoDecoder(uint32_t flags) {
    mVideoSource = OMXCodec::Create(
            mClient.interface(), mVideoTrack->getFormat(),
            false, // createEncoder
            mVideoTrack,
            NULL, flags, USE_SURFACE_ALLOC ? mSurface : NULL);

    if (mVideoSource != NULL) {
        int64_t durationUs;
        if (mVideoTrack->getFormat()->findInt64(kKeyDuration, &durationUs)) {
            Mutex::Autolock autoLock(mMiscStateLock);
            if (mDurationUs < 0 || durationUs > mDurationUs) {
                mDurationUs = durationUs;
            }
        }

        status_t err = mVideoSource->start();

        if (err != OK) {
            mVideoSource.clear();
            return err;
        }
    }

    return mVideoSource != NULL ? OK : UNKNOWN_ERROR;
}

void AwesomePlayer::finishSeekIfNecessary(int64_t videoTimeUs) {
    if (mSeeking == SEEK_VIDEO_ONLY) {
        mSeeking = NO_SEEK;
        return;
    }

    if (mSeeking == NO_SEEK) {
        return;
    }

    if (mAudioPlayer != NULL) {
        LOGV("seeking audio to %lld us (%.2f secs).", videoTimeUs, videoTimeUs / 1E6);

        // If we don't have a video time, seek audio to the originally
        // requested seek time instead.

        mAudioPlayer->seekTo(videoTimeUs < 0 ? mSeekTimeUs : videoTimeUs);
        mWatchForAudioSeekComplete = true;
    } else if (!mSeekNotificationSent) {
        // If we're playing video only, report seek complete now,
        // otherwise audio player will notify us later.
        notifyListener_l(MEDIA_SEEK_COMPLETE);
    }

    mFlags |= FIRST_FRAME;
    mSeeking = NO_SEEK;
    mSeekNotificationSent = false;

    if (mDecryptHandle != NULL) {
        mDrmManagerClient->setPlaybackStatus(mDecryptHandle,
                Playback::PAUSE, 0);
        mDrmManagerClient->setPlaybackStatus(mDecryptHandle,
                Playback::START, videoTimeUs / 1000);
    }
}

void AwesomePlayer::onVideoEvent() {
    Mutex::Autolock autoLock(mLock);
    if (!mVideoEventPending) {
        // The event has been cancelled in reset_l() but had already
        // been scheduled for execution at that time.
        return;
    }
    mVideoEventPending = false;

    if (mSeeking != NO_SEEK) {
        if (mVideoBuffer) {
            mVideoBuffer->release();
            mVideoBuffer = NULL;
        }

        if (mSeeking == SEEK && mCachedSource != NULL && mAudioSource != NULL) {
            // We're going to seek the video source first, followed by
            // the audio source.
            // In order to avoid jumps in the DataSource offset caused by
            // the audio codec prefetching data from the old locations
            // while the video codec is already reading data from the new
            // locations, we'll "pause" the audio source, causing it to
            // stop reading input data until a subsequent seek.

            if (mAudioPlayer != NULL && (mFlags & AUDIO_RUNNING)) {
                mAudioPlayer->pause();

                mFlags &= ~AUDIO_RUNNING;
            }
            mAudioSource->pause();
        }
    }

    if (!mVideoBuffer) {
        MediaSource::ReadOptions options;
        if (mSeeking != NO_SEEK) {
            LOGV("seeking to %lld us (%.2f secs)", mSeekTimeUs, mSeekTimeUs / 1E6);

            options.setSeekTo(
                    mSeekTimeUs,
                    mSeeking == SEEK_VIDEO_ONLY
                        ? MediaSource::ReadOptions::SEEK_NEXT_SYNC
                        : MediaSource::ReadOptions::SEEK_CLOSEST_SYNC);
        }
        for (;;) {
            status_t err = mVideoSource->read(&mVideoBuffer, &options);
            options.clearSeekTo();

            if (err != OK) {
                CHECK(mVideoBuffer == NULL);

                if (err == INFO_FORMAT_CHANGED) {
                    LOGV("VideoSource signalled format change.");

                    notifyVideoSize_l();

                    if (mVideoRenderer != NULL) {
                        mVideoRendererIsPreview = false;
                        initRenderer_l();
                    }
                    continue;
                }

                // So video playback is complete, but we may still have
                // a seek request pending that needs to be applied
                // to the audio track.
                if (mSeeking != NO_SEEK) {
                    LOGV("video stream ended while seeking!");
                }
                finishSeekIfNecessary(-1);

                mFlags |= VIDEO_AT_EOS;
                postStreamDoneEvent_l(err);
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

    if (mSeeking == SEEK_VIDEO_ONLY) {
        if (mSeekTimeUs > timeUs) {
            LOGI("XXX mSeekTimeUs = %lld us, timeUs = %lld us",
                 mSeekTimeUs, timeUs);
        }
    }

    {
        Mutex::Autolock autoLock(mMiscStateLock);
        mVideoTimeUs = timeUs;
    }

    SeekType wasSeeking = mSeeking;
    finishSeekIfNecessary(timeUs);

    if (mAudioPlayer != NULL && !(mFlags & AUDIO_RUNNING)) {
        status_t err = startAudioPlayer_l();
        if (err != OK) {
            LOGE("Startung the audio player failed w/ err %d", err);
            return;
        }
    }

    TimeSource *ts = (mFlags & AUDIO_AT_EOS) ? &mSystemTimeSource : mTimeSource;

    if (mFlags & FIRST_FRAME) {
        mFlags &= ~FIRST_FRAME;
        mSinceLastDropped = 0;
        mTimeSourceDeltaUs = ts->getRealTimeUs() - timeUs;
    }

    int64_t realTimeUs, mediaTimeUs;
    if (!(mFlags & AUDIO_AT_EOS) && mAudioPlayer != NULL
        && mAudioPlayer->getMediaTimeMapping(&realTimeUs, &mediaTimeUs)) {
        mTimeSourceDeltaUs = realTimeUs - mediaTimeUs;
    }

    if (wasSeeking == SEEK_VIDEO_ONLY) {
        int64_t nowUs = ts->getRealTimeUs() - mTimeSourceDeltaUs;

        int64_t latenessUs = nowUs - timeUs;

        if (latenessUs > 0) {
            LOGI("after SEEK_VIDEO_ONLY we're late by %.2f secs", latenessUs / 1E6);
        }
    }

    if (wasSeeking == NO_SEEK) {
        // Let's display the first frame after seeking right away.

        int64_t nowUs = ts->getRealTimeUs() - mTimeSourceDeltaUs;

        int64_t latenessUs = nowUs - timeUs;

        if (latenessUs > 500000ll
                && mRTSPController == NULL
                && mAudioPlayer != NULL
                && mAudioPlayer->getMediaTimeMapping(
                    &realTimeUs, &mediaTimeUs)) {
            LOGI("we're much too late (%.2f secs), video skipping ahead",
                 latenessUs / 1E6);

            mVideoBuffer->release();
            mVideoBuffer = NULL;

            mSeeking = SEEK_VIDEO_ONLY;
            mSeekTimeUs = mediaTimeUs;

            postVideoEvent_l();
            return;
        }

        if (latenessUs > 40000) {
            // We're more than 40ms late.

            LOGV("we're late by %lld us (%.2f secs)",
                 latenessUs, latenessUs / 1E6);

            if ( mSinceLastDropped > FRAME_DROP_FREQ)
            {
                LOGV("we're late by %lld us (%.2f secs) dropping one "
                     "after %d frames",
                     latenessUs, latenessUs / 1E6, mSinceLastDropped);

                mSinceLastDropped = 0;
                mVideoBuffer->release();
                mVideoBuffer = NULL;

                postVideoEvent_l();
                return;
            }
        }

        if (latenessUs < -10000) {
            // We're more than 10ms early.

            postVideoEvent_l(10000);
            return;
        }
    }

    if (mVideoRendererIsPreview || mVideoRenderer == NULL) {
        mVideoRendererIsPreview = false;

        initRenderer_l();
    }

    if (mVideoRenderer != NULL) {
        mSinceLastDropped++;
        mVideoRenderer->render(mVideoBuffer);
    }

    mVideoBuffer->release();
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

void AwesomePlayer::postStreamDoneEvent_l(status_t status) {
    if (mStreamDoneEventPending) {
        return;
    }
    mStreamDoneEventPending = true;

    mStreamDoneStatus = status;
    mQueue.postEvent(mStreamDoneEvent);
}

void AwesomePlayer::postBufferingEvent_l() {
    if (mBufferingEventPending) {
        return;
    }
    mBufferingEventPending = true;
    mQueue.postEventWithDelay(mBufferingEvent, 1000000ll);
}

void AwesomePlayer::postVideoLagEvent_l() {
    if (mVideoLagEventPending) {
        return;
    }
    mVideoLagEventPending = true;
    mQueue.postEventWithDelay(mVideoLagEvent, 1000000ll);
}

void AwesomePlayer::postCheckAudioStatusEvent_l() {
    if (mAudioStatusEventPending) {
        return;
    }
    mAudioStatusEventPending = true;
    mQueue.postEvent(mCheckAudioStatusEvent);
}

void AwesomePlayer::onCheckAudioStatus() {
    Mutex::Autolock autoLock(mLock);
    if (!mAudioStatusEventPending) {
        // Event was dispatched and while we were blocking on the mutex,
        // has already been cancelled.
        return;
    }

    mAudioStatusEventPending = false;

    if (mWatchForAudioSeekComplete && !mAudioPlayer->isSeeking()) {
        mWatchForAudioSeekComplete = false;

        if (!mSeekNotificationSent) {
            notifyListener_l(MEDIA_SEEK_COMPLETE);
            mSeekNotificationSent = true;
        }

        mSeeking = NO_SEEK;
    }

    status_t finalStatus;
    if (mWatchForAudioEOS && mAudioPlayer->reachedEOS(&finalStatus)) {
        mWatchForAudioEOS = false;
        mFlags |= AUDIO_AT_EOS;
        mFlags |= FIRST_FRAME;
        postStreamDoneEvent_l(finalStatus);
    }
}

status_t AwesomePlayer::prepare() {
    Mutex::Autolock autoLock(mLock);
    return prepare_l();
}

status_t AwesomePlayer::prepare_l() {
    if (mFlags & PREPARED) {
        return OK;
    }

    if (mFlags & PREPARING) {
        return UNKNOWN_ERROR;
    }

    mIsAsyncPrepare = false;
    status_t err = prepareAsync_l();

    if (err != OK) {
        return err;
    }

    while (mFlags & PREPARING) {
        mPreparedCondition.wait(mLock);
    }

    return mPrepareResult;
}

status_t AwesomePlayer::prepareAsync() {
    Mutex::Autolock autoLock(mLock);

    if (mFlags & PREPARING) {
        return UNKNOWN_ERROR;  // async prepare already pending
    }

    mIsAsyncPrepare = true;
    return prepareAsync_l();
}

status_t AwesomePlayer::prepareAsync_l() {
    if (mFlags & PREPARING) {
        return UNKNOWN_ERROR;  // async prepare already pending
    }

    if (!mQueueStarted) {
        mQueue.start();
        mQueueStarted = true;
    }

    mFlags |= PREPARING;
    mAsyncPrepareEvent = new AwesomeEvent(
            this, &AwesomePlayer::onPrepareAsyncEvent);

    mQueue.postEvent(mAsyncPrepareEvent);

    return OK;
}

status_t AwesomePlayer::finishSetDataSource_l() {
    sp<DataSource> dataSource;

    if (!strncasecmp("http://", mUri.string(), 7)) {
        mConnectingDataSource = new NuHTTPDataSource;

        mLock.unlock();
        status_t err = mConnectingDataSource->connect(mUri, &mUriHeaders);
        mLock.lock();

        if (err != OK) {
            mConnectingDataSource.clear();

            LOGI("mConnectingDataSource->connect() returned %d", err);
            return err;
        }

#if 0
        mCachedSource = new NuCachedSource2(
                new ThrottledSource(
                    mConnectingDataSource, 50 * 1024 /* bytes/sec */));
#else
        mCachedSource = new NuCachedSource2(mConnectingDataSource);
#endif
        mConnectingDataSource.clear();

        dataSource = mCachedSource;

        // We're going to prefill the cache before trying to instantiate
        // the extractor below, as the latter is an operation that otherwise
        // could block on the datasource for a significant amount of time.
        // During that time we'd be unable to abort the preparation phase
        // without this prefill.

        mLock.unlock();

        for (;;) {
            status_t finalStatus;
            size_t cachedDataRemaining =
                mCachedSource->approxDataRemaining(&finalStatus);

            if (finalStatus != OK || cachedDataRemaining >= kHighWaterMarkBytes
                    || (mFlags & PREPARE_CANCELLED)) {
                break;
            }

            usleep(200000);
        }

        mLock.lock();

        if (mFlags & PREPARE_CANCELLED) {
            LOGI("Prepare cancelled while waiting for initial cache fill.");
            return UNKNOWN_ERROR;
        }
    } else if (!strncasecmp(mUri.string(), "httplive://", 11)) {
        String8 uri("http://");
        uri.append(mUri.string() + 11);

        if (mLooper == NULL) {
            mLooper = new ALooper;
            mLooper->setName("httplive");
            mLooper->start();
        }

        mLiveSession = new LiveSession;
        mLooper->registerHandler(mLiveSession);

        mLiveSession->connect(uri.string());
        dataSource = mLiveSession->getDataSource();

        sp<MediaExtractor> extractor =
            MediaExtractor::Create(dataSource, MEDIA_MIMETYPE_CONTAINER_MPEG2TS);

        static_cast<MPEG2TSExtractor *>(extractor.get())
            ->setLiveSession(mLiveSession);

        return setDataSource_l(extractor);
    } else if (!strncmp("rtsp://gtalk/", mUri.string(), 13)) {
        if (mLooper == NULL) {
            mLooper = new ALooper;
            mLooper->setName("gtalk rtp");
            mLooper->start(
                    false /* runOnCallingThread */,
                    false /* canCallJava */,
                    PRIORITY_HIGHEST);
        }

        const char *startOfCodecString = &mUri.string()[13];
        const char *startOfSlash1 = strchr(startOfCodecString, '/');
        if (startOfSlash1 == NULL) {
            return BAD_VALUE;
        }
        const char *startOfWidthString = &startOfSlash1[1];
        const char *startOfSlash2 = strchr(startOfWidthString, '/');
        if (startOfSlash2 == NULL) {
            return BAD_VALUE;
        }
        const char *startOfHeightString = &startOfSlash2[1];

        String8 codecString(startOfCodecString, startOfSlash1 - startOfCodecString);
        String8 widthString(startOfWidthString, startOfSlash2 - startOfWidthString);
        String8 heightString(startOfHeightString);

#if 0
        mRTPPusher = new UDPPusher("/data/misc/rtpout.bin", 5434);
        mLooper->registerHandler(mRTPPusher);

        mRTCPPusher = new UDPPusher("/data/misc/rtcpout.bin", 5435);
        mLooper->registerHandler(mRTCPPusher);
#endif

        mRTPSession = new ARTPSession;
        mLooper->registerHandler(mRTPSession);

#if 0
        // My AMR SDP
        static const char *raw =
            "v=0\r\n"
            "o=- 64 233572944 IN IP4 127.0.0.0\r\n"
            "s=QuickTime\r\n"
            "t=0 0\r\n"
            "a=range:npt=0-315\r\n"
            "a=isma-compliance:2,2.0,2\r\n"
            "m=audio 5434 RTP/AVP 97\r\n"
            "c=IN IP4 127.0.0.1\r\n"
            "b=AS:30\r\n"
            "a=rtpmap:97 AMR/8000/1\r\n"
            "a=fmtp:97 octet-align\r\n";
#elif 1
        String8 sdp;
        sdp.appendFormat(
            "v=0\r\n"
            "o=- 64 233572944 IN IP4 127.0.0.0\r\n"
            "s=QuickTime\r\n"
            "t=0 0\r\n"
            "a=range:npt=0-315\r\n"
            "a=isma-compliance:2,2.0,2\r\n"
            "m=video 5434 RTP/AVP 97\r\n"
            "c=IN IP4 127.0.0.1\r\n"
            "b=AS:30\r\n"
            "a=rtpmap:97 %s/90000\r\n"
            "a=cliprect:0,0,%s,%s\r\n"
            "a=framesize:97 %s-%s\r\n",

            codecString.string(),
            heightString.string(), widthString.string(),
            widthString.string(), heightString.string()
            );
        const char *raw = sdp.string();

#endif

        sp<ASessionDescription> desc = new ASessionDescription;
        CHECK(desc->setTo(raw, strlen(raw)));

        CHECK_EQ(mRTPSession->setup(desc), (status_t)OK);

        if (mRTPPusher != NULL) {
            mRTPPusher->start();
        }

        if (mRTCPPusher != NULL) {
            mRTCPPusher->start();
        }

        CHECK_EQ(mRTPSession->countTracks(), 1u);
        sp<MediaSource> source = mRTPSession->trackAt(0);

#if 0
        bool eos;
        while (((APacketSource *)source.get())
                ->getQueuedDuration(&eos) < 5000000ll && !eos) {
            usleep(100000ll);
        }
#endif

        const char *mime;
        CHECK(source->getFormat()->findCString(kKeyMIMEType, &mime));

        if (!strncasecmp("video/", mime, 6)) {
            setVideoSource(source);
        } else {
            CHECK(!strncasecmp("audio/", mime, 6));
            setAudioSource(source);
        }

        mExtractorFlags = MediaExtractor::CAN_PAUSE;

        return OK;
    } else if (!strncasecmp("rtsp://", mUri.string(), 7)) {
        if (mLooper == NULL) {
            mLooper = new ALooper;
            mLooper->setName("rtsp");
            mLooper->start();
        }
        mRTSPController = new ARTSPController(mLooper);
        status_t err = mRTSPController->connect(mUri.string());

        LOGI("ARTSPController::connect returned %d", err);

        if (err != OK) {
            mRTSPController.clear();
            return err;
        }

        sp<MediaExtractor> extractor = mRTSPController.get();
        return setDataSource_l(extractor);
    } else {
        dataSource = DataSource::CreateFromURI(mUri.string(), &mUriHeaders);
    }

    if (dataSource == NULL) {
        return UNKNOWN_ERROR;
    }

    sp<MediaExtractor> extractor = MediaExtractor::Create(dataSource);

    if (extractor == NULL) {
        return UNKNOWN_ERROR;
    }

    dataSource->getDrmInfo(&mDecryptHandle, &mDrmManagerClient);
    if (mDecryptHandle != NULL) {
        if (RightsStatus::RIGHTS_VALID == mDecryptHandle->status) {
            if (DecryptApiType::WV_BASED == mDecryptHandle->decryptApiType) {
                LOGD("Setting mCachedSource to NULL for WVM\n");
                mCachedSource.clear();
            }
        } else {
            notifyListener_l(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, ERROR_NO_LICENSE);
        }
    }

    return setDataSource_l(extractor);
}

void AwesomePlayer::abortPrepare(status_t err) {
    CHECK(err != OK);

    if (mIsAsyncPrepare) {
        notifyListener_l(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);
    }

    mPrepareResult = err;
    mFlags &= ~(PREPARING|PREPARE_CANCELLED|PREPARING_CONNECTED);
    mAsyncPrepareEvent = NULL;
    mPreparedCondition.broadcast();
}

// static
bool AwesomePlayer::ContinuePreparation(void *cookie) {
    AwesomePlayer *me = static_cast<AwesomePlayer *>(cookie);

    return (me->mFlags & PREPARE_CANCELLED) == 0;
}

void AwesomePlayer::onPrepareAsyncEvent() {
    Mutex::Autolock autoLock(mLock);

    if (mFlags & PREPARE_CANCELLED) {
        LOGI("prepare was cancelled before doing anything");
        abortPrepare(UNKNOWN_ERROR);
        return;
    }

    if (mUri.size() > 0) {
        status_t err = finishSetDataSource_l();

        if (err != OK) {
            abortPrepare(err);
            return;
        }
    }

    if (mVideoTrack != NULL && mVideoSource == NULL) {
        status_t err = initVideoDecoder();

        if (err != OK) {
            abortPrepare(err);
            return;
        }
    }

    if (mAudioTrack != NULL && mAudioSource == NULL) {
        status_t err = initAudioDecoder();

        if (err != OK) {
            abortPrepare(err);
            return;
        }
    }

    mFlags |= PREPARING_CONNECTED;

    if (mCachedSource != NULL || mRTSPController != NULL) {
        postBufferingEvent_l();
    } else {
        finishAsyncPrepare_l();
    }
}

void AwesomePlayer::finishAsyncPrepare_l() {
    if (mIsAsyncPrepare) {
        if (mVideoSource == NULL) {
            notifyListener_l(MEDIA_SET_VIDEO_SIZE, 0, 0);
        } else {
            notifyVideoSize_l();
        }

        notifyListener_l(MEDIA_PREPARED);
    }

    mPrepareResult = OK;
    mFlags &= ~(PREPARING|PREPARE_CANCELLED|PREPARING_CONNECTED);
    mFlags |= PREPARED;
    mAsyncPrepareEvent = NULL;
    mPreparedCondition.broadcast();
}

uint32_t AwesomePlayer::flags() const {
    return mExtractorFlags;
}

void AwesomePlayer::postAudioEOS() {
    postCheckAudioStatusEvent_l();
}

void AwesomePlayer::postAudioSeekComplete() {
    postCheckAudioStatusEvent_l();
}

}  // namespace android
