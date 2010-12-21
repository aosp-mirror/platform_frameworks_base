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

#ifndef NU_PLAYER_H_

#define NU_PLAYER_H_

#include <media/MediaPlayerInterface.h>
#include <media/stagefright/foundation/AHandler.h>

#include "ATSParser.h"
#include "AnotherPacketSource.h"

namespace android {

struct ACodec;
struct MetaData;

struct NuPlayer : public AHandler {
    NuPlayer();

    void setListener(const wp<MediaPlayerBase> &listener);

    void setDataSource(const sp<IStreamSource> &source);
    void setVideoSurface(const sp<Surface> &surface);
    void setAudioSink(const sp<MediaPlayerBase::AudioSink> &sink);
    void start();

protected:
    virtual ~NuPlayer();

    virtual void onMessageReceived(const sp<AMessage> &msg);

private:
    struct Renderer;
    struct Decoder;
    struct NuPlayerStreamListener;

    enum {
        kWhatSetDataSource,
        kWhatSetVideoSurface,
        kWhatSetAudioSink,
        kWhatMoreDataQueued,
        kWhatStart,
        kWhatScanSources,
        kWhatVideoNotify,
        kWhatAudioNotify,
        kWhatRendererNotify,
    };

    wp<MediaPlayerBase> mListener;
    sp<IStreamSource> mSource;
    sp<Surface> mSurface;
    sp<MediaPlayerBase::AudioSink> mAudioSink;
    sp<NuPlayerStreamListener> mStreamListener;
    sp<ATSParser> mTSParser;
    sp<Decoder> mVideoDecoder;
    sp<Decoder> mAudioDecoder;
    sp<Renderer> mRenderer;

    bool mEOS;
    bool mAudioEOS;
    bool mVideoEOS;

    enum FlushStatus {
        NONE,
        AWAITING_DISCONTINUITY,
        FLUSHING_DECODER,
        SHUTTING_DOWN_DECODER,
        FLUSHED,
        SHUT_DOWN,
    };

    FlushStatus mFlushingAudio;
    FlushStatus mFlushingVideo;

    status_t instantiateDecoder(
            bool audio, sp<Decoder> *decoder, bool ignoreCodecSpecificData);

    status_t feedDecoderInputData(bool audio, const sp<AMessage> &msg);
    void renderBuffer(bool audio, const sp<AMessage> &msg);

    status_t dequeueNextAccessUnit(
            ATSParser::SourceType *type, sp<ABuffer> *accessUnit);

    status_t dequeueAccessUnit(
            ATSParser::SourceType type, sp<ABuffer> *accessUnit);

    void feedMoreTSData();
    void notifyListener(int msg, int ext1, int ext2);

    void finishFlushIfPossible();

    DISALLOW_EVIL_CONSTRUCTORS(NuPlayer);
};

}  // namespace android

#endif  // NU_PLAYER_H_
