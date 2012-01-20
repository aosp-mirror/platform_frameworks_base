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
#define LOG_TAG "rtp_test"
#include <utils/Log.h>

#include <binder/ProcessState.h>

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#include <media/stagefright/foundation/base64.h>

#include "ARTPSession.h"
#include "ASessionDescription.h"
#include "UDPPusher.h"

using namespace android;

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    DataSource::RegisterDefaultSniffers();

    const char *rtpFilename = NULL;
    const char *rtcpFilename = NULL;

    if (argc == 3) {
        rtpFilename = argv[1];
        rtcpFilename = argv[2];
    } else if (argc != 1) {
        fprintf(stderr, "usage: %s [ rtpFilename rtcpFilename ]\n", argv[0]);
        return 1;
    }

#if 0
    static const uint8_t kSPS[] = {
        0x67, 0x42, 0x80, 0x0a, 0xe9, 0x02, 0x83, 0xe4, 0x20, 0x00, 0x00, 0x7d, 0x00, 0x00, 0x0e, 0xa6, 0x00, 0x80
    };
    static const uint8_t kPPS[] = {
        0x68, 0xce, 0x3c, 0x80
    };
    AString out1, out2;
    encodeBase64(kSPS, sizeof(kSPS), &out1);
    encodeBase64(kPPS, sizeof(kPPS), &out2);
    printf("params=%s,%s\n", out1.c_str(), out2.c_str());
#endif

    sp<ALooper> looper = new ALooper;

    sp<UDPPusher> rtp_pusher;
    sp<UDPPusher> rtcp_pusher;

    if (rtpFilename != NULL) {
        rtp_pusher = new UDPPusher(rtpFilename, 5434);
        looper->registerHandler(rtp_pusher);

        rtcp_pusher = new UDPPusher(rtcpFilename, 5435);
        looper->registerHandler(rtcp_pusher);
    }

    sp<ARTPSession> session = new ARTPSession;
    looper->registerHandler(session);

#if 0
    // My H264 SDP
    static const char *raw =
        "v=0\r\n"
        "o=- 64 233572944 IN IP4 127.0.0.0\r\n"
        "s=QuickTime\r\n"
        "t=0 0\r\n"
        "a=range:npt=0-315\r\n"
        "a=isma-compliance:2,2.0,2\r\n"
        "m=video 5434 RTP/AVP 97\r\n"
        "c=IN IP4 127.0.0.1\r\n"
        "b=AS:30\r\n"
        "a=rtpmap:97 H264/90000\r\n"
        "a=fmtp:97 packetization-mode=1;profile-level-id=42000C;"
          "sprop-parameter-sets=Z0IADJZUCg+I,aM44gA==\r\n"
        "a=mpeg4-esid:201\r\n"
        "a=cliprect:0,0,240,320\r\n"
        "a=framesize:97 320-240\r\n";
#elif 0
    // My H263 SDP
    static const char *raw =
        "v=0\r\n"
        "o=- 64 233572944 IN IP4 127.0.0.0\r\n"
        "s=QuickTime\r\n"
        "t=0 0\r\n"
        "a=range:npt=0-315\r\n"
        "a=isma-compliance:2,2.0,2\r\n"
        "m=video 5434 RTP/AVP 97\r\n"
        "c=IN IP4 127.0.0.1\r\n"
        "b=AS:30\r\n"
        "a=rtpmap:97 H263-1998/90000\r\n"
        "a=cliprect:0,0,240,320\r\n"
        "a=framesize:97 320-240\r\n";
#elif 0
    // My AMR SDP
    static const char *raw =
        "v=0\r\n"
        "o=- 64 233572944 IN IP4 127.0.0.0\r\n"
        "s=QuickTime\r\n"
        "t=0 0\r\n"
        "a=range:npt=0-315\r\n"
        "a=isma-compliance:2,2.0,2\r\n"
        "m=audio 5434 RTP/AVP 97\r\n"
        "c=IN IP4 127.0.0.1\r\n"
        "b=AS:30\r\n"
        "a=rtpmap:97 AMR/8000/1\r\n"
        "a=fmtp:97 octet-align\r\n";
#elif 1
    // GTalk's H264 SDP
    static const char *raw =
        "v=0\r\n"
        "o=- 64 233572944 IN IP4 127.0.0.0\r\n"
        "s=QuickTime\r\n"
        "t=0 0\r\n"
        "a=range:npt=now-\r\n"
        "m=video 5434 RTP/AVP 96\r\n"
        "c=IN IP4 127.0.0.1\r\n"
        "b=AS:320000\r\n"
        "a=rtpmap:96 H264/90000\r\n"
        "a=fmtp:96 packetization-mode=1;profile-level-id=42001E;"
          "sprop-parameter-sets=Z0IAHpZUBaHogA==,aM44gA==\r\n"
        "a=cliprect:0,0,480,270\r\n"
        "a=framesize:96 720-480\r\n";
#else
    // sholes H264 SDP
    static const char *raw =
        "v=0\r\n"
        "o=- 64 233572944 IN IP4 127.0.0.0\r\n"
        "s=QuickTime\r\n"
        "t=0 0\r\n"
        "a=range:npt=now-\r\n"
        "m=video 5434 RTP/AVP 96\r\n"
        "c=IN IP4 127.0.0.1\r\n"
        "b=AS:320000\r\n"
        "a=rtpmap:96 H264/90000\r\n"
        "a=fmtp:96 packetization-mode=1;profile-level-id=42001E;"
          "sprop-parameter-sets=Z0KACukCg+QgAAB9AAAOpgCA,aM48gA==\r\n"
        "a=cliprect:0,0,240,320\r\n"
        "a=framesize:96 320-240\r\n";
#endif

    sp<ASessionDescription> desc = new ASessionDescription;
    CHECK(desc->setTo(raw, strlen(raw)));

    CHECK_EQ(session->setup(desc), (status_t)OK);

    if (rtp_pusher != NULL) {
        rtp_pusher->start();
    }

    if (rtcp_pusher != NULL) {
        rtcp_pusher->start();
    }

    looper->start(false /* runOnCallingThread */);

    CHECK_EQ(session->countTracks(), 1u);
    sp<MediaSource> source = session->trackAt(0);

    OMXClient client;
    CHECK_EQ(client.connect(), (status_t)OK);

    sp<MediaSource> decoder = OMXCodec::Create(
            client.interface(),
            source->getFormat(), false /* createEncoder */,
            source,
            NULL,
            0);  // OMXCodec::kPreferSoftwareCodecs);
    CHECK(decoder != NULL);

    CHECK_EQ(decoder->start(), (status_t)OK);

    for (;;) {
        MediaBuffer *buffer;
        status_t err = decoder->read(&buffer);

        if (err != OK) {
            if (err == INFO_FORMAT_CHANGED) {
                int32_t width, height;
                CHECK(decoder->getFormat()->findInt32(kKeyWidth, &width));
                CHECK(decoder->getFormat()->findInt32(kKeyHeight, &height));
                printf("INFO_FORMAT_CHANGED %d x %d\n", width, height);
                continue;
            }

            ALOGE("decoder returned error 0x%08x", err);
            break;
        }

#if 1
        if (buffer->range_length() != 0) {
            int64_t timeUs;
            CHECK(buffer->meta_data()->findInt64(kKeyTime, &timeUs));

            printf("decoder returned frame of size %d at time %.2f secs\n",
                   buffer->range_length(), timeUs / 1E6);
        }
#endif

        buffer->release();
        buffer = NULL;
    }

    CHECK_EQ(decoder->stop(), (status_t)OK);

    looper->stop();

    return 0;
}
