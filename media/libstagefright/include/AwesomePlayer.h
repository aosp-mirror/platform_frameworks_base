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

#ifndef AWESOME_PLAYER_H_

#define AWESOME_PLAYER_H_

#include "TimedEventQueue.h"

#include <media/MediaPlayerInterface.h>
#include <media/stagefright/OMXClient.h>
#include <utils/threads.h>

namespace android {

struct MediaBuffer;
struct MediaExtractor;
struct MediaSource;
struct AudioPlayer;
struct TimeSource;

struct AwesomeRenderer : public RefBase {
    AwesomeRenderer() {}

    virtual void render(MediaBuffer *buffer) = 0;

private:
    AwesomeRenderer(const AwesomeRenderer &);
    AwesomeRenderer &operator=(const AwesomeRenderer &);
};

struct AwesomePlayer {
    AwesomePlayer();
    ~AwesomePlayer();

    void setListener(const wp<MediaPlayerBase> &listener);

    status_t setDataSource(const char *uri);
    status_t setDataSource(int fd, int64_t offset, int64_t length);

    void reset();

    status_t play();
    status_t pause();

    bool isPlaying() const;

    void setISurface(const sp<ISurface> &isurface);
    void setAudioSink(const sp<MediaPlayerBase::AudioSink> &audioSink);
    status_t setLooping(bool shouldLoop);

    status_t getDuration(int64_t *durationUs);
    status_t getPosition(int64_t *positionUs);

    status_t seekTo(int64_t timeUs);

    status_t getVideoDimensions(int32_t *width, int32_t *height) const;

private:
    friend struct AwesomeEvent;

    enum Flags {
        PLAYING     = 1,
        LOOPING     = 2,
        FIRST_FRAME = 4,
    };

    mutable Mutex mLock;

    OMXClient mClient;
    TimedEventQueue mQueue;
    wp<MediaPlayerBase> mListener;

    sp<ISurface> mISurface;
    sp<MediaPlayerBase::AudioSink> mAudioSink;

    TimeSource *mTimeSource;

    sp<MediaSource> mVideoSource;
    sp<AwesomeRenderer> mVideoRenderer;

    sp<MediaSource> mAudioSource;
    AudioPlayer *mAudioPlayer;
    int64_t mDurationUs;

    uint32_t mFlags;

    int32_t mVideoWidth, mVideoHeight;
    int64_t mTimeSourceDeltaUs;
    int64_t mVideoTimeUs;

    bool mSeeking;
    int64_t mSeekTimeUs;

    sp<TimedEventQueue::Event> mVideoEvent;
    bool mVideoEventPending;
    sp<TimedEventQueue::Event> mStreamDoneEvent;
    bool mStreamDoneEventPending;

    void postVideoEvent_l(int64_t delayUs = -1);
    void postStreamDoneEvent_l();

    MediaBuffer *mLastVideoBuffer;
    MediaBuffer *mVideoBuffer;

    status_t setDataSource_l(const sp<MediaExtractor> &extractor);
    void reset_l();
    status_t seekTo_l(int64_t timeUs);
    status_t pause_l();
    void initRenderer_l();
    void seekAudioIfNecessary_l();

    void cancelPlayerEvents();

    status_t setAudioSource(const sp<MediaSource> &source);
    status_t setVideoSource(const sp<MediaSource> &source);

    void onEvent(int32_t code);

    static void AudioNotify(void *me, int what);
    void onStreamDone();

    void notifyListener_l(int msg);

    AwesomePlayer(const AwesomePlayer &);
    AwesomePlayer &operator=(const AwesomePlayer &);
};

}  // namespace android

#endif  // AWESOME_PLAYER_H_

