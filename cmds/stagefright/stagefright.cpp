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

#undef NDEBUG
#include <assert.h>

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
#include <media/stagefright/MediaPlayerImpl.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MmapSource.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXDecoder.h>

#include "WaveWriter.h"

using namespace android;

////////////////////////////////////////////////////////////////////////////////

static bool convertToWav(
        OMXClient *client, const sp<MetaData> &meta, MediaSource *source) {
    printf("convertToWav\n");

    OMXDecoder *decoder = OMXDecoder::Create(client, meta);

    int32_t sampleRate;
    bool success = meta->findInt32(kKeySampleRate, &sampleRate);
    assert(success);

    int32_t numChannels;
    success = meta->findInt32(kKeyChannelCount, &numChannels);
    assert(success);

    const char *mime;
    success = meta->findCString(kKeyMIMEType, &mime);
    assert(success);

    if (!strcasecmp("audio/3gpp", mime)) {
        numChannels = 1;  // XXX
    }

    WaveWriter writer("/sdcard/Music/shoutcast.wav", numChannels, sampleRate);

    decoder->setSource(source);
    for (int i = 0; i < 100; ++i) {
        MediaBuffer *buffer;

        ::status_t err = decoder->read(&buffer);
        if (err != ::OK) {
            break;
        }

        writer.Append((const char *)buffer->data() + buffer->range_offset(),
                      buffer->range_length());

        buffer->release();
        buffer = NULL;
    }

    delete decoder;
    decoder = NULL;

    return true;
}

////////////////////////////////////////////////////////////////////////////////

static int64_t getNowUs() {
    struct timeval tv;
    gettimeofday(&tv, NULL);

    return (int64_t)tv.tv_usec + tv.tv_sec * 1000000;
}

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    bool audioOnly = false;
    if (argc > 1 && !strcmp(argv[1], "--list")) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder = sm->getService(String16("media.player"));
        sp<IMediaPlayerService> service = interface_cast<IMediaPlayerService>(binder);

        assert(service.get() != NULL);

        sp<IOMX> omx = service->createOMX();
        assert(omx.get() != NULL);

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

#if 0
    MediaPlayerImpl player(argv[1]);
    player.play();

    sleep(10000);
#else
    DataSource::RegisterDefaultSniffers();

    OMXClient client;
    status_t err = client.connect();

    MmapSource *dataSource = new MmapSource(argv[1]);
    MediaExtractor *extractor = MediaExtractor::Create(dataSource);
    dataSource = NULL;

    int numTracks;
    err = extractor->countTracks(&numTracks);

    sp<MetaData> meta;
    int i;
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

    OMXDecoder *decoder = OMXDecoder::Create(&client, meta);

    if (decoder != NULL) {
        MediaSource *source;
        err = extractor->getTrack(i, &source);

        decoder->setSource(source);

        decoder->start();

        int64_t startTime = getNowUs();

        int n = 0;
        MediaBuffer *buffer;
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

        delete decoder;
        decoder = NULL;

        delete source;
        source = NULL;
    }

    delete extractor;
    extractor = NULL;

    client.disconnect();
#endif

    return 0;
}
