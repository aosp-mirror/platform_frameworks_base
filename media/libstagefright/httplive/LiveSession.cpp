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
#define LOG_TAG "LiveSession"
#include <utils/Log.h>

#include "include/LiveSession.h"

#include "LiveDataSource.h"

#include "include/M3UParser.h"
#include "include/NuHTTPDataSource.h"

#include <cutils/properties.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/MediaErrors.h>

#include <ctype.h>
#include <openssl/aes.h>

namespace android {

const int64_t LiveSession::kMaxPlaylistAgeUs = 15000000ll;

LiveSession::LiveSession()
    : mDataSource(new LiveDataSource),
      mHTTPDataSource(new NuHTTPDataSource),
      mPrevBandwidthIndex(-1),
      mLastPlaylistFetchTimeUs(-1),
      mSeqNumber(-1),
      mSeekTimeUs(-1),
      mNumRetries(0),
      mDurationUs(-1),
      mSeekDone(false),
      mMonitorQueueGeneration(0) {
}

LiveSession::~LiveSession() {
}

sp<DataSource> LiveSession::getDataSource() {
    return mDataSource;
}

void LiveSession::connect(const char *url) {
    sp<AMessage> msg = new AMessage(kWhatConnect, id());
    msg->setString("url", url);
    msg->post();
}

void LiveSession::disconnect() {
    (new AMessage(kWhatDisconnect, id()))->post();
}

void LiveSession::seekTo(int64_t timeUs) {
    Mutex::Autolock autoLock(mLock);
    mSeekDone = false;

    sp<AMessage> msg = new AMessage(kWhatSeek, id());
    msg->setInt64("timeUs", timeUs);
    msg->post();

    while (!mSeekDone) {
        mCondition.wait(mLock);
    }
}

void LiveSession::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatConnect:
            onConnect(msg);
            break;

        case kWhatDisconnect:
            onDisconnect();
            break;

        case kWhatMonitorQueue:
        {
            int32_t generation;
            CHECK(msg->findInt32("generation", &generation));

            if (generation != mMonitorQueueGeneration) {
                // Stale event
                break;
            }

            onMonitorQueue();
            break;
        }

        case kWhatSeek:
            onSeek(msg);
            break;

        default:
            TRESPASS();
            break;
    }
}

// static
int LiveSession::SortByBandwidth(const BandwidthItem *a, const BandwidthItem *b) {
    if (a->mBandwidth < b->mBandwidth) {
        return -1;
    } else if (a->mBandwidth == b->mBandwidth) {
        return 0;
    }

    return 1;
}

void LiveSession::onConnect(const sp<AMessage> &msg) {
    AString url;
    CHECK(msg->findString("url", &url));

    LOGI("onConnect '%s'", url.c_str());

    mMasterURL = url;

    sp<M3UParser> playlist = fetchPlaylist(url.c_str());
    CHECK(playlist != NULL);

    if (playlist->isVariantPlaylist()) {
        for (size_t i = 0; i < playlist->size(); ++i) {
            BandwidthItem item;

            sp<AMessage> meta;
            playlist->itemAt(i, &item.mURI, &meta);

            unsigned long bandwidth;
            CHECK(meta->findInt32("bandwidth", (int32_t *)&item.mBandwidth));

            mBandwidthItems.push(item);
        }

        CHECK_GT(mBandwidthItems.size(), 0u);

        mBandwidthItems.sort(SortByBandwidth);

        char value[PROPERTY_VALUE_MAX];
        if (property_get("media.httplive.disable-nuplayer", value, NULL)
                && (!strcasecmp(value, "true") || !strcmp(value, "1"))) {
            // The "legacy" player cannot deal with audio format changes,
            // some streams use different audio encoding parameters for
            // their lowest bandwidth stream.
            if (mBandwidthItems.size() > 1) {
                // XXX Remove the lowest bitrate stream for now...
                mBandwidthItems.removeAt(0);
            }
        }
    }

    postMonitorQueue();
}

void LiveSession::onDisconnect() {
    LOGI("onDisconnect");

    mDataSource->queueEOS(ERROR_END_OF_STREAM);
}

