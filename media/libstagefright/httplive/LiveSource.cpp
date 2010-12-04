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

#include <cutils/properties.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/FileSource.h>

#include <ctype.h>
#include <openssl/aes.h>

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
      mPrevBandwidthIndex(-1),
      mAESKey((AES_KEY *)malloc(sizeof(AES_KEY))),
      mStreamEncrypted(false) {
    if (switchToNext()) {
        mInitCheck = OK;

        determineSeekability();
    }
}

LiveSource::~LiveSource() {
    free(mAESKey);
    mAESKey = NULL;
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

size_t LiveSource::getBandwidthIndex() {
    if (mBandwidthItems.size() == 0) {
        return 0;
    }

#if 1
    int32_t bandwidthBps;
    if (mSource != NULL && mSource->estimateBandwidth(&bandwidthBps)) {
        LOGI("bandwidth estimated at %.2f kbps", bandwidthBps / 1024.0f);
    } else {
        LOGI("no bandwidth estimate.");
        return 0;  // Pick the lowest bandwidth stream by default.
    }

    char value[PROPERTY_VALUE_MAX];
    if (property_get("media.httplive.max-bw", value, NULL)) {
        char *end;
        long maxBw = strtoul(value, &end, 10);
        if (end > value && *end == '\0') {
            if (maxBw > 0 && bandwidthBps > maxBw) {
                LOGV("bandwidth capped to %ld bps", maxBw);
                bandwidthBps = maxBw;
            }
        }
    }

    // Consider only 80% of the available bandwidth usable.
    bandwidthBps = (bandwidthBps * 8) / 10;

    // Pick the highest bandwidth stream below or equal to estimated bandwidth.

    size_t index = mBandwidthItems.size() - 1;
    while (index > 0 && mBandwidthItems.itemAt(index).mBandwidth
                            > (size_t)bandwidthBps) {
        --index;
    }
#elif 0
    // Change bandwidth at random()
    size_t index = uniformRand() * mBandwidthItems.size();
#elif 0
    // There's a 50% chance to stay on the current bandwidth and
    // a 50% chance to switch to the next higher bandwidth (wrapping around
    // to lowest)
    const size_t kMinIndex = 0;

    size_t index;
    if (mPrevBandwidthIndex < 0) {
        index = kMinIndex;
    } else if (uniformRand() < 0.5) {
        index = (size_t)mPrevBandwidthIndex;
    } else {
        index = mPrevBandwidthIndex + 1;
        if (index == mBandwidthItems.size()) {
            index = kMinIndex;
        }
    }
#elif 0
    // Pick the highest bandwidth stream below or equal to 1.2 Mbit/sec

    size_t index = mBandwidthItems.size() - 1;
    while (index > 0 && mBandwidthItems.itemAt(index).mBandwidth > 1200000) {
        --index;
    }
#else
    size_t index = mBandwidthItems.size() - 1;  // Highest bandwidth stream
#endif

    return index;
}

bool LiveSource::loadPlaylist(bool fetchMaster, size_t bandwidthIndex) {
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

#if 1  // XXX
            if (mBandwidthItems.size() > 1) {
                // Remove the lowest bandwidth stream, this is sometimes
                // an AAC program stream, which we don't support at this point.
                mBandwidthItems.removeItemsAt(0);
            }
#endif

            for (size_t i = 0; i < mBandwidthItems.size(); ++i) {
                const BandwidthItem &item = mBandwidthItems.itemAt(i);
                LOGV("item #%d: %s", i, item.mURI.c_str());
            }

            bandwidthIndex = getBandwidthIndex();
        }
    }

    if (mBandwidthItems.size() > 0) {
        mURL = mBandwidthItems.editItemAt(bandwidthIndex).mURI;

        if (mPrevBandwidthIndex >= 0
                && (size_t)mPrevBandwidthIndex != bandwidthIndex) {
            // If we switched streams because of bandwidth changes,
            // we'll signal this discontinuity by inserting a
            // special transport stream packet into the stream.
            mSignalDiscontinuity = true;
        }

        mPrevBandwidthIndex = bandwidthIndex;
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

    size_t bandwidthIndex = getBandwidthIndex();

    if (mLastFetchTimeUs < 0 || getNowUs() >= mLastFetchTimeUs + 15000000ll
        || mPlaylistIndex == mPlaylist->size()
        || (ssize_t)bandwidthIndex != mPrevBandwidthIndex) {
        int32_t nextSequenceNumber =
            mPlaylistIndex + mFirstItemSequenceNumber;

        if (!loadPlaylist(mLastFetchTimeUs < 0, bandwidthIndex)) {
            LOGE("failed to reload playlist");
            return false;
        }

        if (mLastFetchTimeUs < 0) {
            if (isSeekable()) {
                mPlaylistIndex = 0;
            } else {
                // This is live streamed content, the first seqnum in the
                // various bandwidth' streams may be slightly off, so don't
                // start at the very first entry.
                // With a segment duration of 6-10secs, this really only
                // delays playback up to 30secs compared to real time.
                mPlaylistIndex = 3;
                if (mPlaylistIndex >= mPlaylist->size()) {
                    mPlaylistIndex = mPlaylist->size() - 1;
                }
            }
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

    if (!setupCipher()) {
        return false;
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

bool LiveSource::setupCipher() {
    sp<AMessage> itemMeta;
    bool found = false;
    AString method;

    for (ssize_t i = mPlaylistIndex; i >= 0; --i) {
        AString uri;
        CHECK(mPlaylist->itemAt(i, &uri, &itemMeta));

        if (itemMeta->findString("cipher-method", &method)) {
            found = true;
            break;
        }
    }

    if (!found) {
        method = "NONE";
    }

    mStreamEncrypted = false;

    if (method == "AES-128") {
        AString keyURI;
        if (!itemMeta->findString("cipher-uri", &keyURI)) {
            LOGE("Missing key uri");
            return false;
        }

        ssize_t index = mAESKeyForURI.indexOfKey(keyURI);

        sp<ABuffer> key;
        if (index >= 0) {
            key = mAESKeyForURI.valueAt(index);
        } else {
            key = new ABuffer(16);

            sp<NuHTTPDataSource> keySource = new NuHTTPDataSource;
            status_t err = keySource->connect(keyURI.c_str());

            if (err == OK) {
                size_t offset = 0;
                while (offset < 16) {
                    ssize_t n = keySource->readAt(
                            offset, key->data() + offset, 16 - offset);
                    if (n <= 0) {
                        err = ERROR_IO;
                        break;
                    }

                    offset += n;
                }
            }

            if (err != OK) {
                LOGE("failed to fetch cipher key from '%s'.", keyURI.c_str());
                return false;
            }

            mAESKeyForURI.add(keyURI, key);
        }

        if (AES_set_decrypt_key(key->data(), 128, (AES_KEY *)mAESKey) != 0) {
            LOGE("failed to set AES decryption key.");
            return false;
        }

        AString iv;
        if (itemMeta->findString("cipher-iv", &iv)) {
            if ((!iv.startsWith("0x") && !iv.startsWith("0X"))
                    || iv.size() != 16 * 2 + 2) {
                LOGE("malformed cipher IV '%s'.", iv.c_str());
                return false;
            }

            memset(mAESIVec, 0, sizeof(mAESIVec));
            for (size_t i = 0; i < 16; ++i) {
                char c1 = tolower(iv.c_str()[2 + 2 * i]);
                char c2 = tolower(iv.c_str()[3 + 2 * i]);
                if (!isxdigit(c1) || !isxdigit(c2)) {
                    LOGE("malformed cipher IV '%s'.", iv.c_str());
                    return false;
                }
                uint8_t nibble1 = isdigit(c1) ? c1 - '0' : c1 - 'a' + 10;
                uint8_t nibble2 = isdigit(c2) ? c2 - '0' : c2 - 'a' + 10;

                mAESIVec[i] = nibble1 << 4 | nibble2;
            }
        } else {
            size_t seqNum = mPlaylistIndex + mFirstItemSequenceNumber;

            memset(mAESIVec, 0, sizeof(mAESIVec));
            mAESIVec[15] = seqNum & 0xff;
            mAESIVec[14] = (seqNum >> 8) & 0xff;
            mAESIVec[13] = (seqNum >> 16) & 0xff;
            mAESIVec[12] = (seqNum >> 24) & 0xff;
        }

        mStreamEncrypted = true;
    } else if (!(method == "NONE")) {
        LOGE("Unsupported cipher method '%s'", method.c_str());
        return false;
    }

    return true;
}

static const ssize_t kHeaderSize = 188;

ssize_t LiveSource::readAt(off64_t offset, void *data, size_t size) {
    CHECK(offset >= mOffsetBias);
    offset -= mOffsetBias;

    off64_t delta = mSignalDiscontinuity ? kHeaderSize : 0;

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

    bool done = false;
    size_t numRead = 0;
    while (numRead < size) {
        ssize_t n = mSource->readAt(
                offset + numRead - delta,
                (uint8_t *)data + numRead, size - numRead);

        if (n <= 0) {
            break;
        }

        if (mStreamEncrypted) {
            size_t nmod = n % 16;
            CHECK(nmod == 0);

            sp<ABuffer> tmp = new ABuffer(n);

            AES_cbc_encrypt((const unsigned char *)data + numRead,
                            tmp->data(),
                            n,
                            (const AES_KEY *)mAESKey,
                            mAESIVec,
                            AES_DECRYPT);

            if (mSourceSize == (off64_t)(offset + numRead - delta + n)) {
                // check for padding at the end of the file.

                size_t pad = tmp->data()[n - 1];
                CHECK_GT(pad, 0u);
                CHECK_LE(pad, 16u);
                CHECK_GE((size_t)n, pad);
                for (size_t i = 0; i < pad; ++i) {
                    CHECK_EQ((unsigned)tmp->data()[n - 1 - i], pad);
                }

                n -= pad;
                mSourceSize -= pad;

                done = true;
            }

            memcpy((uint8_t *)data + numRead, tmp->data(), n);
        }

        numRead += n;

        if (done) {
            break;
        }
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

    off64_t size;
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

    if (index == mPlaylistIndex) {
        return false;
    }

    mPlaylistIndex = index;

    LOGV("seeking to index %lld", index);

    switchToNext();
    mOffsetBias = 0;

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
