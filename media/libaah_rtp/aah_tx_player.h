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

#ifndef __AAH_TX_PLAYER_H__
#define __AAH_TX_PLAYER_H__

#include <libstagefright/include/HTTPBase.h>
#include <libstagefright/include/NuCachedSource2.h>
#include <libstagefright/include/TimedEventQueue.h>
#include <media/MediaPlayerInterface.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaSource.h>
#include <utils/LinearTransform.h>
#include <utils/String8.h>
#include <utils/threads.h>

#include "aah_tx_sender.h"

namespace android {

class AAH_TXPlayer : public MediaPlayerHWInterface {
  public:
    AAH_TXPlayer();

    virtual status_t    initCheck();
    virtual status_t    setDataSource(const char *url,
                                      const KeyedVector<String8, String8>*
                                      headers);
    virtual status_t    setDataSource(int fd, int64_t offset, int64_t length);
    virtual status_t    setVideoSurface(const sp<Surface>& surface);
    virtual status_t    setVideoSurfaceTexture(const sp<ISurfaceTexture>&
                                               surfaceTexture);
    virtual status_t    prepare();
    virtual status_t    prepareAsync();
    virtual status_t    start();
    virtual status_t    stop();
    virtual status_t    pause();
    virtual bool        isPlaying();
    virtual status_t    seekTo(int msec);
    virtual status_t    getCurrentPosition(int *msec);
    virtual status_t    getDuration(int *msec);
    virtual status_t    reset();
    virtual status_t    setLooping(int loop);
    virtual player_type playerType();
    virtual status_t    setParameter(int key, const Parcel &request);
    virtual status_t    getParameter(int key, Parcel *reply);
    virtual status_t    invoke(const Parcel& request, Parcel *reply);
    virtual status_t    getMetadata(const media::Metadata::Filter& ids,
                                    Parcel* records);
    virtual status_t    setVolume(float leftVolume, float rightVolume);
    virtual status_t    setAudioStreamType(int streamType);

    // invoke method IDs
    enum {
        // set the IP address and port of the A@H receiver
        kInvokeSetAAHDstIPPort = 1,
    };

    static const int64_t kAAHRetryKeepAroundTimeNs;

  protected:
    virtual ~AAH_TXPlayer();

  private:
    friend struct AwesomeEvent;

    enum {
        PLAYING             = 1,
        PREPARING           = 8,
        PREPARED            = 16,
        PREPARE_CANCELLED   = 64,
        CACHE_UNDERRUN      = 128,

        // We are basically done preparing but are currently buffering
        // sufficient data to begin playback and finish the preparation
        // phase for good.
        PREPARING_CONNECTED = 2048,

        INCOGNITO           = 32768,
    };

    status_t setDataSource_l(const char *url,
                             const KeyedVector<String8, String8> *headers);
    status_t setDataSource_l(const sp<MediaExtractor>& extractor);
    status_t finishSetDataSource_l();
    status_t prepareAsync_l();
    void onPrepareAsyncEvent();
    void finishAsyncPrepare_l();
    void abortPrepare(status_t err);
    status_t play_l();
    status_t pause_l(bool doClockUpdate = true);
    status_t seekTo_l(int64_t timeUs);
    void updateClockTransform_l(bool pause);
    void sendEOS_l();
    void cancelPlayerEvents(bool keepBufferingGoing = false);
    void reset_l();
    void notifyListener_l(int msg, int ext1 = 0, int ext2 = 0);
    bool getBitrate_l(int64_t* bitrate);
    status_t getDuration_l(int* msec);
    bool getCachedDuration_l(int64_t* durationUs, bool* eos);
    void ensureCacheIsFetching_l();
    void postBufferingEvent_l();
    void postPumpAudioEvent_l(int64_t delayUs);
    void onBufferingUpdate();
    void onPumpAudio();
    void queuePacketToSender_l(const sp<TRTPPacket>& packet);

    Mutex mLock;

    TimedEventQueue mQueue;
    bool mQueueStarted;

    sp<TimedEventQueue::Event> mBufferingEvent;
    bool mBufferingEventPending;

    uint32_t mFlags;
    uint32_t mExtractorFlags;

    String8 mUri;
    KeyedVector<String8, String8> mUriHeaders;

    sp<TimedEventQueue::Event> mAsyncPrepareEvent;
    Condition mPreparedCondition;
    status_t mPrepareResult;

    bool mIsSeeking;
    int64_t mSeekTimeUs;

    sp<TimedEventQueue::Event> mPumpAudioEvent;
    bool mPumpAudioEventPending;

    sp<HTTPBase> mConnectingDataSource;
    sp<NuCachedSource2> mCachedSource;

    sp<MediaSource> mAudioSource;
    int64_t mDurationUs;
    int64_t mBitrate;

    sp<AAH_TXSender> mAAH_Sender;
    LinearTransform  mCurrentClockTransform;
    bool             mCurrentClockTransformValid;
    int64_t          mLastQueuedMediaTimePTS;
    bool             mLastQueuedMediaTimePTSValid;
    bool             mPlayRateIsPaused;

    Mutex mEndpointLock;
    AAH_TXSender::Endpoint mEndpoint;
    bool mEndpointValid;
    bool mEndpointRegistered;
    uint16_t mProgramID;

    uint8_t mTRTPVolume;

    DISALLOW_EVIL_CONSTRUCTORS(AAH_TXPlayer);
};

}  // namespace android

#endif  // __AAH_TX_PLAYER_H__
