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

//#define LOG_NDEBUG 0
#define LOG_TAG "stagefright"
#include <media/stagefright/foundation/ADebug.h>

#include <sys/time.h>

#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "SineSource.h"

#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <media/IMediaPlayerService.h>
#include <media/stagefright/foundation/ALooper.h>
#include "include/ARTSPController.h"
#include "include/LiveSource.h"
#include "include/NuCachedSource2.h"
#include <media/stagefright/AudioPlayer.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/JPEGSource.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#include <media/mediametadataretriever.h>

#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/MPEG2TSWriter.h>
#include <media/stagefright/MPEG4Writer.h>

#include <fcntl.h>

using namespace android;

static long gNumRepetitions;
static long gMaxNumFrames;  // 0 means decode all available.
static long gReproduceBug;  // if not -1.
static bool gPreferSoftwareCodec;
static bool gPlaybackAudio;
static bool gWriteMP4;
static String8 gWriteMP4Filename;

static int64_t getNowUs() {
    struct timeval tv;
    gettimeofday(&tv, NULL);

    return (int64_t)tv.tv_usec + tv.tv_sec * 1000000ll;
}

static void playSource(OMXClient *client, sp<MediaSource> &source) {
    sp<MetaData> meta = source->getFormat();

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    sp<MediaSource> rawSource;
    if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_RAW, mime)) {
        rawSource = source;
    } else {
        rawSource = OMXCodec::Create(
            client->interface(), meta, false /* createEncoder */, source,
            NULL /* matchComponentName */,
            gPreferSoftwareCodec ? OMXCodec::kPreferSoftwareCodecs : 0);

        if (rawSource == NULL) {
            fprintf(stderr, "Failed to instantiate decoder for '%s'.\n", mime);
            return;
        }
    }

    source.clear();

    status_t err = rawSource->start();

    if (err != OK) {
        fprintf(stderr, "rawSource returned error %d (0x%08x)\n", err, err);
        return;
    }

    if (gPlaybackAudio) {
        AudioPlayer *player = new AudioPlayer(NULL);
        player->setSource(rawSource);
        rawSource.clear();

        player->start(true /* sourceAlreadyStarted */);

        status_t finalStatus;
        while (!player->reachedEOS(&finalStatus)) {
            usleep(100000ll);
        }

        delete player;
        player = NULL;

        return;
    } else if (gReproduceBug >= 3 && gReproduceBug <= 5) {
        int64_t durationUs;
        CHECK(meta->findInt64(kKeyDuration, &durationUs));

        status_t err;
        MediaBuffer *buffer;
        MediaSource::ReadOptions options;
        int64_t seekTimeUs = -1;
        for (;;) {
            err = rawSource->read(&buffer, &options);
            options.clearSeekTo();

            bool shouldSeek = false;
            if (err == INFO_FORMAT_CHANGED) {
                CHECK(buffer == NULL);

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

    int64_t sumDecodeUs = 0;
    int64_t totalBytes = 0;

    while (numIterationsLeft-- > 0) {
        long numFrames = 0;

        MediaBuffer *buffer;

        for (;;) {
            int64_t startDecodeUs = getNowUs();
            status_t err = rawSource->read(&buffer, &options);
            int64_t delayDecodeUs = getNowUs() - startDecodeUs;

            options.clearSeekTo();

            if (err != OK) {
                CHECK(buffer == NULL);

                if (err == INFO_FORMAT_CHANGED) {
                    printf("format changed.\n");
                    continue;
                }

                break;
            }

            if (buffer->range_length() > 0 && (n++ % 16) == 0) {
                printf(".");
                fflush(stdout);
            }

            sumDecodeUs += delayDecodeUs;
            totalBytes += buffer->range_length();

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
    if (!strncasecmp("video/", mime, 6)) {
        printf("avg. %.2f fps\n", n * 1E6 / delay);

        printf("avg. time to decode one buffer %.2f usecs\n",
               (double)sumDecodeUs / n);

        printf("decoded a total of %d frame(s).\n", n);
    } else if (!strncasecmp("audio/", mime, 6)) {
        // Frame count makes less sense for audio, as the output buffer
        // sizes may be different across decoders.
        printf("avg. %.2f KB/sec\n", totalBytes / 1024 * 1E6 / delay);

        printf("decoded a total of %lld bytes\n", totalBytes);
    }
}

////////////////////////////////////////////////////////////////////////////////

struct DetectSyncSource : public MediaSource {
    DetectSyncSource(const sp<MediaSource> &source);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);

private:
    enum StreamType {
        AVC,
        MPEG4,
        H263,
        OTHER,
    };

    sp<MediaSource> mSource;
    StreamType mStreamType;

    DISALLOW_EVIL_CONSTRUCTORS(DetectSyncSource);
};

DetectSyncSource::DetectSyncSource(const sp<MediaSource> &source)
    : mSource(source),
      mStreamType(OTHER) {
    const char *mime;
    CHECK(mSource->getFormat()->findCString(kKeyMIMEType, &mime));

    if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC)) {
        mStreamType = AVC;
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_MPEG4)) {
        mStreamType = MPEG4;
        CHECK(!"sync frame detection not implemented yet for MPEG4");
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_H263)) {
        mStreamType = H263;
        CHECK(!"sync frame detection not implemented yet for H.263");
    }
}

