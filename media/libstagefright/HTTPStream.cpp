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
#define LOG_TAG "HTTPStream"
#include <utils/Log.h>

#include "include/HTTPStream.h"

#include <sys/socket.h>

#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <media/stagefright/foundation/ADebug.h>

namespace android {

// static
const char *HTTPStream::kStatusKey = ":status:";

HTTPStream::HTTPStream()
    : mState(READY),
      mSocket(-1) {
}

HTTPStream::~HTTPStream() {
    disconnect();
}

static bool MakeSocketBlocking(int s, bool blocking) {
    // Make socket non-blocking.
    int flags = fcntl(s, F_GETFL, 0);
    if (flags == -1) {
        return false;
    }

    if (blocking) {
        flags &= ~O_NONBLOCK;
    } else {
        flags |= O_NONBLOCK;
    }

    return fcntl(s, F_SETFL, flags) != -1;
}

static status_t MyConnect(
        int s, const struct sockaddr *addr, socklen_t addrlen) {
    status_t result = UNKNOWN_ERROR;

    MakeSocketBlocking(s, false);

    if (connect(s, addr, addrlen) == 0) {
        result = OK;
    } else if (errno != EINPROGRESS) {
        result = -errno;
    } else {
        for (;;) {
            fd_set rs, ws;
            FD_ZERO(&rs);
            FD_ZERO(&ws);
            FD_SET(s, &rs);
            FD_SET(s, &ws);

            struct timeval tv;
            tv.tv_sec = 0;
            tv.tv_usec = 100000ll;

            int nfds = ::select(s + 1, &rs, &ws, NULL, &tv);

            if (nfds < 0) {
                if (errno == EINTR) {
                    continue;
                }

                result = -errno;
                break;
            }

            if (FD_ISSET(s, &ws) && !FD_ISSET(s, &rs)) {
                result = OK;
                break;
            }

            if (FD_ISSET(s, &rs) || FD_ISSET(s, &ws)) {
                // Get the pending error.
                int error = 0;
                socklen_t errorLen = sizeof(error);
                if (getsockopt(s, SOL_SOCKET, SO_ERROR, &error, &errorLen) == -1) {
                    // Couldn't get the real error, so report why not.
                    result = -errno;
                } else {
                    result = -error;
                }
                break;
            }

            // Timeout expired. Try again.
        }
    }

    MakeSocketBlocking(s, true);

    return result;
}

status_t HTTPStream::connect(const char *server, int port) {
    Mutex::Autolock autoLock(mLock);

    status_t err = OK;

    if (mState == CONNECTED) {
        return ERROR_ALREADY_CONNECTED;
    }

    struct hostent *ent = gethostbyname(server);
    if (ent == NULL) {
        return ERROR_UNKNOWN_HOST;
    }

    CHECK_EQ(mSocket, -1);
    mSocket = socket(AF_INET, SOCK_STREAM, 0);

    if (mSocket < 0) {
        return UNKNOWN_ERROR;
    }

    setReceiveTimeout(30);  // Time out reads after 30 secs by default

    mState = CONNECTING;

    int s = mSocket;

    mLock.unlock();

    struct sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = *(in_addr_t *)ent->h_addr;
    memset(addr.sin_zero, 0, sizeof(addr.sin_zero));

    status_t res = MyConnect(s, (const struct sockaddr *)&addr, sizeof(addr));

    mLock.lock();

    if (mState != CONNECTING) {
        return UNKNOWN_ERROR;
    }

    if (res != OK) {
        close(mSocket);
        mSocket = -1;

        mState = READY;
        return res;
    }

    mState = CONNECTED;

    return OK;
}

status_t HTTPStream::disconnect() {
    Mutex::Autolock autoLock(mLock);

    if (mState != CONNECTED && mState != CONNECTING) {
        return ERROR_NOT_CONNECTED;
    }

    CHECK(mSocket >= 0);
    close(mSocket);
    mSocket = -1;

    mState = READY;

    return OK;
}

status_t HTTPStream::send(const char *data, size_t size) {
    if (mState != CONNECTED) {
        return ERROR_NOT_CONNECTED;
    }

    while (size > 0) {
        ssize_t n = ::send(mSocket, data, size, 0);

        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }

            disconnect();

            return ERROR_IO;
        } else if (n == 0) {
            disconnect();

            return ERROR_CONNECTION_LOST;
        }

