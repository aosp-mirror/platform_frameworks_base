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

//#define LOG_NDEBUG 0
#define LOG_TAG "NuPlayer"
#include <utils/Log.h>

#include "NuPlayer.h"
#include "NuPlayerDecoder.h"
#include "NuPlayerRenderer.h"
#include "NuPlayerStreamListener.h"

#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/ACodec.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <surfaceflinger/Surface.h>

#define SHUTDOWN_ON_DISCONTINUITY       0

namespace android {

////////////////////////////////////////////////////////////////////////////////

NuPlayer::NuPlayer()
    : mEOS(false),
      mAudioEOS(false),
      mVideoEOS(false),
      mFlushingAudio(NONE),
      mFlushingVideo(NONE) {
}

NuPlayer::~NuPlayer() {
}

void NuPlayer::setListener(const wp<MediaPlayerBase> &listener) {
    mListener = listener;
}

void NuPlayer::setDataSource(const sp<IStreamSource> &source) {
    sp<AMessage> msg = new AMessage(kWhatSetDataSource, id());

    source->incStrong(this);
    msg->setPointer("source", source.get());  // XXX unsafe.

    msg->post();
}

void NuPlayer::setVideoSurface(const sp<Surface> &surface) {
    sp<AMessage> msg = new AMessage(kWhatSetVideoSurface, id());
    msg->setObject("surface", surface);
    msg->post();
}

void NuPlayer::setAudioSink(const sp<MediaPlayerBase::AudioSink> &sink) {
    sp<AMessage> msg = new AMessage(kWhatSetAudioSink, id());
    msg->setObject("sink", sink);
    msg->post();
}

void NuPlayer::start() {
    (new AMessage(kWhatStart, id()))->post();
}

void NuPlayer::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatSetDataSource:
        {
            LOGI("kWhatSetDataSource");

            CHECK(mSource == NULL);

            void *ptr;
            CHECK(msg->findPointer("source", &ptr));

            mSource = static_cast<IStreamSource *>(ptr);
            mSource->decStrong(this);

            mStreamListener = new NuPlayerStreamListener(mSource, id());
            mTSParser = new ATSParser;
            break;
        }

        case kWhatSetVideoSurface:
        {
            LOGI("kWhatSetVideoSurface");

            sp<RefBase> obj;
            CHECK(msg->findObject("surface", &obj));

            mSurface = static_cast<Surface *>(obj.get());
            break;
        }

        case kWhatSetAudioSink:
        {
            LOGI("kWhatSetAudioSink");

            sp<RefBase> obj;
            CHECK(msg->findObject("sink", &obj));

            mAudioSink = static_cast<MediaPlayerBase::AudioSink *>(obj.get());
            break;
        }

        case kWhatStart:
        {
            mStreamListener->start();

            mRenderer = new Renderer(
                    mAudioSink,
                    new AMessage(kWhatRendererNotify, id()));

            looper()->registerHandler(mRenderer);

            (new AMessage(kWhatScanSources, id()))->post();
            break;
        }

        case kWhatScanSources:
        {
            instantiateDecoder(
                    false,
                    &mVideoDecoder,
                    false /* ignoreCodecSpecificData */);

            if (mAudioSink != NULL) {
                instantiateDecoder(
                        true,
                        &mAudioDecoder,
                        false /* ignoreCodecSpecificData */);
            }

            if (mEOS) {
                break;
            }

            feedMoreTSData();

            if (mAudioDecoder == NULL || mVideoDecoder == NULL) {
                msg->post(100000ll);
            }
            break;
        }