status_t DetectSyncSource::start(MetaData *params) {
    return mSource->start(params);
}

status_t DetectSyncSource::stop() {
    return mSource->stop();
}

sp<MetaData> DetectSyncSource::getFormat() {
    return mSource->getFormat();
}

static bool isIDRFrame(MediaBuffer *buffer) {
    const uint8_t *data =
        (const uint8_t *)buffer->data() + buffer->range_offset();
    size_t size = buffer->range_length();
    for (size_t i = 0; i + 3 < size; ++i) {
        if (!memcmp("\x00\x00\x01", &data[i], 3)) {
            uint8_t nalType = data[i + 3] & 0x1f;
            if (nalType == 5) {
                return true;
            }
        }
    }

    return false;
}

status_t DetectSyncSource::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    status_t err = mSource->read(buffer, options);

    if (err != OK) {
        return err;
    }

    if (mStreamType == AVC && isIDRFrame(*buffer)) {
        (*buffer)->meta_data()->setInt32(kKeyIsSyncFrame, true);
    } else {
        (*buffer)->meta_data()->setInt32(kKeyIsSyncFrame, true);
    }

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

static void writeSourcesToMP4(
        Vector<sp<MediaSource> > &sources, bool syncInfoPresent) {
#if 0
    sp<MPEG4Writer> writer =
        new MPEG4Writer(gWriteMP4Filename.string());
#else
    sp<MPEG2TSWriter> writer =
        new MPEG2TSWriter(gWriteMP4Filename.string());
#endif

    // at most one minute.
    writer->setMaxFileDuration(60000000ll);

    for (size_t i = 0; i < sources.size(); ++i) {
        sp<MediaSource> source = sources.editItemAt(i);

        CHECK_EQ(writer->addSource(
                    syncInfoPresent ? source : new DetectSyncSource(source)),
                (status_t)OK);
    }

    sp<MetaData> params = new MetaData;
    params->setInt32(kKeyNotRealTime, true);
    CHECK_EQ(writer->start(params.get()), (status_t)OK);

    while (!writer->reachedEOS()) {
        usleep(100000);
    }
    writer->stop();
}

