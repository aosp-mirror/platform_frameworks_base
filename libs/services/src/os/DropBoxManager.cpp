/*
 * Copyright (C) 2016 The Android Open Source Project
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

#define LOG_TAG "DropBoxManager"

#include <android/os/DropBoxManager.h>

#include <binder/IServiceManager.h>
#include <com/android/internal/os/IDropBoxManagerService.h>
#include <cutils/log.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

namespace android {
namespace os {

using namespace ::com::android::internal::os;

DropBoxManager::Entry::Entry()
    :mTag(),
     mTimeMillis(0),
     mFlags(IS_EMPTY),
     mData(),
     mFd()
{
    mFlags = IS_EMPTY;
}

DropBoxManager::Entry::Entry(const String16& tag, int32_t flags)
    :mTag(tag),
     mTimeMillis(0),
     mFlags(flags),
     mData(),
     mFd()
{
}

DropBoxManager::Entry::Entry(const String16& tag, int32_t flags, int fd)
    :mTag(tag),
     mTimeMillis(0),
     mFlags(flags),
     mData(),
     mFd(fd)
{
}

DropBoxManager::Entry::~Entry()
{
}

status_t
DropBoxManager::Entry::writeToParcel(Parcel* out) const
{
    status_t err;

    err = out->writeString16(mTag);
    if (err != NO_ERROR) {
        return err;
    }

    err = out->writeInt64(mTimeMillis);
    if (err != NO_ERROR) {
        return err;
    }

    if (mFd.get() != -1) {
        err = out->writeInt32(mFlags & ~HAS_BYTE_ARRAY);  // Clear bit just to be safe
        if (err != NO_ERROR) {
            return err;
        }
        ALOGD("writing fd %d\n", mFd.get());
        err = out->writeParcelFileDescriptor(mFd);
        if (err != NO_ERROR) {
            return err;
        }
    } else {
        err = out->writeInt32(mFlags | HAS_BYTE_ARRAY);
        if (err != NO_ERROR) {
            return err;
        }
        err = out->writeByteVector(mData);
        if (err != NO_ERROR) {
            return err;
        }
    }
    return NO_ERROR;
}

status_t
DropBoxManager::Entry::readFromParcel(const Parcel* in)
{
    status_t err;

    err = in->readString16(&mTag);
    if (err != NO_ERROR) {
        return err;
    }

    err = in->readInt64(&mTimeMillis);
    if (err != NO_ERROR) {
        return err;
    }

    err = in->readInt32(&mFlags);
    if (err != NO_ERROR) {
        return err;
    }

    if ((mFlags & HAS_BYTE_ARRAY) != 0) {
        err = in->readByteVector(&mData);
        if (err != NO_ERROR) {
            return err;
        }
        mFlags &= ~HAS_BYTE_ARRAY;
    } else {
        int fd;
        fd = in->readParcelFileDescriptor();
        if (fd == -1) {
            return EBADF;
        }
        fd = dup(fd);
        if (fd == -1) {
            return errno;
        }
        mFd.reset(fd);
    }

    return NO_ERROR;
}

const vector<uint8_t>&
DropBoxManager::Entry::getData() const
{
    return mData;
}

const unique_fd&
DropBoxManager::Entry::getFd() const
{
    return mFd;
}

int32_t
DropBoxManager::Entry::getFlags() const
{
    return mFlags;
}

int64_t
DropBoxManager::Entry::getTimestamp() const
{
    return mTimeMillis;
}

DropBoxManager::DropBoxManager()
{
}

DropBoxManager::~DropBoxManager()
{
}

Status
DropBoxManager::addText(const String16& tag, const string& text)
{
    Entry entry(tag, IS_TEXT);
    entry.mData.assign(text.c_str(), text.c_str() + text.size());
    return add(entry);
}

Status
DropBoxManager::addData(const String16& tag, uint8_t const* data,
        size_t size, int flags)
{
    Entry entry(tag, flags);
    entry.mData.assign(data, data+size);
    return add(entry);
}

Status
DropBoxManager::addFile(const String16& tag, const string& filename, int flags)
{
    int fd = open(filename.c_str(), O_RDONLY);
    if (fd == -1) {
        string message("addFile can't open file: ");
        message += filename;
        ALOGW("DropboxManager: %s", message.c_str());
        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, message.c_str());
    }
    return addFile(tag, fd, flags);
}

Status
DropBoxManager::addFile(const String16& tag, int fd, int flags)
{
    if (fd == -1) {
        string message("invalid fd (-1) passed to to addFile");
        ALOGW("DropboxManager: %s", message.c_str());
        return Status::fromExceptionCode(Status::EX_ILLEGAL_STATE, message.c_str());
    }
    Entry entry(tag, flags, fd);
    return add(entry);
}

Status
DropBoxManager::add(const Entry& entry)
{
    sp<IDropBoxManagerService> service = interface_cast<IDropBoxManagerService>(
        defaultServiceManager()->getService(android::String16("dropbox")));
    if (service == NULL) {
        return Status::fromExceptionCode(Status::EX_NULL_POINTER, "can't find dropbox service");
    }
    return service->add(entry);
}

}} // namespace android::os
