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

#include <media/stagefright/foundation/ADebug.h>

#include "StreamSource.h"

namespace android {

StreamSource::StreamSource(SkStream *stream)
        : mStream(stream) {
    CHECK(stream != NULL);
    mSize = stream->getLength();
}

StreamSource::~StreamSource() {
    delete mStream;
    mStream = NULL;
}

status_t StreamSource::initCheck() const {
    return mStream != NULL ? OK : NO_INIT;
}

ssize_t StreamSource::readAt(off64_t offset, void *data, size_t size) {
    Mutex::Autolock autoLock(mLock);

    mStream->rewind();
    mStream->skip(offset);
    ssize_t result = mStream->read(data, size);

    return result;
}

status_t StreamSource::getSize(off64_t *size) {
      *size = mSize;
      return OK;
}

}  // namespace android
