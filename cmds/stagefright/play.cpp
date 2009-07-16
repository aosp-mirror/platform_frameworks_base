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

#include <binder/ProcessState.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/TimedEventQueue.h>
#include <media/stagefright/MPEG4Extractor.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MmapSource.h>
#include <media/stagefright/OMXDecoder.h>

using namespace android;

struct NewPlayer {
    NewPlayer();
    ~NewPlayer();

    void setSource(const char *uri);
    void start();
    void pause();
    void stop();

private:
    struct PlayerEvent : public TimedEventQueue::Event {
        PlayerEvent(NewPlayer *player,
                    void (NewPlayer::*method)(int64_t realtime_us))
            : mPlayer(player),
              mMethod(method) {
        }

        virtual void fire(TimedEventQueue *queue, int64_t realtime_us) {
            (mPlayer->*mMethod)(realtime_us);
        }

    private:
        NewPlayer *mPlayer;
        void (NewPlayer::*mMethod)(int64_t realtime_us);

        PlayerEvent(const PlayerEvent &);
        PlayerEvent &operator=(const PlayerEvent &);
    };

    struct PlayVideoFrameEvent : public TimedEventQueue::Event {
        PlayVideoFrameEvent(NewPlayer *player, MediaBuffer *buffer)
            : mPlayer(player),
              mBuffer(buffer) {
        }

        virtual ~PlayVideoFrameEvent() {
            if (mBuffer != NULL) {
                mBuffer->release();
                mBuffer = NULL;
            }
        }

        virtual void fire(TimedEventQueue *queue, int64_t realtime_us) {
            mPlayer->onPlayVideoFrame(realtime_us, mBuffer);
            mBuffer = NULL;
        }

    private:
        NewPlayer *mPlayer;
        MediaBuffer *mBuffer;

        PlayVideoFrameEvent(const PlayVideoFrameEvent &);
        PlayVideoFrameEvent &operator=(const PlayVideoFrameEvent &);
    };

    OMXClient mClient;

    MPEG4Extractor *mExtractor;
    MediaSource *mAudioSource;
    OMXDecoder *mAudioDecoder;
    MediaSource *mVideoSource;
    OMXDecoder *mVideoDecoder;

    int32_t mVideoWidth, mVideoHeight;

    TimedEventQueue mQueue;
    wp<TimedEventQueue::Event> mPlayVideoFrameEvent;

    int64_t mMediaTimeUsStart;
    int64_t mRealTimeUsStart;

    void setAudioSource(MediaSource *source);
    void setVideoSource(MediaSource *source);

    int64_t approxRealTime(int64_t mediatime_us) const;

    void onStart(int64_t realtime_us);
    void onPause(int64_t realtime_us);
    void onFetchVideoFrame(int64_t realtime_us);
    void onPlayVideoFrame(int64_t realtime_us, MediaBuffer *buffer);

    static int64_t getMediaBufferTimeUs(MediaBuffer *buffer);

    NewPlayer(const NewPlayer &);
    NewPlayer &operator=(const NewPlayer &);
};

NewPlayer::NewPlayer()
    : mExtractor(NULL),
      mAudioSource(NULL),
      mAudioDecoder(NULL),
      mVideoSource(NULL),
      mVideoDecoder(NULL),
      mVideoWidth(0),
      mVideoHeight(0) {
    status_t err = mClient.connect();
    assert(err == OK);
}

NewPlayer::~NewPlayer() {
    stop();

    mClient.disconnect();
}

void NewPlayer::setSource(const char *uri) {
    stop();

    mExtractor = new MPEG4Extractor(new MmapSource(uri));

    int num_tracks;
    status_t err = mExtractor->countTracks(&num_tracks);
    assert(err == OK);

    for (int i = 0; i < num_tracks; ++i) {
        const sp<MetaData> meta = mExtractor->getTrackMetaData(i);
        assert(meta != NULL);

        const char *mime;
        if (!meta->findCString(kKeyMIMEType, &mime)) {
            continue;
        }

        bool is_audio = false;
        bool is_acceptable = false;
        if (!strncasecmp(mime, "audio/", 6)) {
            is_audio = true;
            is_acceptable = (mAudioSource == NULL);
        } else if (!strncasecmp(mime, "video/", 6)) {
            is_acceptable = (mVideoSource == NULL);
        }

        if (!is_acceptable) {
            continue;
        }

        MediaSource *source;
        if (mExtractor->getTrack(i, &source) != OK) {
            continue;
        }

        if (is_audio) {
            setAudioSource(source);
        } else {
            setVideoSource(source);
        }
    }
}