        size -= (size_t)n;
        data += (size_t)n;
    }

    return OK;
}

status_t HTTPStream::send(const char *data) {
    return send(data, strlen(data));
}

// A certain application spawns a local webserver that sends invalid responses,
// specifically it terminates header line with only a newline instead of the
// CRLF (carriage-return followed by newline) required by the HTTP specs.
// The workaround accepts both behaviours but could potentially break
// legitimate responses that use a single newline to "fold" headers, which is
// why it's not yet on by default.
#define WORKAROUND_FOR_MISSING_CR       1

status_t HTTPStream::receive_line(char *line, size_t size) {
    if (mState != CONNECTED) {
        return ERROR_NOT_CONNECTED;
    }

    bool saw_CR = false;
    size_t length = 0;

    for (;;) {
        char c;
        ssize_t n = recv(mSocket, &c, 1, 0);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }

            disconnect();

            return ERROR_IO;
        } else if (n == 0) {
            disconnect();

            return ERROR_CONNECTION_LOST;
        }

#if WORKAROUND_FOR_MISSING_CR
        if (c == '\n') {
            // We have a complete line.

            line[saw_CR ? length - 1 : length] = '\0';
            return OK;
        }
#else
        if (saw_CR &&  c == '\n') {
            // We have a complete line.

            line[length - 1] = '\0';
            return OK;
        }
#endif

        saw_CR = (c == '\r');

        if (length + 1 >= size) {
            return ERROR_MALFORMED;
        }
        line[length++] = c;
    }
}

status_t HTTPStream::receive_header(int *http_status) {
    *http_status = -1;
    mHeaders.clear();

    char line[2048];
    status_t err = receive_line(line, sizeof(line));
    if (err != OK) {
        return err;
    }

    mHeaders.add(string(kStatusKey), string(line));

    char *spacePos = strchr(line, ' ');
    if (spacePos == NULL) {
        // Malformed response?
        return UNKNOWN_ERROR;
    }

    char *status_start = spacePos + 1;
    char *status_end = status_start;
    while (isdigit(*status_end)) {
        ++status_end;
    }

    if (status_end == status_start) {
        // Malformed response, status missing?
        return UNKNOWN_ERROR;
    }

    memmove(line, status_start, status_end - status_start);
    line[status_end - status_start] = '\0';

    long tmp = strtol(line, NULL, 10);
    if (tmp < 0 || tmp > 999) {
        return UNKNOWN_ERROR;
    }

    *http_status = (int)tmp;

    for (;;) {
        err = receive_line(line, sizeof(line));
        if (err != OK) {
            return err;
        }

        if (*line == '\0') {
            // Empty line signals the end of the header.
            break;
        }

        // puts(line);

        char *colonPos = strchr(line, ':');
        if (colonPos == NULL) {
            mHeaders.add(string(line), string());
        } else {
            char *end_of_key = colonPos;
            while (end_of_key > line && isspace(end_of_key[-1])) {
                --end_of_key;
            }

            char *start_of_value = colonPos + 1;
            while (isspace(*start_of_value)) {
                ++start_of_value;
            }

            *end_of_key = '\0';

            mHeaders.add(string(line), string(start_of_value));
        }
    }

    return OK;
}

ssize_t HTTPStream::receive(void *data, size_t size) {
    size_t total = 0;
    while (total < size) {
        ssize_t n = recv(mSocket, (char *)data + total, size - total, 0);

        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }

            LOGE("recv failed, errno = %d (%s)", errno, strerror(errno));

            disconnect();
            return (ssize_t)ERROR_IO;
        } else if (n == 0) {
            disconnect();

            LOGE("recv failed, server is gone, total received: %d bytes",
                 total);

            return total == 0 ? (ssize_t)ERROR_CONNECTION_LOST : total;
        }

        total += (size_t)n;
    }

    return (ssize_t)total;
}

bool HTTPStream::find_header_value(const string &key, string *value) const {
    ssize_t index = mHeaders.indexOfKey(key);
    if (index < 0) {
        value->clear();
        return false;
    }

    *value = mHeaders.valueAt(index);

    return true;
}

void HTTPStream::setReceiveTimeout(int seconds) {
    if (seconds < 0) {
        // Disable the timeout.
        seconds = 0;
    }

    struct timeval tv;
    tv.tv_usec = 0;
    tv.tv_sec = seconds;
    CHECK_EQ(0, setsockopt(mSocket, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)));
}

}  // namespace android

