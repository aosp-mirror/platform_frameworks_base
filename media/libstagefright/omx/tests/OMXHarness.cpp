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
#define LOG_TAG "OMXHarness"
#include <utils/Log.h>

#include "OMXHarness.h"

#include <sys/time.h>

#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>
#include <binder/MemoryDealer.h>
#include <media/IMediaPlayerService.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXCodec.h>

#define DEFAULT_TIMEOUT         500000

namespace android {

static int64_t getNowUs() {
    struct timeval tv;
    gettimeofday(&tv, NULL);

    return (int64_t)tv.tv_usec + tv.tv_sec * 1000000;
}

Harness::Harness()
    : mInitCheck(NO_INIT) {
    mInitCheck = initOMX();
}

Harness::~Harness() {
}

status_t Harness::initCheck() const {
    return mInitCheck;
}

status_t Harness::initOMX() {
    sp<IServiceManager> sm = defaultServiceManager();
    sp<IBinder> binder = sm->getService(String16("media.player"));
    sp<IMediaPlayerService> service = interface_cast<IMediaPlayerService>(binder);
    mOMX = service->getOMX();

    return mOMX != 0 ? OK : NO_INIT;
}

void Harness::onMessage(const omx_message &msg) {
    Mutex::Autolock autoLock(mLock);
    mMessageQueue.push_back(msg);
    mMessageAddedCondition.signal();
}

status_t Harness::dequeueMessageForNode(
        IOMX::node_id node, omx_message *msg, int64_t timeoutUs) {
    return dequeueMessageForNodeIgnoringBuffers(
            node, NULL, NULL, msg, timeoutUs);
}

// static
bool Harness::handleBufferMessage(
        const omx_message &msg,
        Vector<Buffer> *inputBuffers,
        Vector<Buffer> *outputBuffers) {
    switch (msg.type) {
        case omx_message::EMPTY_BUFFER_DONE:
        {
            if (inputBuffers) {
                for (size_t i = 0; i < inputBuffers->size(); ++i) {
                    if ((*inputBuffers)[i].mID == msg.u.buffer_data.buffer) {
                        inputBuffers->editItemAt(i).mFlags &= ~kBufferBusy;
                        return true;
                    }
                }
                CHECK(!"should not be here");
            }
            break;
        }

        case omx_message::FILL_BUFFER_DONE:
        {
            if (outputBuffers) {
                for (size_t i = 0; i < outputBuffers->size(); ++i) {
                    if ((*outputBuffers)[i].mID == msg.u.buffer_data.buffer) {
                        outputBuffers->editItemAt(i).mFlags &= ~kBufferBusy;
                        return true;
                    }
                }
                CHECK(!"should not be here");
            }
            break;
        }

        default:
            break;
    }

    return false;
}

status_t Harness::dequeueMessageForNodeIgnoringBuffers(
        IOMX::node_id node,
        Vector<Buffer> *inputBuffers,
        Vector<Buffer> *outputBuffers,
        omx_message *msg, int64_t timeoutUs) {
    int64_t finishBy = getNowUs() + timeoutUs;

    for (;;) {
        Mutex::Autolock autoLock(mLock);
        List<omx_message>::iterator it = mMessageQueue.begin();
        while (it != mMessageQueue.end()) {
            if ((*it).node == node) {
                if (handleBufferMessage(*it, inputBuffers, outputBuffers)) {
                    it = mMessageQueue.erase(it);
                    continue;
                }

                *msg = *it;
                mMessageQueue.erase(it);

                return OK;
            }

            ++it;
        }

        status_t err = (timeoutUs < 0)
            ? mMessageAddedCondition.wait(mLock)
            : mMessageAddedCondition.waitRelative(
                    mLock, (finishBy - getNowUs()) * 1000);

        if (err == TIMED_OUT) {
            return err;
        }
        CHECK_EQ(err, OK);
    }
}

status_t Harness::getPortDefinition(
        IOMX::node_id node, OMX_U32 portIndex,
        OMX_PARAM_PORTDEFINITIONTYPE *def) {
    def->nSize = sizeof(*def);
    def->nVersion.s.nVersionMajor = 1;
    def->nVersion.s.nVersionMinor = 0;
    def->nVersion.s.nRevision = 0;
    def->nVersion.s.nStep = 0;
    def->nPortIndex = portIndex;
    return mOMX->getParameter(
            node, OMX_IndexParamPortDefinition, def, sizeof(*def));
}

#define EXPECT(condition, info) \
    if (!(condition)) {         \
        ALOGE(info); printf("\n  * " info "\n"); return UNKNOWN_ERROR; \
    }

#define EXPECT_SUCCESS(err, info) \
    EXPECT((err) == OK, info " failed")

status_t Harness::allocatePortBuffers(
        const sp<MemoryDealer> &dealer,
        IOMX::node_id node, OMX_U32 portIndex,
        Vector<Buffer> *buffers) {
    buffers->clear();

    OMX_PARAM_PORTDEFINITIONTYPE def;
    status_t err = getPortDefinition(node, portIndex, &def);
    EXPECT_SUCCESS(err, "getPortDefinition");

    for (OMX_U32 i = 0; i < def.nBufferCountActual; ++i) {
        Buffer buffer;
        buffer.mMemory = dealer->allocate(def.nBufferSize);
        buffer.mFlags = 0;
        CHECK(buffer.mMemory != NULL);

        err = mOMX->allocateBufferWithBackup(
                node, portIndex, buffer.mMemory, &buffer.mID);
        EXPECT_SUCCESS(err, "allocateBuffer");

        buffers->push(buffer);
    }

    return OK;
}

status_t Harness::setRole(IOMX::node_id node, const char *role) {
    OMX_PARAM_COMPONENTROLETYPE params;
    params.nSize = sizeof(params);
    params.nVersion.s.nVersionMajor = 1;
    params.nVersion.s.nVersionMinor = 0;
    params.nVersion.s.nRevision = 0;
    params.nVersion.s.nStep = 0;
    strncpy((char *)params.cRole, role, OMX_MAX_STRINGNAME_SIZE - 1);
    params.cRole[OMX_MAX_STRINGNAME_SIZE - 1] = '\0';

    return mOMX->setParameter(
            node, OMX_IndexParamStandardComponentRole,
            &params, sizeof(params));
}

struct NodeReaper {
    NodeReaper(const sp<Harness> &harness, IOMX::node_id node)
        : mHarness(harness),
          mNode(node) {
    }

