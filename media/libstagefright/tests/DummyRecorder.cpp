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

#define LOG_TAG "DummyRecorder"
// #define LOG_NDEBUG 0

#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaSource.h>
#include "DummyRecorder.h"

#include <utils/Log.h>

namespace android {

// static
void *DummyRecorder::threadWrapper(void *pthis) {
    ALOGV("ThreadWrapper: %p", pthis);
    DummyRecorder *writer = static_cast<DummyRecorder *>(pthis);
    writer->readFromSource();
    return NULL;
}


status_t DummyRecorder::start() {
    ALOGV("Start");
    mStarted = true;

    mSource->start();

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);
    int err = pthread_create(&mThread, &attr, threadWrapper, this);
    pthread_attr_destroy(&attr);

    if (err) {
        LOGE("Error creating thread!");
        return -ENODEV;
    }
    return OK;
}


status_t DummyRecorder::stop() {
    ALOGV("Stop");
    mStarted = false;

    mSource->stop();
    void *dummy;
    pthread_join(mThread, &dummy);
    status_t err = (status_t) dummy;

    ALOGV("Ending the reading thread");
    return err;
}

// pretend to read the source buffers
void DummyRecorder::readFromSource() {
    ALOGV("ReadFromSource");
    if (!mStarted) {
        return;
    }

    status_t err = OK;
    MediaBuffer *buffer;
    ALOGV("A fake writer accessing the frames");
    while (mStarted && (err = mSource->read(&buffer)) == OK){
        // if not getting a valid buffer from source, then exit
        if (buffer == NULL) {
            return;
        }
        buffer->release();
        buffer = NULL;
    }
}


} // end of namespace android
