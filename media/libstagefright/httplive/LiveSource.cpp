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
#define LOG_TAG "LiveSource"
#include <utils/Log.h>

#include "include/LiveSource.h"
#include "include/M3UParser.h"
#include "include/NuHTTPDataSource.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/MediaDebug.h>

namespace android {

LiveSource::LiveSource(const char *url)
    : mMasterURL(url),
      mInitCheck(NO_INIT),
      mDurationUs(-1),
      mPlaylistIndex(0),
      mLastFetchTimeUs(-1),
      mSource(new NuHTTPDataSource),
      mSourceSize(0),
      mOffsetBias(0),
      mSignalDiscontinuity(false),
      mPrevBandwidthIndex(-1) {
    if (switchToNext()) {
        mInitCheck = OK;

        determineSeekability();
    }
}

LiveSource::~LiveSource() {
}

status_t LiveSource::initCheck() const {
    return mInitCheck;
}

// static
int LiveSource::SortByBandwidth(const BandwidthItem *a, const BandwidthItem *b) {
    if (a->mBandwidth < b->mBandwidth) {
        return -1;
    } else if (a->mBandwidth == b->mBandwidth) {
        return 0;
    }

    return 1;
}

static double uniformRand() {
    return (double)rand() / RAND_MAX;
}

bool LiveSource::loadPlaylist(bool fetchMaster) {
    mSignalDiscontinuity = false;

    mPlaylist.clear();
    mPlaylistIndex = 0;

    if (fetchMaster) {
        mPrevBandwidthIndex = -1;

        sp<ABuffer> buffer;
        status_t err = fetchM3U(mMasterURL.c_str(), &buffer);

        if (err != OK) {
            return false;
        }

        mPlaylist = new M3UParser(
                mMasterURL.c_str(), buffer->data(), buffer->size());

        if (mPlaylist->initCheck() != OK) {
            return false;
        }

        if (mPlaylist->isVariantPlaylist()) {
            for (size_t i = 0; i < mPlaylist->size(); ++i) {
                BandwidthItem item;

                sp<AMessage> meta;
                mPlaylist->itemAt(i, &item.mURI, &meta);

                unsigned long bandwidth;
                CHECK(meta->findInt32("bandwidth", (int32_t *)&item.mBandwidth));

                mBandwidthItems.push(item);
            }
            mPlaylist.clear();

            // fall through
            if (mBandwidthItems.size() == 0) {
                return false;
            }

            mBandwidthItems.sort(SortByBandwidth);

            for (size_t i = 0; i < mBandwidthItems.size(); ++i) {
                const BandwidthItem &item = mBandwidthItems.itemAt(i);
                LOGV("item #%d: %s", i, item.mURI.c_str());
            }
        }
    }

    if (mBandwidthItems.size() > 0) {
#if 0
        // Change bandwidth at random()
        size_t index = uniformRand() * mBandwidthItems.size();
#elif 0
        // There's a 50% chance to stay on the current bandwidth and
        // a 50% chance to switch to the next higher bandwidth (wrapping around
        // to lowest)
        size_t index;
        if (uniformRand() < 0.5) {
            index = mPrevBandwidthIndex < 0 ? 0 : (size_t)mPrevBandwidthIndex;
        } else {
            if (mPrevBandwidthIndex < 0) {
                index = 0;
            } else {
                index = mPrevBandwidthIndex + 1;
                if (index == mBandwidthItems.size()) {
                    index = 0;
                }
            }
        }
#else
        // Stay on the lowest bandwidth available.
        size_t index = mBandwidthItems.size() - 1;  // Highest bandwidth stream
#endif

        mURL = mBandwidthItems.editItemAt(index).mURI;

        if (mPrevBandwidthIndex >= 0 && (size_t)mPrevBandwidthIndex != index) {
            // If we switched streams because of bandwidth changes,
            // we'll signal this discontinuity by inserting a
            // special transport stream packet into the stream.
            mSignalDiscontinuity = true;
        }

        mPrevBandwidthIndex = index;
    } else {
        mURL = mMasterURL;
    }

    if (mPlaylist == NULL) {
        sp<ABuffer> buffer;
        status_t err = fetchM3U(mURL.c_str(), &buffer);

        if (err != OK) {
            return false;
        }

        mPlaylist = new M3UParser(mURL.c_str(), buffer->data(), buffer->size());

        if (mPlaylist->initCheck() != OK) {
            return false;
        }

        if (mPlaylist->isVariantPlaylist()) {
            return false;
        }
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
    mSignalDiscontinuity = false;

    mOffsetBias += mSourceSize;
    mSourceSize = 0;

    if (mLastFetchTimeUs < 0 || getNowUs() >= mLastFetchTimeUs + 15000000ll
        || mPlaylistIndex == mPlaylist->size()) {
        int32_t nextSequenceNumber =
            mPlaylistIndex + mFirstItemSequenceNumber;

        if (!loadPlaylist(mLastFetchTimeUs < 0)) {
            LOGE("failed to reload playlist");
            return false;
        }

        if (mLastFetchTimeUs < 0) {
            mPlaylistIndex = 0;
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
    sp<AMessage> itemMeta;
    CHECK(mPlaylist->itemAt(mPlaylistIndex, &uri, &itemMeta));
    LOGV("switching to %s", uri.c_str());

    if (mSource->connect(uri.c_str()) != OK
            || mSource->getSize(&mSourceSize) != OK) {
        return false;
    }

    int32_t val;
    if (itemMeta->findInt32("discontinuity", &val) && val != 0) {
        mSignalDiscontinuity = true;
    }

    mPlaylistIndex++;
    return true;
}

static const ssize_t kHeaderSize = 188;

ssize_t LiveSource::readAt(off_t offset, void *data, size_t size) {
    CHECK(offset >= mOffsetBias);
    offset -= mOffsetBias;

    off_t delta = mSignalDiscontinuity ? kHeaderSize : 0;

    if (offset >= mSourceSize + delta) {
        CHECK_EQ(offset, mSourceSize + delta);

        offset -= mSourceSize + delta;
        if (!switchToNext()) {
            return ERROR_END_OF_STREAM;
        }

        if (mSignalDiscontinuity) {
            LOGV("switchToNext changed streams");
        } else {
            LOGV("switchToNext stayed within the same stream");
        }

        mOffsetBias += delta;

        delta = mSignalDiscontinuity ? kHeaderSize : 0;
    }

    if (offset < delta) {
        size_t avail = delta - offset;
        memset(data, 0, avail);
        return avail;
    }

    size_t numRead = 0;
    while (numRead < size) {
        ssize_t n = mSource->readAt(
                offset + numRead - delta,
                (uint8_t *)data + numRead, size - numRead);

        if (n <= 0) {
            break;
        }

        numRead += n;
    }

    return numRead;
}

status_t LiveSource::fetchM3U(const char *url, sp<ABuffer> *out) {
    *out = NULL;

    sp<DataSource> source;

    if (!strncasecmp(url, "file://", 7)) {
        source = new FileSource(url + 7);
    } else {
        CHECK(!strncasecmp(url, "http://", 7));

        status_t err = mSource->connect(url);

        if (err != OK) {
            return err;
        }

        source = mSource;
    }

    off_t size;
    status_t err = source->getSize(&size);

    if (err != OK) {
        size = 65536;
    }

    sp<ABuffer> buffer = new ABuffer(size);
    buffer->setRange(0, 0);

    for (;;) {
        size_t bufferRemaining = buffer->capacity() - buffer->size();

        if (bufferRemaining == 0) {
            bufferRemaining = 32768;

            LOGV("increasing download buffer to %d bytes",
                 buffer->size() + bufferRemaining);

            sp<ABuffer> copy = new ABuffer(buffer->size() + bufferRemaining);
            memcpy(copy->data(), buffer->data(), buffer->size());
            copy->setRange(0, buffer->size());

            buffer = copy;
        }

        ssize_t n = source->readAt(
                buffer->size(), buffer->data() + buffer->size(),
                bufferRemaining);

        if (n < 0) {
            return err;
        }

        if (n == 0) {
            break;
        }

        buffer->setRange(0, buffer->size() + (size_t)n);
    }

    *out = buffer;

    return OK;
}

bool LiveSource::seekTo(int64_t seekTimeUs) {
    LOGV("seek to %lld us", seekTimeUs);

    if (!mPlaylist->isComplete()) {
        return false;
    }

    int32_t targetDuration;
    if (!mPlaylist->meta()->findInt32("target-duration", &targetDuration)) {
        return false;
    }

    int64_t seekTimeSecs = (seekTimeUs + 500000ll) / 1000000ll;

    int64_t index = seekTimeSecs / targetDuration;

    if (index < 0 || index >= mPlaylist->size()) {
        return false;
    }

    size_t newPlaylistIndex = mFirstItemSequenceNumber + index;

    if (newPlaylistIndex == mPlaylistIndex) {
        return false;
    }

    mPlaylistIndex = newPlaylistIndex;

    switchToNext();
    mOffsetBias = 0;

    LOGV("seeking to index %lld", index);

    return true;
}

bool LiveSource::getDuration(int64_t *durationUs) const {
    if (mDurationUs >= 0) {
        *durationUs = mDurationUs;
        return true;
    }

    *durationUs = 0;
    return false;
}

bool LiveSource::isSeekable() const {
    return mDurationUs >= 0;
}

void LiveSource::determineSeekability() {
    mDurationUs = -1;

    if (!mPlaylist->isComplete()) {
        return;
    }

    int32_t targetDuration;
    if (!mPlaylist->meta()->findInt32("target-duration", &targetDuration)) {
        return;
    }

    mDurationUs = targetDuration * 1000000ll * mPlaylist->size();
}

}  // namespace android