    ~NodeReaper() {
        if (mNode != 0) {
            mHarness->mOMX->freeNode(mNode);
            mNode = 0;
        }
    }

    void disarm() {
        mNode = 0;
    }

private:
    sp<Harness> mHarness;
    IOMX::node_id mNode;

    NodeReaper(const NodeReaper &);
    NodeReaper &operator=(const NodeReaper &);
};

static sp<MediaExtractor> CreateExtractorFromURI(const char *uri) {
    sp<DataSource> source = DataSource::CreateFromURI(uri);

    if (source == NULL) {
        return NULL;
    }

    return MediaExtractor::Create(source);
}

static sp<MediaSource> MakeSource(
        const char *uri,
        const char *mimeType) {
    sp<MediaExtractor> extractor = CreateExtractorFromURI(uri);

    if (extractor == NULL) {
        return NULL;
    }

    for (size_t i = 0; i < extractor->countTracks(); ++i) {
        sp<MetaData> meta = extractor->getTrackMetaData(i);

        const char *trackMIME;
        CHECK(meta->findCString(kKeyMIMEType, &trackMIME));

        if (!strcasecmp(trackMIME, mimeType)) {
            return extractor->getTrack(i);
        }
    }

    return NULL;
}

status_t Harness::testStateTransitions(
        const char *componentName, const char *componentRole) {
    if (strncmp(componentName, "OMX.", 4)) {
        // Non-OMX components, i.e. software decoders won't execute this
        // test.
        return OK;
    }

    sp<MemoryDealer> dealer = new MemoryDealer(16 * 1024 * 1024, "OMXHarness");
    IOMX::node_id node;

    status_t err =
        mOMX->allocateNode(componentName, this, &node);
    EXPECT_SUCCESS(err, "allocateNode");

    NodeReaper reaper(this, node);

    err = setRole(node, componentRole);
    EXPECT_SUCCESS(err, "setRole");

    // Initiate transition Loaded->Idle
    err = mOMX->sendCommand(node, OMX_CommandStateSet, OMX_StateIdle);
    EXPECT_SUCCESS(err, "sendCommand(go-to-Idle)");

    omx_message msg;
    err = dequeueMessageForNode(node, &msg, DEFAULT_TIMEOUT);
    // Make sure node doesn't just transition to idle before we are done
    // allocating all input and output buffers.
    EXPECT(err == TIMED_OUT,
            "Component must not transition from loaded to idle before "
            "all input and output buffers are allocated.");

    // Now allocate buffers.
    Vector<Buffer> inputBuffers;
    err = allocatePortBuffers(dealer, node, 0, &inputBuffers);
    EXPECT_SUCCESS(err, "allocatePortBuffers(input)");

    err = dequeueMessageForNode(node, &msg, DEFAULT_TIMEOUT);
    CHECK_EQ(err, TIMED_OUT);

    Vector<Buffer> outputBuffers;
    err = allocatePortBuffers(dealer, node, 1, &outputBuffers);
    EXPECT_SUCCESS(err, "allocatePortBuffers(output)");

    err = dequeueMessageForNode(node, &msg, DEFAULT_TIMEOUT);
    EXPECT(err == OK
            && msg.type == omx_message::EVENT
            && msg.u.event_data.event == OMX_EventCmdComplete
            && msg.u.event_data.data1 == OMX_CommandStateSet
            && msg.u.event_data.data2 == OMX_StateIdle,
           "Component did not properly transition to idle state "
           "after all input and output buffers were allocated.");

    // Initiate transition Idle->Executing
    err = mOMX->sendCommand(node, OMX_CommandStateSet, OMX_StateExecuting);
    EXPECT_SUCCESS(err, "sendCommand(go-to-Executing)");

    err = dequeueMessageForNode(node, &msg, DEFAULT_TIMEOUT);
    EXPECT(err == OK
            && msg.type == omx_message::EVENT
            && msg.u.event_data.event == OMX_EventCmdComplete
            && msg.u.event_data.data1 == OMX_CommandStateSet
            && msg.u.event_data.data2 == OMX_StateExecuting,
           "Component did not properly transition from idle to "
           "executing state.");

    for (size_t i = 0; i < outputBuffers.size(); ++i) {
        err = mOMX->fillBuffer(node, outputBuffers[i].mID);
        EXPECT_SUCCESS(err, "fillBuffer");

        outputBuffers.editItemAt(i).mFlags |= kBufferBusy;
    }

    err = mOMX->sendCommand(node, OMX_CommandFlush, 1);
    EXPECT_SUCCESS(err, "sendCommand(flush-output-port)");

    err = dequeueMessageForNodeIgnoringBuffers(
            node, &inputBuffers, &outputBuffers, &msg, DEFAULT_TIMEOUT);
    EXPECT(err == OK
            && msg.type == omx_message::EVENT
            && msg.u.event_data.event == OMX_EventCmdComplete
            && msg.u.event_data.data1 == OMX_CommandFlush
            && msg.u.event_data.data2 == 1,
           "Component did not properly acknowledge flushing the output port.");

    for (size_t i = 0; i < outputBuffers.size(); ++i) {
        EXPECT((outputBuffers[i].mFlags & kBufferBusy) == 0,
               "Not all output buffers have been returned to us by the time "
               "we received the flush-complete notification.");
    }

    for (size_t i = 0; i < outputBuffers.size(); ++i) {
        err = mOMX->fillBuffer(node, outputBuffers[i].mID);
        EXPECT_SUCCESS(err, "fillBuffer");

        outputBuffers.editItemAt(i).mFlags |= kBufferBusy;
    }

    // Initiate transition Executing->Idle
    err = mOMX->sendCommand(node, OMX_CommandStateSet, OMX_StateIdle);
    EXPECT_SUCCESS(err, "sendCommand(go-to-Idle)");

    err = dequeueMessageForNodeIgnoringBuffers(
            node, &inputBuffers, &outputBuffers, &msg, DEFAULT_TIMEOUT);
    EXPECT(err == OK
            && msg.type == omx_message::EVENT
            && msg.u.event_data.event == OMX_EventCmdComplete
            && msg.u.event_data.data1 == OMX_CommandStateSet
            && msg.u.event_data.data2 == OMX_StateIdle,
           "Component did not properly transition to from executing to "
           "idle state.");

    for (size_t i = 0; i < inputBuffers.size(); ++i) {
        EXPECT((inputBuffers[i].mFlags & kBufferBusy) == 0,
                "Not all input buffers have been returned to us by the "
                "time we received the transition-to-idle complete "
                "notification.");
    }

    for (size_t i = 0; i < outputBuffers.size(); ++i) {
        EXPECT((outputBuffers[i].mFlags & kBufferBusy) == 0,
                "Not all output buffers have been returned to us by the "
                "time we received the transition-to-idle complete "
                "notification.");
    }

    // Initiate transition Idle->Loaded
    err = mOMX->sendCommand(node, OMX_CommandStateSet, OMX_StateLoaded);
    EXPECT_SUCCESS(err, "sendCommand(go-to-Loaded)");

    // Make sure node doesn't just transition to loaded before we are done
    // freeing all input and output buffers.
    err = dequeueMessageForNode(node, &msg, DEFAULT_TIMEOUT);
    CHECK_EQ(err, TIMED_OUT);

    for (size_t i = 0; i < inputBuffers.size(); ++i) {
        err = mOMX->freeBuffer(node, 0, inputBuffers[i].mID);
        EXPECT_SUCCESS(err, "freeBuffer");
    }

    err = dequeueMessageForNode(node, &msg, DEFAULT_TIMEOUT);
    CHECK_EQ(err, TIMED_OUT);

    for (size_t i = 0; i < outputBuffers.size(); ++i) {
        err = mOMX->freeBuffer(node, 1, outputBuffers[i].mID);
        EXPECT_SUCCESS(err, "freeBuffer");
    }

    err = dequeueMessageForNode(node, &msg, DEFAULT_TIMEOUT);
    EXPECT(err == OK
            && msg.type == omx_message::EVENT
            && msg.u.event_data.event == OMX_EventCmdComplete
            && msg.u.event_data.data1 == OMX_CommandStateSet
            && msg.u.event_data.data2 == OMX_StateLoaded,
           "Component did not properly transition to from idle to "
           "loaded state after freeing all input and output buffers.");

    err = mOMX->freeNode(node);
    EXPECT_SUCCESS(err, "freeNode");

    reaper.disarm();

    node = 0;

    return OK;
}

static const char *GetMimeFromComponentRole(const char *componentRole) {
    struct RoleToMime {
        const char *mRole;
        const char *mMime;
    };
    const RoleToMime kRoleToMime[] = {
        { "video_decoder.avc", "video/avc" },
        { "video_decoder.mpeg4", "video/mp4v-es" },
        { "video_decoder.h263", "video/3gpp" },
        { "video_decoder.vpx", "video/x-vnd.on2.vp8" },

        // we appear to use this as a synonym to amrnb.
        { "audio_decoder.amr", "audio/3gpp" },

        { "audio_decoder.amrnb", "audio/3gpp" },
        { "audio_decoder.amrwb", "audio/amr-wb" },
        { "audio_decoder.aac", "audio/mp4a-latm" },
        { "audio_decoder.mp3", "audio/mpeg" },
        { "audio_decoder.vorbis", "audio/vorbis" },
        { "audio_decoder.g711alaw", MEDIA_MIMETYPE_AUDIO_G711_ALAW },
        { "audio_decoder.g711mlaw", MEDIA_MIMETYPE_AUDIO_G711_MLAW },
    };

    for (size_t i = 0; i < sizeof(kRoleToMime) / sizeof(kRoleToMime[0]); ++i) {
        if (!strcmp(componentRole, kRoleToMime[i].mRole)) {
            return kRoleToMime[i].mMime;
        }
    }

    return NULL;
}

static const char *GetURLForMime(const char *mime) {
    struct MimeToURL {
        const char *mMime;
        const char *mURL;
    };
    static const MimeToURL kMimeToURL[] = {
        { "video/avc",
          "file:///sdcard/media_api/video/H264_500_AAC_128.3gp" },
        { "video/mp4v-es", "file:///sdcard/media_api/video/MPEG4_320_AAC_64.mp4" },
        { "video/3gpp",
          "file:///sdcard/media_api/video/H263_500_AMRNB_12.3gp" },
        { "audio/3gpp",
          "file:///sdcard/media_api/video/H263_500_AMRNB_12.3gp" },
        { "audio/amr-wb", NULL },
        { "audio/mp4a-latm",
          "file:///sdcard/media_api/video/H263_56_AAC_24.3gp" },
        { "audio/mpeg",
          "file:///sdcard/media_api/music/MP3_48KHz_128kbps_s_1_17_CBR.mp3" },
        { "audio/vorbis", NULL },
        { "video/x-vnd.on2.vp8",
          "file:///sdcard/media_api/video/big-buck-bunny_trailer.webm" },
        { MEDIA_MIMETYPE_AUDIO_G711_ALAW, "file:///sdcard/M1F1-Alaw-AFsp.wav" },
        { MEDIA_MIMETYPE_AUDIO_G711_MLAW,
          "file:///sdcard/M1F1-mulaw-AFsp.wav" },
    };

    for (size_t i = 0; i < sizeof(kMimeToURL) / sizeof(kMimeToURL[0]); ++i) {
        if (!strcasecmp(kMimeToURL[i].mMime, mime)) {
            return kMimeToURL[i].mURL;
        }
    }

    return NULL;
}

static sp<MediaSource> CreateSourceForMime(const char *mime) {
    const char *url = GetURLForMime(mime);
    CHECK(url != NULL);

    sp<MediaExtractor> extractor = CreateExtractorFromURI(url);

    if (extractor == NULL) {
        return NULL;
    }

    for (size_t i = 0; i < extractor->countTracks(); ++i) {
        sp<MetaData> meta = extractor->getTrackMetaData(i);
        CHECK(meta != NULL);

        const char *trackMime;
        CHECK(meta->findCString(kKeyMIMEType, &trackMime));

        if (!strcasecmp(mime, trackMime)) {
            return extractor->getTrack(i);
        }
    }

    return NULL;
}

static double uniform_rand() {
    return (double)rand() / RAND_MAX;
}

static bool CloseEnough(int64_t time1Us, int64_t time2Us) {
#if 0
    int64_t diff = time1Us - time2Us;
    if (diff < 0) {
        diff = -diff;
    }

    return diff <= 50000;
#else
    return time1Us == time2Us;
#endif
}

status_t Harness::testSeek(
        const char *componentName, const char *componentRole) {
    bool isEncoder =
        !strncmp(componentRole, "audio_encoder.", 14)
        || !strncmp(componentRole, "video_encoder.", 14);

    if (isEncoder) {
        // Not testing seek behaviour for encoders.

        printf("  * Not testing seek functionality for encoders.\n");
        return OK;
    }

    const char *mime = GetMimeFromComponentRole(componentRole);

    if (!mime) {
        ALOGI("Cannot perform seek test with this componentRole (%s)",
             componentRole);

        return OK;
    }

    sp<MediaSource> source = CreateSourceForMime(mime);

    sp<MediaSource> seekSource = CreateSourceForMime(mime);
    if (source == NULL || seekSource == NULL) {
        return UNKNOWN_ERROR;
    }

    CHECK_EQ(seekSource->start(), OK);

    sp<MediaSource> codec = OMXCodec::Create(
            mOMX, source->getFormat(), false /* createEncoder */,
            source, componentName);

    CHECK(codec != NULL);

    CHECK_EQ(codec->start(), OK);

    int64_t durationUs;
    CHECK(source->getFormat()->findInt64(kKeyDuration, &durationUs));

    ALOGI("stream duration is %lld us (%.2f secs)",
         durationUs, durationUs / 1E6);

    static const int32_t kNumIterations = 5000;

    // We are always going to seek beyond EOS in the first iteration (i == 0)
    // followed by a linear read for the second iteration (i == 1).
    // After that it's all random.
    for (int32_t i = 0; i < kNumIterations; ++i) {
        int64_t requestedSeekTimeUs;
        int64_t actualSeekTimeUs;
        MediaSource::ReadOptions options;

        double r = uniform_rand();

        if ((i == 1) || (i > 0 && r < 0.5)) {
            // 50% chance of just continuing to decode from last position.

            requestedSeekTimeUs = -1;

            ALOGI("requesting linear read");
        } else {
            if (i == 0 || r < 0.55) {
                // 5% chance of seeking beyond end of stream.

                requestedSeekTimeUs = durationUs;

                ALOGI("requesting seek beyond EOF");
            } else {
                requestedSeekTimeUs =
                    (int64_t)(uniform_rand() * durationUs);

                ALOGI("requesting seek to %lld us (%.2f secs)",
                     requestedSeekTimeUs, requestedSeekTimeUs / 1E6);
            }

            MediaBuffer *buffer = NULL;
            options.setSeekTo(
                    requestedSeekTimeUs, MediaSource::ReadOptions::SEEK_NEXT_SYNC);

            if (seekSource->read(&buffer, &options) != OK) {
                CHECK_EQ(buffer, NULL);
                actualSeekTimeUs = -1;
            } else {
                CHECK(buffer != NULL);
                CHECK(buffer->meta_data()->findInt64(kKeyTime, &actualSeekTimeUs));
                CHECK(actualSeekTimeUs >= 0);

                buffer->release();
                buffer = NULL;
            }

            ALOGI("nearest keyframe is at %lld us (%.2f secs)",
                 actualSeekTimeUs, actualSeekTimeUs / 1E6);
        }

        status_t err;
        MediaBuffer *buffer;
        for (;;) {
            err = codec->read(&buffer, &options);
            options.clearSeekTo();
            if (err == INFO_FORMAT_CHANGED) {
                CHECK_EQ(buffer, NULL);
                continue;
            }
            if (err == OK) {
                CHECK(buffer != NULL);
                if (buffer->range_length() == 0) {
                    buffer->release();
                    buffer = NULL;
                    continue;
                }
            } else {
                CHECK_EQ(buffer, NULL);
            }

            break;
        }

        if (requestedSeekTimeUs < 0) {
            // Linear read.
            if (err != OK) {
                CHECK_EQ(buffer, NULL);
            } else {
                CHECK(buffer != NULL);
                buffer->release();
                buffer = NULL;
            }
        } else if (actualSeekTimeUs < 0) {
            EXPECT(err != OK,
                   "We attempted to seek beyond EOS and expected "
                   "ERROR_END_OF_STREAM to be returned, but instead "
                   "we got a valid buffer.");
            EXPECT(err == ERROR_END_OF_STREAM,
                   "We attempted to seek beyond EOS and expected "
                   "ERROR_END_OF_STREAM to be returned, but instead "
                   "we found some other error.");
            CHECK_EQ(err, ERROR_END_OF_STREAM);
            CHECK_EQ(buffer, NULL);
        } else {
            EXPECT(err == OK,
                   "Expected a valid buffer to be returned from "
                   "OMXCodec::read.");
            CHECK(buffer != NULL);

            int64_t bufferTimeUs;
            CHECK(buffer->meta_data()->findInt64(kKeyTime, &bufferTimeUs));
            if (!CloseEnough(bufferTimeUs, actualSeekTimeUs)) {
                printf("\n  * Attempted seeking to %lld us (%.2f secs)",
                       requestedSeekTimeUs, requestedSeekTimeUs / 1E6);
                printf("\n  * Nearest keyframe is at %lld us (%.2f secs)",
                       actualSeekTimeUs, actualSeekTimeUs / 1E6);
                printf("\n  * Returned buffer was at %lld us (%.2f secs)\n\n",
                       bufferTimeUs, bufferTimeUs / 1E6);

                buffer->release();
                buffer = NULL;

                CHECK_EQ(codec->stop(), OK);

                return UNKNOWN_ERROR;
            }

            buffer->release();
            buffer = NULL;
        }
    }

    CHECK_EQ(codec->stop(), OK);

    return OK;
}

status_t Harness::test(
        const char *componentName, const char *componentRole) {
    printf("testing %s [%s] ... ", componentName, componentRole);
    ALOGI("testing %s [%s].", componentName, componentRole);

    status_t err1 = testStateTransitions(componentName, componentRole);
    status_t err2 = testSeek(componentName, componentRole);

    if (err1 != OK) {
        return err1;
    }

    return err2;
}

status_t Harness::testAll() {
    List<IOMX::ComponentInfo> componentInfos;
    status_t err = mOMX->listNodes(&componentInfos);
    EXPECT_SUCCESS(err, "listNodes");

    for (List<IOMX::ComponentInfo>::iterator it = componentInfos.begin();
         it != componentInfos.end(); ++it) {
        const IOMX::ComponentInfo &info = *it;
        const char *componentName = info.mName.string();

        if (strncmp(componentName, "OMX.google.", 11)) {
            continue;
        }

        for (List<String8>::const_iterator role_it = info.mRoles.begin();
             role_it != info.mRoles.end(); ++role_it) {
            const char *componentRole = (*role_it).string();

            err = test(componentName, componentRole);

            if (err == OK) {
                printf("OK\n");
            }
        }
    }

    return OK;
}

}  // namespace android

