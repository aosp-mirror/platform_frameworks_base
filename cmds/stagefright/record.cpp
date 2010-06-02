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

#include "SineSource.h"

#include <binder/ProcessState.h>
#include <media/stagefright/AudioPlayer.h>
#include <media/stagefright/CameraSource.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MPEG4Writer.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#include <media/MediaPlayerInterface.h>

using namespace android;

#if 1
class DummySource : public MediaSource {
    static const int32_t kFramerate = 24;  // fps

public:
    DummySource(int width, int height)
        : mWidth(width),
          mHeight(height),
          mSize((width * height * 3) / 2) {
        mGroup.add_buffer(new MediaBuffer(mSize));
    }

    virtual sp<MetaData> getFormat() {
        sp<MetaData> meta = new MetaData;
        meta->setInt32(kKeyWidth, mWidth);
        meta->setInt32(kKeyHeight, mHeight);
        meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_RAW);

        return meta;
    }

    virtual status_t start(MetaData *params) {
        mNumFramesOutput = 0;
        return OK;
    }

    virtual status_t stop() {
        return OK;
    }

    virtual status_t read(
            MediaBuffer **buffer, const MediaSource::ReadOptions *options) {
        if (mNumFramesOutput == kFramerate * 10) {
            // Stop returning data after 10 secs.
            return ERROR_END_OF_STREAM;
        }

        // printf("DummySource::read\n");
        status_t err = mGroup.acquire_buffer(buffer);
        if (err != OK) {
            return err;
        }

        char x = (char)((double)rand() / RAND_MAX * 255);
        memset((*buffer)->data(), x, mSize);
        (*buffer)->set_range(0, mSize);
        (*buffer)->meta_data()->clear();
        (*buffer)->meta_data()->setInt64(
                kKeyTime, (mNumFramesOutput * 1000000) / kFramerate);
        ++mNumFramesOutput;

        // printf("DummySource::read - returning buffer\n");
        // LOGI("DummySource::read - returning buffer");
        return OK;
    }

protected:
    virtual ~DummySource() {}

private:
    MediaBufferGroup mGroup;
    int mWidth, mHeight;
    size_t mSize;
    int64_t mNumFramesOutput;;

    DummySource(const DummySource &);
    DummySource &operator=(const DummySource &);
};

sp<MediaSource> createSource(const char *filename) {
    sp<MediaSource> source;

    sp<MediaExtractor> extractor =
        MediaExtractor::Create(new FileSource(filename));
    if (extractor == NULL) {
        return NULL;
    }

    size_t num_tracks = extractor->countTracks();

    sp<MetaData> meta;
    for (size_t i = 0; i < num_tracks; ++i) {
        meta = extractor->getTrackMetaData(i);
        CHECK(meta.get() != NULL);

        const char *mime;
        if (!meta->findCString(kKeyMIMEType, &mime)) {
            continue;
        }

        if (strncasecmp(mime, "video/", 6)) {
            continue;
        }

        source = extractor->getTrack(i);
        break;
    }

    return source;
}

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    DataSource::RegisterDefaultSniffers();

#if 1
    if (argc != 2) {
        fprintf(stderr, "usage: %s filename\n", argv[0]);
        return 1;
    }

    OMXClient client;
    CHECK_EQ(client.connect(), OK);

#if 0
    sp<MediaSource> source = createSource(argv[1]);

    if (source == NULL) {
        fprintf(stderr, "Unable to find a suitable video track.\n");
        return 1;
    }

    sp<MetaData> meta = source->getFormat();

    sp<MediaSource> decoder = OMXCodec::Create(
            client.interface(), meta, false /* createEncoder */, source);

    int width, height;
    bool success = meta->findInt32(kKeyWidth, &width);
    success = success && meta->findInt32(kKeyHeight, &height);
    CHECK(success);
#else
    int width = 720;
    int height = 480;
    sp<MediaSource> decoder = new DummySource(width, height);
#endif

    sp<MetaData> enc_meta = new MetaData;
    // enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_H263);
    // enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_MPEG4);
    enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);
    enc_meta->setInt32(kKeyWidth, width);
    enc_meta->setInt32(kKeyHeight, height);

    sp<MediaSource> encoder =
        OMXCodec::Create(
                client.interface(), enc_meta, true /* createEncoder */, decoder);

#if 1
    sp<MPEG4Writer> writer = new MPEG4Writer("/sdcard/output.mp4");
    writer->addSource(encoder);
    writer->start();
    while (!writer->reachedEOS()) {
        usleep(100000);
    }
    writer->stop();
#else
    encoder->start();

    MediaBuffer *buffer;
    while (encoder->read(&buffer) == OK) {
        int32_t isSync;
        if (!buffer->meta_data()->findInt32(kKeyIsSyncFrame, &isSync)) {
            isSync = false;
        }

        printf("got an output frame of size %d%s\n", buffer->range_length(),
               isSync ? " (SYNC)" : "");

        buffer->release();
        buffer = NULL;
    }

    encoder->stop();
#endif

    client.disconnect();
#endif

#if 0
    CameraSource *source = CameraSource::Create();
    source->start();

    printf("source = %p\n", source);

    for (int i = 0; i < 100; ++i) {
        MediaBuffer *buffer;
        status_t err = source->read(&buffer);
        CHECK_EQ(err, OK);

        printf("got a frame, data=%p, size=%d\n",
               buffer->data(), buffer->range_length());

        buffer->release();
        buffer = NULL;
    }

    source->stop();

    delete source;
    source = NULL;
#endif

    return 0;
}
#else

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    OMXClient client;
    CHECK_EQ(client.connect(), OK);

    const int32_t kSampleRate = 22050;
    const int32_t kNumChannels = 2;
    sp<MediaSource> audioSource = new SineSource(kSampleRate, kNumChannels);

#if 0
    sp<MediaPlayerBase::AudioSink> audioSink;
    AudioPlayer *player = new AudioPlayer(audioSink);
    player->setSource(audioSource);
    player->start();

    sleep(10);

    player->stop();
#endif

    sp<MetaData> encMeta = new MetaData;
    encMeta->setCString(kKeyMIMEType,
            1 ? MEDIA_MIMETYPE_AUDIO_AMR_WB : MEDIA_MIMETYPE_AUDIO_AAC);
    encMeta->setInt32(kKeySampleRate, kSampleRate);
    encMeta->setInt32(kKeyChannelCount, kNumChannels);
    encMeta->setInt32(kKeyMaxInputSize, 8192);

    sp<MediaSource> encoder =
        OMXCodec::Create(client.interface(), encMeta, true, audioSource);

    encoder->start();

    int32_t n = 0;
    status_t err;
    MediaBuffer *buffer;
    while ((err = encoder->read(&buffer)) == OK) {
        printf(".");
        fflush(stdout);

        buffer->release();
        buffer = NULL;

        if (++n == 100) {
            break;
        }
    }
    printf("$\n");

    encoder->stop();

    client.disconnect();

    return 0;
}
#endif
