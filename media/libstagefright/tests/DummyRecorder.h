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

#ifndef DUMMY_RECORDER_H_
#define DUMMY_RECORDER_H_

#include <pthread.h>
#include <utils/String8.h>
#include <media/stagefright/foundation/ABase.h>


namespace android {

class MediaSource;
class MediaBuffer;

class DummyRecorder {
    public:
    // The media source from which this will receive frames
    sp<MediaSource> mSource;
    bool mStarted;
    pthread_t mThread;

    status_t start();
    status_t stop();

    // actual entry point for the thread
    void readFromSource();

    // static function to wrap the actual thread entry point
    static void *threadWrapper(void *pthis);

    DummyRecorder(const sp<MediaSource> &source) : mSource(source)
                                                    , mStarted(false) {}
    ~DummyRecorder( ) {}

    private:

    DISALLOW_EVIL_CONSTRUCTORS(DummyRecorder);
};

} // end of namespace android
#endif


