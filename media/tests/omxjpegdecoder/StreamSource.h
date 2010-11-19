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

#ifndef STREAM_SOURCE_H_

#define STREAM_SOURCE_H_

#include <stdio.h>

#include <core/SkStream.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaErrors.h>
#include <utils/threads.h>

namespace android {

class StreamSource : public DataSource {
public:
    // Pass the ownership of SkStream to StreamSource.
    StreamSource(SkStream *SkStream);
    virtual status_t initCheck() const;
    virtual ssize_t readAt(off64_t offset, void *data, size_t size);
    virtual status_t getSize(off64_t *size);

protected:
    virtual ~StreamSource();

private:
    SkStream *mStream;
    size_t mSize;
    Mutex mLock;

    StreamSource(const StreamSource &);
    StreamSource &operator=(const StreamSource &);
};

}  // namespace android

#endif  // STREAM_SOURCE_H_
