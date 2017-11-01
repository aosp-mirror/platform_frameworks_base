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
#include <pthread.h>
#include <queue>
#include <utils/Condition.h>
#include <utils/Mutex.h>

namespace android {

class NativeCallbackThread {
    typedef std::function<void(JNIEnv*)> Task;

    pthread_t mThread;
    Mutex mQueueMutex;
    Condition mQueueCond;
    std::atomic<bool> mExitting;

    JavaVM *mvm;
    std::queue<Task> mQueue;

    static void* main(void *args);
    void main();

    DISALLOW_COPY_AND_ASSIGN(NativeCallbackThread);

public:
    NativeCallbackThread(JavaVM *vm);
    virtual ~NativeCallbackThread();

    void enqueue(const Task &task);
    void stop();
};

} // namespace android

#endif // _ANDROID_NATIVE_CALLBACK_THREAD_H
