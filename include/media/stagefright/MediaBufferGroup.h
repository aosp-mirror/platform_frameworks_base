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

#ifndef MEDIA_BUFFER_GROUP_H_

#define MEDIA_BUFFER_GROUP_H_

#include <media/stagefright/MediaBuffer.h>
#include <utils/Errors.h>
#include <utils/threads.h>

namespace android {

class MediaBuffer;
class MetaData;

class MediaBufferGroup : public MediaBufferObserver {
public:
    MediaBufferGroup();
    ~MediaBufferGroup();

    void add_buffer(MediaBuffer *buffer);

    // Blocks until a buffer is available and returns it to the caller,
    // the returned buffer will have a reference count of 1.
    status_t acquire_buffer(MediaBuffer **buffer);

protected:
    virtual void signalBufferReturned(MediaBuffer *buffer);

private:
    friend class MediaBuffer;

    Mutex mLock;
    Condition mCondition;

    MediaBuffer *mFirstBuffer, *mLastBuffer;

    MediaBufferGroup(const MediaBufferGroup &);
    MediaBufferGroup &operator=(const MediaBufferGroup &);
};

}  // namespace android

#endif  // MEDIA_BUFFER_GROUP_H_
