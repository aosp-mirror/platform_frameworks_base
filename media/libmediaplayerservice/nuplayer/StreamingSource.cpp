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
#define LOG_TAG "StreamingSource"
#include <utils/Log.h>

#include "StreamingSource.h"

#include "ATSParser.h"
#include "AnotherPacketSource.h"
#include "NuPlayerStreamListener.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>

namespace android {

NuPlayer::StreamingSource::StreamingSource(const sp<IStreamSource> &source)
    : mSource(source),
      mFinalResult(OK) {
}

NuPlayer::StreamingSource::~StreamingSource() {
}

void NuPlayer::StreamingSource::start() {
    mStreamListener = new NuPlayerStreamListener(mSource, 0);
    mTSParser = new ATSParser(ATSParser::TS_TIMESTAMPS_ARE_ABSOLUTE);

    mStreamListener->start();
}

status_t NuPlayer::StreamingSource::feedMoreTSData() {
    if (mFinalResult != OK) {
        return mFinalResult;
    }

    for (int32_t i = 0; i < 50; ++i) {
        char buffer[188];
        sp<AMessage> extra;
        ssize_t n = mStreamListener->read(buffer, sizeof(buffer), &extra);

        if (n == 0) {
            ALOGI("input data EOS reached.");
            mTSParser->signalEOS(ERROR_END_OF_STREAM);
            mFinalResult = ERROR_END_OF_STREAM;
            break;
        } else if (n == INFO_DISCONTINUITY) {
            int32_t type = ATSParser::DISCONTINUITY_SEEK;

            int32_t mask;
            if (extra != NULL
                    && extra->findInt32(
                        IStreamListener::kKeyDiscontinuityMask, &mask)) {
                if (mask == 0) {
                    ALOGE("Client specified an illegal discontinuity type.");
                    return ERROR_UNSUPPORTED;
                }

                type = mask;
            }

            mTSParser->signalDiscontinuity(
                    (ATSParser::DiscontinuityType)type, extra);
        } else if (n < 0) {
            CHECK_EQ(n, -EWOULDBLOCK);
            break;
        } else {
            if (buffer[0] == 0x00) {
                // XXX legacy
                mTSParser->signalDiscontinuity(
                        buffer[1] == 0x00
                            ? ATSParser::DISCONTINUITY_SEEK
                            : ATSParser::DISCONTINUITY_FORMATCHANGE,
                        extra);
            } else {
                status_t err = mTSParser->feedTSPacket(buffer, sizeof(buffer));

                if (err != OK) {
                    ALOGE("TS Parser returned error %d", err);

                    mTSParser->signalEOS(err);
                    mFinalResult = err;
                    break;
                }
            }
        }
    }

    return OK;
}

sp<MetaData> NuPlayer::StreamingSource::getFormat(bool audio) {
    ATSParser::SourceType type =
        audio ? ATSParser::AUDIO : ATSParser::VIDEO;

    sp<AnotherPacketSource> source =
        static_cast<AnotherPacketSource *>(mTSParser->getSource(type).get());

    if (source == NULL) {
        return NULL;
    }

    return source->getFormat();
}

status_t NuPlayer::StreamingSource::dequeueAccessUnit(
        bool audio, sp<ABuffer> *accessUnit) {
    ATSParser::SourceType type =
        audio ? ATSParser::AUDIO : ATSParser::VIDEO;

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

}  // namespace android

