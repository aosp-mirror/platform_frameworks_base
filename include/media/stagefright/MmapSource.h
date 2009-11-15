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

#ifndef MMAP_SOURCE_H_

#define MMAP_SOURCE_H_

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaErrors.h>

namespace android {

class MmapSource : public DataSource {
public:
    MmapSource(const char *filename);

    // Assumes ownership of "fd".
    MmapSource(int fd, int64_t offset, int64_t length);

    virtual ~MmapSource();

    status_t InitCheck() const;

    virtual ssize_t read_at(off_t offset, void *data, size_t size);
    virtual status_t getSize(off_t *size);

private:
    int mFd;
    void *mBase;
    size_t mSize;

    MmapSource(const MmapSource &);
    MmapSource &operator=(const MmapSource &);
};

}  // namespace android

#endif  // MMAP_SOURCE_H_

