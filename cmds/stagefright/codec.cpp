/*
 * Copyright (C) 2012 The Android Open Source Project
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
#define LOG_TAG "codec"
#include <utils/Log.h>

#include "SimplePlayer.h"

#include <binder/ProcessState.h>

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaCodec.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/NuMediaExtractor.h>
#include <gui/SurfaceComposerClient.h>

static void usage(const char *me) {
    fprintf(stderr, "usage: %s [-a] use audio\n"
                    "\t\t[-v] use video\n"
                    "\t\t[-p] playback\n", me);

    exit(1);
}

namespace android {

struct CodecState {
    sp<MediaCodec> mCodec;
    Vector<sp<ABuffer> > mCSD;
    size_t mCSDIndex;
    Vector<sp<ABuffer> > mInBuffers;
    Vector<sp<ABuffer> > mOutBuffers;
    bool mSawOutputEOS;
};

}  // namespace android

static int decode(
        const android::sp<android::ALooper> &looper,
        const char *path,
        bool useAudio,
        bool useVideo) {
    using namespace android;

    sp<NuMediaExtractor> extractor = new NuMediaExtractor;
    if (extractor->setDataSource(path) != OK) {
        fprintf(stderr, "unable to instantiate extractor.\n");
        return 1;
    }

    KeyedVector<size_t, CodecState> stateByTrack;

    bool haveAudio = false;
    bool haveVideo = false;
    for (size_t i = 0; i < extractor->countTracks(); ++i) {
        sp<AMessage> format;
        status_t err = extractor->getTrackFormat(i, &format);
        CHECK_EQ(err, (status_t)OK);

        AString mime;
        CHECK(format->findString("mime", &mime));

        if (useAudio && !haveAudio
                && !strncasecmp(mime.c_str(), "audio/", 6)) {
            haveAudio = true;
        } else if (useVideo && !haveVideo
                && !strncasecmp(mime.c_str(), "video/", 6)) {
            haveVideo = true;
        } else {
            continue;
        }

        ALOGV("selecting track %d", i);

        err = extractor->selectTrack(i);
        CHECK_EQ(err, (status_t)OK);

        CodecState *state =
            &stateByTrack.editValueAt(stateByTrack.add(i, CodecState()));

        state->mCodec = MediaCodec::CreateByType(
                looper, mime.c_str(), false /* encoder */);

        CHECK(state->mCodec != NULL);

        err = state->mCodec->configure(
                format, NULL /* surfaceTexture */, 0 /* flags */);

        CHECK_EQ(err, (status_t)OK);

        size_t j = 0;
        sp<ABuffer> buffer;
        while (format->findBuffer(StringPrintf("csd-%d", j).c_str(), &buffer)) {
            state->mCSD.push_back(buffer);

            ++j;
        }

        state->mCSDIndex = 0;
        state->mSawOutputEOS = false;

        ALOGV("got %d pieces of codec specific data.", state->mCSD.size());
    }

    CHECK(!stateByTrack.isEmpty());

    for (size_t i = 0; i < stateByTrack.size(); ++i) {
        CodecState *state = &stateByTrack.editValueAt(i);

        sp<MediaCodec> codec = state->mCodec;

        CHECK_EQ((status_t)OK, codec->start());

        CHECK_EQ((status_t)OK, codec->getInputBuffers(&state->mInBuffers));
        CHECK_EQ((status_t)OK, codec->getOutputBuffers(&state->mOutBuffers));

        ALOGV("got %d input and %d output buffers",
              state->mInBuffers.size(), state->mOutBuffers.size());

        while (state->mCSDIndex < state->mCSD.size()) {
            size_t index;
            status_t err = codec->dequeueInputBuffer(&index);

            if (err == -EAGAIN) {
                usleep(10000);
                continue;
            }

            CHECK_EQ(err, (status_t)OK);

            const sp<ABuffer> &srcBuffer =
                state->mCSD.itemAt(state->mCSDIndex++);

            const sp<ABuffer> &buffer = state->mInBuffers.itemAt(index);

            memcpy(buffer->data(), srcBuffer->data(), srcBuffer->size());

            err = codec->queueInputBuffer(
                    index,
                    0 /* offset */,
                    srcBuffer->size(),
                    0ll /* timeUs */,
                    MediaCodec::BUFFER_FLAG_CODECCONFIG);

            CHECK_EQ(err, (status_t)OK);
        }
    }

    bool sawInputEOS = false;

    for (;;) {
        if (!sawInputEOS) {
            size_t trackIndex;
            status_t err = extractor->getSampleTrackIndex(&trackIndex);

            if (err != OK) {
                ALOGV("signalling EOS.");

                for (size_t i = 0; i < stateByTrack.size(); ++i) {
                    CodecState *state = &stateByTrack.editValueAt(i);

                    for (;;) {
                        size_t index;
                        err = state->mCodec->dequeueInputBuffer(&index);

                        if (err == -EAGAIN) {
                            continue;
                        }

                        CHECK_EQ(err, (status_t)OK);

                        err = state->mCodec->queueInputBuffer(
                                index,
                                0 /* offset */,
                                0 /* size */,
                                0ll /* timeUs */,
                                MediaCodec::BUFFER_FLAG_EOS);

                        CHECK_EQ(err, (status_t)OK);
                        break;
                    }
                }

                sawInputEOS = true;
            } else {
                CodecState *state = &stateByTrack.editValueFor(trackIndex);

                size_t index;
                err = state->mCodec->dequeueInputBuffer(&index);

                if (err == OK) {
                    ALOGV("filling input buffer %d", index);

                    const sp<ABuffer> &buffer = state->mInBuffers.itemAt(index);

                    err = extractor->readSampleData(buffer);
                    CHECK_EQ(err, (status_t)OK);

                    int64_t timeUs;
                    err = extractor->getSampleTime(&timeUs);
                    CHECK_EQ(err, (status_t)OK);

                    err = state->mCodec->queueInputBuffer(
                            index,
                            0 /* offset */,
                            buffer->size(),
                            timeUs,
                            0 /* flags */);

                    CHECK_EQ(err, (status_t)OK);

                    extractor->advance();
                } else {
                    CHECK_EQ(err, -EAGAIN);
                }
            }
        }

        bool sawOutputEOSOnAllTracks = true;
        for (size_t i = 0; i < stateByTrack.size(); ++i) {
            CodecState *state = &stateByTrack.editValueAt(i);
            if (!state->mSawOutputEOS) {
                sawOutputEOSOnAllTracks = false;
                break;
            }
        }

        if (sawOutputEOSOnAllTracks) {
            break;
        }

        for (size_t i = 0; i < stateByTrack.size(); ++i) {
            CodecState *state = &stateByTrack.editValueAt(i);

            if (state->mSawOutputEOS) {
                continue;
            }

            size_t index;
            size_t offset;
            size_t size;
            int64_t presentationTimeUs;
            uint32_t flags;
            status_t err = state->mCodec->dequeueOutputBuffer(
                    &index, &offset, &size, &presentationTimeUs, &flags,
                    10000ll);

            if (err == OK) {
                ALOGV("draining output buffer %d, time = %lld us",
                      index, presentationTimeUs);

                err = state->mCodec->releaseOutputBuffer(index);
                CHECK_EQ(err, (status_t)OK);

                if (flags & MediaCodec::BUFFER_FLAG_EOS) {
                    ALOGV("reached EOS on output.");

                    state->mSawOutputEOS = true;
                }
            } else if (err == INFO_OUTPUT_BUFFERS_CHANGED) {
                ALOGV("INFO_OUTPUT_BUFFERS_CHANGED");
                CHECK_EQ((status_t)OK,
                         state->mCodec->getOutputBuffers(&state->mOutBuffers));

                ALOGV("got %d output buffers", state->mOutBuffers.size());
            } else if (err == INFO_FORMAT_CHANGED) {
                sp<AMessage> format;
                CHECK_EQ((status_t)OK, state->mCodec->getOutputFormat(&format));

                ALOGV("INFO_FORMAT_CHANGED: %s", format->debugString().c_str());
            } else {
                CHECK_EQ(err, -EAGAIN);
            }
        }
    }

    for (size_t i = 0; i < stateByTrack.size(); ++i) {
        CodecState *state = &stateByTrack.editValueAt(i);

        CHECK_EQ((status_t)OK, state->mCodec->release());
    }

    return 0;
}

