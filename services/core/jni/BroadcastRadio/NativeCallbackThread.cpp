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

using std::lock_guard;
using std::mutex;
using std::unique_lock;

NativeCallbackThread::NativeCallbackThread(JavaVM *vm) : mvm(vm), mExiting(false),
        mThread(&NativeCallbackThread::threadLoop, this) {
    ALOGD("Started native callback thread %p", this);
}

NativeCallbackThread::~NativeCallbackThread() {
    ALOGV("%s %p", __func__, this);
    stop();
}

void NativeCallbackThread::threadLoop() {
    ALOGV("%s", __func__);

    JNIEnv *env = nullptr;
    JavaVMAttachArgs aargs = {JNI_VERSION_1_4, "NativeCallbackThread", nullptr};
    if (mvm->AttachCurrentThread(&env, &aargs) != JNI_OK || env == nullptr) {
        ALOGE("Couldn't attach thread");
        mExiting = true;
        return;
    }

    while (true) {
        Task task;
        {
            unique_lock<mutex> lk(mQueueMutex);

            if (mExiting) break;
            if (mQueue.empty()) {
                ALOGV("Waiting for task...");
                mQueueCond.wait(lk);
                if (mExiting) break;
                if (mQueue.empty()) continue;
            }

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
    ALOGD_IF(!mQueue.empty(), "Skipped execution of %zu tasks", mQueue.size());
}

void NativeCallbackThread::enqueue(const Task &task) {
    lock_guard<mutex> lk(mQueueMutex);

    if (mExiting) {
        ALOGW("Callback thread %p is not serving calls", this);
        return;
    }

    ALOGV("Adding task to the queue...");
    mQueue.push(task);
    mQueueCond.notify_one();
}

void NativeCallbackThread::stop() {
    ALOGV("%s %p", __func__, this);

    {
        lock_guard<mutex> lk(mQueueMutex);

        if (mExiting) return;

        mExiting = true;
        mQueueCond.notify_one();
    }

    if (mThread.get_id() == std::thread::id()) {
        // you can't self-join a thread, but it's ok when calling from our sub-task
        ALOGD("About to stop native callback thread %p", this);
        mThread.detach();
    } else {
        mThread.join();
        ALOGD("Stopped native callback thread %p", this);
    }
}

} // namespace android
