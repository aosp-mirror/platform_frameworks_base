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
#include "include/LiveSession.h"
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

#include <private/media/VideoFrame.h>
#include <SkBitmap.h>
#include <SkImageEncoder.h>

#include <fcntl.h>

#include <gui/SurfaceTextureClient.h>

#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/SurfaceComposerClient.h>

using namespace android;

static long gNumRepetitions;
static long gMaxNumFrames;  // 0 means decode all available.
static long gReproduceBug;  // if not -1.
static bool gPreferSoftwareCodec;
static bool gForceToUseHardwareCodec;
static bool gPlaybackAudio;
static bool gWriteMP4;
static bool gDisplayHistogram;
static String8 gWriteMP4Filename;

static sp<ANativeWindow> gSurface;

static int64_t getNowUs() {
    struct timeval tv;
    gettimeofday(&tv, NULL);

    return (int64_t)tv.tv_usec + tv.tv_sec * 1000000ll;
}

static int CompareIncreasing(const int64_t *a, const int64_t *b) {
    return (*a) < (*b) ? -1 : (*a) > (*b) ? 1 : 0;
}

static void displayDecodeHistogram(Vector<int64_t> *decodeTimesUs) {
    printf("decode times:\n");

    decodeTimesUs->sort(CompareIncreasing);

    size_t n = decodeTimesUs->size();
    int64_t minUs = decodeTimesUs->itemAt(0);
    int64_t maxUs = decodeTimesUs->itemAt(n - 1);

    printf("min decode time %lld us (%.2f secs)\n", minUs, minUs / 1E6);
    printf("max decode time %lld us (%.2f secs)\n", maxUs, maxUs / 1E6);

    size_t counts[100];
    for (size_t i = 0; i < 100; ++i) {
        counts[i] = 0;
    }

    for (size_t i = 0; i < n; ++i) {
        int64_t x = decodeTimesUs->itemAt(i);

        size_t slot = ((x - minUs) * 100) / (maxUs - minUs);
        if (slot == 100) { slot = 99; }

        ++counts[slot];
    }

    for (size_t i = 0; i < 100; ++i) {
        int64_t slotUs = minUs + (i * (maxUs - minUs) / 100);

        double fps = 1E6 / slotUs;
        printf("[%.2f fps]: %d\n", fps, counts[i]);
    }
}

static void displayAVCProfileLevelIfPossible(const sp<MetaData>& meta) {
    uint32_t type;
    const void *data;
    size_t size;
    if (meta->findData(kKeyAVCC, &type, &data, &size)) {
        const uint8_t *ptr = (const uint8_t *)data;
        CHECK(size >= 7);
        CHECK(ptr[0] == 1);  // configurationVersion == 1
        uint8_t profile = ptr[1];
        uint8_t level = ptr[3];
        fprintf(stderr, "AVC video profile %d and level %d\n", profile, level);
    }
}

