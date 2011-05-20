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
#define LOG_TAG "NuHTTPDataSource"
#include <utils/Log.h>

#include "include/NuHTTPDataSource.h"

#include <cutils/properties.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaErrors.h>

namespace android {

static bool ParseSingleUnsignedLong(
        const char *from, unsigned long *x) {
    char *end;
    *x = strtoul(from, &end, 10);

    if (end == from || *end != '\0') {
        return false;
    }

    return true;
}

static bool ParseURL(
        const char *url, String8 *host, unsigned *port,
        String8 *path, bool *https) {
    host->setTo("");
    *port = 0;
    path->setTo("");

    size_t hostStart;
    if (!strncasecmp("http://", url, 7)) {
        hostStart = 7;
        *https = false;
    } else if (!strncasecmp("https://", url, 8)) {
        hostStart = 8;
        *https = true;
    } else {
        return false;
    }

    const char *slashPos = strchr(&url[hostStart], '/');

    if (slashPos == NULL) {
        host->setTo(&url[hostStart]);
        path->setTo("/");
    } else {
        host->setTo(&url[hostStart], slashPos - &url[hostStart]);
        path->setTo(slashPos);
    }

    const char *colonPos = strchr(host->string(), ':');

    if (colonPos != NULL) {
        unsigned long x;
        if (!ParseSingleUnsignedLong(colonPos + 1, &x) || x >= 65536) {
            return false;
        }

        *port = x;

        size_t colonOffset = colonPos - host->string();
        String8 tmp(host->string(), colonOffset);
        *host = tmp;
    } else {
        *port = (*https) ? 443 : 80;
    }

    return true;
}

NuHTTPDataSource::NuHTTPDataSource(uint32_t flags)
    : mFlags(flags),
      mState(DISCONNECTED),
      mPort(0),
      mHTTPS(false),
      mOffset(0),
      mContentLength(0),
      mContentLengthValid(false),
      mHasChunkedTransferEncoding(false),
      mChunkDataBytesLeft(0),
      mNumBandwidthHistoryItems(0),
      mTotalTransferTimeUs(0),
      mTotalTransferBytes(0),
      mPrevBandwidthMeasureTimeUs(0),
      mDecryptHandle(NULL),
      mDrmManagerClient(NULL) {
}

NuHTTPDataSource::~NuHTTPDataSource() {
    if (mDecryptHandle != NULL) {
        // To release mDecryptHandle
        CHECK(mDrmManagerClient);
        mDrmManagerClient->closeDecryptSession(mDecryptHandle);
        mDecryptHandle = NULL;
    }

    if (mDrmManagerClient != NULL) {
        delete mDrmManagerClient;
        mDrmManagerClient = NULL;
    }
}

status_t NuHTTPDataSource::connect(
        const char *uri,
        const KeyedVector<String8, String8> *overrides,
        off64_t offset) {
    String8 headers;
    MakeFullHeaders(overrides, &headers);

    return connect(uri, headers, offset);
}

status_t NuHTTPDataSource::connect(
        const char *uri,
        const String8 &headers,
        off64_t offset) {
    String8 host, path;
    unsigned port;

    mUri = uri;

    bool https;
    if (!ParseURL(uri, &host, &port, &path, &https)) {
        return ERROR_MALFORMED;
    }

    return connect(host, port, path, https, headers, offset);
}

static bool IsRedirectStatusCode(int httpStatus) {
    return httpStatus == 301 || httpStatus == 302
        || httpStatus == 303 || httpStatus == 307;
}

status_t NuHTTPDataSource::connect(
        const char *host, unsigned port, const char *path,
        bool https,
        const String8 &headers,
        off64_t offset) {
    if (!(mFlags & kFlagIncognito)) {
        LOGI("connect to %s:%u%s @%lld", host, port, path, offset);
    } else {
        LOGI("connect to <URL suppressed> @%lld", offset);
    }

    bool needsToReconnect = true;

    if (mState == CONNECTED && host == mHost && port == mPort
            && https == mHTTPS && offset == mOffset) {
        if (mContentLengthValid && mOffset == mContentLength) {
            LOGI("Didn't have to reconnect, old one's still good.");
            needsToReconnect = false;
        }
    }

    mHost = host;
    mPort = port;
    mPath = path;
    mHTTPS = https;
    mHeaders = headers;

    status_t err = OK;

    mState = CONNECTING;

    if (needsToReconnect) {
        mHTTP.disconnect();
        err = mHTTP.connect(host, port, https);
    }

    if (err != OK) {
        mState = DISCONNECTED;
    } else if (mState != CONNECTING) {
        err = UNKNOWN_ERROR;
    } else {
        mState = CONNECTED;

        mOffset = offset;
        mContentLength = 0;
        mContentLengthValid = false;

        String8 request("GET ");
        request.append(mPath);
        request.append(" HTTP/1.1\r\n");
        request.append("Host: ");
        request.append(mHost);
        if (mPort != 80) {
            request.append(StringPrintf(":%u", mPort).c_str());
        }
        request.append("\r\n");

        if (offset != 0) {
            char rangeHeader[128];
            sprintf(rangeHeader, "Range: bytes=%lld-\r\n", offset);
            request.append(rangeHeader);
        }

        request.append(mHeaders);
        request.append("\r\n");

        int httpStatus;
        if ((err = mHTTP.send(request.string(), request.size())) != OK
                || (err = mHTTP.receive_header(&httpStatus)) != OK) {
            mHTTP.disconnect();
            mState = DISCONNECTED;
            return err;
        }

        if (IsRedirectStatusCode(httpStatus)) {
            AString value;
            CHECK(mHTTP.find_header_value("Location", &value));

            mState = DISCONNECTED;

            mHTTP.disconnect();

            return connect(value.c_str(), headers, offset);
        }

        if (httpStatus < 200 || httpStatus >= 300) {
            mState = DISCONNECTED;
            mHTTP.disconnect();

            return ERROR_IO;
        }

        mHasChunkedTransferEncoding = false;

        {
            AString value;
            if (mHTTP.find_header_value("Transfer-Encoding", &value)) {
                // We don't currently support any transfer encodings but
                // chunked.

                if (!strcasecmp(value.c_str(), "chunked")) {
                    LOGI("Chunked transfer encoding applied.");
                    mHasChunkedTransferEncoding = true;
                    mChunkDataBytesLeft = 0;
                } else {
                    mState = DISCONNECTED;
                    mHTTP.disconnect();

                    LOGE("We don't support '%s' transfer encoding.", value.c_str());

                    return ERROR_UNSUPPORTED;
                }
            }
        }

        applyTimeoutResponse();

        if (offset == 0) {
            AString value;
            unsigned long x;
            if (mHTTP.find_header_value(AString("Content-Length"), &value)
                    && ParseSingleUnsignedLong(value.c_str(), &x)) {
                mContentLength = (off64_t)x;
                mContentLengthValid = true;
            } else {
                LOGW("Server did not give us the content length!");
            }
        } else {
            if (httpStatus != 206 /* Partial Content */) {
                // We requested a range but the server didn't support that.
                LOGE("We requested a range but the server didn't "
                     "support that.");
                return ERROR_UNSUPPORTED;
            }

            AString value;
            unsigned long x;
            if (mHTTP.find_header_value(AString("Content-Range"), &value)) {
                const char *slashPos = strchr(value.c_str(), '/');
                if (slashPos != NULL
                        && ParseSingleUnsignedLong(slashPos + 1, &x)) {
                    mContentLength = x;
                    mContentLengthValid = true;
                }
            }
        }
    }

    return err;
}

void NuHTTPDataSource::disconnect() {
    if (mState == CONNECTING || mState == CONNECTED) {
        mHTTP.disconnect();
    }
    mState = DISCONNECTED;
}

status_t NuHTTPDataSource::initCheck() const {
    return mState == CONNECTED ? OK : NO_INIT;
}

ssize_t NuHTTPDataSource::internalRead(void *data, size_t size) {
    if (!mHasChunkedTransferEncoding) {
        return mHTTP.receive(data, size);
    }

    if (mChunkDataBytesLeft < 0) {
        return 0;
    } else if (mChunkDataBytesLeft == 0) {
        char line[1024];
        status_t err = mHTTP.receive_line(line, sizeof(line));

        if (err != OK) {
            return err;
        }

        LOGV("line = '%s'", line);

        char *end;
        unsigned long n = strtoul(line, &end, 16);

        if (end == line || (*end != ';' && *end != '\0')) {
            LOGE("malformed HTTP chunk '%s'", line);
            return ERROR_MALFORMED;
        }

        mChunkDataBytesLeft = n;
        LOGV("chunk data size = %lu", n);

        if (mChunkDataBytesLeft == 0) {
            mChunkDataBytesLeft = -1;
            return 0;
        }

        // fall through
    }

    if (size > (size_t)mChunkDataBytesLeft) {
        size = mChunkDataBytesLeft;
    }

    ssize_t n = mHTTP.receive(data, size);

    if (n < 0) {
        return n;
    }

    mChunkDataBytesLeft -= (size_t)n;

    if (mChunkDataBytesLeft == 0) {
        char line[1024];
        status_t err = mHTTP.receive_line(line, sizeof(line));

        if (err != OK) {
            return err;
        }

        if (line[0] != '\0') {
            LOGE("missing HTTP chunk terminator.");
            return ERROR_MALFORMED;
        }
    }

    return n;
}

ssize_t NuHTTPDataSource::readAt(off64_t offset, void *data, size_t size) {
    LOGV("readAt offset %ld, size %d", offset, size);

    Mutex::Autolock autoLock(mLock);

    // if it's a DRM container based streaming, call pread() of the DRM plugin
    // to get the decrypted data
    if (mDecryptHandle != NULL && DecryptApiType::CONTAINER_BASED
            == mDecryptHandle->decryptApiType) {
        return mDrmManagerClient->pread(mDecryptHandle, data, size, offset);
    }

    if (offset != mOffset) {
        String8 host = mHost;
        String8 path = mPath;
        String8 headers = mHeaders;
        status_t err = connect(host, mPort, path, mHTTPS, headers, offset);

        if (err != OK) {
            return err;
        }
    }

    if (mContentLengthValid) {
        size_t avail =
            (offset >= mContentLength) ? 0 : mContentLength - offset;

        if (size > avail) {
            size = avail;
        }
    }

    size_t numBytesRead = 0;
    while (numBytesRead < size) {
        int64_t startTimeUs = ALooper::GetNowUs();

        ssize_t n =
            internalRead((uint8_t *)data + numBytesRead, size - numBytesRead);

        if (n < 0) {
            if (numBytesRead == 0 || mContentLengthValid) {
                return n;
            }

            // If there was an error we want to at least return the data
            // we've already successfully read. The next call to read will
            // then return the error.
            n = 0;
        }

        int64_t delayUs = ALooper::GetNowUs() - startTimeUs;
        addBandwidthMeasurement_l(n, delayUs);

        numBytesRead += (size_t)n;

        if (n == 0) {
            if (mContentLengthValid) {
                // We know the content length and made sure not to read beyond
                // it and yet the server closed the connection on us.
                return ERROR_IO;
            }

            break;
        }
    }

    mOffset += numBytesRead;

    return numBytesRead;
}

status_t NuHTTPDataSource::getSize(off64_t *size) {
    *size = 0;

    if (mState != CONNECTED) {
        return ERROR_IO;
    }

    if (mContentLengthValid) {
        *size = mContentLength;
        return OK;
    }

    return ERROR_UNSUPPORTED;
}

uint32_t NuHTTPDataSource::flags() {
    return kWantsPrefetching;
}

// static
void NuHTTPDataSource::MakeFullHeaders(
        const KeyedVector<String8, String8> *overrides, String8 *headers) {
    headers->setTo("");

    headers->append("User-Agent: stagefright/1.1 (Linux;Android ");

#if (PROPERTY_VALUE_MAX < 8)
#error "PROPERTY_VALUE_MAX must be at least 8"
#endif

    char value[PROPERTY_VALUE_MAX];
    property_get("ro.build.version.release", value, "Unknown");
    headers->append(value);
    headers->append(")\r\n");

    if (overrides == NULL) {
        return;
    }

    for (size_t i = 0; i < overrides->size(); ++i) {
        String8 line;
        line.append(overrides->keyAt(i));
        line.append(": ");
        line.append(overrides->valueAt(i));
        line.append("\r\n");

        headers->append(line);
    }
}

void NuHTTPDataSource::applyTimeoutResponse() {
    AString timeout;
    if (mHTTP.find_header_value("X-SocketTimeout", &timeout)) {
        const char *s = timeout.c_str();
        char *end;
        long tmp = strtol(s, &end, 10);
        if (end == s || *end != '\0') {
            LOGW("Illegal X-SocketTimeout value given.");
            return;
        }

        LOGI("overriding default timeout, new timeout is %ld seconds", tmp);
        mHTTP.setReceiveTimeout(tmp);
    }
}

bool NuHTTPDataSource::estimateBandwidth(int32_t *bandwidth_bps) {
    Mutex::Autolock autoLock(mLock);

    if (mNumBandwidthHistoryItems < 2) {
        return false;
    }

    *bandwidth_bps = ((double)mTotalTransferBytes * 8E6 / mTotalTransferTimeUs);

    return true;
}

void NuHTTPDataSource::addBandwidthMeasurement_l(
        size_t numBytes, int64_t delayUs) {
    BandwidthEntry entry;
    entry.mDelayUs = delayUs;
    entry.mNumBytes = numBytes;
    mTotalTransferTimeUs += delayUs;
    mTotalTransferBytes += numBytes;

    mBandwidthHistory.push_back(entry);
    if (++mNumBandwidthHistoryItems > 100) {
        BandwidthEntry *entry = &*mBandwidthHistory.begin();
        mTotalTransferTimeUs -= entry->mDelayUs;
        mTotalTransferBytes -= entry->mNumBytes;
        mBandwidthHistory.erase(mBandwidthHistory.begin());
        --mNumBandwidthHistoryItems;
        int64_t timeNowUs = ALooper::GetNowUs();
        if (timeNowUs - mPrevBandwidthMeasureTimeUs > 2000000LL) {
            if (mPrevBandwidthMeasureTimeUs != 0) {
                double estimatedBandwidth =
                    ((double)mTotalTransferBytes * 8E3 / mTotalTransferTimeUs);
                LOGI("estimated avg bandwidth is %8.2f kbps in the past %lld us",
                    estimatedBandwidth, timeNowUs - mPrevBandwidthMeasureTimeUs);
            }
            mPrevBandwidthMeasureTimeUs = timeNowUs;
        }
    }
}

DecryptHandle* NuHTTPDataSource::DrmInitialization() {
    if (mDrmManagerClient == NULL) {
        mDrmManagerClient = new DrmManagerClient();
    }

    if (mDrmManagerClient == NULL) {
        return NULL;
    }

    if (mDecryptHandle == NULL) {
        /* Note if redirect occurs, mUri is the redirect uri instead of the
         * original one
         */
        mDecryptHandle = mDrmManagerClient->openDecryptSession(mUri);
    }

    if (mDecryptHandle == NULL) {
        delete mDrmManagerClient;
        mDrmManagerClient = NULL;
    }

    return mDecryptHandle;
}

void NuHTTPDataSource::getDrmInfo(DecryptHandle **handle, DrmManagerClient **client) {
    *handle = mDecryptHandle;

    *client = mDrmManagerClient;
}

String8 NuHTTPDataSource::getUri() {
    return mUri;
}

}  // namespace android
