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

#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <media/IMediaPlayerService.h>
#include <media/stagefright/CachingDataSource.h>
#include <media/stagefright/HTTPDataSource.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaPlayerImpl.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MmapSource.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>

#include "JPEGSource.h"

using namespace android;

static long gNumRepetitions;
static long gMaxNumFrames;  // 0 means decode all available.
static long gReproduceBug;  // if not -1.

static int64_t getNowUs() {
    struct timeval tv;
    gettimeofday(&tv, NULL);

    return (int64_t)tv.tv_usec + tv.tv_sec * 1000000;
}

static void playSource(OMXClient *client, const sp<MediaSource> &source) {
    sp<MetaData> meta = source->getFormat();

    sp<OMXCodec> decoder = OMXCodec::Create(
            client->interface(), meta, false /* createEncoder */, source);

    if (decoder == NULL) {
        return;
    }

    decoder->start();

    int n = 0;
    int64_t startTime = getNowUs();

    long numIterationsLeft = gNumRepetitions;
    MediaSource::ReadOptions options;

    while (numIterationsLeft-- > 0) {
        long numFrames = 0;

        MediaBuffer *buffer;

        for (;;) {
            status_t err = decoder->read(&buffer, &options);
            options.clearSeekTo();

            if (err != OK) {
                CHECK_EQ(buffer, NULL);
                break;
            }

            if ((n++ % 16) == 0) {
                printf(".");
                fflush(stdout);
            }

            buffer->release();
            buffer = NULL;

            ++numFrames;
            if (gMaxNumFrames > 0 && numFrames == gMaxNumFrames) {
                break;
            }

            if (gReproduceBug == 1 && numFrames == 40) {
                printf("seeking past the end now.");
                options.setSeekTo(LONG_MAX);
            }
        }

        printf("$");
        fflush(stdout);

        options.setSeekTo(0);
    }

    decoder->stop();
    printf("\n");

    int64_t delay = getNowUs() - startTime;
    printf("avg. %.2f fps\n", n * 1E6 / delay);

    printf("decoded a total of %d frame(s).\n", n);
}

static void usage(const char *me) {
    fprintf(stderr, "usage: %s\n", me);
    fprintf(stderr, "       -h(elp)\n");
    fprintf(stderr, "       -a(udio)\n");
    fprintf(stderr, "       -n repetitions\n");
    fprintf(stderr, "       -l(ist) components\n");
    fprintf(stderr, "       -m max-number-of-frames-to-decode in each pass\n");
    fprintf(stderr, "       -b bug to reproduce\n");
}

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    bool audioOnly = false;
    bool listComponents = false;
    gNumRepetitions = 1;
    gMaxNumFrames = 0;
    gReproduceBug = -1;

    int res;
    while ((res = getopt(argc, argv, "han:lm:b:")) >= 0) {
        switch (res) {
            case 'a':
            {
                audioOnly = true;
                break;
            }

            case 'l':
            {
                listComponents = true;
                break;
            }

            case 'm':
            case 'n':
            case 'b':
            {
                char *end;
                long x = strtol(optarg, &end, 10);

                if (*end != '\0' || end == optarg || x <= 0) {
                    x = 1;
                }

                if (res == 'n') {
                    gNumRepetitions = x;
                } else if (res == 'm') {
                    gMaxNumFrames = x;
                } else {
                    CHECK_EQ(res, 'b');
                    gReproduceBug = x;
                }
                break;
            }

            case '?':
            case 'h':
            default:
            {
                usage(argv[0]);
                exit(1);
                break;
            }
        }
    }

    argc -= optind;
    argv += optind;

    if (listComponents) {
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
    }

    DataSource::RegisterDefaultSniffers();

    OMXClient client;
    status_t err = client.connect();

    for (int k = 0; k < argc; ++k) {
        const char *filename = argv[k];

        sp<DataSource> dataSource;
        if (!strncasecmp("http://", filename, 7)) {
            dataSource = new HTTPDataSource(filename);
            dataSource = new CachingDataSource(dataSource, 64 * 1024, 10);
        } else {
            dataSource = new MmapSource(filename);
        }

        bool isJPEG = false;

        size_t len = strlen(filename);
        if (len >= 4 && !strcasecmp(filename + len - 4, ".jpg")) {
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
    }

    client.disconnect();

    return 0;
}
