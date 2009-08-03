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

#undef NDEBUG
#include <assert.h>

#include <binder/ProcessState.h>
#include <media/stagefright/CameraSource.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MPEG4Extractor.h>
#include <media/stagefright/MPEG4Writer.h>
#include <media/stagefright/MmapSource.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXDecoder.h>

using namespace android;

class DummySource : public MediaSource {
public:
    DummySource(int width, int height)
        : mSize((width * height * 3) / 2) {
        mGroup.add_buffer(new MediaBuffer(mSize));
    }

    virtual ::status_t getMaxSampleSize(size_t *max_size) {
        *max_size = mSize;
        return ::OK;
    }

    virtual ::status_t read(MediaBuffer **buffer) {
        ::status_t err = mGroup.acquire_buffer(buffer);
        if (err != ::OK) {
            return err;
        }

        char x = (char)((double)rand() / RAND_MAX * 255);
        memset((*buffer)->data(), x, mSize);
        (*buffer)->set_range(0, mSize);

        return ::OK;
    }

private:
    MediaBufferGroup mGroup;
    size_t mSize;

    DummySource(const DummySource &);
    DummySource &operator=(const DummySource &);
};

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

#if 1
    if (argc != 2) {
        fprintf(stderr, "usage: %s filename\n", argv[0]);
        return 1;
    }

    MPEG4Extractor extractor(new MmapSource(argv[1]));
    int num_tracks;
    assert(extractor.countTracks(&num_tracks) == ::OK);

    MediaSource *source = NULL;
    sp<MetaData> meta;
    for (int i = 0; i < num_tracks; ++i) {
        meta = extractor.getTrackMetaData(i);
        assert(meta.get() != NULL);

        const char *mime;
        if (!meta->findCString(kKeyMIMEType, &mime)) {
            continue;
        }

        if (strncasecmp(mime, "video/", 6)) {
            continue;
        }

        if (extractor.getTrack(i, &source) != ::OK) {
            source = NULL;
            continue;
        }
        break;
    }

    if (source == NULL) {
        fprintf(stderr, "Unable to find a suitable video track.\n");
        return 1;
    }

    OMXClient client;
    assert(client.connect() == android::OK);

    OMXDecoder *decoder = OMXDecoder::Create(&client, meta);
    decoder->setSource(source);

    int width, height;
    bool success = meta->findInt32(kKeyWidth, &width);
    success = success && meta->findInt32(kKeyHeight, &height);
    assert(success);

    sp<MetaData> enc_meta = new MetaData;
    enc_meta->setCString(kKeyMIMEType, "video/3gpp");
    // enc_meta->setCString(kKeyMIMEType, "video/mp4v-es");
    enc_meta->setInt32(kKeyWidth, width);
    enc_meta->setInt32(kKeyHeight, height);

    OMXDecoder *encoder =
        OMXDecoder::Create(&client, enc_meta, true /* createEncoder */);

    encoder->setSource(decoder);
    // encoder->setSource(meta, new DummySource(width, height));

#if 1
    MPEG4Writer writer("/sdcard/output.mp4");
    writer.addSource(enc_meta, encoder);
    writer.start();
    sleep(20);
    printf("stopping now.\n");
    writer.stop();
#else
    encoder->start();

    MediaBuffer *buffer;
    while (encoder->read(&buffer) == ::OK) {
        printf("got an output frame of size %d\n", buffer->range_length());

        buffer->release();
        buffer = NULL;
    }

    encoder->stop();
#endif

    delete encoder;
    encoder = NULL;

    delete decoder;
    decoder = NULL;

    client.disconnect();

    delete source;
    source = NULL;
#endif

#if 0
    CameraSource *source = CameraSource::Create();
    printf("source = %p\n", source);

    for (int i = 0; i < 100; ++i) {
        MediaBuffer *buffer;
        status_t err = source->read(&buffer);
        assert(err == OK);

        printf("got a frame, data=%p, size=%d\n",
               buffer->data(), buffer->range_length());

        buffer->release();
        buffer = NULL;
    }

    delete source;
    source = NULL;
#endif

    return 0;
}