static void performSeekTest(const sp<MediaSource> &source) {
    CHECK_EQ((status_t)OK, source->start());

    int64_t durationUs;
    CHECK(source->getFormat()->findInt64(kKeyDuration, &durationUs));

    for (int64_t seekTimeUs = 0; seekTimeUs <= durationUs;
            seekTimeUs += 60000ll) {
        MediaSource::ReadOptions options;
        options.setSeekTo(
                seekTimeUs, MediaSource::ReadOptions::SEEK_PREVIOUS_SYNC);

        MediaBuffer *buffer;
        status_t err;
        for (;;) {
            err = source->read(&buffer, &options);

            options.clearSeekTo();

            if (err == INFO_FORMAT_CHANGED) {
                CHECK(buffer == NULL);
                continue;
            }

            if (err != OK) {
                CHECK(buffer == NULL);
                break;
            }

            if (buffer->range_length() > 0) {
                break;
            }

            CHECK(buffer != NULL);

            buffer->release();
            buffer = NULL;
        }

        if (err == OK) {
            int64_t timeUs;
            CHECK(buffer->meta_data()->findInt64(kKeyTime, &timeUs));

            printf("%lld\t%lld\t%lld\n", seekTimeUs, timeUs, seekTimeUs - timeUs);

            buffer->release();
            buffer = NULL;
        } else {
            printf("ERROR\n");
            break;
        }
    }

    CHECK_EQ((status_t)OK, source->stop());
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
    fprintf(stderr, "       -t(humbnail) extract video thumbnail or album art\n");
    fprintf(stderr, "       -s(oftware) prefer software codec\n");
    fprintf(stderr, "       -o playback audio\n");
    fprintf(stderr, "       -w(rite) filename (write to .mp4 file)\n");
    fprintf(stderr, "       -k seek test\n");
}

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    bool audioOnly = false;
    bool listComponents = false;
    bool dumpProfiles = false;
    bool extractThumbnail = false;
    bool seekTest = false;
    gNumRepetitions = 1;
    gMaxNumFrames = 0;
    gReproduceBug = -1;
    gPreferSoftwareCodec = false;
    gPlaybackAudio = false;
    gWriteMP4 = false;

    sp<ALooper> looper;
    sp<ARTSPController> rtspController;

    int res;
    while ((res = getopt(argc, argv, "han:lm:b:ptsow:k")) >= 0) {
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

            case 'w':
            {
                gWriteMP4 = true;
                gWriteMP4Filename.setTo(optarg);
                break;
            }

            case 'p':
            {
                dumpProfiles = true;
                break;
            }

            case 't':
            {
                extractThumbnail = true;
                break;
            }

            case 's':
            {
                gPreferSoftwareCodec = true;
                break;
            }

            case 'o':
            {
                gPlaybackAudio = true;
                break;
            }

            case 'k':
            {
                seekTest = true;
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

    if (gPlaybackAudio && !audioOnly) {
        // This doesn't make any sense if we're decoding the video track.
        gPlaybackAudio = false;
    }

    argc -= optind;
    argv += optind;

    if (extractThumbnail) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder = sm->getService(String16("media.player"));
        sp<IMediaPlayerService> service =
            interface_cast<IMediaPlayerService>(binder);

        CHECK(service.get() != NULL);

        sp<IMediaMetadataRetriever> retriever =
            service->createMetadataRetriever(getpid());

        CHECK(retriever != NULL);

        for (int k = 0; k < argc; ++k) {
            const char *filename = argv[k];

            CHECK_EQ(retriever->setDataSource(filename), (status_t)OK);
            sp<IMemory> mem =
                    retriever->getFrameAtTime(-1,
                                    MediaSource::ReadOptions::SEEK_PREVIOUS_SYNC);

            if (mem != NULL) {
                printf("getFrameAtTime(%s) => OK\n", filename);
            } else {
                mem = retriever->extractAlbumArt();

                if (mem != NULL) {
                    printf("extractAlbumArt(%s) => OK\n", filename);
                } else {
                    printf("both getFrameAtTime and extractAlbumArt "
                           "failed on file '%s'.\n", filename);
                }
            }
        }

        return 0;
    }

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
            MEDIA_MIMETYPE_VIDEO_H263, MEDIA_MIMETYPE_AUDIO_AAC,
            MEDIA_MIMETYPE_AUDIO_AMR_NB, MEDIA_MIMETYPE_AUDIO_AMR_WB,
            MEDIA_MIMETYPE_AUDIO_MPEG
        };

        for (size_t k = 0; k < sizeof(kMimeTypes) / sizeof(kMimeTypes[0]);
             ++k) {
            printf("type '%s':\n", kMimeTypes[k]);

            Vector<CodecCapabilities> results;
            CHECK_EQ(QueryCodecs(omx, kMimeTypes[k],
                                 true, // queryDecoders
                                 &results), (status_t)OK);

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

        List<IOMX::ComponentInfo> list;
        omx->listNodes(&list);

        for (List<IOMX::ComponentInfo>::iterator it = list.begin();
             it != list.end(); ++it) {
            printf("%s\n", (*it).mName.string());
        }
    }

    DataSource::RegisterDefaultSniffers();

    OMXClient client;
    status_t err = client.connect();

    for (int k = 0; k < argc; ++k) {
        bool syncInfoPresent = true;

        const char *filename = argv[k];

        sp<DataSource> dataSource = DataSource::CreateFromURI(filename);

        if (strncasecmp(filename, "sine:", 5)
                && strncasecmp(filename, "rtsp://", 7)
                && strncasecmp(filename, "httplive://", 11)
                && dataSource == NULL) {
            fprintf(stderr, "Unable to create data source.\n");
            return 1;
        }

        bool isJPEG = false;

        size_t len = strlen(filename);
        if (len >= 4 && !strcasecmp(filename + len - 4, ".jpg")) {
            isJPEG = true;
        }

        Vector<sp<MediaSource> > mediaSources;
        sp<MediaSource> mediaSource;

        if (isJPEG) {
            mediaSource = new JPEGSource(dataSource);
            if (gWriteMP4) {
                mediaSources.push(mediaSource);
            }
        } else if (!strncasecmp("sine:", filename, 5)) {
            char *end;
            long sampleRate = strtol(filename + 5, &end, 10);

            if (end == filename + 5) {
                sampleRate = 44100;
            }
            mediaSource = new SineSource(sampleRate, 1);
            if (gWriteMP4) {
                mediaSources.push(mediaSource);
            }
        } else {
            sp<MediaExtractor> extractor;

            if (!strncasecmp("rtsp://", filename, 7)) {
                if (looper == NULL) {
                    looper = new ALooper;
                    looper->start();
                }

                rtspController = new ARTSPController(looper);
                status_t err = rtspController->connect(filename);
                if (err != OK) {
                    fprintf(stderr, "could not connect to rtsp server.\n");
                    return -1;
                }

                extractor = rtspController.get();

                syncInfoPresent = false;
            } else if (!strncasecmp("httplive://", filename, 11)) {
                String8 uri("http://");
                uri.append(filename + 11);

                dataSource = new LiveSource(uri.string());
                dataSource = new NuCachedSource2(dataSource);

                extractor =
                    MediaExtractor::Create(
                            dataSource, MEDIA_MIMETYPE_CONTAINER_MPEG2TS);

                syncInfoPresent = false;
            } else {
                extractor = MediaExtractor::Create(dataSource);
                if (extractor == NULL) {
                    fprintf(stderr, "could not create extractor.\n");
                    return -1;
                }
            }

            size_t numTracks = extractor->countTracks();

            if (gWriteMP4) {
                bool haveAudio = false;
                bool haveVideo = false;
                for (size_t i = 0; i < numTracks; ++i) {
                    sp<MediaSource> source = extractor->getTrack(i);

                    const char *mime;
                    CHECK(source->getFormat()->findCString(
                                kKeyMIMEType, &mime));

                    bool useTrack = false;
                    if (!haveAudio && !strncasecmp("audio/", mime, 6)) {
                        haveAudio = true;
                        useTrack = true;
                    } else if (!haveVideo && !strncasecmp("video/", mime, 6)) {
                        haveVideo = true;
                        useTrack = true;
                    }

                    if (useTrack) {
                        mediaSources.push(source);

                        if (haveAudio && haveVideo) {
                            break;
                        }
                    }
                }
            } else {
                sp<MetaData> meta;
                size_t i;
                for (i = 0; i < numTracks; ++i) {
                    meta = extractor->getTrackMetaData(
                            i, MediaExtractor::kIncludeExtensiveMetaData);

                    const char *mime;
                    meta->findCString(kKeyMIMEType, &mime);

                    if (audioOnly && !strncasecmp(mime, "audio/", 6)) {
                        break;
                    }

                    if (!audioOnly && !strncasecmp(mime, "video/", 6)) {
                        break;
                    }

                    meta = NULL;
                }

                if (meta == NULL) {
                    fprintf(stderr,
                            "No suitable %s track found. The '-a' option will "
                            "target audio tracks only, the default is to target "
                            "video tracks only.\n",
                            audioOnly ? "audio" : "video");
                    return -1;
                }

                int64_t thumbTimeUs;
                if (meta->findInt64(kKeyThumbnailTime, &thumbTimeUs)) {
                    printf("thumbnailTime: %lld us (%.2f secs)\n",
                           thumbTimeUs, thumbTimeUs / 1E6);
                }

                mediaSource = extractor->getTrack(i);
            }
        }

        if (gWriteMP4) {
            writeSourcesToMP4(mediaSources, syncInfoPresent);
        } else if (seekTest) {
            performSeekTest(mediaSource);
        } else {
            playSource(&client, mediaSource);
        }

        if (rtspController != NULL) {
            rtspController->disconnect();
            rtspController.clear();

            sleep(3);
        }
    }

    client.disconnect();

    return 0;
}
