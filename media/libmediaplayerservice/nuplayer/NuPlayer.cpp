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

#include "HTTPLiveSource.h"
#include "NuPlayerDecoder.h"
#include "NuPlayerRenderer.h"
#include "NuPlayerSource.h"
#include "StreamingSource.h"

#include "ATSParser.h"

#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/ACodec.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <surfaceflinger/Surface.h>

namespace android {

////////////////////////////////////////////////////////////////////////////////

NuPlayer::NuPlayer()
    : mAudioEOS(false),
      mVideoEOS(false),
      mScanSourcesPending(false),
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

    msg->setObject("source", new StreamingSource(source));
    msg->post();
}

void NuPlayer::setDataSource(
        const char *url, const KeyedVector<String8, String8> *headers) {
    sp<AMessage> msg = new AMessage(kWhatSetDataSource, id());

    msg->setObject("source", new HTTPLiveSource(url));
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

// static
bool NuPlayer::IsFlushingState(FlushStatus state, bool *formatChange) {
    switch (state) {
        case FLUSHING_DECODER:
            if (formatChange != NULL) {
                *formatChange = false;
            }
            return true;

        case FLUSHING_DECODER_FORMATCHANGE:
            if (formatChange != NULL) {
                *formatChange = true;
            }
            return true;

        default:
            return false;
    }
}

void NuPlayer::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatSetDataSource:
        {
            LOGI("kWhatSetDataSource");

            CHECK(mSource == NULL);

            sp<RefBase> obj;
            CHECK(msg->findObject("source", &obj));

            mSource = static_cast<Source *>(obj.get());
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
            mSource->start();

            mRenderer = new Renderer(
                    mAudioSink,
                    new AMessage(kWhatRendererNotify, id()));

            looper()->registerHandler(mRenderer);

            (new AMessage(kWhatScanSources, id()))->post();
            mScanSourcesPending = true;
            break;
        }

        case kWhatScanSources:
        {
            mScanSourcesPending = false;

            instantiateDecoder(false, &mVideoDecoder);

            if (mAudioSink != NULL) {
                instantiateDecoder(true, &mAudioDecoder);
            }

            if (!mSource->feedMoreTSData()) {
                break;
            }

            if (mAudioDecoder == NULL || mVideoDecoder == NULL) {
                msg->post(100000ll);
                mScanSourcesPending = true;
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

                if (err == -EWOULDBLOCK) {
                    if (mSource->feedMoreTSData()) {
                        msg->post();
                    }
                }
            } else if (what == ACodec::kWhatEOS) {
                mRenderer->queueEOS(audio, ERROR_END_OF_STREAM);
            } else if (what == ACodec::kWhatFlushCompleted) {
                bool formatChange;

                if (audio) {
                    CHECK(IsFlushingState(mFlushingAudio, &formatChange));
                    mFlushingAudio = FLUSHED;
                } else {
                    CHECK(IsFlushingState(mFlushingVideo, &formatChange));
                    mFlushingVideo = FLUSHED;
                }

                LOGI("decoder %s flush completed", audio ? "audio" : "video");

                if (formatChange) {
                    LOGI("initiating %s decoder shutdown",
                         audio ? "audio" : "video");

                    (audio ? mAudioDecoder : mVideoDecoder)->initiateShutdown();

                    if (audio) {
                        mFlushingAudio = SHUTTING_DOWN_DECODER;
                    } else {
                        mFlushingVideo = SHUTTING_DOWN_DECODER;
                    }
                }

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

    bool scanSourcesAgain = false;

    if (mFlushingAudio == SHUT_DOWN) {
        scanSourcesAgain = true;
    } else if (mAudioDecoder != NULL) {
        mAudioDecoder->signalResume();
    }

    if (mFlushingVideo == SHUT_DOWN) {
        scanSourcesAgain = true;
    } else if (mVideoDecoder != NULL) {
        mVideoDecoder->signalResume();
    }

    mFlushingAudio = NONE;
    mFlushingVideo = NONE;

    if (scanSourcesAgain && !mScanSourcesPending) {
        mScanSourcesPending = true;
        (new AMessage(kWhatScanSources, id()))->post();
    }
}

status_t NuPlayer::instantiateDecoder(bool audio, sp<Decoder> *decoder) {
    if (*decoder != NULL) {
        return OK;
    }

    sp<MetaData> meta = mSource->getFormat(audio);

    if (meta == NULL) {
        return -EWOULDBLOCK;
    }

    sp<AMessage> notify =
        new AMessage(audio ? kWhatAudioNotify : kWhatVideoNotify,
                     id());

    *decoder = new Decoder(notify, audio ? NULL : mSurface);
    looper()->registerHandler(*decoder);

    (*decoder)->configure(meta);

    return OK;
}

status_t NuPlayer::feedDecoderInputData(bool audio, const sp<AMessage> &msg) {
    sp<AMessage> reply;
    CHECK(msg->findMessage("reply", &reply));

    if ((audio && IsFlushingState(mFlushingAudio))
            || (!audio && IsFlushingState(mFlushingVideo))) {
        reply->setInt32("err", INFO_DISCONTINUITY);
        reply->post();
        return OK;
    }

    sp<ABuffer> accessUnit;
    status_t err = mSource->dequeueAccessUnit(audio, &accessUnit);

    if (err == -EWOULDBLOCK) {
        return err;
    } else if (err != OK) {
        if (err == INFO_DISCONTINUITY) {
            int32_t type;
            CHECK(accessUnit->meta()->findInt32("discontinuity", &type));

            bool formatChange =
                type == ATSParser::DISCONTINUITY_FORMATCHANGE;

            LOGI("%s discontinuity (formatChange=%d)",
                 audio ? "audio" : "video", formatChange);

            (audio ? mAudioDecoder : mVideoDecoder)->signalFlush();
            mRenderer->flush(audio);

            if (audio) {
                CHECK(mFlushingAudio == NONE
                        || mFlushingAudio == AWAITING_DISCONTINUITY);

                mFlushingAudio = formatChange
                    ? FLUSHING_DECODER_FORMATCHANGE : FLUSHING_DECODER;

                if (mFlushingVideo == NONE) {
                    mFlushingVideo = (mVideoDecoder != NULL)
                        ? AWAITING_DISCONTINUITY
                        : FLUSHED;
                }
            } else {
                CHECK(mFlushingVideo == NONE
                        || mFlushingVideo == AWAITING_DISCONTINUITY);

                mFlushingVideo = formatChange
                    ? FLUSHING_DECODER_FORMATCHANGE : FLUSHING_DECODER;

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
