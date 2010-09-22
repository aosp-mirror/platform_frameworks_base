//#define LOG_NDEBUG 0
#define LOG_TAG "NuHTTPDataSource"
#include <utils/Log.h>

#include "include/NuHTTPDataSource.h"

#include <cutils/properties.h>
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
        const char *url, String8 *host, unsigned *port, String8 *path) {
    host->setTo("");
    *port = 0;
    path->setTo("");

    if (strncasecmp("http://", url, 7)) {
        return false;
    }

    const char *slashPos = strchr(&url[7], '/');

    if (slashPos == NULL) {
        host->setTo(&url[7]);
        path->setTo("/");
    } else {
        host->setTo(&url[7], slashPos - &url[7]);
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
        *port = 80;
    }

    return true;
}

NuHTTPDataSource::NuHTTPDataSource()
    : mState(DISCONNECTED),
      mPort(0),
      mOffset(0),
      mContentLength(0),
      mContentLengthValid(false) {
}

NuHTTPDataSource::~NuHTTPDataSource() {
}

status_t NuHTTPDataSource::connect(
        const char *uri,
        const KeyedVector<String8, String8> *overrides,
        off_t offset) {
    String8 headers;
    MakeFullHeaders(overrides, &headers);

    return connect(uri, headers, offset);
}

status_t NuHTTPDataSource::connect(
        const char *uri,
        const String8 &headers,
        off_t offset) {
    String8 host, path;
    unsigned port;
    if (!ParseURL(uri, &host, &port, &path)) {
        return ERROR_MALFORMED;
    }

    return connect(host, port, path, headers, offset);
}

status_t NuHTTPDataSource::connect(
        const char *host, unsigned port, const char *path,
        const String8 &headers,
        off_t offset) {
    LOGI("connect to %s:%u%s @%ld", host, port, path, offset);

    bool needsToReconnect = true;

    if (mState == CONNECTED && host == mHost && port == mPort
            && offset == mOffset) {
        if (mContentLengthValid && mOffset == mContentLength) {
            LOGI("Didn't have to reconnect, old one's still good.");
            needsToReconnect = false;
        }
    }

    mHost = host;
    mPort = port;
    mPath = path;
    mHeaders = headers;

    status_t err = OK;

    mState = CONNECTING;

    if (needsToReconnect) {
        mHTTP.disconnect();
        err = mHTTP.connect(host, port);
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
        request.append("\r\n");

        if (offset != 0) {
            char rangeHeader[128];
            sprintf(rangeHeader, "Range: bytes=%ld-\r\n", offset);
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

        if (httpStatus == 302) {
            string value;
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

        applyTimeoutResponse();

        if (offset == 0) {
            string value;
            unsigned long x;
            if (mHTTP.find_header_value(string("Content-Length"), &value)
                    && ParseSingleUnsignedLong(value.c_str(), &x)) {
                mContentLength = (off_t)x;
                mContentLengthValid = true;
            }
        } else {
            string value;
            unsigned long x;
            if (mHTTP.find_header_value(string("Content-Range"), &value)) {
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

ssize_t NuHTTPDataSource::readAt(off_t offset, void *data, size_t size) {
    LOGV("readAt offset %ld, size %d", offset, size);

    Mutex::Autolock autoLock(mLock);

    if (offset != mOffset) {
        String8 host = mHost;
        String8 path = mPath;
        String8 headers = mHeaders;
        status_t err = connect(host, mPort, path, headers, offset);

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
        ssize_t n =
            mHTTP.receive((uint8_t *)data + numBytesRead, size - numBytesRead);

        if (n < 0) {
            return n;
        }

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

status_t NuHTTPDataSource::getSize(off_t *size) {
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
    string timeout;
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

}  // namespace android
