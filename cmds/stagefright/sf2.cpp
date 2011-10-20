/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <binder/ProcessState.h>

#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/foundation/AMessage.h>

#include <media/stagefright/ACodec.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/NativeWindowWrapper.h>
#include <media/stagefright/Utils.h>

#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/SurfaceComposerClient.h>

#include "include/ESDS.h"

using namespace android;

struct Controller : public AHandler {
    Controller(const char *uri, bool decodeAudio,
               const sp<Surface> &surface, bool renderToSurface)
        : mURI(uri),
          mDecodeAudio(decodeAudio),
          mSurface(surface),
          mRenderToSurface(renderToSurface),
          mCodec(new ACodec),
          mIsVorbis(false) {
        CHECK(!mDecodeAudio || mSurface == NULL);
    }

    void startAsync() {
        (new AMessage(kWhatStart, id()))->post();
    }

protected:
    virtual ~Controller() {
    }

    virtual void onMessageReceived(const sp<AMessage> &msg) {
        switch (msg->what()) {
            case kWhatStart:
            {
#if 1
                mDecodeLooper = looper();
#else
                mDecodeLooper = new ALooper;
                mDecodeLooper->setName("sf2 decode looper");
                mDecodeLooper->start();
#endif

                sp<DataSource> dataSource =
                    DataSource::CreateFromURI(mURI.c_str());

                sp<MediaExtractor> extractor =
                    MediaExtractor::Create(dataSource);

                for (size_t i = 0; i < extractor->countTracks(); ++i) {
                    sp<MetaData> meta = extractor->getTrackMetaData(i);

                    const char *mime;
                    CHECK(meta->findCString(kKeyMIMEType, &mime));

                    if (!strncasecmp(mDecodeAudio ? "audio/" : "video/",
                                     mime, 6)) {
                        mSource = extractor->getTrack(i);

                        if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_VORBIS)) {
                            mIsVorbis = true;
                        } else {
                            mIsVorbis = false;
                        }
                        break;
                    }
                }
                CHECK(mSource != NULL);

                CHECK_EQ(mSource->start(), (status_t)OK);

                mDecodeLooper->registerHandler(mCodec);

                mCodec->setNotificationMessage(
                        new AMessage(kWhatCodecNotify, id()));

                sp<AMessage> format = makeFormat(mSource->getFormat());

                if (mSurface != NULL) {
                    format->setObject(
                            "native-window", new NativeWindowWrapper(mSurface));
                }

                mCodec->initiateSetup(format);

                mCSDIndex = 0;
                mStartTimeUs = ALooper::GetNowUs();
                mNumOutputBuffersReceived = 0;
                mTotalBytesReceived = 0;
                mLeftOverBuffer = NULL;
                mFinalResult = OK;
                mSeekState = SEEK_NONE;

                // (new AMessage(kWhatSeek, id()))->post(5000000ll);
                break;
            }

            case kWhatSeek:
            {
                printf("+");
                fflush(stdout);

                CHECK(mSeekState == SEEK_NONE
                        || mSeekState == SEEK_FLUSH_COMPLETED);

                if (mLeftOverBuffer != NULL) {
                    mLeftOverBuffer->release();
                    mLeftOverBuffer = NULL;
                }

                mSeekState = SEEK_FLUSHING;
                mSeekTimeUs = 30000000ll;

                mCodec->signalFlush();
                break;
            }

            case kWhatStop:
            {
                if (mLeftOverBuffer != NULL) {
                    mLeftOverBuffer->release();
                    mLeftOverBuffer = NULL;
                }

                CHECK_EQ(mSource->stop(), (status_t)OK);
                mSource.clear();

                mCodec->initiateShutdown();
                break;
            }

