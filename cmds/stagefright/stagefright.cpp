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

#include <sys/time.h>

#include <pthread.h>
#include <stdlib.h>

#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <media/IMediaPlayerService.h>
#include <media/stagefright/AudioPlayer.h>
#include <media/stagefright/CachingDataSource.h>
#include <media/stagefright/ESDS.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaPlayerImpl.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MmapSource.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#include <media/stagefright/OMXDecoder.h>

#include "WaveWriter.h"

using namespace android;

////////////////////////////////////////////////////////////////////////////////

struct JPEGSource : public MediaSource {
    // Assumes ownership of "source".
    JPEGSource(const sp<DataSource> &source);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~JPEGSource();

private:
    sp<DataSource> mSource;
    MediaBufferGroup *mGroup;
    bool mStarted;
    off_t mSize;
    int32_t mWidth, mHeight;
    off_t mOffset;

    status_t parseJPEG();

    JPEGSource(const JPEGSource &);
    JPEGSource &operator=(const JPEGSource &);
};

JPEGSource::JPEGSource(const sp<DataSource> &source)
    : mSource(source),
      mGroup(NULL),
      mStarted(false),
      mSize(0),
      mWidth(0),
      mHeight(0),
      mOffset(0) {
    CHECK_EQ(parseJPEG(), OK);
}

JPEGSource::~JPEGSource() {
    if (mStarted) {
        stop();
    }
}

status_t JPEGSource::start(MetaData *) {
    if (mStarted) {
        return UNKNOWN_ERROR;
    }

    if (mSource->getSize(&mSize) != OK) {
        return UNKNOWN_ERROR;
    }

    mGroup = new MediaBufferGroup;
    mGroup->add_buffer(new MediaBuffer(mSize));

    mOffset = 0;

    mStarted = true;
    
    return OK;
}

status_t JPEGSource::stop() {
    if (!mStarted) {
        return UNKNOWN_ERROR;
    }

    delete mGroup;
    mGroup = NULL;

    mStarted = false;

    return OK;
}

sp<MetaData> JPEGSource::getFormat() {
    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, "image/jpeg");
    meta->setInt32(kKeyWidth, mWidth);
    meta->setInt32(kKeyHeight, mHeight);

    return meta;
}

status_t JPEGSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    int64_t seekTimeUs;
    if (options != NULL && options->getSeekTo(&seekTimeUs)) {
        return UNKNOWN_ERROR;
    }

    MediaBuffer *buffer;
    mGroup->acquire_buffer(&buffer);

    ssize_t n = mSource->read_at(mOffset, buffer->data(), mSize - mOffset);

    if (n <= 0) {
        buffer->release();
        buffer = NULL;

        return UNKNOWN_ERROR;
    }

    buffer->set_range(0, n);

    mOffset += n;

    *out = buffer;

    return OK;
}

#define JPEG_SOF0  0xC0            /* nStart Of Frame N*/
#define JPEG_SOF1  0xC1            /* N indicates which compression process*/
#define JPEG_SOF2  0xC2            /* Only SOF0-SOF2 are now in common use*/
#define JPEG_SOF3  0xC3
#define JPEG_SOF5  0xC5            /* NB: codes C4 and CC are NOT SOF markers*/
#define JPEG_SOF6  0xC6
#define JPEG_SOF7  0xC7
#define JPEG_SOF9  0xC9
#define JPEG_SOF10 0xCA
#define JPEG_SOF11 0xCB
#define JPEG_SOF13 0xCD
#define JPEG_SOF14 0xCE
#define JPEG_SOF15 0xCF
#define JPEG_SOI   0xD8            /* nStart Of Image (beginning of datastream)*/
#define JPEG_EOI   0xD9            /* End Of Image (end of datastream)*/
#define JPEG_SOS   0xDA            /* nStart Of Scan (begins compressed data)*/
#define JPEG_JFIF  0xE0            /* Jfif marker*/
#define JPEG_EXIF  0xE1            /* Exif marker*/
#define JPEG_COM   0xFE            /* COMment */
#define JPEG_DQT   0xDB
#define JPEG_DHT   0xC4
#define JPEG_DRI   0xDD