        case kWhatVideoNotify:
        case kWhatAudioNotify:
        {
            bool audio = msg->what() == kWhatAudioNotify;

            sp<AMessage> codecRequest;
            CHECK(msg->findMessage("codec-request", &codecRequest));

            int32_t what;
            CHECK(codecRequest->findInt32("what", &what));

            if (what == ACodec::kWhatFillThisBuffer) {
                status_t err = feedDecoderInputData(
                        audio, codecRequest);

                if (err == -EWOULDBLOCK && !mEOS) {
                    feedMoreTSData();
                    msg->post();
                }
            } else if (what == ACodec::kWhatEOS) {
                mRenderer->queueEOS(audio, ERROR_END_OF_STREAM);
            } else if (what == ACodec::kWhatFlushCompleted) {
                if (audio) {
                    CHECK_EQ((int)mFlushingAudio, (int)FLUSHING_DECODER);
                    mFlushingAudio = FLUSHED;
                } else {
                    CHECK_EQ((int)mFlushingVideo, (int)FLUSHING_DECODER);
                    mFlushingVideo = FLUSHED;
                }

                LOGI("decoder %s flush completed", audio ? "audio" : "video");

#if SHUTDOWN_ON_DISCONTINUITY
                LOGI("initiating %s decoder shutdown",
                     audio ? "audio" : "video");

                (audio ? mAudioDecoder : mVideoDecoder)->initiateShutdown();

                if (audio) {
                    mFlushingAudio = SHUTTING_DOWN_DECODER;
                } else {
                    mFlushingVideo = SHUTTING_DOWN_DECODER;
                }
#endif

                finishFlushIfPossible();
            } else if (what == ACodec::kWhatOutputFormatChanged) {
                CHECK(audio);

                int32_t numChannels;
                CHECK(codecRequest->findInt32("channel-count", &numChannels));

                int32_t sampleRate;
                CHECK(codecRequest->findInt32("sample-rate", &sampleRate));

                LOGI("Audio output format changed to %d Hz, %d channels",
                     sampleRate, numChannels);

                mAudioSink->close();
                CHECK_EQ(mAudioSink->open(sampleRate, numChannels), (status_t)OK);
                mAudioSink->start();

                mRenderer->signalAudioSinkChanged();
            } else if (what == ACodec::kWhatShutdownCompleted) {
                LOGI("%s shutdown completed", audio ? "audio" : "video");
                if (audio) {
                    mAudioDecoder.clear();

                    CHECK_EQ((int)mFlushingAudio, (int)SHUTTING_DOWN_DECODER);
                    mFlushingAudio = SHUT_DOWN;
                } else {
                    mVideoDecoder.clear();

                    CHECK_EQ((int)mFlushingVideo, (int)SHUTTING_DOWN_DECODER);
                    mFlushingVideo = SHUT_DOWN;
                }

                finishFlushIfPossible();
            } else {
                CHECK_EQ((int)what, (int)ACodec::kWhatDrainThisBuffer);

                renderBuffer(audio, codecRequest);
            }

            break;
        }

        case kWhatRendererNotify:
        {
            int32_t what;
            CHECK(msg->findInt32("what", &what));

            if (what == Renderer::kWhatEOS) {
                int32_t audio;
                CHECK(msg->findInt32("audio", &audio));

                if (audio) {
                    mAudioEOS = true;
                } else {
                    mVideoEOS = true;
                }

                LOGI("reached %s EOS", audio ? "audio" : "video");

                if ((mAudioEOS || mAudioDecoder == NULL)
                        && (mVideoEOS || mVideoDecoder == NULL)) {
                    notifyListener(MEDIA_PLAYBACK_COMPLETE, 0, 0);
                }
            } else {
                CHECK_EQ(what, (int32_t)Renderer::kWhatFlushComplete);

                int32_t audio;
                CHECK(msg->findInt32("audio", &audio));

                LOGI("renderer %s flush completed.", audio ? "audio" : "video");
            }
            break;
        }

