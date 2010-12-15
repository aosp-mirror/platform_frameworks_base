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
#define LOG_TAG "NuPlayerDecoder"
#include <utils/Log.h>

#include "NuPlayerDecoder.h"

#include "DecoderWrapper.h"
#include "ESDS.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/ACodec.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>
#include <surfaceflinger/Surface.h>

namespace android {

NuPlayer::Decoder::Decoder(
        const sp<AMessage> &notify, const sp<Surface> &surface)
    : mNotify(notify),
      mSurface(surface) {
}

NuPlayer::Decoder::~Decoder() {
}

void NuPlayer::Decoder::configure(const sp<MetaData> &meta) {
    CHECK(mCodec == NULL);
    CHECK(mWrapper == NULL);

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    sp<AMessage> notifyMsg =
        new AMessage(kWhatCodecNotify, id());

    sp<AMessage> format = makeFormat(meta);

    if (mSurface != NULL) {
        format->setObject("surface", mSurface);
    }

    if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC)) {
        mWrapper = new DecoderWrapper;
        looper()->registerHandler(mWrapper);

        mWrapper->setNotificationMessage(notifyMsg);
        mWrapper->initiateSetup(format);
    } else {
        mCodec = new ACodec;
        looper()->registerHandler(mCodec);

        mCodec->setNotificationMessage(notifyMsg);
        mCodec->initiateSetup(format);
    }
}

void NuPlayer::Decoder::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatCodecNotify:
        {
            int32_t what;
            CHECK(msg->findInt32("what", &what));

            if (what == ACodec::kWhatFillThisBuffer) {
                onFillThisBuffer(msg);
            } else {
                sp<AMessage> notify = mNotify->dup();
                notify->setMessage("codec-request", msg);
                notify->post();
            }
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

sp<AMessage> NuPlayer::Decoder::makeFormat(const sp<MetaData> &meta) {
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
#if 0
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
#else
        sp<ABuffer> buffer = new ABuffer(size);
        memcpy(buffer->data(), data, size);

        msg->setObject("esds", buffer);
#endif
    }

    int32_t maxInputSize;
    if (meta->findInt32(kKeyMaxInputSize, &maxInputSize)) {
        msg->setInt32("max-input-size", maxInputSize);
    }

    mCSDIndex = 0;

    return msg;
}

void NuPlayer::Decoder::onFillThisBuffer(const sp<AMessage> &msg) {
    sp<AMessage> reply;
    CHECK(msg->findMessage("reply", &reply));

#if 0
    sp<RefBase> obj;
    CHECK(msg->findObject("buffer", &obj));
    sp<ABuffer> outBuffer = static_cast<ABuffer *>(obj.get());
#else
    sp<ABuffer> outBuffer;
#endif

    if (mCSDIndex < mCSD.size()) {
        outBuffer = mCSD.editItemAt(mCSDIndex++);
        outBuffer->meta()->setInt64("timeUs", 0);

        reply->setObject("buffer", outBuffer);
        reply->post();
        return;
    }

    sp<AMessage> notify = mNotify->dup();
    notify->setMessage("codec-request", msg);
    notify->post();
}

void NuPlayer::Decoder::signalFlush() {
    if (mCodec != NULL) {
        mCodec->signalFlush();
    } else {
        CHECK(mWrapper != NULL);
        mWrapper->signalFlush();
    }
}

void NuPlayer::Decoder::signalResume() {
    if (mCodec != NULL) {
        mCodec->signalResume();
    } else {
        CHECK(mWrapper != NULL);
        mWrapper->signalResume();
    }
}

}  // namespace android

