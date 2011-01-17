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

ALooper* ALooper_forThread() {
    return Looper::getForThread().get();
}

ALooper* ALooper_prepare(int opts) {
    return Looper::prepare(opts).get();
}

void ALooper_acquire(ALooper* looper) {
    static_cast<Looper*>(looper)->incStrong((void*)ALooper_acquire);
}

void ALooper_release(ALooper* looper) {
    static_cast<Looper*>(looper)->decStrong((void*)ALooper_acquire);
}

int ALooper_pollOnce(int timeoutMillis, int* outFd, int* outEvents, void** outData) {
    sp<Looper> looper = Looper::getForThread();
    if (looper == NULL) {
        LOGE("ALooper_pollOnce: No looper for this thread!");
        return ALOOPER_POLL_ERROR;
    }

    IPCThreadState::self()->flushCommands();
    return looper->pollOnce(timeoutMillis, outFd, outEvents, outData);
}

int ALooper_pollAll(int timeoutMillis, int* outFd, int* outEvents, void** outData) {
    sp<Looper> looper = Looper::getForThread();
    if (looper == NULL) {
        LOGE("ALooper_pollAll: No looper for this thread!");
        return ALOOPER_POLL_ERROR;
    }

    IPCThreadState::self()->flushCommands();
    return looper->pollAll(timeoutMillis, outFd, outEvents, outData);
}

void ALooper_wake(ALooper* looper) {
    static_cast<Looper*>(looper)->wake();
}

int ALooper_addFd(ALooper* looper, int fd, int ident, int events,
        ALooper_callbackFunc callback, void* data) {
    return static_cast<Looper*>(looper)->addFd(fd, ident, events, callback, data);
}

int ALooper_removeFd(ALooper* looper, int fd) {
    return static_cast<Looper*>(looper)->removeFd(fd);
}
