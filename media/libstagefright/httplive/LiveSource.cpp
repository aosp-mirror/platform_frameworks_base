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

#define LOG_TAG "LiveSource"
#include <utils/Log.h>

#include "include/LiveSource.h"

#include "include/HTTPStream.h"
#include "include/M3UParser.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/HTTPDataSource.h>
#include <media/stagefright/MediaDebug.h>

namespace android {

LiveSource::LiveSource(const char *url)
    : mURL(url),
      mInitCheck(NO_INIT),
      mPlaylistIndex(0),
      mLastFetchTimeUs(-1),
      mSourceSize(0),
      mOffsetBias(0) {
    if (switchToNext()) {
        mInitCheck = OK;
    }
}

LiveSource::~LiveSource() {
}

status_t LiveSource::initCheck() const {
    return mInitCheck;
}

bool LiveSource::loadPlaylist() {
    mPlaylist.clear();
    mPlaylistIndex = 0;

    sp<ABuffer> buffer;
    status_t err = fetchM3U(mURL.c_str(), &buffer);

    if (err != OK) {
        return false;
    }

    mPlaylist = new M3UParser(mURL.c_str(), buffer->data(), buffer->size());

    if (mPlaylist->initCheck() != OK) {
        return false;
    }

    if (!mPlaylist->meta()->findInt32(
                "media-sequence", &mFirstItemSequenceNumber)) {
        mFirstItemSequenceNumber = 0;
    }

    return true;
}

static int64_t getNowUs() {
    struct timeval tv;
    gettimeofday(&tv, NULL);

    return (int64_t)tv.tv_usec + tv.tv_sec * 1000000ll;
}

bool LiveSource::switchToNext() {
    mOffsetBias += mSourceSize;
    mSourceSize = 0;

    if (mLastFetchTimeUs < 0 || getNowUs() >= mLastFetchTimeUs + 15000000ll
        || mPlaylistIndex == mPlaylist->size()) {
        int32_t nextSequenceNumber =
            mPlaylistIndex + mFirstItemSequenceNumber;

        if (!loadPlaylist()) {
            LOGE("failed to reload playlist");
            return false;
        }

        if (mLastFetchTimeUs < 0) {
            mPlaylistIndex = mPlaylist->size() / 2;
        } else {
            if (nextSequenceNumber < mFirstItemSequenceNumber
                    || nextSequenceNumber
                            >= mFirstItemSequenceNumber + (int32_t)mPlaylist->size()) {
                LOGE("Cannot find sequence number %d in new playlist",
                     nextSequenceNumber);

                return false;
            }

            mPlaylistIndex = nextSequenceNumber - mFirstItemSequenceNumber;
        }

        mLastFetchTimeUs = getNowUs();
    }

    AString uri;
    CHECK(mPlaylist->itemAt(mPlaylistIndex, &uri));
    LOGI("switching to %s", uri.c_str());

    mSource = new HTTPDataSource(uri.c_str());
    if (mSource->connect() != OK
            || mSource->getSize(&mSourceSize) != OK) {
        return false;
    }

    mPlaylistIndex++;
    return true;
}

ssize_t LiveSource::readAt(off_t offset, void *data, size_t size) {
    CHECK(offset >= mOffsetBias);
    offset -= mOffsetBias;

    if (offset >= mSourceSize) {
        CHECK_EQ(offset, mSourceSize);

        offset -= mSourceSize;
        if (!switchToNext()) {
            return ERROR_END_OF_STREAM;
        }
    }

    size_t numRead = 0;
    while (numRead < size) {
        ssize_t n = mSource->readAt(
                offset + numRead, (uint8_t *)data + numRead, size - numRead);

        if (n <= 0) {
            break;
        }

        numRead += n;
    }

    return numRead;
}

status_t LiveSource::fetchM3U(const char *url, sp<ABuffer> *out) {
    *out = NULL;

    mSource = new HTTPDataSource(url);
    status_t err = mSource->connect();

    if (err != OK) {
        return err;
    }

    off_t size;
    err = mSource->getSize(&size);

    if (err != OK) {
        return err;
    }

    sp<ABuffer> buffer = new ABuffer(size);
    size_t offset = 0;
    while (offset < (size_t)size) {
        ssize_t n = mSource->readAt(
                offset, buffer->data() + offset, size - offset);

        if (n <= 0) {
            return ERROR_IO;
        }

        offset += n;
    }

    *out = buffer;

    return OK;
}

}  // namespace android
