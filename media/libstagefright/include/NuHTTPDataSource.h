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

#ifndef NU_HTTP_DATA_SOURCE_H_

#define NU_HTTP_DATA_SOURCE_H_

#include <media/stagefright/DataSource.h>
#include <utils/List.h>
#include <utils/String8.h>
#include <utils/threads.h>

#include "HTTPStream.h"

namespace android {

struct NuHTTPDataSource : public DataSource {
    enum Flags {
        // Don't log any URLs.
        kFlagIncognito = 1
    };
    NuHTTPDataSource(uint32_t flags = 0);

    status_t connect(
            const char *uri,
            const KeyedVector<String8, String8> *headers = NULL,
            off64_t offset = 0);

    void disconnect();

    virtual status_t initCheck() const;

    virtual ssize_t readAt(off64_t offset, void *data, size_t size);
    virtual status_t getSize(off64_t *size);
    virtual uint32_t flags();

    // Returns true if bandwidth could successfully be estimated,
    // false otherwise.
    bool estimateBandwidth(int32_t *bandwidth_bps);

    virtual DecryptHandle* DrmInitialization();
    virtual void getDrmInfo(DecryptHandle **handle, DrmManagerClient **client);
    virtual String8 getUri();

protected:
    virtual ~NuHTTPDataSource();

private:
    enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    };

    struct BandwidthEntry {
        int64_t mDelayUs;
        size_t mNumBytes;
    };

    Mutex mLock;

    uint32_t mFlags;

    State mState;

    String8 mHost;
    unsigned mPort;
    String8 mPath;
    bool mHTTPS;
    String8 mHeaders;
    String8 mUri;

    HTTPStream mHTTP;
    off64_t mOffset;
    off64_t mContentLength;
    bool mContentLengthValid;
    bool mHasChunkedTransferEncoding;

    // The number of data bytes in the current chunk before any subsequent
    // chunk header (or -1 if no more chunks).
    ssize_t mChunkDataBytesLeft;

    List<BandwidthEntry> mBandwidthHistory;
    size_t mNumBandwidthHistoryItems;
    int64_t mTotalTransferTimeUs;
    size_t mTotalTransferBytes;

    DecryptHandle *mDecryptHandle;
    DrmManagerClient *mDrmManagerClient;

    status_t connect(
            const char *uri, const String8 &headers, off64_t offset);

    status_t connect(
            const char *host, unsigned port, const char *path,
            bool https,
            const String8 &headers,
            off64_t offset);

    // Read up to "size" bytes of data, respect transfer encoding.
    ssize_t internalRead(void *data, size_t size);

    void applyTimeoutResponse();
    void addBandwidthMeasurement_l(size_t numBytes, int64_t delayUs);

    static void MakeFullHeaders(
            const KeyedVector<String8, String8> *overrides,
            String8 *headers);

    NuHTTPDataSource(const NuHTTPDataSource &);
    NuHTTPDataSource &operator=(const NuHTTPDataSource &);
};

}  // namespace android

#endif  // NU_HTTP_DATA_SOURCE_H_
