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
#define LOG_TAG "MmapSource"
#include <utils/Log.h>

#include <sys/mman.h>

#include <fcntl.h>
#include <string.h>
#include <unistd.h>

#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MmapSource.h>

namespace android {

MmapSource::MmapSource(const char *filename)
    : mFd(open(filename, O_RDONLY)),
      mBase(NULL),
      mSize(0) {
    LOGV("MmapSource '%s'", filename);

    if (mFd < 0) {
        return;
    }

    off_t size = lseek(mFd, 0, SEEK_END);
    mSize = (size_t)size;

    mBase = mmap(0, mSize, PROT_READ, MAP_FILE | MAP_SHARED, mFd, 0);

    if (mBase == (void *)-1) {
        mBase = NULL;

        close(mFd);
        mFd = -1;
    }
}

MmapSource::MmapSource(int fd, int64_t offset, int64_t length)
    : mFd(fd),
      mBase(NULL),
      mSize(length) {
    LOGV("MmapSource fd:%d offset:%lld length:%lld", fd, offset, length);
    CHECK(fd >= 0);

    mBase = mmap(0, mSize, PROT_READ, MAP_FILE | MAP_SHARED, mFd, offset);

    if (mBase == (void *)-1) {
        mBase = NULL;

        close(mFd);
        mFd = -1;
    }

}

MmapSource::~MmapSource() {
    if (mFd != -1) {
        munmap(mBase, mSize);
        mBase = NULL;
        mSize = 0;

        close(mFd);
        mFd = -1;
    }
}

status_t MmapSource::InitCheck() const {
    return mFd == -1 ? NO_INIT : OK;
}

ssize_t MmapSource::read_at(off_t offset, void *data, size_t size) {
    LOGV("read_at offset:%ld data:%p size:%d", offset, data, size);
    CHECK(offset >= 0);

    size_t avail = 0;
    if (offset >= 0 && offset < (off_t)mSize) {
        avail = mSize - offset;
    }

    if (size > avail) {
        size = avail;
    }

    memcpy(data, (const uint8_t *)mBase + offset, size);

    return (ssize_t)size;
}

status_t MmapSource::getSize(off_t *size) {
    *size = mSize;

    return OK;
}

}  // namespace android