status_t LiveSession::fetchFile(const char *url, sp<ABuffer> *out) {
    *out = NULL;

    sp<DataSource> source;

    if (!strncasecmp(url, "file://", 7)) {
        source = new FileSource(url + 7);
    } else if (strncasecmp(url, "http://", 7)) {
        return ERROR_UNSUPPORTED;
    } else {
        status_t err = mHTTPDataSource->connect(url);

        if (err != OK) {
            return err;
        }

        source = mHTTPDataSource;
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
            return n;
        }

        if (n == 0) {
            break;
        }

        buffer->setRange(0, buffer->size() + (size_t)n);
    }

    *out = buffer;

    return OK;
}

sp<M3UParser> LiveSession::fetchPlaylist(const char *url) {
    sp<ABuffer> buffer;
    status_t err = fetchFile(url, &buffer);

    if (err != OK) {
        return NULL;
    }

    sp<M3UParser> playlist =
        new M3UParser(url, buffer->data(), buffer->size());

    if (playlist->initCheck() != OK) {
        return NULL;
    }

    return playlist;
}

static double uniformRand() {
    return (double)rand() / RAND_MAX;
}

size_t LiveSession::getBandwidthIndex() {
    if (mBandwidthItems.size() == 0) {
        return 0;
    }

#if 1
    int32_t bandwidthBps;
    if (mHTTPDataSource != NULL
            && mHTTPDataSource->estimateBandwidth(&bandwidthBps)) {
        LOGV("bandwidth estimated at %.2f kbps", bandwidthBps / 1024.0f);
    } else {
        LOGV("no bandwidth estimate.");
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

void LiveSession::onDownloadNext() {
    size_t bandwidthIndex = getBandwidthIndex();

rinse_repeat:
    int64_t nowUs = ALooper::GetNowUs();

    if (mLastPlaylistFetchTimeUs < 0
            || (ssize_t)bandwidthIndex != mPrevBandwidthIndex
            || (!mPlaylist->isComplete()
                && mLastPlaylistFetchTimeUs + kMaxPlaylistAgeUs <= nowUs)) {
        AString url;
        if (mBandwidthItems.size() > 0) {
            url = mBandwidthItems.editItemAt(bandwidthIndex).mURI;
        } else {
            url = mMasterURL;
        }

        bool firstTime = (mPlaylist == NULL);

        mPlaylist = fetchPlaylist(url.c_str());
        if (mPlaylist == NULL) {
            LOGE("failed to load playlist at url '%s'", url.c_str());
            mDataSource->queueEOS(ERROR_IO);
            return;
        }

        if (firstTime) {
            Mutex::Autolock autoLock(mLock);

            int32_t targetDuration;
            if (!mPlaylist->isComplete()
                    || !mPlaylist->meta()->findInt32(
                    "target-duration", &targetDuration)) {
                mDurationUs = -1;
            } else {
                mDurationUs = 1000000ll * targetDuration * mPlaylist->size();
            }
        }

        mLastPlaylistFetchTimeUs = ALooper::GetNowUs();
    }

    int32_t firstSeqNumberInPlaylist;
    if (mPlaylist->meta() == NULL || !mPlaylist->meta()->findInt32(
                "media-sequence", &firstSeqNumberInPlaylist)) {
        firstSeqNumberInPlaylist = 0;
    }

    bool explicitDiscontinuity = false;
    bool bandwidthChanged = false;

    if (mSeekTimeUs >= 0) {
        int32_t targetDuration;
        if (mPlaylist->isComplete() &&
                mPlaylist->meta()->findInt32(
                    "target-duration", &targetDuration)) {
            int64_t seekTimeSecs = (mSeekTimeUs + 500000ll) / 1000000ll;
            int64_t index = seekTimeSecs / targetDuration;

            if (index >= 0 && index < mPlaylist->size()) {
                int32_t newSeqNumber = firstSeqNumberInPlaylist + index;

                if (newSeqNumber != mSeqNumber) {
                    LOGI("seeking to seq no %d", newSeqNumber);

                    mSeqNumber = newSeqNumber;

                    mDataSource->reset();

                    // reseting the data source will have had the
                    // side effect of discarding any previously queued
                    // bandwidth change discontinuity.
                    // Therefore we'll need to treat these explicit
                    // discontinuities as involving a bandwidth change
                    // even if they aren't directly.
                    explicitDiscontinuity = true;
                    bandwidthChanged = true;
                }
            }
        }

        mSeekTimeUs = -1;

        Mutex::Autolock autoLock(mLock);
        mSeekDone = true;
        mCondition.broadcast();
    }

    if (mSeqNumber < 0) {
        if (mPlaylist->isComplete()) {
            mSeqNumber = firstSeqNumberInPlaylist;
        } else {
            mSeqNumber = firstSeqNumberInPlaylist + mPlaylist->size() / 2;
        }
    }

    int32_t lastSeqNumberInPlaylist =
        firstSeqNumberInPlaylist + (int32_t)mPlaylist->size() - 1;

    if (mSeqNumber < firstSeqNumberInPlaylist
            || mSeqNumber > lastSeqNumberInPlaylist) {
        if (mSeqNumber < firstSeqNumberInPlaylist
                && mPrevBandwidthIndex != (ssize_t)bandwidthIndex) {
            // Go back to the previous bandwidth.

            LOGI("new bandwidth does not have the sequence number "
                 "we're looking for, switching back to previous bandwidth");

            mLastPlaylistFetchTimeUs = -1;
            bandwidthIndex = mPrevBandwidthIndex;
            goto rinse_repeat;
        }

        if (!mPlaylist->isComplete()
                && mSeqNumber > lastSeqNumberInPlaylist
                && mNumRetries < kMaxNumRetries) {
            ++mNumRetries;

            mLastPlaylistFetchTimeUs = -1;
            postMonitorQueue(3000000ll);
            return;
        }

        LOGE("Cannot find sequence number %d in playlist "
             "(contains %d - %d)",
             mSeqNumber, firstSeqNumberInPlaylist,
             firstSeqNumberInPlaylist + mPlaylist->size() - 1);

        mDataSource->queueEOS(ERROR_END_OF_STREAM);
        return;
    }

    mNumRetries = 0;

    AString uri;
    sp<AMessage> itemMeta;
    CHECK(mPlaylist->itemAt(
                mSeqNumber - firstSeqNumberInPlaylist,
                &uri,
                &itemMeta));

    int32_t val;
    if (itemMeta->findInt32("discontinuity", &val) && val != 0) {
        explicitDiscontinuity = true;
    }

    sp<ABuffer> buffer;
    status_t err = fetchFile(uri.c_str(), &buffer);
    if (err != OK) {
        LOGE("failed to fetch .ts segment at url '%s'", uri.c_str());
        mDataSource->queueEOS(err);
        return;
    }

    CHECK(buffer != NULL);

    err = decryptBuffer(mSeqNumber - firstSeqNumberInPlaylist, buffer);

    if (err != OK) {
        LOGE("decryptBuffer failed w/ error %d", err);

        mDataSource->queueEOS(err);
        return;
    }

    if (buffer->size() == 0 || buffer->data()[0] != 0x47) {
        // Not a transport stream???

        LOGE("This doesn't look like a transport stream...");

        mDataSource->queueEOS(ERROR_UNSUPPORTED);
        return;
    }

    if ((size_t)mPrevBandwidthIndex != bandwidthIndex) {
        bandwidthChanged = true;
    }

    if (mPrevBandwidthIndex < 0) {
        // Don't signal a bandwidth change at the very beginning of
        // playback.
        bandwidthChanged = false;
    }

    if (explicitDiscontinuity || bandwidthChanged) {
        // Signal discontinuity.

        LOGI("queueing discontinuity (explicit=%d, bandwidthChanged=%d)",
             explicitDiscontinuity, bandwidthChanged);

        sp<ABuffer> tmp = new ABuffer(188);
        memset(tmp->data(), 0, tmp->size());
        tmp->data()[1] = bandwidthChanged;

        mDataSource->queueBuffer(tmp);
    }

    mDataSource->queueBuffer(buffer);

    mPrevBandwidthIndex = bandwidthIndex;
    ++mSeqNumber;

    postMonitorQueue();
}

void LiveSession::onMonitorQueue() {
    if (mSeekTimeUs >= 0
            || mDataSource->countQueuedBuffers() < kMaxNumQueuedFragments) {
        onDownloadNext();
    } else {
        postMonitorQueue(1000000ll);
    }
}

status_t LiveSession::decryptBuffer(
        size_t playlistIndex, const sp<ABuffer> &buffer) {
    sp<AMessage> itemMeta;
    bool found = false;
    AString method;

    for (ssize_t i = playlistIndex; i >= 0; --i) {
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

    if (method == "NONE") {
        return OK;
    } else if (!(method == "AES-128")) {
        LOGE("Unsupported cipher method '%s'", method.c_str());
        return ERROR_UNSUPPORTED;
    }

    AString keyURI;
    if (!itemMeta->findString("cipher-uri", &keyURI)) {
        LOGE("Missing key uri");
        return ERROR_MALFORMED;
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
            return ERROR_IO;
        }

        mAESKeyForURI.add(keyURI, key);
    }

    AES_KEY aes_key;
    if (AES_set_decrypt_key(key->data(), 128, &aes_key) != 0) {
        LOGE("failed to set AES decryption key.");
        return UNKNOWN_ERROR;
    }

    unsigned char aes_ivec[16];

    AString iv;
    if (itemMeta->findString("cipher-iv", &iv)) {
        if ((!iv.startsWith("0x") && !iv.startsWith("0X"))
                || iv.size() != 16 * 2 + 2) {
            LOGE("malformed cipher IV '%s'.", iv.c_str());
            return ERROR_MALFORMED;
        }

        memset(aes_ivec, 0, sizeof(aes_ivec));
        for (size_t i = 0; i < 16; ++i) {
            char c1 = tolower(iv.c_str()[2 + 2 * i]);
            char c2 = tolower(iv.c_str()[3 + 2 * i]);
            if (!isxdigit(c1) || !isxdigit(c2)) {
                LOGE("malformed cipher IV '%s'.", iv.c_str());
                return ERROR_MALFORMED;
            }
            uint8_t nibble1 = isdigit(c1) ? c1 - '0' : c1 - 'a' + 10;
            uint8_t nibble2 = isdigit(c2) ? c2 - '0' : c2 - 'a' + 10;

            aes_ivec[i] = nibble1 << 4 | nibble2;
        }
    } else {
        memset(aes_ivec, 0, sizeof(aes_ivec));
        aes_ivec[15] = mSeqNumber & 0xff;
        aes_ivec[14] = (mSeqNumber >> 8) & 0xff;
        aes_ivec[13] = (mSeqNumber >> 16) & 0xff;
        aes_ivec[12] = (mSeqNumber >> 24) & 0xff;
    }

    AES_cbc_encrypt(
            buffer->data(), buffer->data(), buffer->size(),
            &aes_key, aes_ivec, AES_DECRYPT);

    // hexdump(buffer->data(), buffer->size());

    size_t n = buffer->size();
    CHECK_GT(n, 0u);

    size_t pad = buffer->data()[n - 1];

    CHECK_GT(pad, 0u);
    CHECK_LE(pad, 16u);
    CHECK_GE((size_t)n, pad);
    for (size_t i = 0; i < pad; ++i) {
        CHECK_EQ((unsigned)buffer->data()[n - 1 - i], pad);
    }

    n -= pad;

    buffer->setRange(buffer->offset(), n);

    return OK;
}

void LiveSession::postMonitorQueue(int64_t delayUs) {
    sp<AMessage> msg = new AMessage(kWhatMonitorQueue, id());
    msg->setInt32("generation", ++mMonitorQueueGeneration);
    msg->post(delayUs);
}

void LiveSession::onSeek(const sp<AMessage> &msg) {
    int64_t timeUs;
    CHECK(msg->findInt64("timeUs", &timeUs));

    mSeekTimeUs = timeUs;
    postMonitorQueue();
}

status_t LiveSession::getDuration(int64_t *durationUs) {
    Mutex::Autolock autoLock(mLock);
    *durationUs = mDurationUs;

    return OK;
}

bool LiveSession::isSeekable() {
    int64_t durationUs;
    return getDuration(&durationUs) == OK && durationUs >= 0;
}

}  // namespace android

