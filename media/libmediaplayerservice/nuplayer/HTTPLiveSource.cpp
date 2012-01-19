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
#define LOG_TAG "HTTPLiveSource"
#include <utils/Log.h>

#include "HTTPLiveSource.h"

#include "ATSParser.h"
#include "AnotherPacketSource.h"
#include "LiveDataSource.h"
#include "LiveSession.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>

namespace android {

NuPlayer::HTTPLiveSource::HTTPLiveSource(
        const char *url,
        const KeyedVector<String8, String8> *headers,
        bool uidValid, uid_t uid)
    : mURL(url),
      mUIDValid(uidValid),
      mUID(uid),
      mFlags(0),
      mFinalResult(OK),
      mOffset(0) {
    if (headers) {
        mExtraHeaders = *headers;

        ssize_t index =
            mExtraHeaders.indexOfKey(String8("x-hide-urls-from-log"));

        if (index >= 0) {
            mFlags |= kFlagIncognito;

            mExtraHeaders.removeItemsAt(index);
        }
    }
}

NuPlayer::HTTPLiveSource::~HTTPLiveSource() {
    if (mLiveSession != NULL) {
        mLiveSession->disconnect();
        mLiveLooper->stop();
    }
}

void NuPlayer::HTTPLiveSource::start() {
    mLiveLooper = new ALooper;
    mLiveLooper->setName("http live");
    mLiveLooper->start();

    mLiveSession = new LiveSession(
            (mFlags & kFlagIncognito) ? LiveSession::kFlagIncognito : 0,
            mUIDValid, mUID);

    mLiveLooper->registerHandler(mLiveSession);

    mLiveSession->connect(
            mURL.c_str(), mExtraHeaders.isEmpty() ? NULL : &mExtraHeaders);

    mTSParser = new ATSParser;
}

sp<MetaData> NuPlayer::HTTPLiveSource::getFormat(bool audio) {
    ATSParser::SourceType type =
        audio ? ATSParser::AUDIO : ATSParser::VIDEO;

    sp<AnotherPacketSource> source =
        static_cast<AnotherPacketSource *>(mTSParser->getSource(type).get());

    if (source == NULL) {
        return NULL;
    }

    return source->getFormat();
}

status_t NuPlayer::HTTPLiveSource::feedMoreTSData() {
    if (mFinalResult != OK) {
        return mFinalResult;
    }

    sp<LiveDataSource> source =
        static_cast<LiveDataSource *>(mLiveSession->getDataSource().get());

    for (int32_t i = 0; i < 50; ++i) {
        char buffer[188];
        ssize_t n = source->readAtNonBlocking(mOffset, buffer, sizeof(buffer));

        if (n == -EWOULDBLOCK) {
            break;
        } else if (n < 0) {
            if (n != ERROR_END_OF_STREAM) {
                ALOGI("input data EOS reached, error %ld", n);
            } else {
                ALOGI("input data EOS reached.");
            }
            mTSParser->signalEOS(n);
            mFinalResult = n;
            break;
        } else {
            if (buffer[0] == 0x00) {
                // XXX legacy
                sp<AMessage> extra;
                mTSParser->signalDiscontinuity(
                        buffer[1] == 0x00
                            ? ATSParser::DISCONTINUITY_SEEK
                            : ATSParser::DISCONTINUITY_FORMATCHANGE,
                        extra);
            } else {
                status_t err = mTSParser->feedTSPacket(buffer, sizeof(buffer));

                if (err != OK) {
                    LOGE("TS Parser returned error %d", err);
                    mTSParser->signalEOS(err);
                    mFinalResult = err;
                    break;
                }
            }

            mOffset += n;
        }
    }

    return OK;
}

status_t NuPlayer::HTTPLiveSource::dequeueAccessUnit(
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

status_t NuPlayer::HTTPLiveSource::getDuration(int64_t *durationUs) {
    return mLiveSession->getDuration(durationUs);
}

status_t NuPlayer::HTTPLiveSource::seekTo(int64_t seekTimeUs) {
    // We need to make sure we're not seeking until we have seen the very first
    // PTS timestamp in the whole stream (from the beginning of the stream).
    while (!mTSParser->PTSTimeDeltaEstablished() && feedMoreTSData() == OK) {
        usleep(100000);
    }

    mLiveSession->seekTo(seekTimeUs);

    return OK;
}

bool NuPlayer::HTTPLiveSource::isSeekable() {
    return mLiveSession->isSeekable();
}

}  // namespace android