        case kWhatMoreDataQueued:
        {
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

void NuPlayer::finishFlushIfPossible() {
    if (mFlushingAudio != FLUSHED && mFlushingAudio != SHUT_DOWN) {
        return;
    }

    if (mFlushingVideo != FLUSHED && mFlushingVideo != SHUT_DOWN) {
        return;
    }

    LOGI("both audio and video are flushed now.");

    mRenderer->signalTimeDiscontinuity();

    if (mFlushingAudio == SHUT_DOWN) {
        instantiateDecoder(
                true,
                &mAudioDecoder,
                true /* ignoreCodecSpecificData */);
        CHECK(mAudioDecoder != NULL);
    } else if (mAudioDecoder != NULL) {
        mAudioDecoder->signalResume();
    }

    if (mFlushingVideo == SHUT_DOWN) {
        instantiateDecoder(
                false,
                &mVideoDecoder,
                true /* ignoreCodecSpecificData */);
        CHECK(mVideoDecoder != NULL);
    } else if (mVideoDecoder != NULL) {
        mVideoDecoder->signalResume();
    }

    mFlushingAudio = NONE;
    mFlushingVideo = NONE;
}

void NuPlayer::feedMoreTSData() {
    CHECK(!mEOS);

    for (int32_t i = 0; i < 10; ++i) {
        char buffer[188];
        ssize_t n = mStreamListener->read(buffer, sizeof(buffer));

        if (n == 0) {
            LOGI("input data EOS reached.");
            mTSParser->signalEOS(ERROR_END_OF_STREAM);
            mEOS = true;
            break;
        } else if (n == INFO_DISCONTINUITY) {
            mTSParser->signalDiscontinuity(ATSParser::DISCONTINUITY_SEEK);
        } else if (n < 0) {
            CHECK_EQ(n, -EWOULDBLOCK);
            break;
        } else {
            if (buffer[0] == 0x00) {
                // XXX legacy
                mTSParser->signalDiscontinuity(
                        buffer[1] == 0x00
                            ? ATSParser::DISCONTINUITY_SEEK
                            : ATSParser::DISCONTINUITY_FORMATCHANGE);
            } else {
                mTSParser->feedTSPacket(buffer, sizeof(buffer));
            }
        }
    }
}

status_t NuPlayer::dequeueNextAccessUnit(
        ATSParser::SourceType *type, sp<ABuffer> *accessUnit) {
    accessUnit->clear();

    status_t audioErr = -EWOULDBLOCK;
    int64_t audioTimeUs;

    sp<AnotherPacketSource> audioSource =
        static_cast<AnotherPacketSource *>(
                mTSParser->getSource(ATSParser::MPEG2ADTS_AUDIO).get());

    if (audioSource != NULL) {
        audioErr = audioSource->nextBufferTime(&audioTimeUs);
    }

    status_t videoErr = -EWOULDBLOCK;
    int64_t videoTimeUs;

    sp<AnotherPacketSource> videoSource =
        static_cast<AnotherPacketSource *>(
                mTSParser->getSource(ATSParser::AVC_VIDEO).get());

    if (videoSource != NULL) {
        videoErr = videoSource->nextBufferTime(&videoTimeUs);
    }

    if (audioErr == -EWOULDBLOCK || videoErr == -EWOULDBLOCK) {
        return -EWOULDBLOCK;
    }

    if (audioErr != OK && videoErr != OK) {
        return audioErr;
    }

    if (videoErr != OK || (audioErr == OK && audioTimeUs < videoTimeUs)) {
        *type = ATSParser::MPEG2ADTS_AUDIO;
        return audioSource->dequeueAccessUnit(accessUnit);
    } else {
        *type = ATSParser::AVC_VIDEO;
        return videoSource->dequeueAccessUnit(accessUnit);
    }
}

status_t NuPlayer::dequeueAccessUnit(
        ATSParser::SourceType type, sp<ABuffer> *accessUnit) {
    sp<AnotherPacketSource> source =
        static_cast<AnotherPacketSource *>(mTSParser->getSource(type).get());

    if (source == NULL) {
        return -EWOULDBLOCK;
    }

    status_t finalResult;
    if (!source->hasBufferAvailable(&finalResult)) {
        return finalResult == OK ? -EWOULDBLOCK : finalResult;
    }

    return source->dequeueAccessUnit(accessUnit);
}

status_t NuPlayer::instantiateDecoder(
        bool audio, sp<Decoder> *decoder, bool ignoreCodecSpecificData) {
    if (*decoder != NULL) {
        return OK;
    }

    ATSParser::SourceType type =
        audio ? ATSParser::MPEG2ADTS_AUDIO : ATSParser::AVC_VIDEO;

    sp<AnotherPacketSource> source =
        static_cast<AnotherPacketSource *>(
                mTSParser->getSource(type).get());

    if (source == NULL) {
        return -EWOULDBLOCK;
    }

    sp<AMessage> notify =
        new AMessage(audio ? kWhatAudioNotify : kWhatVideoNotify,
                     id());

    *decoder = new Decoder(notify, audio ? NULL : mSurface);
    looper()->registerHandler(*decoder);

    const sp<MetaData> &meta = source->getFormat();
    (*decoder)->configure(meta, ignoreCodecSpecificData);

    return OK;
}

status_t NuPlayer::feedDecoderInputData(bool audio, const sp<AMessage> &msg) {
    sp<AMessage> reply;
    CHECK(msg->findMessage("reply", &reply));

    if ((audio && mFlushingAudio == FLUSHING_DECODER)
            || (!audio && mFlushingVideo == FLUSHING_DECODER)) {
        reply->setInt32("err", INFO_DISCONTINUITY);
        reply->post();
        return OK;
    }

    sp<ABuffer> accessUnit;
    status_t err = dequeueAccessUnit(
            audio ? ATSParser::MPEG2ADTS_AUDIO : ATSParser::AVC_VIDEO,
            &accessUnit);

    if (err == -EWOULDBLOCK) {
        return err;
    } else if (err != OK) {
        if (err == INFO_DISCONTINUITY) {
            LOGI("%s discontinuity", audio ? "audio" : "video");
            (audio ? mAudioDecoder : mVideoDecoder)->signalFlush();
            mRenderer->flush(audio);

            if (audio) {
                CHECK(mFlushingAudio == NONE
                        || mFlushingAudio == AWAITING_DISCONTINUITY);
                mFlushingAudio = FLUSHING_DECODER;
                if (mFlushingVideo == NONE) {
                    mFlushingVideo = (mVideoDecoder != NULL)
                        ? AWAITING_DISCONTINUITY
                        : FLUSHED;
                }
            } else {
                CHECK(mFlushingVideo == NONE
                        || mFlushingVideo == AWAITING_DISCONTINUITY);
                mFlushingVideo = FLUSHING_DECODER;
                if (mFlushingAudio == NONE) {
                    mFlushingAudio = (mAudioDecoder != NULL)
                        ? AWAITING_DISCONTINUITY
                        : FLUSHED;
                }
            }
        }

        reply->setInt32("err", err);
        reply->post();
        return OK;
    }

    LOGV("returned a valid buffer of %s data", audio ? "audio" : "video");

#if 0
    int64_t mediaTimeUs;
    CHECK(accessUnit->meta()->findInt64("timeUs", &mediaTimeUs));
    LOGI("feeding %s input buffer at media time %.2f secs",
         audio ? "audio" : "video",
         mediaTimeUs / 1E6);
#endif

    reply->setObject("buffer", accessUnit);
    reply->post();

    return OK;
}

void NuPlayer::renderBuffer(bool audio, const sp<AMessage> &msg) {
    LOGV("renderBuffer %s", audio ? "audio" : "video");

    sp<AMessage> reply;
    CHECK(msg->findMessage("reply", &reply));

    sp<RefBase> obj;
    CHECK(msg->findObject("buffer", &obj));

    sp<ABuffer> buffer = static_cast<ABuffer *>(obj.get());

    mRenderer->queueBuffer(audio, buffer, reply);
}

void NuPlayer::notifyListener(int msg, int ext1, int ext2) {
    if (mListener == NULL) {
        return;
    }

    sp<MediaPlayerBase> listener = mListener.promote();

    if (listener == NULL) {
        return;
    }

    listener->sendEvent(msg, ext1, ext2);
}

}  // namespace android
