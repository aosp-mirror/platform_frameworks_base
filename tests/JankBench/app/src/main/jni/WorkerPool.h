/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef ANDROID_WORKER_POOL_H
#define ANDROID_WORKER_POOL_H

#include <pthread.h>
#include <string.h>



class WorkerPool {
public:
    WorkerPool();
    ~WorkerPool();

    typedef void (*WorkerCallback_t)(void *usr, uint32_t idx);

    bool init(int threadCount = -1);
    int getWorkerCount() const {return mCount;}

    void waitForAll() const;
    void waitFor(uint64_t) const;
    uint64_t launchWork(WorkerCallback_t cb, void *usr, int maxThreads = -1);




protected:
    class Signal {
    public:
        Signal();
        ~Signal();

        bool init();
        void set();

        // returns true if the signal occured
        // false for timeout
        bool wait(uint64_t timeout = 0);

    protected:
        bool mSet;
        pthread_mutex_t mMutex;
        pthread_cond_t mCondition;
    };

    bool mExit;
    volatile int mRunningCount;
    volatile int mLaunchCount;
    uint32_t mCount;
    pthread_t *mThreadId;
    pid_t *mNativeThreadId;
    Signal mCompleteSignal;
    Signal *mLaunchSignals;
    WorkerCallback_t mLaunchCallback;
    void *mLaunchData;




private:
    //static void * threadProc(void *);
    static void * helperThreadProc(void *);


};


#endif
