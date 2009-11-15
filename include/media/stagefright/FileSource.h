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
    virtual ~FileSource();

    status_t InitCheck() const;

    virtual ssize_t read_at(off_t offset, void *data, size_t size);

private:
    FILE *mFile;
    Mutex mLock;

    FileSource(const FileSource &);
    FileSource &operator=(const FileSource &);
};

}  // namespace android

#endif  // FILE_SOURCE_H_

