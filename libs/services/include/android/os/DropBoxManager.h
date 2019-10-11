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

#ifndef _ANDROID_OS_DROPBOXMANAGER_H
#define _ANDROID_OS_DROPBOXMANAGER_H

#include <android-base/unique_fd.h>
#include <binder/Parcel.h>
#include <binder/Parcelable.h>
#include <binder/Status.h>
#include <utils/RefBase.h>

#include <vector>

namespace android {
namespace os {

using namespace android;
using namespace android::base;
using namespace android::binder;
using namespace std;

class DropBoxManager : public virtual RefBase
{
public:
    enum {
        IS_EMPTY = 1,
        IS_TEXT = 2,
        IS_GZIPPED = 4
    };

    DropBoxManager();
    virtual ~DropBoxManager();

    static sp<DropBoxManager> create();

    // Create a new entry with plain text contents.
    Status addText(const String16& tag, const string& text);

    // Create a new Entry with byte array contents. Makes a copy of the data.
    Status addData(const String16& tag, uint8_t const* data, size_t size, int flags);

    // Create a new Entry from a file. The file will be opened in this process
    // and a handle will be passed to the system process, so no additional permissions
    // are required from the system process.  Returns NULL if the file can't be opened.
    Status addFile(const String16& tag, const string& filename, int flags);

    // Create a new Entry from an already opened file. Takes ownership of the
    // file descriptor.
    Status addFile(const String16& tag, int fd, int flags);

    class Entry : public Parcelable {
    public:
        Entry();
        virtual ~Entry();

        virtual status_t writeToParcel(Parcel* out) const;
        virtual status_t readFromParcel(const Parcel* in);

        const vector<uint8_t>& getData() const;
        const unique_fd& getFd() const;
        int32_t getFlags() const;
        int64_t getTimestamp() const;

    private:
        Entry(const String16& tag, int32_t flags);
        Entry(const String16& tag, int32_t flags, int fd);

        String16 mTag;
        int64_t mTimeMillis;
        int32_t mFlags;

        vector<uint8_t> mData;
        unique_fd mFd;

        friend class DropBoxManager;
    };

private:
    enum {
        HAS_BYTE_ARRAY = 8
    };

    Status add(const Entry& entry);
};

}} // namespace android::os

#endif // _ANDROID_OS_DROPBOXMANAGER_H

