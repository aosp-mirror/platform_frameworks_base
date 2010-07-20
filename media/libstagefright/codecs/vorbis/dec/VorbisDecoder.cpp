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

#include "VorbisDecoder.h"

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>

extern "C" {
    #include <Tremolo/codec_internal.h>

    int _vorbis_unpack_books(vorbis_info *vi,oggpack_buffer *opb);
    int _vorbis_unpack_info(vorbis_info *vi,oggpack_buffer *opb);
    int _vorbis_unpack_comment(vorbis_comment *vc,oggpack_buffer *opb);
}

namespace android {

VorbisDecoder::VorbisDecoder(const sp<MediaSource> &source)
    : mSource(source),
      mStarted(false),
      mBufferGroup(NULL),
      mAnchorTimeUs(0),
      mNumFramesOutput(0),
      mState(NULL),
      mVi(NULL) {
    sp<MetaData> srcFormat = mSource->getFormat();
    CHECK(srcFormat->findInt32(kKeyChannelCount, &mNumChannels));
    CHECK(srcFormat->findInt32(kKeySampleRate, &mSampleRate));
}

VorbisDecoder::~VorbisDecoder() {
    if (mStarted) {
        stop();
    }
}

static void makeBitReader(
        const void *data, size_t size,
        ogg_buffer *buf, ogg_reference *ref, oggpack_buffer *bits) {
    buf->data = (uint8_t *)data;
    buf->size = size;
    buf->refcount = 1;
    buf->ptr.owner = NULL;

    ref->buffer = buf;
    ref->begin = 0;
    ref->length = size;
    ref->next = NULL;

    oggpack_readinit(bits, ref);
}

status_t VorbisDecoder::start(MetaData *params) {
    CHECK(!mStarted);

    mBufferGroup = new MediaBufferGroup;
    mBufferGroup->add_buffer(
            new MediaBuffer(kMaxNumSamplesPerBuffer * sizeof(int16_t)));

    mSource->start();

    sp<MetaData> meta = mSource->getFormat();

    mVi = new vorbis_info;
    vorbis_info_init(mVi);

    ///////////////////////////////////////////////////////////////////////////

    uint32_t type;
    const void *data;
    size_t size;
    CHECK(meta->findData(kKeyVorbisInfo, &type, &data, &size));

    ogg_buffer buf;
    ogg_reference ref;
    oggpack_buffer bits;
    makeBitReader((const uint8_t *)data + 7, size - 7, &buf, &ref, &bits);
    CHECK_EQ(0, _vorbis_unpack_info(mVi, &bits));

    ///////////////////////////////////////////////////////////////////////////

    CHECK(meta->findData(kKeyVorbisBooks, &type, &data, &size));

    makeBitReader((const uint8_t *)data + 7, size - 7, &buf, &ref, &bits);
    CHECK_EQ(0, _vorbis_unpack_books(mVi, &bits));

    ///////////////////////////////////////////////////////////////////////////

    mState = new vorbis_dsp_state;
    CHECK_EQ(0, vorbis_dsp_init(mState, mVi));

    mAnchorTimeUs = 0;
    mNumFramesOutput = 0;
    mStarted = true;

    return OK;
}

status_t VorbisDecoder::stop() {
    CHECK(mStarted);

    vorbis_dsp_clear(mState);
    delete mState;
    mState = NULL;

    vorbis_info_clear(mVi);
    delete mVi;
    mVi = NULL;

    delete mBufferGroup;
    mBufferGroup = NULL;

    mSource->stop();

    mStarted = false;

    return OK;
}

sp<MetaData> VorbisDecoder::getFormat() {
    sp<MetaData> srcFormat = mSource->getFormat();

    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_RAW);
    meta->setInt32(kKeyChannelCount, mNumChannels);
    meta->setInt32(kKeySampleRate, mSampleRate);

    int64_t durationUs;
    if (srcFormat->findInt64(kKeyDuration, &durationUs)) {
        meta->setInt64(kKeyDuration, durationUs);
    }

    meta->setCString(kKeyDecoderComponent, "VorbisDecoder");

    return meta;
}

int VorbisDecoder::decodePacket(MediaBuffer *packet, MediaBuffer *out) {
    ogg_buffer buf;
    buf.data = (uint8_t *)packet->data() + packet->range_offset();
    buf.size = packet->range_length();
    buf.refcount = 1;
    buf.ptr.owner = NULL;

    ogg_reference ref;
    ref.buffer = &buf;
    ref.begin = 0;
    ref.length = packet->range_length();
    ref.next = NULL;

    ogg_packet pack;
    pack.packet = &ref;
    pack.bytes = packet->range_length();
    pack.b_o_s = 0;
    pack.e_o_s = 0;
    pack.granulepos = 0;
    pack.packetno = 0;

    int numFrames = 0;

    int err = vorbis_dsp_synthesis(mState, &pack, 1);
    if (err != 0) {
        LOGW("vorbis_dsp_synthesis returned %d", err);
    } else {
        numFrames = vorbis_dsp_pcmout(
                mState, (int16_t *)out->data(), kMaxNumSamplesPerBuffer);

        if (numFrames < 0) {
            LOGE("vorbis_dsp_pcmout returned %d", numFrames);
            numFrames = 0;
        }
    }

    out->set_range(0, numFrames * sizeof(int16_t) * mNumChannels);

    return numFrames;
}

status_t VorbisDecoder::read(
        MediaBuffer **out, const ReadOptions *options) {
    status_t err;

    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        CHECK(seekTimeUs >= 0);

        mNumFramesOutput = 0;
        vorbis_dsp_restart(mState);
    } else {
        seekTimeUs = -1;
    }

    MediaBuffer *inputBuffer;
    err = mSource->read(&inputBuffer, options);

    if (err != OK) {
        return ERROR_END_OF_STREAM;
    }

    int64_t timeUs;
    if (inputBuffer->meta_data()->findInt64(kKeyTime, &timeUs)) {
        mAnchorTimeUs = timeUs;
        mNumFramesOutput = 0;
    } else {
        // We must have a new timestamp after seeking.
        CHECK(seekTimeUs < 0);
    }

    MediaBuffer *outputBuffer;
    CHECK_EQ(mBufferGroup->acquire_buffer(&outputBuffer), OK);

    int numFrames = decodePacket(inputBuffer, outputBuffer);

    inputBuffer->release();
    inputBuffer = NULL;

    outputBuffer->meta_data()->setInt64(
            kKeyTime,
            mAnchorTimeUs
                + (mNumFramesOutput * 1000000ll) / mSampleRate);

    mNumFramesOutput += numFrames;

    *out = outputBuffer;

    return OK;
}

}  // namespace android
