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

#ifndef VORBIS_DECODER_H_

#define VORBIS_DECODER_H_

#include <media/stagefright/MediaSource.h>

struct vorbis_dsp_state;
struct vorbis_info;

namespace android {

struct MediaBufferGroup;

struct VorbisDecoder : public MediaSource {
    VorbisDecoder(const sp<MediaSource> &source);

    virtual status_t start(MetaData *params);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);

protected:
    virtual ~VorbisDecoder();

private:
    enum {
        kMaxNumSamplesPerBuffer = 8192 * 2
    };

    sp<MediaSource> mSource;
    bool mStarted;

    MediaBufferGroup *mBufferGroup;

    int32_t mNumChannels;
    int32_t mSampleRate;
    int64_t mAnchorTimeUs;
    int64_t mNumFramesOutput;
    int32_t mNumFramesLeftOnPage;

    vorbis_dsp_state *mState;
    vorbis_info *mVi;

    int decodePacket(MediaBuffer *packet, MediaBuffer *out);

    VorbisDecoder(const VorbisDecoder &);
    VorbisDecoder &operator=(const VorbisDecoder &);
};

}  // namespace android

#endif  // VORBIS_DECODER_H_

