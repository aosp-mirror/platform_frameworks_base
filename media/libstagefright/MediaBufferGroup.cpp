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

#define LOG_TAG "MediaBufferGroup"
#include <utils/Log.h>

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaBufferGroup.h>

namespace android {

MediaBufferGroup::MediaBufferGroup()
    : mFirstBuffer(NULL),
      mLastBuffer(NULL) {
}

MediaBufferGroup::~MediaBufferGroup() {
    MediaBuffer *next;
    for (MediaBuffer *buffer = mFirstBuffer; buffer != NULL;
         buffer = next) {
        next = buffer->nextBuffer();

        CHECK_EQ(buffer->refcount(), 0);

        buffer->setObserver(NULL);
        buffer->release();
    }
}

void MediaBufferGroup::add_buffer(MediaBuffer *buffer) {
    Mutex::Autolock autoLock(mLock);

    buffer->setObserver(this);

    if (mLastBuffer) {
        mLastBuffer->setNextBuffer(buffer);
    } else {
        mFirstBuffer = buffer;
    }

    mLastBuffer = buffer;
}

status_t MediaBufferGroup::acquire_buffer(MediaBuffer **out) {
    Mutex::Autolock autoLock(mLock);

    for (;;) {
        for (MediaBuffer *buffer = mFirstBuffer;
             buffer != NULL; buffer = buffer->nextBuffer()) {
            if (buffer->refcount() == 0) {
                buffer->add_ref();
                buffer->reset();

                *out = buffer;
                goto exit;
            }
        }

        // All buffers are in use. Block until one of them is returned to us.
        mCondition.wait(mLock);
    }

exit:
    return OK;
}

void MediaBufferGroup::signalBufferReturned(MediaBuffer *) {
    Mutex::Autolock autoLock(mLock);
    mCondition.signal();
}

}  // namespace android
