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
#include <utils/Looper.h>
#include <binder/IPCThreadState.h>

using android::Looper;
using android::sp;
using android::IPCThreadState;

static inline Looper* ALooper_to_Looper(ALooper* alooper) {
    return reinterpret_cast<Looper*>(alooper);
}

static inline ALooper* Looper_to_ALooper(Looper* looper) {
    return reinterpret_cast<ALooper*>(looper);
}

ALooper* ALooper_forThread() {
    return Looper_to_ALooper(Looper::getForThread().get());
}

ALooper* ALooper_prepare(int opts) {
    return Looper_to_ALooper(Looper::prepare(opts).get());
}

void ALooper_acquire(ALooper* looper) {
    ALooper_to_Looper(looper)->incStrong((void*)ALooper_acquire);
}

void ALooper_release(ALooper* looper) {
    ALooper_to_Looper(looper)->decStrong((void*)ALooper_acquire);
}

int ALooper_pollOnce(int timeoutMillis, int* outFd, int* outEvents, void** outData) {
    sp<Looper> looper = Looper::getForThread();
    if (looper == NULL) {
        ALOGE("ALooper_pollOnce: No looper for this thread!");
        return ALOOPER_POLL_ERROR;
    }

    IPCThreadState::self()->flushCommands();
    return looper->pollOnce(timeoutMillis, outFd, outEvents, outData);
}

int ALooper_pollAll(int timeoutMillis, int* outFd, int* outEvents, void** outData) {
    sp<Looper> looper = Looper::getForThread();
    if (looper == NULL) {
        ALOGE("ALooper_pollAll: No looper for this thread!");
        return ALOOPER_POLL_ERROR;
    }

    IPCThreadState::self()->flushCommands();
    return looper->pollAll(timeoutMillis, outFd, outEvents, outData);
}

void ALooper_wake(ALooper* looper) {
    ALooper_to_Looper(looper)->wake();
}

int ALooper_addFd(ALooper* looper, int fd, int ident, int events,
        ALooper_callbackFunc callback, void* data) {
    return ALooper_to_Looper(looper)->addFd(fd, ident, events, callback, data);
}

int ALooper_removeFd(ALooper* looper, int fd) {
    return ALooper_to_Looper(looper)->removeFd(fd);
}
