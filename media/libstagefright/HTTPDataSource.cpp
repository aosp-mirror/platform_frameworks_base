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

#include "include/stagefright_string.h"
#include "include/HTTPStream.h"

#include <stdlib.h>

#include <media/stagefright/HTTPDataSource.h>
#include <media/stagefright/MediaDebug.h>

namespace android {

static const char *kUserAgent = "stagefright-http";

// Given a connected HTTPStream, determine if the given path redirects
// somewhere else, if so, disconnect the stream, update host path and port
// accordingly and return true, otherwise return false and leave the stream
// connected.
static bool PerformRedirectIfNecessary(
        HTTPStream *http, const String8 &headers,
        string *host, string *path, int *port) {
    String8 request;
    request.append("HEAD ");
    request.append(path->c_str());
    request.append(" HTTP/1.1\r\n");
    request.append(headers);
    request.append("Host: ");
    request.append(host->c_str());
    request.append("\r\n\r\n");

    status_t err = http->send(request.string());

    int http_status;
    if (err == OK) {
        err = http->receive_header(&http_status);
    }

    if (err != OK) {
        return false;
    }

    if (http_status != 301 && http_status != 302) {
        return false;
    }

    string location;
    CHECK(http->find_header_value("Location", &location));

    CHECK(string(location, 0, 7) == "http://");
    location.erase(0, 7);
    string::size_type slashPos = location.find('/');
    if (slashPos == string::npos) {
        slashPos = location.size();
        location += '/';
    }

    http->disconnect();

    LOGI("Redirecting to %s\n", location.c_str());

    *host = string(location, 0, slashPos);

    string::size_type colonPos = host->find(':');
    if (colonPos != string::npos) {
        const char *start = host->c_str() + colonPos + 1;
        char *end;
        long tmp = strtol(start, &end, 10);
        CHECK(end > start && (*end == '\0'));

        *port = (tmp >= 0 && tmp < 65536) ? (int)tmp : 80;

        host->erase(colonPos, host->size() - colonPos);
    } else {
        *port = 80;
    }

    *path = string(location, slashPos);

    return true;
}

HTTPDataSource::HTTPDataSource(
        const char *uri, const KeyedVector<String8, String8> *headers)
    : mHttp(new HTTPStream),
      mHost(NULL),
      mPort(0),
      mPath(NULL),
      mBuffer(malloc(kBufferSize)),
      mBufferLength(0),
      mBufferOffset(0),
      mFirstRequest(true) {
    CHECK(!strncasecmp("http://", uri, 7));

    initHeaders(headers);

    string host;
    string path;
    int port;

    char *slash = strchr(uri + 7, '/');
    if (slash == NULL) {
        host = uri + 7;
        path = "/";
    } else {
        host = string(uri + 7, slash - (uri + 7));
        path = slash;
    }

    char *colon = strchr(host.c_str(), ':');
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

    do {
        LOGI("Connecting to host '%s', port %d, path '%s'",
             host.c_str(), port, path.c_str());

        mInitCheck = mHttp->connect(host.c_str(), port);

        if (mInitCheck != OK) {
            return;
        }
    } while (PerformRedirectIfNecessary(mHttp, mHeaders, &host, &path, &port));

    mHost = strdup(host.c_str());
    mPort = port;
    mPath = strdup(path.c_str());
}

HTTPDataSource::HTTPDataSource(
        const char *_host, int port, const char *_path,
        const KeyedVector<String8, String8> *headers)
    : mHttp(new HTTPStream),
      mHost(NULL),
      mPort(0),
      mPath(NULL),
      mBuffer(malloc(kBufferSize)),
      mBufferLength(0),
      mBufferOffset(0),
      mFirstRequest(true) {
    initHeaders(headers);

    string host = _host;
    string path = _path;

    LOGI("Connecting to host '%s', port %d, path '%s'",
         host.c_str(), port, path.c_str());

    do {
        mInitCheck = mHttp->connect(host.c_str(), port);

        if (mInitCheck != OK) {
            return;
        }
    } while (PerformRedirectIfNecessary(mHttp, mHeaders, &host, &path, &port));

    mHost = strdup(host.c_str());
    mPort = port;
    mPath = strdup(path.c_str());
}

status_t HTTPDataSource::initCheck() const {
    return mInitCheck;
}

HTTPDataSource::~HTTPDataSource() {
    mHttp->disconnect();

    free(mBuffer);
    mBuffer = NULL;

    if (mPath) {
        free(mPath);
        mPath = NULL;
    }

    if (mHost) {
        free(mHost);
        mHost = NULL;
    }

    delete mHttp;
    mHttp = NULL;
}

ssize_t HTTPDataSource::sendRangeRequest(size_t offset) {
    char host[128];
    sprintf(host, "Host: %s\r\n", mHost);

    char range[128];
    if (offset > 0) {
        sprintf(range, "Range: bytes=%d-\r\n\r\n", offset);
    } else {
        range[0] = '\0';
    }

    int http_status;

    status_t err;
    int attempt = 1;
    for (;;) {
        if ((err = mHttp->send("GET ")) != OK
            || (err = mHttp->send(mPath)) != OK
            || (err = mHttp->send(" HTTP/1.1\r\n")) != OK
            || (err = mHttp->send(mHeaders.string())) != OK
            || (err = mHttp->send(host)) != OK
            || (err = mHttp->send(range)) != OK
            || (err = mHttp->send("\r\n")) != OK
            || (err = mHttp->receive_header(&http_status)) != OK) {

            if (attempt == 3) {
                return err;
            }

            mHttp->connect(mHost, mPort);
            ++attempt;
        } else {
            break;
        }
    }

    if ((http_status / 100) != 2) {
        return UNKNOWN_ERROR;
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
    if (offset >= mBufferOffset
            && offset < (off_t)(mBufferOffset + mBufferLength)) {
        size_t num_bytes_available = mBufferLength - (offset - mBufferOffset);

        size_t copy = num_bytes_available;
        if (copy > size) {
            copy = size;
        }

        memcpy(data, (const char *)mBuffer + (offset - mBufferOffset), copy);

        return copy;
    }

    ssize_t contentLength = 0;
    if (mFirstRequest || offset != mBufferOffset + mBufferLength) {
        if (!mFirstRequest) {
            LOGV("new range offset=%ld (old=%ld)",
                 offset, mBufferOffset + mBufferLength);

            mHttp->disconnect();
        }
        mFirstRequest = false;

        contentLength = sendRangeRequest(offset);

        if (contentLength > kBufferSize) {
            contentLength = kBufferSize;
        }
    } else {
        contentLength = kBufferSize;
    }

    mBufferOffset = offset;

    if (contentLength <= 0) {
        return contentLength;
    }

    ssize_t num_bytes_received = mHttp->receive(mBuffer, contentLength);

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

    mHeaders.append("User-Agent: ");
    mHeaders.append(kUserAgent);
    mHeaders.append("\r\n");

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

}  // namespace android

