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
#include <media/stagefright/HTTPStream.h>

namespace android {

class HTTPDataSource : public DataSource {
public:
    HTTPDataSource(const char *host, int port, const char *path);
    HTTPDataSource(const char *uri);

    virtual ~HTTPDataSource();

    // XXXandih
    status_t InitCheck() const { return OK; }

    virtual ssize_t read_at(off_t offset, void *data, size_t size);

private:
    enum {
        kBufferSize = 64 * 1024
    };

    HTTPStream mHttp;
    char *mHost;
    int mPort;
    char *mPath;

    void *mBuffer;
    size_t mBufferLength;
    off_t mBufferOffset;

    HTTPDataSource(const HTTPDataSource &);
    HTTPDataSource &operator=(const HTTPDataSource &);
};

}  // namespace android

#endif  // HTTP_DATASOURCE_H_

