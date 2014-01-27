/*
 * Copyright (C) 2012 The Android Open Source Project
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

// #define LOG_NDEBUG 0
#define LOG_TAG "WorkQueue"

#include <utils/Log.h>
#include "WorkQueue.h"

namespace android {

// --- WorkQueue ---

WorkQueue::WorkQueue(size_t maxThreads, bool canCallJava) :
        mMaxThreads(maxThreads), mCanCallJava(canCallJava),
        mCanceled(false), mFinished(false), mIdleThreads(0) {
}

WorkQueue::~WorkQueue() {
    if (!cancel()) {
        finish();
    }
}

status_t WorkQueue::schedule(WorkUnit* workUnit, size_t backlog) {
    AutoMutex _l(mLock);

    if (mFinished || mCanceled) {
        return INVALID_OPERATION;
    }

    if (mWorkThreads.size() < mMaxThreads
            && mIdleThreads < mWorkUnits.size() + 1) {
        sp<WorkThread> workThread = new WorkThread(this, mCanCallJava);
        status_t status = workThread->run("WorkQueue::WorkThread");
        if (status) {
            return status;
        }
        mWorkThreads.add(workThread);
        mIdleThreads += 1;
    } else if (backlog) {
        while (mWorkUnits.size() >= mMaxThreads * backlog) {
            mWorkDequeuedCondition.wait(mLock);
            if (mFinished || mCanceled) {
                return INVALID_OPERATION;
            }
        }
    }

    mWorkUnits.add(workUnit);
    mWorkChangedCondition.broadcast();
    return OK;
}

status_t WorkQueue::cancel() {
    AutoMutex _l(mLock);

    return cancelLocked();
}

status_t WorkQueue::cancelLocked() {
    if (mFinished) {
        return INVALID_OPERATION;
    }

    if (!mCanceled) {
        mCanceled = true;

        size_t count = mWorkUnits.size();
        for (size_t i = 0; i < count; i++) {
            delete mWorkUnits.itemAt(i);
        }
        mWorkUnits.clear();
        mWorkChangedCondition.broadcast();
        mWorkDequeuedCondition.broadcast();
    }
    return OK;
}

status_t WorkQueue::finish() {
    { // acquire lock
        AutoMutex _l(mLock);

        if (mFinished) {
            return INVALID_OPERATION;
        }

        mFinished = true;
        mWorkChangedCondition.broadcast();
    } // release lock

    // It is not possible for the list of work threads to change once the mFinished
    // flag has been set, so we can access mWorkThreads outside of the lock here.
    size_t count = mWorkThreads.size();
    for (size_t i = 0; i < count; i++) {
        mWorkThreads.itemAt(i)->join();
    }
    mWorkThreads.clear();
    return OK;
}

bool WorkQueue::threadLoop() {
    WorkUnit* workUnit;
    { // acquire lock
        AutoMutex _l(mLock);

        for (;;) {
            if (mCanceled) {
                return false;
            }

            if (!mWorkUnits.isEmpty()) {
                workUnit = mWorkUnits.itemAt(0);
                mWorkUnits.removeAt(0);
                mIdleThreads -= 1;
                mWorkDequeuedCondition.broadcast();
                break;
            }

            if (mFinished) {
                return false;
            }

            mWorkChangedCondition.wait(mLock);
        }
    } // release lock

    bool shouldContinue = workUnit->run();
    delete workUnit;

    { // acquire lock
        AutoMutex _l(mLock);

        mIdleThreads += 1;

        if (!shouldContinue) {
            cancelLocked();
            return false;
        }
    } // release lock

    return true;
}

// --- WorkQueue::WorkThread ---

WorkQueue::WorkThread::WorkThread(WorkQueue* workQueue, bool canCallJava) :
        Thread(canCallJava), mWorkQueue(workQueue) {
}

WorkQueue::WorkThread::~WorkThread() {
}

bool WorkQueue::WorkThread::threadLoop() {
    return mWorkQueue->threadLoop();
}

};  // namespace android
