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

//#define LOG_NDEBUG 0
#define LOG_TAG "ALooper"
#include <utils/Log.h>

#include <sys/time.h>

#include "ALooper.h"

#include "AHandler.h"
#include "ALooperRoster.h"
#include "AMessage.h"

namespace android {

ALooperRoster gLooperRoster;

struct ALooper::LooperThread : public Thread {
    LooperThread(ALooper *looper, bool canCallJava)
        : Thread(canCallJava),
          mLooper(looper) {
    }

    virtual bool threadLoop() {
        return mLooper->loop();
    }

protected:
    virtual ~LooperThread() {}

private:
    ALooper *mLooper;

    DISALLOW_EVIL_CONSTRUCTORS(LooperThread);
};

// static
int64_t ALooper::GetNowUs() {
    struct timeval tv;
    gettimeofday(&tv, NULL);

    return (int64_t)tv.tv_sec * 1000000ll + tv.tv_usec;
}

ALooper::ALooper()
    : mRunningLocally(false) {
}

ALooper::~ALooper() {
    stop();
}

ALooper::handler_id ALooper::registerHandler(const sp<AHandler> &handler) {
    return gLooperRoster.registerHandler(this, handler);
}

void ALooper::unregisterHandler(handler_id handlerID) {
    gLooperRoster.unregisterHandler(handlerID);
}

status_t ALooper::start(bool runOnCallingThread, bool canCallJava) {
    if (runOnCallingThread) {
        {
            Mutex::Autolock autoLock(mLock);

            if (mThread != NULL || mRunningLocally) {
                return INVALID_OPERATION;
            }

            mRunningLocally = true;
        }

        do {
        } while (loop());

        return OK;
    }

    Mutex::Autolock autoLock(mLock);

    if (mThread != NULL || mRunningLocally) {
        return INVALID_OPERATION;
    }

    mThread = new LooperThread(this, canCallJava);

    status_t err = mThread->run("ALooper");
    if (err != OK) {
        mThread.clear();
    }

    return err;
}

status_t ALooper::stop() {
    sp<LooperThread> thread;
    bool runningLocally;

    {
        Mutex::Autolock autoLock(mLock);

        thread = mThread;
        runningLocally = mRunningLocally;
        mThread.clear();
        mRunningLocally = false;
    }

    if (thread == NULL && !runningLocally) {
        return INVALID_OPERATION;
    }

    if (thread != NULL) {
        thread->requestExit();
    }

    mQueueChangedCondition.signal();

    if (!runningLocally) {
        thread->requestExitAndWait();
    }

    return OK;
}

void ALooper::post(const sp<AMessage> &msg, int64_t delayUs) {
    Mutex::Autolock autoLock(mLock);

    int64_t whenUs;
    if (delayUs > 0) {
        whenUs = GetNowUs() + delayUs;
    } else {
        whenUs = GetNowUs();
    }

    List<Event>::iterator it = mEventQueue.begin();
    while (it != mEventQueue.end() && (*it).mWhenUs <= whenUs) {
        ++it;
    }

    Event event;
    event.mWhenUs = whenUs;
    event.mMessage = msg;

    if (it == mEventQueue.begin()) {
        mQueueChangedCondition.signal();
    }

    mEventQueue.insert(it, event);
}

bool ALooper::loop() {
    Event event;

    {
        Mutex::Autolock autoLock(mLock);
        if (mThread == NULL && !mRunningLocally) {
            return false;
        }
        if (mEventQueue.empty()) {
            mQueueChangedCondition.wait(mLock);
            return true;
        }
        int64_t whenUs = (*mEventQueue.begin()).mWhenUs;
        int64_t nowUs = GetNowUs();

        if (whenUs > nowUs) {
            int64_t delayUs = whenUs - nowUs;
            mQueueChangedCondition.waitRelative(mLock, delayUs * 1000ll);

            return true;
        }

        event = *mEventQueue.begin();
        mEventQueue.erase(mEventQueue.begin());
    }

    gLooperRoster.deliverMessage(event.mMessage);

    return true;
}

}  // namespace android
