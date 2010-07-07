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

#define LOG_TAG "ALooper"
#include <utils/Log.h>

#include <android/looper.h>
#include <utils/PollLoop.h>

using android::PollLoop;
using android::sp;

ALooper* ALooper_forThread() {
    return PollLoop::getForThread().get();
}

ALooper* ALooper_prepare() {
    sp<PollLoop> loop = PollLoop::getForThread();
    if (loop == NULL) {
        loop = new PollLoop();
        PollLoop::setForThread(loop);
    }
    return loop.get();
}

int32_t ALooper_pollOnce(int timeoutMillis) {
    sp<PollLoop> loop = PollLoop::getForThread();
    if (loop == NULL) {
        LOGW("ALooper_pollOnce: No looper for this thread!");
        return -1;
    }
    return loop->pollOnce(timeoutMillis) ? 1 : 0;
}

void ALooper_acquire(ALooper* looper) {
    static_cast<PollLoop*>(looper)->incStrong((void*)ALooper_acquire);
}

void ALooper_release(ALooper* looper) {
    static_cast<PollLoop*>(looper)->decStrong((void*)ALooper_acquire);
}

void ALooper_setCallback(ALooper* looper, int fd, int events,
        ALooper_callbackFunc* callback, void* data) {
    static_cast<PollLoop*>(looper)->setLooperCallback(fd, events, callback, data);
}

int32_t ALooper_removeCallback(ALooper* looper, int fd) {
    return static_cast<PollLoop*>(looper)->removeCallback(fd) ? 1 : 0;
}
