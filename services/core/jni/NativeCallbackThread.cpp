/**
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "NativeCallbackThread"
//#define LOG_NDEBUG 0

#include "NativeCallbackThread.h"

#include <utils/Log.h>

namespace android {

NativeCallbackThread::NativeCallbackThread(JavaVM *vm) : mExitting(false), mvm(vm) {
    auto res = pthread_create(&mThread, nullptr, main, this);
    if (res != 0) {
        ALOGE("Couldn't start NativeCallbackThread");
        mThread = 0;
        return;
    }
    ALOGD("Started native callback thread %p", this);
}

NativeCallbackThread::~NativeCallbackThread() {
    ALOGV("~NativeCallbackThread %p", this);
    stop();
}

void* NativeCallbackThread::main(void *args) {
    auto self = reinterpret_cast<NativeCallbackThread*>(args);
    self->main();
    return nullptr;
}

void NativeCallbackThread::main() {
    ALOGV("NativeCallbackThread::main()");

    JNIEnv *env = nullptr;
    JavaVMAttachArgs aargs = {JNI_VERSION_1_4, "NativeCallbackThread", nullptr};
    if (mvm->AttachCurrentThread(&env, &aargs) != JNI_OK || env == nullptr) {
        ALOGE("Couldn't attach thread");
        return;
    }

    while (!mExitting) {
        ALOGV("Waiting for task...");
        Task task;
        {
            AutoMutex _l(mQueueMutex);
            auto res = mQueueCond.wait(mQueueMutex);
            ALOGE_IF(res != 0, "Wait failed: %d", res);
            if (mExitting || res != 0) break;

            if (mQueue.empty()) continue;
            task = mQueue.front();
            mQueue.pop();
        }

        ALOGV("Executing task...");
        task(env);
        if (env->ExceptionCheck()) {
            ALOGE("Unexpected exception:");
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    auto res = mvm->DetachCurrentThread();
    ALOGE_IF(res != JNI_OK, "Couldn't detach thread");

    ALOGV("Native callback thread %p finished", this);
}

void NativeCallbackThread::enqueue(const Task &task) {
    AutoMutex _l(mQueueMutex);

    if (mThread == 0 || mExitting) {
        ALOGW("Callback thread %p is not serving calls", this);
        return;
    }

    mQueue.push(task);
    mQueueCond.signal();
}

void NativeCallbackThread::stop() {
    ALOGV("stop() %p", this);

    {
        AutoMutex _l(mQueueMutex);

        if (mThread == 0 || mExitting) return;

        mExitting = true;
        mQueueCond.signal();
    }

    if (pthread_self() == mThread) {
        // you can't self-join a thread, but it's ok when calling from our sub-task
        ALOGD("About to stop native callback thread %p", this);
    } else {
        auto ret = pthread_join(mThread, nullptr);
        ALOGE_IF(ret != 0, "Couldn't join thread: %d", ret);

        ALOGD("Stopped native callback thread %p", this);
    }
}

} // namespace android
