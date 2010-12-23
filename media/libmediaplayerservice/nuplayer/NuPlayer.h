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

namespace android {

struct ACodec;
struct MetaData;

struct NuPlayer : public AHandler {
    NuPlayer();

    void setListener(const wp<MediaPlayerBase> &listener);

    void setDataSource(const sp<IStreamSource> &source);

    void setDataSource(
            const char *url, const KeyedVector<String8, String8> *headers);

    void setVideoSurface(const sp<Surface> &surface);
    void setAudioSink(const sp<MediaPlayerBase::AudioSink> &sink);
    void start();

protected:
    virtual ~NuPlayer();

    virtual void onMessageReceived(const sp<AMessage> &msg);

private:
    struct Decoder;
    struct HTTPLiveSource;
    struct NuPlayerStreamListener;
    struct Renderer;
    struct Source;
    struct StreamingSource;

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
    sp<Source> mSource;
    sp<Surface> mSurface;
    sp<MediaPlayerBase::AudioSink> mAudioSink;
    sp<Decoder> mVideoDecoder;
    sp<Decoder> mAudioDecoder;
    sp<Renderer> mRenderer;

    bool mEOS;
    bool mAudioEOS;
    bool mVideoEOS;

    bool mScanSourcesPending;

    enum FlushStatus {
        NONE,
        AWAITING_DISCONTINUITY,
        FLUSHING_DECODER,
        FLUSHING_DECODER_FORMATCHANGE,
        SHUTTING_DOWN_DECODER,
        FLUSHED,
        SHUT_DOWN,
    };

    FlushStatus mFlushingAudio;
    FlushStatus mFlushingVideo;

    status_t instantiateDecoder(bool audio, sp<Decoder> *decoder);

    status_t feedDecoderInputData(bool audio, const sp<AMessage> &msg);
    void renderBuffer(bool audio, const sp<AMessage> &msg);

    void notifyListener(int msg, int ext1, int ext2);

    void finishFlushIfPossible();

    static bool IsFlushingState(FlushStatus state, bool *formatChange = NULL);

    DISALLOW_EVIL_CONSTRUCTORS(NuPlayer);
};

}  // namespace android

#endif  // NU_PLAYER_H_