static void usage(const char *me) {
    fprintf(stderr, "usage: %s\n"
                    "  -h(elp)  Show this information\n"
                    "  -s(eed)  Set the random seed\n"
                    "    [ component role ]\n\n"
                    "When launched without specifying a specific component "
                    "and role, tool will test all available OMX components "
                    "in all their supported roles. To determine available "
                    "component names, use \"stagefright -l\"\n"
                    "It's also a good idea to run a separate \"adb logcat\""
                    " for additional debug and progress information.", me);

    exit(0);
}

int main(int argc, char **argv) {
    using namespace android;

    android::ProcessState::self()->startThreadPool();
    DataSource::RegisterDefaultSniffers();

    const char *me = argv[0];

    unsigned long seed = 0xdeadbeef;

    int res;
    while ((res = getopt(argc, argv, "hs:")) >= 0) {
        switch (res) {
            case 's':
            {
                char *end;
                unsigned long x = strtoul(optarg, &end, 10);

                if (*end != '\0' || end == optarg) {
                    fprintf(stderr, "Malformed seed.\n");
                    return 1;
                }

                seed = x;
                break;
            }

            case '?':
                fprintf(stderr, "\n");
                // fall through

            case 'h':
            default:
            {
                usage(me);
                exit(1);
                break;
            }
        }
    }

    argc -= optind;
    argv += optind;

    printf("To reproduce the conditions for this test, launch "
           "with \"%s -s %lu\"\n", me, seed);

    srand(seed);

    sp<Harness> h = new Harness;
    CHECK_EQ(h->initCheck(), OK);

    if (argc == 0) {
        h->testAll();
    } else if (argc == 2) {
        if (h->test(argv[0], argv[1]) == OK) {
            printf("OK\n");
        }
    }

    return 0;
}