            case kWhatCodecNotify:
            {
                int32_t what;
                CHECK(msg->findInt32("what", &what));

                if (what == ACodec::kWhatFillThisBuffer) {
                    onFillThisBuffer(msg);
                } else if (what == ACodec::kWhatDrainThisBuffer) {
                    if ((mNumOutputBuffersReceived++ % 16) == 0) {
                        printf(".");
                        fflush(stdout);
                    }

                    onDrainThisBuffer(msg);
                } else if (what == ACodec::kWhatEOS) {
                    printf("$\n");

                    int64_t delayUs = ALooper::GetNowUs() - mStartTimeUs;

                    if (mDecodeAudio) {
                        printf("%lld bytes received. %.2f KB/sec\n",
                               mTotalBytesReceived,
                               mTotalBytesReceived * 1E6 / 1024 / delayUs);
                    } else {
                        printf("%d frames decoded, %.2f fps. %lld bytes "
                               "received. %.2f KB/sec\n",
                               mNumOutputBuffersReceived,
                               mNumOutputBuffersReceived * 1E6 / delayUs,
                               mTotalBytesReceived,
                               mTotalBytesReceived * 1E6 / 1024 / delayUs);
                    }

                    (new AMessage(kWhatStop, id()))->post();
                } else if (what == ACodec::kWhatFlushCompleted) {
                    mSeekState = SEEK_FLUSH_COMPLETED;
                    mCodec->signalResume();

                    (new AMessage(kWhatSeek, id()))->post(5000000ll);
                } else if (what == ACodec::kWhatOutputFormatChanged) {
                } else {
                    CHECK_EQ(what, (int32_t)ACodec::kWhatShutdownCompleted);

                    mDecodeLooper->unregisterHandler(mCodec->id());

                    if (mDecodeLooper != looper()) {
                        mDecodeLooper->stop();
                    }

                    looper()->stop();
                }
                break;
            }

            default:
                TRESPASS();
                break;
        }
    }

