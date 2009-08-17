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

#ifndef MEDIA_PLAYER_IMPL_H_

#define MEDIA_PLAYER_IMPL_H_

#include <pthread.h>

#include <media/MediaPlayerInterface.h>
#include <media/stagefright/OMXClient.h>
#include <utils/RefBase.h>
#include <utils/threads.h>

namespace android {

class AudioPlayer;
class IOMXRenderer;
class ISurface;
class MediaExtractor;
class MediaBuffer;
class MediaSource;
class MemoryHeapPmem;
class MetaData;
class Surface;
class TimeSource;

class MediaPlayerImpl {
public:
    MediaPlayerImpl(const char *uri);

    status_t initCheck() const;

    // Assumes ownership of "fd".
    MediaPlayerImpl(int fd, int64_t offset, int64_t length);

    ~MediaPlayerImpl();

    void play();
    void pause();
    bool isPlaying() const;

    void setSurface(const sp<Surface> &surface);
    void setISurface(const sp<ISurface> &isurface);

    void setAudioSink(const sp<MediaPlayerBase::AudioSink> &audioSink);

    int32_t getWidth() const { return mVideoWidth; }
    int32_t getHeight() const { return mVideoHeight; }

    int64_t getDuration();
    int64_t getPosition();
    status_t seekTo(int64_t time);

private:
    status_t mInitCheck;

    OMXClient mClient;

    sp<MediaExtractor> mExtractor;

    TimeSource *mTimeSource;

    sp<MediaSource> mAudioSource;
    sp<MediaSource> mAudioDecoder;
    AudioPlayer *mAudioPlayer;

    sp<MediaSource> mVideoSource;
    sp<MediaSource> mVideoDecoder;
    int32_t mVideoWidth, mVideoHeight;
    int64_t mVideoPosition;

    int64_t mDuration;

    bool mPlaying;
    bool mPaused;

    int64_t mTimeSourceDeltaUs;

    sp<Surface> mSurface;
    sp<ISurface> mISurface;
    sp<IOMXRenderer> mVideoRenderer;

    sp<MediaPlayerBase::AudioSink> mAudioSink;

    Mutex mLock;
    pthread_t mVideoThread;

    bool mSeeking;
    int64_t mSeekTimeUs;

    void init();

    static void *VideoWrapper(void *me);
    void videoEntry();

    void setAudioSource(const sp<MediaSource> &source);
    void setVideoSource(const sp<MediaSource> &source);

    MediaSource *makeShoutcastSource(const char *path);

    void displayOrDiscardFrame(MediaBuffer *buffer, int64_t pts_us);
    void populateISurface();
    void depopulateISurface();
    void sendFrameToISurface(MediaBuffer *buffer);

    void stop();

    MediaPlayerImpl(const MediaPlayerImpl &);
    MediaPlayerImpl &operator=(const MediaPlayerImpl &);
};

}  // namespace android

#endif  // MEDIA_PLAYER_IMPL_H_
