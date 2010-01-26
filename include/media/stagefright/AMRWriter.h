/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef AMR_WRITER_H_

#define AMR_WRITER_H_

#include <stdio.h>

#include <utils/RefBase.h>
#include <utils/threads.h>

namespace android {

struct MediaSource;

struct AMRWriter : public RefBase {
    AMRWriter(const char *filename);
    AMRWriter(int fd);

    status_t initCheck() const;

    status_t addSource(const sp<MediaSource> &source);

    status_t start();
    void stop();

protected:
    virtual ~AMRWriter();

private:
    Mutex mLock;

    FILE *mFile;
    status_t mInitCheck;
    sp<MediaSource> mSource;
    bool mStarted;
    volatile bool mDone;
    pthread_t mThread;

    static void *ThreadWrapper(void *);
    void threadFunc();

    AMRWriter(const AMRWriter &);
    AMRWriter &operator=(const AMRWriter &);
};

}  // namespace android

#endif  // AMR_WRITER_H_