private:
    enum {
        kWhatStart             = 'strt',
        kWhatStop              = 'stop',
        kWhatCodecNotify       = 'noti',
        kWhatSeek              = 'seek',
    };

    sp<ALooper> mDecodeLooper;

    AString mURI;
    bool mDecodeAudio;
    sp<Surface> mSurface;
    bool mRenderToSurface;
    sp<ACodec> mCodec;
    sp<MediaSource> mSource;
    bool mIsVorbis;

    Vector<sp<ABuffer> > mCSD;
    size_t mCSDIndex;

    MediaBuffer *mLeftOverBuffer;
    status_t mFinalResult;

    int64_t mStartTimeUs;
    int32_t mNumOutputBuffersReceived;
    int64_t mTotalBytesReceived;

    enum SeekState {
        SEEK_NONE,
        SEEK_FLUSHING,
        SEEK_FLUSH_COMPLETED,
    };
    SeekState mSeekState;
    int64_t mSeekTimeUs;

    sp<AMessage> makeFormat(const sp<MetaData> &meta) {
        CHECK(mCSD.isEmpty());

        const char *mime;
        CHECK(meta->findCString(kKeyMIMEType, &mime));

        sp<AMessage> msg = new AMessage;
        msg->setString("mime", mime);

        if (!strncasecmp("video/", mime, 6)) {
            int32_t width, height;
            CHECK(meta->findInt32(kKeyWidth, &width));
            CHECK(meta->findInt32(kKeyHeight, &height));

            msg->setInt32("width", width);
            msg->setInt32("height", height);
        } else {
            CHECK(!strncasecmp("audio/", mime, 6));

            int32_t numChannels, sampleRate;
            CHECK(meta->findInt32(kKeyChannelCount, &numChannels));
            CHECK(meta->findInt32(kKeySampleRate, &sampleRate));

            msg->setInt32("channel-count", numChannels);
            msg->setInt32("sample-rate", sampleRate);
        }

        uint32_t type;
        const void *data;
        size_t size;
        if (meta->findData(kKeyAVCC, &type, &data, &size)) {
            // Parse the AVCDecoderConfigurationRecord

            const uint8_t *ptr = (const uint8_t *)data;

            CHECK(size >= 7);
            CHECK_EQ((unsigned)ptr[0], 1u);  // configurationVersion == 1
            uint8_t profile = ptr[1];
            uint8_t level = ptr[3];

            // There is decodable content out there that fails the following
            // assertion, let's be lenient for now...
            // CHECK((ptr[4] >> 2) == 0x3f);  // reserved

            size_t lengthSize = 1 + (ptr[4] & 3);

            // commented out check below as H264_QVGA_500_NO_AUDIO.3gp
            // violates it...
            // CHECK((ptr[5] >> 5) == 7);  // reserved

            size_t numSeqParameterSets = ptr[5] & 31;

            ptr += 6;
            size -= 6;

            sp<ABuffer> buffer = new ABuffer(1024);
            buffer->setRange(0, 0);

            for (size_t i = 0; i < numSeqParameterSets; ++i) {
                CHECK(size >= 2);
                size_t length = U16_AT(ptr);

                ptr += 2;
                size -= 2;

                CHECK(size >= length);

                memcpy(buffer->data() + buffer->size(), "\x00\x00\x00\x01", 4);
                memcpy(buffer->data() + buffer->size() + 4, ptr, length);
                buffer->setRange(0, buffer->size() + 4 + length);

                ptr += length;
                size -= length;
            }

            buffer->meta()->setInt32("csd", true);
            mCSD.push(buffer);

            buffer = new ABuffer(1024);
            buffer->setRange(0, 0);

            CHECK(size >= 1);
            size_t numPictureParameterSets = *ptr;
            ++ptr;
            --size;

            for (size_t i = 0; i < numPictureParameterSets; ++i) {
                CHECK(size >= 2);
                size_t length = U16_AT(ptr);

                ptr += 2;
                size -= 2;

                CHECK(size >= length);

                memcpy(buffer->data() + buffer->size(), "\x00\x00\x00\x01", 4);
                memcpy(buffer->data() + buffer->size() + 4, ptr, length);
                buffer->setRange(0, buffer->size() + 4 + length);

                ptr += length;
                size -= length;
            }

            buffer->meta()->setInt32("csd", true);
            mCSD.push(buffer);

            msg->setObject("csd", buffer);
        } else if (meta->findData(kKeyESDS, &type, &data, &size)) {
            ESDS esds((const char *)data, size);
            CHECK_EQ(esds.InitCheck(), (status_t)OK);

            const void *codec_specific_data;
            size_t codec_specific_data_size;
            esds.getCodecSpecificInfo(
                    &codec_specific_data, &codec_specific_data_size);

            sp<ABuffer> buffer = new ABuffer(codec_specific_data_size);

            memcpy(buffer->data(), codec_specific_data,
                   codec_specific_data_size);

            buffer->meta()->setInt32("csd", true);
            mCSD.push(buffer);
        } else if (meta->findData(kKeyVorbisInfo, &type, &data, &size)) {
            sp<ABuffer> buffer = new ABuffer(size);
            memcpy(buffer->data(), data, size);

            buffer->meta()->setInt32("csd", true);
            mCSD.push(buffer);

            CHECK(meta->findData(kKeyVorbisBooks, &type, &data, &size));

            buffer = new ABuffer(size);
            memcpy(buffer->data(), data, size);

            buffer->meta()->setInt32("csd", true);
            mCSD.push(buffer);
        }

        int32_t maxInputSize;
        if (meta->findInt32(kKeyMaxInputSize, &maxInputSize)) {
            msg->setInt32("max-input-size", maxInputSize);
        }

        return msg;
    }

    void onFillThisBuffer(const sp<AMessage> &msg) {
        sp<AMessage> reply;
        CHECK(msg->findMessage("reply", &reply));

        if (mSeekState == SEEK_FLUSHING) {
            reply->post();
            return;
        }

        sp<RefBase> obj;
        CHECK(msg->findObject("buffer", &obj));
        sp<ABuffer> outBuffer = static_cast<ABuffer *>(obj.get());

        if (mCSDIndex < mCSD.size()) {
            outBuffer = mCSD.editItemAt(mCSDIndex++);
            outBuffer->meta()->setInt64("timeUs", 0);
        } else {
            size_t sizeLeft = outBuffer->capacity();
            outBuffer->setRange(0, 0);

            int32_t n = 0;

            for (;;) {
                MediaBuffer *inBuffer;

                if (mLeftOverBuffer != NULL) {
                    inBuffer = mLeftOverBuffer;
                    mLeftOverBuffer = NULL;
                } else if (mFinalResult != OK) {
                    break;
                } else {
                    MediaSource::ReadOptions options;
                    if (mSeekState == SEEK_FLUSH_COMPLETED) {
                        options.setSeekTo(mSeekTimeUs);
                        mSeekState = SEEK_NONE;
                    }
                    status_t err = mSource->read(&inBuffer, &options);

                    if (err != OK) {
                        mFinalResult = err;
                        break;
                    }
                }

                size_t sizeNeeded = inBuffer->range_length();
                if (mIsVorbis) {
                    // Vorbis data is suffixed with the number of
                    // valid samples on the page.
                    sizeNeeded += sizeof(int32_t);
                }

                if (sizeNeeded > sizeLeft) {
                    if (outBuffer->size() == 0) {
                        LOGE("Unable to fit even a single input buffer of size %d.",
                             sizeNeeded);
                    }
                    CHECK_GT(outBuffer->size(), 0u);

                    mLeftOverBuffer = inBuffer;
                    break;
                }

                ++n;

                if (outBuffer->size() == 0) {
                    int64_t timeUs;
                    CHECK(inBuffer->meta_data()->findInt64(kKeyTime, &timeUs));

                    outBuffer->meta()->setInt64("timeUs", timeUs);
                }

                memcpy(outBuffer->data() + outBuffer->size(),
                       (const uint8_t *)inBuffer->data()
                        + inBuffer->range_offset(),
                       inBuffer->range_length());

                if (mIsVorbis) {
                    int32_t numPageSamples;
                    if (!inBuffer->meta_data()->findInt32(
                                kKeyValidSamples, &numPageSamples)) {
                        numPageSamples = -1;
                    }

                    memcpy(outBuffer->data()
                            + outBuffer->size() + inBuffer->range_length(),
                           &numPageSamples, sizeof(numPageSamples));
                }

                outBuffer->setRange(
                        0, outBuffer->size() + sizeNeeded);

                sizeLeft -= sizeNeeded;

                inBuffer->release();
                inBuffer = NULL;

                break;  // Don't coalesce
            }

            ALOGV("coalesced %d input buffers", n);

            if (outBuffer->size() == 0) {
                CHECK_NE(mFinalResult, (status_t)OK);

                reply->setInt32("err", mFinalResult);
                reply->post();
                return;
            }
        }

        reply->setObject("buffer", outBuffer);
        reply->post();
    }

    void onDrainThisBuffer(const sp<AMessage> &msg) {
        sp<RefBase> obj;
        CHECK(msg->findObject("buffer", &obj));

        sp<ABuffer> buffer = static_cast<ABuffer *>(obj.get());
        mTotalBytesReceived += buffer->size();

        sp<AMessage> reply;
        CHECK(msg->findMessage("reply", &reply));

        if (mRenderToSurface) {
            reply->setInt32("render", 1);
        }

        reply->post();
    }

    DISALLOW_EVIL_CONSTRUCTORS(Controller);
};

