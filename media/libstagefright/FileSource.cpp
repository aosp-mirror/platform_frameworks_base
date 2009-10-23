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

#include <media/stagefright/FileSource.h>
#include <media/stagefright/MediaDebug.h>

namespace android {

FileSource::FileSource(const char *filename)
    : mFile(fopen(filename, "rb")) {
}

FileSource::~FileSource() {
    if (mFile != NULL) {
        fclose(mFile);
        mFile = NULL;
    }
}

status_t FileSource::initCheck() const {
    return mFile != NULL ? OK : NO_INIT;
}

ssize_t FileSource::readAt(off_t offset, void *data, size_t size) {
    Mutex::Autolock autoLock(mLock);

    int err = fseeko(mFile, offset, SEEK_SET);
    CHECK(err != -1);

    ssize_t result = fread(data, 1, size, mFile);

    return result;
}

}  // namespace android
