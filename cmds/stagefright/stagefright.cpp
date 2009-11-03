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
#include <media/stagefright/JPEGSource.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaPlayerImpl.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MmapSource.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>

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

    int64_t durationUs;
    CHECK(meta->findInt64(kKeyDuration, &durationUs));

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    sp<MediaSource> rawSource;
    if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_RAW, mime)) {
        rawSource = source;
    } else {
        rawSource = OMXCodec::Create(
            client->interface(), meta, false /* createEncoder */, source);

        if (rawSource == NULL) {
            fprintf(stderr, "Failed to instantiate decoder for '%s'.\n", mime);
            return;
        }
    }

    rawSource->start();

    if (gReproduceBug >= 3 && gReproduceBug <= 5) {
        status_t err;
        MediaBuffer *buffer;
        MediaSource::ReadOptions options;
        int64_t seekTimeUs = -1;
        for (;;) {
            err = rawSource->read(&buffer, &options);
            options.clearSeekTo();

            bool shouldSeek = false;
            if (err == INFO_FORMAT_CHANGED) {
                CHECK_EQ(buffer, NULL);

                printf("format changed.\n");
                continue;
            } else if (err != OK) {
                printf("reached EOF.\n");

                shouldSeek = true;
            } else {
                int64_t timestampUs;
                CHECK(buffer->meta_data()->findInt64(kKeyTime, &timestampUs));

                bool failed = false;

                if (seekTimeUs >= 0) {
                    int64_t diff = timestampUs - seekTimeUs;

                    if (diff < 0) {
                        diff = -diff;
                    }

                    if ((gReproduceBug == 4 && diff > 500000)
                        || (gReproduceBug == 5 && timestampUs < 0)) {
                        printf("wanted: %.2f secs, got: %.2f secs\n",
                               seekTimeUs / 1E6, timestampUs / 1E6);

                        printf("ERROR: ");
                        failed = true;
                    }
                }

                printf("buffer has timestamp %lld us (%.2f secs)\n",
                       timestampUs, timestampUs / 1E6);

                buffer->release();
                buffer = NULL;

                if (failed) {
                    break;
                }

                shouldSeek = ((double)rand() / RAND_MAX) < 0.1;

                if (gReproduceBug == 3) {
                    shouldSeek = false;
                }
            }

            seekTimeUs = -1;

            if (shouldSeek) {
                seekTimeUs = (rand() * (float)durationUs) / RAND_MAX;
                options.setSeekTo(seekTimeUs);

                printf("seeking to %lld us (%.2f secs)\n",
                       seekTimeUs, seekTimeUs / 1E6);
            }
        }

        rawSource->stop();

        return;
    }

    int n = 0;
    int64_t startTime = getNowUs();

    long numIterationsLeft = gNumRepetitions;
    MediaSource::ReadOptions options;

    while (numIterationsLeft-- > 0) {
        long numFrames = 0;

        MediaBuffer *buffer;

        for (;;) {
            status_t err = rawSource->read(&buffer, &options);
            options.clearSeekTo();

            if (err != OK) {
                CHECK_EQ(buffer, NULL);

                if (err == INFO_FORMAT_CHANGED) {
                    printf("format changed.\n");
                    continue;
                }

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
                options.setSeekTo(0x7fffffffL);
            } else if (gReproduceBug == 2 && numFrames == 40) {
                printf("seeking to 5 secs.");
                options.setSeekTo(5000000);
            }
        }

        printf("$");
        fflush(stdout);

        options.setSeekTo(0);
    }

    rawSource->stop();
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
    fprintf(stderr, "       -p(rofiles) dump decoder profiles supported\n");
}

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    bool audioOnly = false;
    bool listComponents = false;
    bool dumpProfiles = false;
    gNumRepetitions = 1;
    gMaxNumFrames = 0;
    gReproduceBug = -1;

    int res;
    while ((res = getopt(argc, argv, "han:lm:b:p")) >= 0) {
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

            case 'p':
            {
                dumpProfiles = true;
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

    if (dumpProfiles) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder = sm->getService(String16("media.player"));
        sp<IMediaPlayerService> service =
            interface_cast<IMediaPlayerService>(binder);

        CHECK(service.get() != NULL);

        sp<IOMX> omx = service->getOMX();
        CHECK(omx.get() != NULL);

        const char *kMimeTypes[] = {
            MEDIA_MIMETYPE_VIDEO_AVC, MEDIA_MIMETYPE_VIDEO_MPEG4,
            MEDIA_MIMETYPE_VIDEO_H263
        };

        for (size_t k = 0; k < sizeof(kMimeTypes) / sizeof(kMimeTypes[0]);
             ++k) {
            printf("type '%s':\n", kMimeTypes[k]);

            Vector<CodecCapabilities> results;
            CHECK_EQ(QueryCodecs(omx, kMimeTypes[k],
                                 true, // queryDecoders
                                 &results), OK);

            for (size_t i = 0; i < results.size(); ++i) {
                printf("  decoder '%s' supports ",
                       results[i].mComponentName.string());

                if (results[i].mProfileLevels.size() == 0) {
                    printf("NOTHING.\n");
                    continue;
                }

                for (size_t j = 0; j < results[i].mProfileLevels.size(); ++j) {
                    const CodecProfileLevel &profileLevel =
                        results[i].mProfileLevels[j];

                    printf("%s%ld/%ld", j > 0 ? ", " : "",
                           profileLevel.mProfile, profileLevel.mLevel);
                }

                printf("\n");
            }
        }
    }

    if (listComponents) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder = sm->getService(String16("media.player"));
        sp<IMediaPlayerService> service = interface_cast<IMediaPlayerService>(binder);

        CHECK(service.get() != NULL);

        sp<IOMX> omx = service->getOMX();
        CHECK(omx.get() != NULL);

        List<String8> list;
        omx->listNodes(&list);

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
