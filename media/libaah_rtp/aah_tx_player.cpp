/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "LibAAH_RTP"
#include <utils/Log.h>

#include <netdb.h>
#include <netinet/ip.h>

#include <aah_timesrv/cc_helper.h>
#include <media/IMediaPlayer.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MetaData.h>
#include <utils/Timers.h>

#include "aah_tx_packet.h"
#include "aah_tx_player.h"

namespace android {

static int64_t kLowWaterMarkUs = 2000000ll;  // 2secs
static int64_t kHighWaterMarkUs = 10000000ll;  // 10secs
static const size_t kLowWaterMarkBytes = 40000;
static const size_t kHighWaterMarkBytes = 200000;

// When we start up, how much lead time should we put on the first access unit?
static const int64_t kAAHStartupLeadTimeUs = 300000LL;

// How much time do we attempt to lead the clock by in steady state?
static const int64_t kAAHBufferTimeUs = 1000000LL;

// how long do we keep data in our retransmit buffer after sending it.
const int64_t AAH_TXPlayer::kAAHRetryKeepAroundTimeNs =
    kAAHBufferTimeUs * 1100;

sp<MediaPlayerBase> createAAH_TXPlayer() {
    sp<MediaPlayerBase> ret = new AAH_TXPlayer();
    return ret;
}

template <typename T> static T clamp(T val, T min, T max) {
    if (val < min) {
        return min;
    } else if (val > max) {
        return max;
    } else {
        return val;
    }
}

struct AAH_TXEvent : public TimedEventQueue::Event {
    AAH_TXEvent(AAH_TXPlayer *player,
                void (AAH_TXPlayer::*method)()) : mPlayer(player)
                                                , mMethod(method) {}

  protected:
    virtual ~AAH_TXEvent() {}

    virtual void fire(TimedEventQueue *queue, int64_t /* now_us */) {
        (mPlayer->*mMethod)();
    }

  private:
    AAH_TXPlayer *mPlayer;
    void (AAH_TXPlayer::*mMethod)();