status_t JPEGSource::parseJPEG() {
    mWidth = 0;
    mHeight = 0;

    off_t i = 0;

    uint16_t soi;
    if (!mSource->getUInt16(i, &soi)) {
        return ERROR_IO;
    }

    i += 2;

    if (soi != 0xffd8) {
        return UNKNOWN_ERROR;
    }

    for (;;) {
        uint8_t marker;
        if (mSource->read_at(i++, &marker, 1) != 1) {
            return ERROR_IO;
        }

        CHECK_EQ(marker, 0xff);

        if (mSource->read_at(i++, &marker, 1) != 1) {
            return ERROR_IO;
        }

        CHECK(marker != 0xff);

        uint16_t chunkSize;
        if (!mSource->getUInt16(i, &chunkSize)) {
            return ERROR_IO;
        }

        i += 2;

        if (chunkSize < 2) {
            return UNKNOWN_ERROR;
        }

        switch (marker) {
            case JPEG_SOS:
            {
                return (mWidth > 0 && mHeight > 0) ? OK : UNKNOWN_ERROR;
            }

            case JPEG_EOI:
            {
                return UNKNOWN_ERROR;
            }

            case JPEG_SOF0: 
            case JPEG_SOF1: 
            case JPEG_SOF3: 
            case JPEG_SOF5: 
            case JPEG_SOF6: 
            case JPEG_SOF7: 
            case JPEG_SOF9: 
            case JPEG_SOF10:
            case JPEG_SOF11:
            case JPEG_SOF13:
            case JPEG_SOF14:
            case JPEG_SOF15:
            {
                uint16_t width, height;
                if (!mSource->getUInt16(i + 1, &height)
                    || !mSource->getUInt16(i + 3, &width)) {
                    return ERROR_IO;
                }

                mWidth = width;
                mHeight = height;

                i += chunkSize - 2;
                break;
            }

            default:
            {
                // Skip chunk

                i += chunkSize - 2;

                break;
            }
        }
    }

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

static int64_t getNowUs() {
    struct timeval tv;
    gettimeofday(&tv, NULL);

    return (int64_t)tv.tv_usec + tv.tv_sec * 1000000;
}

#define USE_OMX_CODEC   1

static void playSource(OMXClient *client, const sp<MediaSource> &source) {
    sp<MetaData> meta = source->getFormat();

#if !USE_OMX_CODEC
    sp<OMXDecoder> decoder = OMXDecoder::Create(
            client, meta, false /* createEncoder */, source);
#else
    sp<OMXCodec> decoder = OMXCodec::Create(
            client->interface(), meta, false /* createEncoder */, source);
#endif

    if (decoder == NULL) {
        return;
    }

    decoder->start();

    int64_t startTime = getNowUs();

    int n = 0;
    MediaBuffer *buffer;
    status_t err;
    while ((err = decoder->read(&buffer)) == OK) {
        if ((++n % 16) == 0) {
            printf(".");
            fflush(stdout);
        }

        buffer->release();
        buffer = NULL;
    }
    decoder->stop();
    printf("\n");

    int64_t delay = getNowUs() - startTime;
    printf("avg. %.2f fps\n", n * 1E6 / delay);

    printf("decoded a total of %d frame(s).\n", n);
}

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    bool audioOnly = false;
    if (argc > 1 && !strcmp(argv[1], "--list")) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder = sm->getService(String16("media.player"));
        sp<IMediaPlayerService> service = interface_cast<IMediaPlayerService>(binder);

        CHECK(service.get() != NULL);

        sp<IOMX> omx = service->createOMX();
        CHECK(omx.get() != NULL);

        List<String8> list;
        omx->list_nodes(&list);

        for (List<String8>::iterator it = list.begin();
             it != list.end(); ++it) {
            printf("%s\n", (*it).string());
        }

        return 0;
    } else if (argc > 1 && !strcmp(argv[1], "--audio")) {
        audioOnly = true;
        ++argv;
        --argc;
    }

    DataSource::RegisterDefaultSniffers();

    OMXClient client;
    status_t err = client.connect();

    sp<MmapSource> dataSource = new MmapSource(argv[1]);

    bool isJPEG = false;

    size_t len = strlen(argv[1]);
    if (len >= 4 && !strcasecmp(argv[1] + len - 4, ".jpg")) {
        isJPEG = true;
    }

    sp<MediaSource> mediaSource;

    if (isJPEG) {
        mediaSource = new JPEGSource(dataSource);
    } else {
        sp<MediaExtractor> extractor = MediaExtractor::Create(dataSource);

        size_t numTracks = extractor->countTracks();

        sp<MetaData> meta;
        size_t i;
        for (i = 0; i < numTracks; ++i) {
            meta = extractor->getTrackMetaData(i);

            const char *mime;
            meta->findCString(kKeyMIMEType, &mime);

            if (audioOnly && !strncasecmp(mime, "audio/", 6)) {
                break;
            }

            if (!audioOnly && !strncasecmp(mime, "video/", 6)) {
                break;
            }
        }

        mediaSource = extractor->getTrack(i);
    }

    playSource(&client, mediaSource);

    client.disconnect();

    return 0;
}