static void playSource(OMXClient *client, sp<MediaSource> &source) {
    sp<MetaData> meta = source->getFormat();

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    sp<MediaSource> rawSource;
    if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_RAW, mime)) {
        rawSource = source;
    } else {
        int flags = 0;
        if (gPreferSoftwareCodec) {
            flags |= OMXCodec::kPreferSoftwareCodecs;
        }
        if (gForceToUseHardwareCodec) {
            CHECK(!gPreferSoftwareCodec);
            flags |= OMXCodec::kHardwareCodecsOnly;
        }
        rawSource = OMXCodec::Create(
            client->interface(), meta, false /* createEncoder */, source,
            NULL /* matchComponentName */,
            flags,
            gSurface);

        if (rawSource == NULL) {
            fprintf(stderr, "Failed to instantiate decoder for '%s'.\n", mime);
            return;
        }
        displayAVCProfileLevelIfPossible(meta);
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

    Vector<int64_t> decodeTimesUs;

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

            if (buffer->range_length() > 0) {
                if (gDisplayHistogram && n > 0) {
                    // Ignore the first time since it includes some setup
                    // cost.
                    decodeTimesUs.push(delayDecodeUs);
                }

                if ((n++ % 16) == 0) {
                    printf(".");
                    fflush(stdout);
                }
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

        if (gDisplayHistogram) {
            displayDecodeHistogram(&decodeTimesUs);
        }
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
    bool mSawFirstIDRFrame;

    DISALLOW_EVIL_CONSTRUCTORS(DetectSyncSource);
};

DetectSyncSource::DetectSyncSource(const sp<MediaSource> &source)
    : mSource(source),
      mStreamType(OTHER),
      mSawFirstIDRFrame(false) {
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
    mSawFirstIDRFrame = false;

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
    for (;;) {
        status_t err = mSource->read(buffer, options);

        if (err != OK) {
            return err;
        }

        if (mStreamType == AVC) {
            bool isIDR = isIDRFrame(*buffer);
            (*buffer)->meta_data()->setInt32(kKeyIsSyncFrame, isIDR);
            if (isIDR) {
                mSawFirstIDRFrame = true;
            }
        } else {
            (*buffer)->meta_data()->setInt32(kKeyIsSyncFrame, true);
        }

        if (mStreamType != AVC || mSawFirstIDRFrame) {
            break;
        }

        // Ignore everything up to the first IDR frame.
        (*buffer)->release();
        *buffer = NULL;
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
    fprintf(stderr, "       -r(hardware) force to use hardware codec\n");
    fprintf(stderr, "       -o playback audio\n");
    fprintf(stderr, "       -w(rite) filename (write to .mp4 file)\n");
    fprintf(stderr, "       -k seek test\n");
    fprintf(stderr, "       -x display a histogram of decoding times/fps "
                    "(video only)\n");
    fprintf(stderr, "       -S allocate buffers from a surface\n");
    fprintf(stderr, "       -T allocate buffers from a surface texture\n");
}

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    bool audioOnly = false;
    bool listComponents = false;
    bool dumpProfiles = false;
    bool extractThumbnail = false;
    bool seekTest = false;
    bool useSurfaceAlloc = false;
    bool useSurfaceTexAlloc = false;
    gNumRepetitions = 1;
    gMaxNumFrames = 0;
    gReproduceBug = -1;
    gPreferSoftwareCodec = false;
    gForceToUseHardwareCodec = false;
    gPlaybackAudio = false;
    gWriteMP4 = false;
    gDisplayHistogram = false;

    sp<ALooper> looper;
    sp<ARTSPController> rtspController;
    sp<LiveSession> liveSession;

    int res;
    while ((res = getopt(argc, argv, "han:lm:b:ptsrow:kxST")) >= 0) {
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

            case 'r':
            {
                gForceToUseHardwareCodec = true;
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

            case 'x':
            {
                gDisplayHistogram = true;
                break;
            }

            case 'S':
            {
                useSurfaceAlloc = true;
                break;
            }

            case 'T':
            {
                useSurfaceTexAlloc = true;
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

            bool failed = true;
            CHECK_EQ(retriever->setDataSource(filename), (status_t)OK);
            sp<IMemory> mem =
                    retriever->getFrameAtTime(-1,
                                    MediaSource::ReadOptions::SEEK_PREVIOUS_SYNC);

            if (mem != NULL) {
                failed = false;
                printf("getFrameAtTime(%s) => OK\n", filename);

                VideoFrame *frame = (VideoFrame *)mem->pointer();

                SkBitmap bitmap;
                bitmap.setConfig(
                        SkBitmap::kRGB_565_Config, frame->mWidth, frame->mHeight);

                bitmap.setPixels((uint8_t *)frame + sizeof(VideoFrame));

                CHECK(SkImageEncoder::EncodeFile(
                            "/sdcard/out.jpg", bitmap,
                            SkImageEncoder::kJPEG_Type,
                            SkImageEncoder::kDefaultQuality));
            }

            {
                mem = retriever->extractAlbumArt();

                if (mem != NULL) {
                    failed = false;
                    printf("extractAlbumArt(%s) => OK\n", filename);
                }
            }

            if (failed) {
                printf("both getFrameAtTime and extractAlbumArt "
                    "failed on file '%s'.\n", filename);
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
            MEDIA_MIMETYPE_AUDIO_MPEG, MEDIA_MIMETYPE_AUDIO_G711_MLAW,
            MEDIA_MIMETYPE_AUDIO_G711_ALAW, MEDIA_MIMETYPE_AUDIO_VORBIS,
            MEDIA_MIMETYPE_VIDEO_VPX
        };

        for (size_t k = 0; k < sizeof(kMimeTypes) / sizeof(kMimeTypes[0]);
             ++k) {
            printf("type '%s':\n", kMimeTypes[k]);

            Vector<CodecCapabilities> results;
            // will retrieve hardware and software codecs
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
            printf("%s\t Roles: ", (*it).mName.string());
            for (List<String8>::iterator itRoles = (*it).mRoles.begin() ;
                    itRoles != (*it).mRoles.end() ; ++itRoles) {
                printf("%s\t", (*itRoles).string());
            }
            printf("\n");
        }
    }

    sp<SurfaceComposerClient> composerClient;
    sp<SurfaceControl> control;

    if ((useSurfaceAlloc || useSurfaceTexAlloc) && !audioOnly) {
        if (useSurfaceAlloc) {
            composerClient = new SurfaceComposerClient;
            CHECK_EQ(composerClient->initCheck(), (status_t)OK);

            control = composerClient->createSurface(
                    String8("A Surface"),
                    0,
                    1280,
                    800,
                    PIXEL_FORMAT_RGB_565,
                    0);

            CHECK(control != NULL);
            CHECK(control->isValid());

            SurfaceComposerClient::openGlobalTransaction();
            CHECK_EQ(control->setLayer(30000), (status_t)OK);
            CHECK_EQ(control->show(), (status_t)OK);
            SurfaceComposerClient::closeGlobalTransaction();

            gSurface = control->getSurface();
            CHECK(gSurface != NULL);
        } else {
            CHECK(useSurfaceTexAlloc);

            sp<SurfaceTexture> texture = new SurfaceTexture(0 /* tex */);
            gSurface = new SurfaceTextureClient(texture);
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

                if (looper == NULL) {
                    looper = new ALooper;
                    looper->start();
                }
                liveSession = new LiveSession;
                looper->registerHandler(liveSession);

                liveSession->connect(uri.string());
                dataSource = liveSession->getDataSource();

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

                sp<MetaData> meta = extractor->getMetaData();

                if (meta != NULL) {
                    const char *mime;
                    CHECK(meta->findCString(kKeyMIMEType, &mime));

                    if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_MPEG2TS)) {
                        syncInfoPresent = false;
                    }
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

    if ((useSurfaceAlloc || useSurfaceTexAlloc) && !audioOnly) {
        gSurface.clear();

        if (useSurfaceAlloc) {
            composerClient->dispose();
        }
    }

    client.disconnect();

    return 0;
}
