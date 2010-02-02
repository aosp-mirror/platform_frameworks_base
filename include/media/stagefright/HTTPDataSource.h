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

#ifndef HTTP_DATASOURCE_H_

#define HTTP_DATASOURCE_H_

#include <media/stagefright/DataSource.h>
#include <utils/String8.h>

namespace android {

class HTTPStream;

class HTTPDataSource : public DataSource {
public:
    HTTPDataSource(
            const char *host, int port, const char *path,
            const KeyedVector<String8, String8> *headers = NULL);

    HTTPDataSource(
            const char *uri,
            const KeyedVector<String8, String8> *headers = NULL);

    virtual status_t initCheck() const;

    virtual ssize_t readAt(off_t offset, void *data, size_t size);

    virtual status_t getSize(off_t *size);

    virtual uint32_t flags() {
        return kWantsPrefetching;
    }

protected:
    virtual ~HTTPDataSource();

private:
    enum {
        kBufferSize = 64 * 1024
    };

    String8 mHeaders;

    HTTPStream *mHttp;
    char *mHost;
    int mPort;
    char *mPath;

    void *mBuffer;
    size_t mBufferLength;
    off_t mBufferOffset;
    bool mFirstRequest;

    bool mContentLengthValid;
    unsigned long long mContentLength;

    status_t mInitCheck;

    void init(
            const char *_host, int port, const char *_path,
            const KeyedVector<String8, String8> *headers);

    ssize_t sendRangeRequest(size_t offset);
    void initHeaders(const KeyedVector<String8, String8> *overrides);

    HTTPDataSource(const HTTPDataSource &);
    HTTPDataSource &operator=(const HTTPDataSource &);
};

}  // namespace android

#endif  // HTTP_DATASOURCE_H_