static void usage(const char *me) {
    fprintf(stderr, "usage: %s\n", me);
    fprintf(stderr, "       -h(elp)\n");
    fprintf(stderr, "       -a(udio)\n");

    fprintf(stderr,
            "       -S(urface) Allocate output buffers on a surface.\n"
            "       -R(ender)  Render surface-allocated buffers.\n");
}

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    bool decodeAudio = false;
    bool useSurface = false;
    bool renderToSurface = false;

    int res;
    while ((res = getopt(argc, argv, "haSR")) >= 0) {
        switch (res) {
            case 'a':
                decodeAudio = true;
                break;

            case 'S':
                useSurface = true;
                break;

            case 'R':
                renderToSurface = true;
                break;

            case '?':
            case 'h':
            default:
            {
                usage(argv[0]);
                return 1;
            }
        }
    }

    argc -= optind;
    argv += optind;

    if (argc != 1) {
        usage(argv[-optind]);
        return 1;
    }

    DataSource::RegisterDefaultSniffers();

    sp<ALooper> looper = new ALooper;
    looper->setName("sf2");

    sp<SurfaceComposerClient> composerClient;
    sp<SurfaceControl> control;
    sp<Surface> surface;

    if (!decodeAudio && useSurface) {
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
        CHECK_EQ(control->setLayer(INT_MAX), (status_t)OK);
        CHECK_EQ(control->show(), (status_t)OK);
        SurfaceComposerClient::closeGlobalTransaction();

        surface = control->getSurface();
        CHECK(surface != NULL);

        CHECK_EQ((status_t)OK,
                 native_window_api_connect(
                     surface.get(), NATIVE_WINDOW_API_MEDIA));
    }

    sp<Controller> controller =
        new Controller(argv[0], decodeAudio, surface, renderToSurface);

    looper->registerHandler(controller);

    controller->startAsync();

    CHECK_EQ(looper->start(true /* runOnCallingThread */), (status_t)OK);

    looper->unregisterHandler(controller->id());

    if (!decodeAudio && useSurface) {
        CHECK_EQ((status_t)OK,
                 native_window_api_disconnect(
                     surface.get(), NATIVE_WINDOW_API_MEDIA));

        composerClient->dispose();
    }

    return 0;
}