    AAH_TXEvent(const AAH_TXEvent &);
    AAH_TXEvent& operator=(const AAH_TXEvent &);
};

AAH_TXPlayer::AAH_TXPlayer()
        : mQueueStarted(false)
        , mFlags(0)
        , mExtractorFlags(0) {
    DataSource::RegisterDefaultSniffers();

    mBufferingEvent = new AAH_TXEvent(this, &AAH_TXPlayer::onBufferingUpdate);
    mBufferingEventPending = false;

    mPumpAudioEvent = new AAH_TXEvent(this, &AAH_TXPlayer::onPumpAudio);
    mPumpAudioEventPending = false;

    reset();
}

AAH_TXPlayer::~AAH_TXPlayer() {
    if (mQueueStarted) {
        mQueue.stop();
    }

    reset();
}

void AAH_TXPlayer::cancelPlayerEvents(bool keepBufferingGoing) {
    if (!keepBufferingGoing) {
        mQueue.cancelEvent(mBufferingEvent->eventID());
        mBufferingEventPending = false;

        mQueue.cancelEvent(mPumpAudioEvent->eventID());
        mPumpAudioEventPending = false;
    }
}

status_t AAH_TXPlayer::initCheck() {
    // Check for the presense of the A@H common time service by attempting to
    // query for CommonTime's frequency.  If we get an error back, we cannot
    // talk to the service at all and should abort now.
    status_t res;
    uint64_t freq;
    res = CCHelper::getCommonFreq(&freq);
    if (OK != res) {
        LOGE("Failed to connect to common time service!");
        return res;
    }

    return OK;
}

status_t AAH_TXPlayer::setDataSource(
        const char *url,
        const KeyedVector<String8, String8> *headers) {
    Mutex::Autolock autoLock(mLock);
    return setDataSource_l(url, headers);
}

status_t AAH_TXPlayer::setDataSource_l(
        const char *url,
        const KeyedVector<String8, String8> *headers) {
    reset_l();

    // the URL must consist of "aahTX://" followed by the real URL of
    // the data source
    const char *kAAHPrefix = "aahTX://";
    if (strncasecmp(url, kAAHPrefix, strlen(kAAHPrefix))) {
        return INVALID_OPERATION;
    }

    mUri.setTo(url + strlen(kAAHPrefix));

    if (headers) {
        mUriHeaders = *headers;

        ssize_t index = mUriHeaders.indexOfKey(String8("x-hide-urls-from-log"));
        if (index >= 0) {
            // Browser is in "incognito" mode, suppress logging URLs.

            // This isn't something that should be passed to the server.
            mUriHeaders.removeItemsAt(index);

            mFlags |= INCOGNITO;
        }
    }

    // The URL may optionally contain a "#" character followed by a Skyjam
    // cookie.  Ideally the cookie header should just be passed in the headers
    // argument, but the Java API for supplying headers is apparently not yet
    // exposed in the SDK used by application developers.
    const char kSkyjamCookieDelimiter = '#';
    char* skyjamCookie = strrchr(mUri.string(), kSkyjamCookieDelimiter);
    if (skyjamCookie) {
        skyjamCookie++;
        mUriHeaders.add(String8("Cookie"), String8(skyjamCookie));
        mUri = String8(mUri.string(), skyjamCookie - mUri.string());
    }

    return OK;
}

status_t AAH_TXPlayer::setDataSource(int fd, int64_t offset, int64_t length) {
    return INVALID_OPERATION;
}

status_t AAH_TXPlayer::setVideoSurface(const sp<Surface>& surface) {
    return OK;
}

status_t AAH_TXPlayer::setVideoSurfaceTexture(
        const sp<ISurfaceTexture>& surfaceTexture) {
    return OK;
}

status_t AAH_TXPlayer::prepare() {
    return INVALID_OPERATION;
}

status_t AAH_TXPlayer::prepareAsync() {
    Mutex::Autolock autoLock(mLock);

    return prepareAsync_l();
}

status_t AAH_TXPlayer::prepareAsync_l() {
    if (mFlags & PREPARING) {
        return UNKNOWN_ERROR;  // async prepare already pending
    }

    mAAH_Sender = AAH_TXSender::GetInstance();
    if (mAAH_Sender == NULL) {
        return NO_MEMORY;
    }

    if (!mQueueStarted) {
        mQueue.start();
        mQueueStarted = true;
    }

    mFlags |= PREPARING;
    mAsyncPrepareEvent = new AAH_TXEvent(
            this, &AAH_TXPlayer::onPrepareAsyncEvent);

    mQueue.postEvent(mAsyncPrepareEvent);

    return OK;
}

status_t AAH_TXPlayer::finishSetDataSource_l() {
    sp<DataSource> dataSource;

    if (!strncasecmp("http://",  mUri.string(), 7) ||
        !strncasecmp("https://", mUri.string(), 8)) {

        mConnectingDataSource = HTTPBase::Create(
                (mFlags & INCOGNITO)
                    ? HTTPBase::kFlagIncognito
                    : 0);

        mLock.unlock();
        status_t err = mConnectingDataSource->connect(mUri, &mUriHeaders);
        mLock.lock();

        if (err != OK) {
            mConnectingDataSource.clear();

            LOGI("mConnectingDataSource->connect() returned %d", err);
            return err;
        }

        mCachedSource = new NuCachedSource2(mConnectingDataSource);
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

            if (finalStatus != OK ||
                cachedDataRemaining >= kHighWaterMarkBytes ||
                (mFlags & PREPARE_CANCELLED)) {
                break;
            }

            usleep(200000);
        }

        mLock.lock();

        if (mFlags & PREPARE_CANCELLED) {
            LOGI("Prepare cancelled while waiting for initial cache fill.");
            return UNKNOWN_ERROR;
        }
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

    return setDataSource_l(extractor);
}

status_t AAH_TXPlayer::setDataSource_l(const sp<MediaExtractor> &extractor) {
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
    for (size_t i = 0; i < extractor->countTracks(); ++i) {
        sp<MetaData> meta = extractor->getTrackMetaData(i);

        const char *mime;
        CHECK(meta->findCString(kKeyMIMEType, &mime));

        if (!strncasecmp(mime, "audio/", 6)) {
            mAudioSource = extractor->getTrack(i);
            CHECK(mAudioSource != NULL);
            haveAudio = true;
            break;
        }
    }

    if (!haveAudio) {
        return UNKNOWN_ERROR;
    }

    mExtractorFlags = extractor->flags();

    return OK;
}

void AAH_TXPlayer::abortPrepare(status_t err) {
    CHECK(err != OK);

    notifyListener_l(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, err);

    mPrepareResult = err;
    mFlags &= ~(PREPARING|PREPARE_CANCELLED|PREPARING_CONNECTED);
    mPreparedCondition.broadcast();
}

void AAH_TXPlayer::onPrepareAsyncEvent() {
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

    mAudioSource->getFormat()->findInt64(kKeyDuration, &mDurationUs);

    mAudioSource->start();

    mFlags |= PREPARING_CONNECTED;

    if (mCachedSource != NULL) {
        postBufferingEvent_l();
    } else {
        finishAsyncPrepare_l();
    }
}

void AAH_TXPlayer::finishAsyncPrepare_l() {
    notifyListener_l(MEDIA_PREPARED);

    mPrepareResult = OK;
    mFlags &= ~(PREPARING|PREPARE_CANCELLED|PREPARING_CONNECTED);
    mFlags |= PREPARED;
    mPreparedCondition.broadcast();
}

status_t AAH_TXPlayer::start() {
    Mutex::Autolock autoLock(mLock);

    mFlags &= ~CACHE_UNDERRUN;

    return play_l();
}

status_t AAH_TXPlayer::play_l() {
    if (mFlags & PLAYING) {
        return OK;
    }

    if (!(mFlags & PREPARED)) {
        return INVALID_OPERATION;
    }

    {
        Mutex::Autolock lock(mEndpointLock);
        if (!mEndpointValid) {
            return INVALID_OPERATION;
        }
        if (!mEndpointRegistered) {
            mProgramID = mAAH_Sender->registerEndpoint(mEndpoint);
            mEndpointRegistered = true;
        }
    }

    mFlags |= PLAYING;

    updateClockTransform_l(false);

    postPumpAudioEvent_l(-1);

    return OK;
}

status_t AAH_TXPlayer::stop() {
    status_t ret = pause();
    sendEOS_l();
    return ret;
}

status_t AAH_TXPlayer::pause() {
    Mutex::Autolock autoLock(mLock);

    mFlags &= ~CACHE_UNDERRUN;

    return pause_l();
}

status_t AAH_TXPlayer::pause_l(bool doClockUpdate) {
    if (!(mFlags & PLAYING)) {
        return OK;
    }

    cancelPlayerEvents(true /* keepBufferingGoing */);

    mFlags &= ~PLAYING;

    if (doClockUpdate) {
        updateClockTransform_l(true);
    }

    return OK;
}

void AAH_TXPlayer::updateClockTransform_l(bool pause) {
    // record the new pause status so that onPumpAudio knows what rate to apply
    // when it initializes the transform
    mPlayRateIsPaused = pause;

    // if we haven't yet established a valid clock transform, then we can't
    // do anything here
    if (!mCurrentClockTransformValid) {
        return;
    }

    // sample the current common time
    int64_t commonTimeNow;
    if (OK != CCHelper::getCommonTime(&commonTimeNow)) {
        LOGE("updateClockTransform_l get common time failed");
        mCurrentClockTransformValid = false;
        return;
    }

    // convert the current common time to media time using the old
    // transform
    int64_t mediaTimeNow;
    if (!mCurrentClockTransform.doReverseTransform(
            commonTimeNow, &mediaTimeNow)) {
        LOGE("updateClockTransform_l reverse transform failed");
        mCurrentClockTransformValid = false;
        return;
    }

    // calculate a new transform that preserves the old transform's
    // result for the current time
    mCurrentClockTransform.a_zero = mediaTimeNow;
    mCurrentClockTransform.b_zero = commonTimeNow;
    mCurrentClockTransform.a_to_b_numer = 1;
    mCurrentClockTransform.a_to_b_denom = pause ? 0 : 1;

    // send a packet announcing the new transform
    sp<TRTPControlPacket> packet = new TRTPControlPacket();
    packet->setClockTransform(mCurrentClockTransform);
    packet->setCommandID(TRTPControlPacket::kCommandNop);
    queuePacketToSender_l(packet);
}

void AAH_TXPlayer::sendEOS_l() {
    sp<TRTPControlPacket> packet = new TRTPControlPacket();
    packet->setCommandID(TRTPControlPacket::kCommandEOS);
    queuePacketToSender_l(packet);
}

bool AAH_TXPlayer::isPlaying() {
    return (mFlags & PLAYING) || (mFlags & CACHE_UNDERRUN);
}

status_t AAH_TXPlayer::seekTo(int msec) {
    if (mExtractorFlags & MediaExtractor::CAN_SEEK) {
        Mutex::Autolock autoLock(mLock);
        return seekTo_l(static_cast<int64_t>(msec) * 1000);
    }

    notifyListener_l(MEDIA_SEEK_COMPLETE);
    return OK;
}

status_t AAH_TXPlayer::seekTo_l(int64_t timeUs) {
    mIsSeeking = true;
    mSeekTimeUs = timeUs;

    mCurrentClockTransformValid = false;
    mLastQueuedMediaTimePTSValid = false;

    // send a flush command packet
    sp<TRTPControlPacket> packet = new TRTPControlPacket();
    packet->setCommandID(TRTPControlPacket::kCommandFlush);
    queuePacketToSender_l(packet);

    return OK;
}

status_t AAH_TXPlayer::getCurrentPosition(int *msec) {
    if (!msec) {
        return BAD_VALUE;
    }

    Mutex::Autolock lock(mLock);

    int position;

    if (mIsSeeking) {
        position = mSeekTimeUs / 1000;
    } else if (mCurrentClockTransformValid) {
        // sample the current common time
        int64_t commonTimeNow;
        if (OK != CCHelper::getCommonTime(&commonTimeNow)) {
            LOGE("getCurrentPosition get common time failed");
            return INVALID_OPERATION;
        }

        int64_t mediaTimeNow;
        if (!mCurrentClockTransform.doReverseTransform(commonTimeNow,
                    &mediaTimeNow)) {
            LOGE("getCurrentPosition reverse transform failed");
            return INVALID_OPERATION;
        }

        position = static_cast<int>(mediaTimeNow / 1000);
    } else {
        position = 0;
    }

    int duration;
    if (getDuration_l(&duration) == OK) {
        *msec = clamp(position, 0, duration);
    } else {
        *msec = (position >= 0) ? position : 0;
    }

    return OK;
}

status_t AAH_TXPlayer::getDuration(int* msec) {
    if (!msec) {
        return BAD_VALUE;
    }

    Mutex::Autolock lock(mLock);

    return getDuration_l(msec);
}

status_t AAH_TXPlayer::getDuration_l(int* msec) {
    if (mDurationUs < 0) {
        return UNKNOWN_ERROR;
    }

    *msec = (mDurationUs + 500) / 1000;

    return OK;
}

status_t AAH_TXPlayer::reset() {
    Mutex::Autolock autoLock(mLock);
    reset_l();
    return OK;
}

void AAH_TXPlayer::reset_l() {
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

    sendEOS_l();

    mCachedSource.clear();

    if (mAudioSource != NULL) {
        mAudioSource->stop();
    }
    mAudioSource.clear();

    mFlags = 0;
    mExtractorFlags = 0;

    mDurationUs = -1;
    mIsSeeking = false;
    mSeekTimeUs = 0;

    mUri.setTo("");
    mUriHeaders.clear();

    mBitrate = -1;

    {
        Mutex::Autolock lock(mEndpointLock);
        if (mAAH_Sender != NULL && mEndpointRegistered) {
            mAAH_Sender->unregisterEndpoint(mEndpoint);
        }
        mEndpointRegistered = false;
        mEndpointValid = false;
    }

    mProgramID = 0;

    mAAH_Sender.clear();
    mLastQueuedMediaTimePTSValid = false;
    mCurrentClockTransformValid = false;
    mPlayRateIsPaused = false;

    mTRTPVolume = 255;
}

status_t AAH_TXPlayer::setLooping(int loop) {
    return OK;
}

player_type AAH_TXPlayer::playerType() {
    return AAH_TX_PLAYER;
}

status_t AAH_TXPlayer::setParameter(int key, const Parcel &request) {
    return ERROR_UNSUPPORTED;
}

status_t AAH_TXPlayer::getParameter(int key, Parcel *reply) {
    return ERROR_UNSUPPORTED;
}

status_t AAH_TXPlayer::invoke(const Parcel& request, Parcel *reply) {
    if (!reply) {
        return BAD_VALUE;
    }

    int32_t methodID;
    status_t err = request.readInt32(&methodID);
    if (err != android::OK) {
        return err;
    }

    switch (methodID) {
        case kInvokeSetAAHDstIPPort: {
            if (mEndpointValid) {
                return INVALID_OPERATION;
            }

            String16 addr = request.readString16();

            int32_t port32;
            err = request.readInt32(&port32);
            if (err != android::OK) {
                return err;
            }

            struct hostent* ent = gethostbyname(String8(addr).string());
            if (ent == NULL) {
                return ERROR_UNKNOWN_HOST;
            }
            if (!(ent->h_addrtype == AF_INET && ent->h_length == 4)) {
                return BAD_VALUE;
            }

            Mutex::Autolock lock(mEndpointLock);
            mEndpoint = AAH_TXSender::Endpoint(
                        reinterpret_cast<struct in_addr*>(ent->h_addr)->s_addr,
                        static_cast<uint16_t>(port32));
            mEndpointValid = true;
            return OK;
        };

        default:
            return INVALID_OPERATION;
    }
}

status_t AAH_TXPlayer::getMetadata(const media::Metadata::Filter& ids,
                                   Parcel* records) {
    using media::Metadata;

    Metadata metadata(records);

    metadata.appendBool(Metadata::kPauseAvailable, true);
    metadata.appendBool(Metadata::kSeekBackwardAvailable, false);
    metadata.appendBool(Metadata::kSeekForwardAvailable, false);
    metadata.appendBool(Metadata::kSeekAvailable, false);

    return OK;
}

status_t AAH_TXPlayer::setVolume(float leftVolume, float rightVolume) {
    if (leftVolume != rightVolume) {
        LOGE("%s does not support per channel volume: %f, %f",
             __PRETTY_FUNCTION__, leftVolume, rightVolume);
    }

    float volume = clamp(leftVolume, 0.0f, 1.0f);

    Mutex::Autolock lock(mLock);
    mTRTPVolume = static_cast<uint8_t>((leftVolume * 255.0) + 0.5);

    return OK;
}

status_t AAH_TXPlayer::setAudioStreamType(int streamType) {
    return OK;
}

void AAH_TXPlayer::notifyListener_l(int msg, int ext1, int ext2) {
    sendEvent(msg, ext1, ext2);
}

bool AAH_TXPlayer::getBitrate_l(int64_t *bitrate) {
    off64_t size;
    if (mDurationUs >= 0 &&
        mCachedSource != NULL &&
        mCachedSource->getSize(&size) == OK) {
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
bool AAH_TXPlayer::getCachedDuration_l(int64_t *durationUs, bool *eos) {
    int64_t bitrate;

    if (mCachedSource != NULL && getBitrate_l(&bitrate)) {
        status_t finalStatus;
        size_t cachedDataRemaining = mCachedSource->approxDataRemaining(
                                        &finalStatus);
        *durationUs = cachedDataRemaining * 8000000ll / bitrate;
        *eos = (finalStatus != OK);
        return true;
    }

    return false;
}

void AAH_TXPlayer::ensureCacheIsFetching_l() {
    if (mCachedSource != NULL) {
        mCachedSource->resumeFetchingIfNecessary();
    }
}

void AAH_TXPlayer::postBufferingEvent_l() {
    if (mBufferingEventPending) {
        return;
    }
    mBufferingEventPending = true;
    mQueue.postEventWithDelay(mBufferingEvent, 1000000ll);
}

void AAH_TXPlayer::postPumpAudioEvent_l(int64_t delayUs) {
    if (mPumpAudioEventPending) {
        return;
    }
    mPumpAudioEventPending = true;
    mQueue.postEventWithDelay(mPumpAudioEvent, delayUs < 0 ? 10000 : delayUs);
}

void AAH_TXPlayer::onBufferingUpdate() {
    Mutex::Autolock autoLock(mLock);
    if (!mBufferingEventPending) {
        return;
    }
    mBufferingEventPending = false;

    if (mCachedSource != NULL) {
        status_t finalStatus;
        size_t cachedDataRemaining = mCachedSource->approxDataRemaining(
                                        &finalStatus);
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
            if (getBitrate_l(&bitrate)) {
                size_t cachedSize = mCachedSource->cachedSize();
                int64_t cachedDurationUs = cachedSize * 8000000ll / bitrate;

                int percentage = (100.0 * (double) cachedDurationUs)
                               / mDurationUs;
                if (percentage > 100) {
                    percentage = 100;
                }

                notifyListener_l(MEDIA_BUFFERING_UPDATE, percentage);
            } else {
                // We don't know the bitrate of the stream, use absolute size
                // limits to maintain the cache.

                if ((mFlags & PLAYING) &&
                    !eos &&
                    (cachedDataRemaining < kLowWaterMarkBytes)) {
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

        if ((mFlags & PLAYING) &&
            !eos &&
            (cachedDurationUs < kLowWaterMarkUs)) {
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

void AAH_TXPlayer::onPumpAudio() {
    while (true) {
        Mutex::Autolock autoLock(mLock);
        // If this flag is clear, its because someone has externally canceled
        // this pump operation (probably because we a resetting/shutting down).
        // Get out immediately, do not reschedule ourselves.
        if (!mPumpAudioEventPending) {
            return;
        }

        // Start by checking if there is still work to be doing.  If we have
        // never queued a payload (so we don't know what the last queued PTS is)
        // or we have never established a MediaTime->CommonTime transformation,
        // then we have work to do (one time through this loop should establish
        // both).  Otherwise, we want to keep a fixed amt of presentation time
        // worth of data buffered.  If we cannot get common time (service is
        // unavailable, or common time is undefined)) then we don't have a lot
        // of good options here.  For now, signal an error up to the app level
        // and shut down the transmission pump.
        int64_t commonTimeNow;
        if (OK != CCHelper::getCommonTime(&commonTimeNow)) {
            // Failed to get common time; either the service is down or common
            // time is not synced.  Raise an error and shutdown the player.
            LOGE("*** Cannot pump audio, unable to fetch common time."
                 "  Shutting down.");
            notifyListener_l(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, UNKNOWN_ERROR);
            mPumpAudioEventPending = false;
            break;
        }

        if (mCurrentClockTransformValid && mLastQueuedMediaTimePTSValid) {
            int64_t mediaTimeNow;
            bool conversionResult = mCurrentClockTransform.doReverseTransform(
                                        commonTimeNow,
                                        &mediaTimeNow);
            CHECK(conversionResult);

            if ((mediaTimeNow +
                 kAAHBufferTimeUs -
                 mLastQueuedMediaTimePTS) <= 0) {
                break;
            }
        }

        MediaSource::ReadOptions options;
        if (mIsSeeking) {
            options.setSeekTo(mSeekTimeUs);
        }

        MediaBuffer* mediaBuffer;
        status_t err = mAudioSource->read(&mediaBuffer, &options);
        if (err != NO_ERROR) {
            if (err == ERROR_END_OF_STREAM) {
                LOGI("*** %s reached end of stream", __PRETTY_FUNCTION__);
                notifyListener_l(MEDIA_BUFFERING_UPDATE, 100);
                notifyListener_l(MEDIA_PLAYBACK_COMPLETE);
                pause_l(false);
                sendEOS_l();
            } else {
                LOGE("*** %s read failed err=%d", __PRETTY_FUNCTION__, err);
            }
            return;
        }

        if (mIsSeeking) {
            mIsSeeking = false;
            notifyListener_l(MEDIA_SEEK_COMPLETE);
        }

        uint8_t* data = (static_cast<uint8_t*>(mediaBuffer->data()) +
                mediaBuffer->range_offset());
        LOGV("*** %s got media buffer data=[%02hhx %02hhx %02hhx %02hhx]"
             " offset=%d length=%d", __PRETTY_FUNCTION__,
             data[0], data[1], data[2], data[3],
             mediaBuffer->range_offset(), mediaBuffer->range_length());

        int64_t mediaTimeUs;
        CHECK(mediaBuffer->meta_data()->findInt64(kKeyTime, &mediaTimeUs));
        LOGV("*** timeUs=%lld", mediaTimeUs);

        if (!mCurrentClockTransformValid) {
            if (OK == CCHelper::getCommonTime(&commonTimeNow)) {
                mCurrentClockTransform.a_zero = mediaTimeUs;
                mCurrentClockTransform.b_zero = commonTimeNow +
                                                kAAHStartupLeadTimeUs;
                mCurrentClockTransform.a_to_b_numer = 1;
                mCurrentClockTransform.a_to_b_denom = mPlayRateIsPaused ? 0 : 1;
                mCurrentClockTransformValid = true;
            } else {
                // Failed to get common time; either the service is down or
                // common time is not synced.  Raise an error and shutdown the
                // player.
                LOGE("*** Cannot begin transmission, unable to fetch common"
                     " time. Dropping sample with pts=%lld", mediaTimeUs);
                notifyListener_l(MEDIA_ERROR,
                                 MEDIA_ERROR_UNKNOWN,
                                 UNKNOWN_ERROR);
                mPumpAudioEventPending = false;
                break;
            }
        }

        LOGV("*** transmitting packet with pts=%lld", mediaTimeUs);

        sp<TRTPAudioPacket> packet = new TRTPAudioPacket();
        packet->setPTS(mediaTimeUs);
        packet->setSubstreamID(1);

        packet->setCodecType(TRTPAudioPacket::kCodecMPEG1Audio);
        packet->setVolume(mTRTPVolume);
        // TODO : introduce a throttle for this so we can control the
        // frequency with which transforms get sent.
        packet->setClockTransform(mCurrentClockTransform);
        packet->setAccessUnitData(data, mediaBuffer->range_length());
        packet->setRandomAccessPoint(true);

        queuePacketToSender_l(packet);
        mediaBuffer->release();

        mLastQueuedMediaTimePTSValid = true;
        mLastQueuedMediaTimePTS = mediaTimeUs;
    }

    { // Explicit scope for the autolock pattern.
        Mutex::Autolock autoLock(mLock);

        // If someone externally has cleared this flag, its because we should be
        // shutting down.  Do not reschedule ourselves.
        if (!mPumpAudioEventPending) {
            return;
        }

        // Looks like no one canceled us explicitly.  Clear our flag and post a
        // new event to ourselves.
        mPumpAudioEventPending = false;
        postPumpAudioEvent_l(10000);
    }
}

void AAH_TXPlayer::queuePacketToSender_l(const sp<TRTPPacket>& packet) {
    if (mAAH_Sender == NULL) {
        return;
    }

    sp<AMessage> message = new AMessage(AAH_TXSender::kWhatSendPacket,
                                        mAAH_Sender->handlerID());

    {
        Mutex::Autolock lock(mEndpointLock);
        if (!mEndpointValid) {
            return;
        }

        mAAH_Sender->assignSeqNumber(mEndpoint, packet);
        message->setInt32(AAH_TXSender::kSendPacketIPAddr, mEndpoint.addr);
        message->setInt32(AAH_TXSender::kSendPacketPort, mEndpoint.port);
    }

    packet->setProgramID(mProgramID);
    packet->setExpireTime(systemTime() + kAAHRetryKeepAroundTimeNs);
    packet->pack();

    message->setObject(AAH_TXSender::kSendPacketTRTPPacket, packet);

    message->post();
}

}  // namespace android
