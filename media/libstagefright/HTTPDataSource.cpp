/*
 * Copyright (C) 2009 The Android Open Source Project
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
#define LOG_TAG "HTTPDataSource"
#include <utils/Log.h>

#include "include/stagefright_string.h"
#include "include/HTTPStream.h"

#include <stdlib.h>

#include <cutils/properties.h>
#include <media/stagefright/HTTPDataSource.h>
#include <media/stagefright/MediaDebug.h>

namespace android {

status_t HTTPDataSource::connectWithRedirectsAndRange(off_t rangeStart) {
    string host = mStartingHost.string();
    string path = mStartingPath.string();
    int port = mStartingPort;

    LOGV("Connecting to host '%s', port %d, path '%s'",
         host.c_str(), port, path.c_str());

    int numRedirectsRemaining = 5;
    while (numRedirectsRemaining-- > 0) {
        {
            Mutex::Autolock autoLock(mStateLock);
            if (mState == DISCONNECTED) {
                return UNKNOWN_ERROR;
            }
        }

        status_t err = mHttp->connect(host.c_str(), port);

        if (err != OK) {
            return err;
        }

        String8 request;
        request.append("GET ");
        request.append(path.c_str());
        request.append(" HTTP/1.1\r\n");
        request.append(mHeaders);
        request.append("Host: ");
        request.append(host.c_str());
        request.append("\r\n");

        if (rangeStart > 0) {
            char range[128];
            sprintf(range, "Range: bytes=%ld-\r\n", rangeStart);

            request.append(range);
        }

        request.append("\r\n");

        err = mHttp->send(request.string());

        if (err != OK) {
            return err;
        }

        int httpStatus;
        err = mHttp->receive_header(&httpStatus);

        if (err != OK) {
            return err;
        }

        if (httpStatus >= 200 && httpStatus < 300) {
            applyTimeoutResponse();
            return OK;
        }

        if (httpStatus != 301 && httpStatus != 302) {
            LOGE("HTTP request failed w/ http status %d", httpStatus);
            return ERROR_IO;
        }

        string location;
        CHECK(mHttp->find_header_value("Location", &location));

        CHECK(string(location, 0, 7) == "http://");
        location.erase(0, 7);
        string::size_type slashPos = location.find('/');
        if (slashPos == string::npos) {
            slashPos = location.size();
            location += '/';
        }

        mHttp->disconnect();

        LOGV("Redirecting to %s\n", location.c_str());

        host = string(location, 0, slashPos);

        string::size_type colonPos = host.find(':');
        if (colonPos != string::npos) {
            const char *start = host.c_str() + colonPos + 1;
            char *end;
            long tmp = strtol(start, &end, 10);
            CHECK(end > start && (*end == '\0'));

            port = (tmp >= 0 && tmp < 65536) ? (int)tmp : 80;

            host.erase(colonPos, host.size() - colonPos);
        } else {
            port = 80;
        }

        path = string(location, slashPos);

        mStartingHost = host.c_str();
        mStartingPath = path.c_str();
        mStartingPort = port;
    }

    return ERROR_IO;
}

void HTTPDataSource::applyTimeoutResponse() {
    string timeout;
    if (mHttp->find_header_value("X-SocketTimeout", &timeout)) {
        const char *s = timeout.c_str();
        char *end;
        long tmp = strtol(s, &end, 10);
        if (end == s || *end != '\0') {
            LOGW("Illegal X-SocketTimeout value given.");
            return;
        }

        LOGI("overriding default timeout, new timeout is %ld seconds", tmp);
        mHttp->setReceiveTimeout(tmp);
    }
}

HTTPDataSource::HTTPDataSource(
        const char *uri, const KeyedVector<String8, String8> *headers) {
    CHECK(!strncasecmp("http://", uri, 7));

    string host;
    string path;
    int port;

    const char *slash = strchr(uri + 7, '/');
    if (slash == NULL) {
        host = uri + 7;
        path = "/";
    } else {
        host = string(uri + 7, slash - (uri + 7));
        path = slash;
    }

    const char *colon = strchr(host.c_str(), ':');
    if (colon == NULL) {
        port = 80;
    } else {
        char *end;
        long tmp = strtol(colon + 1, &end, 10);
        CHECK(end > colon + 1);
        CHECK(tmp > 0 && tmp < 65536);
        port = tmp;

        host = string(host, 0, colon - host.c_str());
    }

    mStartingHost = host.c_str();
    mStartingPath = path.c_str();
    mStartingPort = port;

    init(headers);
}

HTTPDataSource::HTTPDataSource(
        const char *_host, int port, const char *_path,
        const KeyedVector<String8, String8> *headers) {
    mStartingHost = _host;
    mStartingPath = _path;
    mStartingPort = port;

    init(headers);
}

void HTTPDataSource::init(const KeyedVector<String8, String8> *headers) {
    mState = DISCONNECTED;
    mHttp = new HTTPStream;

    initHeaders(headers);

    mBuffer = malloc(kBufferSize);

    mNumRetriesLeft = kMaxNumRetries;
}

status_t HTTPDataSource::connect() {
    {
        Mutex::Autolock autoLock(mStateLock);

        if (mState != DISCONNECTED) {
            return ERROR_ALREADY_CONNECTED;
        }

        mState = CONNECTING;
    }

    mBufferLength = 0;
    mBufferOffset = 0;
    mContentLengthValid = false;

    status_t err = connectWithRedirectsAndRange(0);

    if (err != OK) {
        Mutex::Autolock autoLock(mStateLock);

        if (mState != CONNECTING) {
            LOGV("connect() cancelled");
        }
        mState = DISCONNECTED;

        return err;
    }

    string value;
    if (mHttp->find_header_value("Content-Length", &value)) {
        char *end;
        mContentLength = strtoull(value.c_str(), &end, 10);
        mContentLengthValid = true;
    }

    Mutex::Autolock autoLock(mStateLock);

    if (mState != CONNECTING) {
        // disconnect was called when we had just successfully connected.
        LOGV("connect() cancelled (we had just succeeded connecting)");

        mHttp->disconnect();
        return UNKNOWN_ERROR;
    }

    mState = CONNECTED;

    return OK;
}

void HTTPDataSource::disconnect() {
    Mutex::Autolock autoLock(mStateLock);

    if (mState == CONNECTING || mState == CONNECTED) {
        mHttp->disconnect();
        mState = DISCONNECTED;
    }
}

status_t HTTPDataSource::initCheck() const {
    Mutex::Autolock autoLock(mStateLock);

    return (mState == CONNECTED) ? (status_t)OK : ERROR_NOT_CONNECTED;
}

status_t HTTPDataSource::getSize(off_t *size) {
    *size = 0;

    {
        Mutex::Autolock autoLock(mStateLock);
        if (mState != CONNECTED) {
            return ERROR_NOT_CONNECTED;
        }
    }

    if (!mContentLengthValid) {
        return ERROR_UNSUPPORTED;
    }

    *size = mContentLength;

    return OK;
}

HTTPDataSource::~HTTPDataSource() {
    disconnect();

    delete mHttp;
    mHttp = NULL;

    free(mBuffer);
    mBuffer = NULL;
}

ssize_t HTTPDataSource::sendRangeRequest(size_t offset) {
    status_t err = connectWithRedirectsAndRange(offset);

    if (err != OK) {
        return err;
    }

    string value;
    if (!mHttp->find_header_value("Content-Length", &value)) {
        return kBufferSize;
    }

    char *end;
    unsigned long contentLength = strtoul(value.c_str(), &end, 10);

    return contentLength;
}

ssize_t HTTPDataSource::readAt(off_t offset, void *data, size_t size) {
    LOGV("readAt %ld, size %d", offset, size);

rinse_repeat:
    {
        Mutex::Autolock autoLock(mStateLock);
        if (mState != CONNECTED) {
            return ERROR_NOT_CONNECTED;
        }
    }

    if (offset >= mBufferOffset
            && offset < (off_t)(mBufferOffset + mBufferLength)) {
        size_t num_bytes_available = mBufferLength - (offset - mBufferOffset);

        size_t copy = num_bytes_available;
        if (copy > size) {
            copy = size;
        }

        memcpy(data, (const char *)mBuffer + (offset - mBufferOffset), copy);

        if (copy < size) {
            LOGV("short read (1), returning %d vs. %d requested", copy, size);
        }

        return copy;
    }

    ssize_t contentLength = 0;
    if (offset != (off_t)(mBufferOffset + mBufferLength)) {
        LOGV("new range offset=%ld (old=%ld)",
             offset, mBufferOffset + mBufferLength);

        mHttp->disconnect();

        contentLength = sendRangeRequest(offset);

        if (contentLength > kBufferSize) {
            contentLength = kBufferSize;
        }
    } else {
        contentLength = kBufferSize;
    }

    mBufferOffset = offset;

    if (mContentLengthValid
            && mBufferOffset + contentLength >= (off_t)mContentLength) {
        // If we never triggered a range request but know the content length,
        // make sure to not read more data than there could be, otherwise
        // we'd block indefinitely if the server doesn't close the connection.

        contentLength = mContentLength - mBufferOffset;
    }

    if (contentLength <= 0) {
        return contentLength;
    }

    ssize_t num_bytes_received = mHttp->receive(mBuffer, contentLength);

    if (num_bytes_received < 0
            || (mContentLengthValid && num_bytes_received < contentLength)) {
        if (mNumRetriesLeft-- > 0) {
            mHttp->disconnect();
            mBufferLength = 0;
            num_bytes_received = connectWithRedirectsAndRange(mBufferOffset);
            if (num_bytes_received == OK) {
                LOGI("retrying connection succeeded.");
                goto rinse_repeat;
            }
            LOGE("retrying connection failed");
        }

        mBufferLength = 0;

        return num_bytes_received;
    }

    mBufferLength = (size_t)num_bytes_received;

    size_t copy = mBufferLength;
    if (copy > size) {
        copy = size;
    }

    memcpy(data, mBuffer, copy);

    return copy;
}

void HTTPDataSource::initHeaders(
        const KeyedVector<String8, String8> *overrides) {
    mHeaders = String8();

    mHeaders.append("User-Agent: stagefright/1.0 (Linux;Android ");

#if (PROPERTY_VALUE_MAX < 8)
#error "PROPERTY_VALUE_MAX must be at least 8"
#endif

    char value[PROPERTY_VALUE_MAX];
    property_get("ro.build.version.release", value, "Unknown");
    mHeaders.append(value);
    mHeaders.append(")\r\n");

    if (overrides == NULL) {
        return;
    }

    for (size_t i = 0; i < overrides->size(); ++i) {
        String8 line;
        line.append(overrides->keyAt(i));
        line.append(": ");
        line.append(overrides->valueAt(i));
        line.append("\r\n");

        mHeaders.append(line);
    }
}

uint32_t HTTPDataSource::flags() {
    uint32_t f = kWantsPrefetching;

    if (!strcasecmp(mStartingHost.string(), "localhost")
            || !strcmp(mStartingHost.string(), "127.0.0.1")) {
        f |= kStreamedFromLocalHost;
    }

    return f;
}

}  // namespace android

