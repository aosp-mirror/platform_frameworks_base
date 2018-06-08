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

#include "WorkerPool.h"
//#include <atomic>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <android/log.h>


//static pthread_key_t gThreadTLSKey = 0;
//static uint32_t gThreadTLSKeyCount = 0;
//static pthread_mutex_t gInitMutex = PTHREAD_MUTEX_INITIALIZER;


WorkerPool::Signal::Signal() {
    mSet = true;
}

WorkerPool::Signal::~Signal() {
    pthread_mutex_destroy(&mMutex);
    pthread_cond_destroy(&mCondition);
}

bool WorkerPool::Signal::init() {
    int status = pthread_mutex_init(&mMutex, NULL);
    if (status) {
        __android_log_print(ANDROID_LOG_INFO, "bench", "WorkerPool mutex init failure");
        return false;
    }

    status = pthread_cond_init(&mCondition, NULL);
    if (status) {
        __android_log_print(ANDROID_LOG_INFO, "bench", "WorkerPool condition init failure");
        pthread_mutex_destroy(&mMutex);
        return false;
    }

    return true;
}

void WorkerPool::Signal::set() {
    int status;

    status = pthread_mutex_lock(&mMutex);
    if (status) {
        __android_log_print(ANDROID_LOG_INFO, "bench", "WorkerPool: error %i locking for set condition.", status);
        return;
    }

    mSet = true;

    status = pthread_cond_signal(&mCondition);
    if (status) {
        __android_log_print(ANDROID_LOG_INFO, "bench", "WorkerPool: error %i on set condition.", status);
    }

    status = pthread_mutex_unlock(&mMutex);
    if (status) {
        __android_log_print(ANDROID_LOG_INFO, "bench", "WorkerPool: error %i unlocking for set condition.", status);
    }
}

bool WorkerPool::Signal::wait(uint64_t timeout) {
    int status;
    bool ret = false;

    status = pthread_mutex_lock(&mMutex);
    if (status) {
        __android_log_print(ANDROID_LOG_INFO, "bench", "WorkerPool: error %i locking for condition.", status);
        return false;
    }

    if (!mSet) {
        if (!timeout) {
            status = pthread_cond_wait(&mCondition, &mMutex);
        } else {
#if defined(HAVE_PTHREAD_COND_TIMEDWAIT_RELATIVE)
            status = pthread_cond_timeout_np(&mCondition, &mMutex, timeout / 1000000);
#else
            // This is safe it will just make things less reponsive
            status = pthread_cond_wait(&mCondition, &mMutex);
#endif
        }
    }

    if (!status) {
        mSet = false;
        ret = true;
    } else {
#ifndef RS_SERVER
        if (status != ETIMEDOUT) {
            __android_log_print(ANDROID_LOG_INFO, "bench", "WorkerPool: error %i waiting for condition.", status);
        }
#endif
    }

    status = pthread_mutex_unlock(&mMutex);
    if (status) {
        __android_log_print(ANDROID_LOG_INFO, "bench", "WorkerPool: error %i unlocking for condition.", status);
    }

    return ret;
}



WorkerPool::WorkerPool() {
    mExit = false;
    mRunningCount = 0;
    mLaunchCount = 0;
    mCount = 0;
    mThreadId = NULL;
    mNativeThreadId = NULL;
    mLaunchSignals = NULL;
    mLaunchCallback = NULL;


}


WorkerPool::~WorkerPool() {
__android_log_print(ANDROID_LOG_INFO, "bench", "~wp");
    mExit = true;
    mLaunchData = NULL;
    mLaunchCallback = NULL;
    mRunningCount = mCount;

    __sync_synchronize();
    for (uint32_t ct = 0; ct < mCount; ct++) {
        mLaunchSignals[ct].set();
    }
    void *res;
    for (uint32_t ct = 0; ct < mCount; ct++) {
        pthread_join(mThreadId[ct], &res);
    }
    //rsAssert(__sync_fetch_and_or(&mRunningCount, 0) == 0);
    free(mThreadId);
    free(mNativeThreadId);
    delete[] mLaunchSignals;
}