int main(int argc, char **argv) {
    using namespace android;

    const char *me = argv[0];

    bool useAudio = false;
    bool useVideo = false;
    bool playback = false;

    int res;
    while ((res = getopt(argc, argv, "havp")) >= 0) {
        switch (res) {
            case 'a':
            {
                useAudio = true;
                break;
            }

            case 'v':
            {
                useVideo = true;
                break;
            }

            case 'p':
            {
                playback = true;
                break;
            }

            case '?':
            case 'h':
            default:
            {
                usage(me);
            }
        }
    }

    argc -= optind;
    argv += optind;

    if (argc != 1) {
        usage(me);
    }

    if (!useAudio && !useVideo) {
        useAudio = useVideo = true;
    }

    ProcessState::self()->startThreadPool();

    DataSource::RegisterDefaultSniffers();

    sp<ALooper> looper = new ALooper;
    looper->start();

    if (playback) {
        sp<SurfaceComposerClient> composerClient = new SurfaceComposerClient;
        CHECK_EQ(composerClient->initCheck(), (status_t)OK);

        ssize_t displayWidth = composerClient->getDisplayWidth(0);
        ssize_t displayHeight = composerClient->getDisplayHeight(0);

        ALOGV("display is %ld x %ld\n", displayWidth, displayHeight);

        sp<SurfaceControl> control =
            composerClient->createSurface(
                    String8("A Surface"),
                    0,
                    displayWidth,
                    displayHeight,
                    PIXEL_FORMAT_RGB_565,
                    0);

        CHECK(control != NULL);
        CHECK(control->isValid());

        SurfaceComposerClient::openGlobalTransaction();
        CHECK_EQ(control->setLayer(INT_MAX), (status_t)OK);
        CHECK_EQ(control->show(), (status_t)OK);
        SurfaceComposerClient::closeGlobalTransaction();

        sp<Surface> surface = control->getSurface();
        CHECK(surface != NULL);

        sp<SimplePlayer> player = new SimplePlayer;
        looper->registerHandler(player);

        player->setDataSource(argv[0]);
        player->setSurface(surface->getSurfaceTexture());
        player->start();
        sleep(60);
        player->stop();
        player->reset();

        composerClient->dispose();
    } else {
        decode(looper, argv[0], useAudio, useVideo);
    }

    looper->stop();

    return 0;
}