void NewPlayer::setAudioSource(MediaSource *source) {
    mAudioSource = source;

    sp<MetaData> meta = source->getFormat();

    mAudioDecoder = OMXDecoder::Create(&mClient, meta);
    mAudioDecoder->setSource(source);
}

void NewPlayer::setVideoSource(MediaSource *source) {
    mVideoSource = source;

    sp<MetaData> meta = source->getFormat();

    bool success = meta->findInt32(kKeyWidth, &mVideoWidth);
    assert(success);

    success = meta->findInt32(kKeyHeight, &mVideoHeight);
    assert(success);

    mVideoDecoder = OMXDecoder::Create(&mClient, meta);
    mVideoDecoder->setSource(source);
}

void NewPlayer::start() {
    mQueue.start();
    mQueue.postEvent(new PlayerEvent(this, &NewPlayer::onStart));
}

void NewPlayer::pause() {
    mQueue.postEvent(new PlayerEvent(this, &NewPlayer::onPause));
}

void NewPlayer::stop() {
    mQueue.stop();

    delete mVideoDecoder;
    mVideoDecoder = NULL;
    delete mVideoSource;
    mVideoSource = NULL;
    mVideoWidth = mVideoHeight = 0;

    delete mAudioDecoder;
    mAudioDecoder = NULL;
    delete mAudioSource;
    mAudioSource = NULL;

    delete mExtractor;
    mExtractor = NULL;
}

int64_t NewPlayer::approxRealTime(int64_t mediatime_us) const {
    return mRealTimeUsStart + (mediatime_us - mMediaTimeUsStart);
}

void NewPlayer::onStart(int64_t realtime_us) {
    mRealTimeUsStart = TimedEventQueue::getRealTimeUs();

    if (mVideoDecoder != NULL) {
        mQueue.postEvent(new PlayerEvent(this, &NewPlayer::onFetchVideoFrame));
    }
}

void NewPlayer::onFetchVideoFrame(int64_t realtime_us) {
    MediaBuffer *buffer;
    status_t err = mVideoDecoder->read(&buffer);
    assert(err == OK);

    int64_t mediatime_us = getMediaBufferTimeUs(buffer);

    sp<TimedEventQueue::Event> event = new PlayVideoFrameEvent(this, buffer);
    mPlayVideoFrameEvent = event;

    mQueue.postTimedEvent(event, approxRealTime(mediatime_us));
}

// static
int64_t NewPlayer::getMediaBufferTimeUs(MediaBuffer *buffer) {
    int32_t units, scale;
    bool success =
        buffer->meta_data()->findInt32(kKeyTimeUnits, &units);
    assert(success);
    success =
        buffer->meta_data()->findInt32(kKeyTimeScale, &scale);
    assert(success);

    return (int64_t)units * 1000000 / scale;
}

void NewPlayer::onPlayVideoFrame(int64_t realtime_us, MediaBuffer *buffer) {
    LOGI("playing video frame (mediatime: %.2f sec)\n",
         getMediaBufferTimeUs(buffer) / 1E6);
    fflush(stdout);

    buffer->release();
    buffer = NULL;

    mQueue.postEvent(new PlayerEvent(this, &NewPlayer::onFetchVideoFrame));
}

void NewPlayer::onPause(int64_t realtime_us) {
}

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    if (argc != 2) {
        fprintf(stderr, "usage: %s filename\n", argv[0]);
        return 1;
    }

    NewPlayer player;
    player.setSource(argv[1]);
    player.start();
    sleep(10);
    player.stop();

    return 0;
}
