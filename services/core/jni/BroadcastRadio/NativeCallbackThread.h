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

#ifndef _ANDROID_NATIVE_CALLBACK_THREAD_H
#define _ANDROID_NATIVE_CALLBACK_THREAD_H

#include <android-base/macros.h>
#include <functional>
#include <jni.h>
#include <queue>
#include <thread>

namespace android {

class NativeCallbackThread {
    typedef std::function<void(JNIEnv*)> Task;

    JavaVM *mvm;
    std::queue<Task> mQueue;

    std::mutex mQueueMutex;
    std::condition_variable mQueueCond;
    std::atomic<bool> mExiting;
    std::thread mThread;

    void threadLoop();

    DISALLOW_COPY_AND_ASSIGN(NativeCallbackThread);

public:
    explicit NativeCallbackThread(JavaVM *vm);
    virtual ~NativeCallbackThread();

    void enqueue(const Task &task);
    void stop();
};

} // namespace android

#endif // _ANDROID_NATIVE_CALLBACK_THREAD_H