bool WorkerPool::init(int threadCount) {
    int cpu = sysconf(_SC_NPROCESSORS_CONF);
    if (threadCount > 0) {
        cpu = threadCount;
    }
    if (cpu < 1) {
        return false;
    }
    mCount = (uint32_t)cpu;

    __android_log_print(ANDROID_LOG_INFO, "Bench", "ThreadLaunch %i", mCount);

    mThreadId = (pthread_t *) calloc(mCount, sizeof(pthread_t));
    mNativeThreadId = (pid_t *) calloc(mCount, sizeof(pid_t));
    mLaunchSignals = new Signal[mCount];
    mLaunchCallback = NULL;

    mCompleteSignal.init();
    mRunningCount = mCount;
    mLaunchCount = 0;
    __sync_synchronize();

    pthread_attr_t threadAttr;
    int status = pthread_attr_init(&threadAttr);
    if (status) {
        __android_log_print(ANDROID_LOG_INFO, "bench", "Failed to init thread attribute.");
        return false;
    }

    for (uint32_t ct=0; ct < mCount; ct++) {
        status = pthread_create(&mThreadId[ct], &threadAttr, helperThreadProc, this);
        if (status) {
            mCount = ct;
            __android_log_print(ANDROID_LOG_INFO, "bench", "Created fewer than expected number of threads.");
            return false;
        }
    }
    while (__sync_fetch_and_or(&mRunningCount, 0) != 0) {
        usleep(100);
    }

    pthread_attr_destroy(&threadAttr);
    return true;
}

void * WorkerPool::helperThreadProc(void *vwp) {
    WorkerPool *wp = (WorkerPool *)vwp;

    uint32_t idx = __sync_fetch_and_add(&wp->mLaunchCount, 1);

    wp->mLaunchSignals[idx].init();
    wp->mNativeThreadId[idx] = gettid();

    while (!wp->mExit) {
        wp->mLaunchSignals[idx].wait();
        if (wp->mLaunchCallback) {
           // idx +1 is used because the calling thread is always worker 0.
           wp->mLaunchCallback(wp->mLaunchData, idx);
        }
        __sync_fetch_and_sub(&wp->mRunningCount, 1);
        wp->mCompleteSignal.set();
    }

    //ALOGV("RS helperThread exited %p idx=%i", dc, idx);
    return NULL;
}


void WorkerPool::waitForAll() const {
}

void WorkerPool::waitFor(uint64_t) const {
}



uint64_t WorkerPool::launchWork(WorkerCallback_t cb, void *usr, int maxThreads) {
    //__android_log_print(ANDROID_LOG_INFO, "bench", "lw 1");
    mLaunchData = usr;
    mLaunchCallback = cb;

    if (maxThreads < 1) {
        maxThreads = mCount;
    }
    if ((uint32_t)maxThreads > mCount) {
        //__android_log_print(ANDROID_LOG_INFO, "bench", "launchWork max > count", maxThreads, mCount);
        maxThreads = mCount;
    }

    //__android_log_print(ANDROID_LOG_INFO, "bench", "lw 2  %i  %i  %i", maxThreads, mRunningCount, mCount);
    mRunningCount = maxThreads;
    __sync_synchronize();

    for (int ct = 0; ct < maxThreads; ct++) {
        mLaunchSignals[ct].set();
    }

    //__android_log_print(ANDROID_LOG_INFO, "bench", "lw 3    %i", mRunningCount);
    while (__sync_fetch_and_or(&mRunningCount, 0) != 0) {
        //__android_log_print(ANDROID_LOG_INFO, "bench", "lw 3.1    %i", mRunningCount);
        mCompleteSignal.wait();
    }

    //__android_log_print(ANDROID_LOG_INFO, "bench", "lw 4    %i", mRunningCount);
    return 0;

}



