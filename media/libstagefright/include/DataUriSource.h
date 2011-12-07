/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef DATA_URI_SOURCE_H_

#define DATA_URI_SOURCE_H_

#include <stdio.h>

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/foundation/AString.h>

namespace android {

class DataUriSource : public DataSource {
public:
    DataUriSource(const char *uri);

    virtual status_t initCheck() const {
        return mInited;
    }

    virtual ssize_t readAt(off64_t offset, void *data, size_t size);

    virtual status_t getSize(off64_t *size) {
        if (mInited != OK) {
            return mInited;
        }

        *size = mData.size();
        return OK;
    }

    virtual String8 getUri() {
        return mDataUri;
    }

    virtual String8 getMIMEType() const {
        return mMimeType;
    }

protected:
    virtual ~DataUriSource() {
        // Nothing to delete.
    }

private:
    const String8 mDataUri;

    String8 mMimeType;
    // Use AString because individual bytes may not be valid UTF8 chars.
    AString mData;
    status_t mInited;

    // Disallow copy and assign.
    DataUriSource(const DataUriSource &);
    DataUriSource &operator=(const DataUriSource &);
};

}  // namespace android

#endif  // DATA_URI_SOURCE_H_
