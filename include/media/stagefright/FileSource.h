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

#ifndef FILE_SOURCE_H_

#define FILE_SOURCE_H_

#include <stdio.h>

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaErrors.h>
#include <utils/threads.h>

namespace android {

class FileSource : public DataSource {
public:
    FileSource(const char *filename);
    FileSource(int fd, int64_t offset, int64_t length);

    virtual status_t initCheck() const;

    virtual ssize_t readAt(off_t offset, void *data, size_t size);

    virtual status_t getSize(off_t *size);

protected:
    virtual ~FileSource();

private:
    FILE *mFile;
    int64_t mOffset;
    int64_t mLength;
    Mutex mLock;

    FileSource(const FileSource &);
    FileSource &operator=(const FileSource &);
};

}  // namespace android

#endif  // FILE_SOURCE_H_

